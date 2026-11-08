package dev.mars.peegeeq.sidecar;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code GET /primary} against a real streaming-replication standby (finding 8).
 *
 * <p>HAProxy routes by status code only, so a standby and an unreachable node both answer 503.
 * The body tells an operator which of the two it is. Before this class the replica answer, the
 * main contract of the endpoint, was never run: every other test used a standalone node.</p>
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
class PgPrimaryCheckReplicaIntegrationTest {

    private static final int SIDECAR_HTTP_PORT = 18018;
    private static final String REPLICA_DATA = "/var/lib/postgresql/replica";

    private static Network network;
    private static PostgreSQLContainer primary;
    private static GenericContainer<?> standby;

    @BeforeAll
    static void startReplication() throws Exception {
        network = Network.newNetwork();
        primary = PostgreSQLTestConstants.createStandardContainer()
                .withNetwork(network)
                .withNetworkAliases("pg_primary")
                .withCommand("postgres", "-c", "wal_level=replica", "-c", "max_wal_senders=5");
        primary.start();
        requireSuccess(primary.execInContainer("psql", "-U", primary.getUsername(), "-d", primary.getDatabaseName(),
                "-c", "CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD 'sidecar_replication'"));
        requireSuccess(primary.execInContainer("sh", "-c",
                "echo 'host replication replicator 0.0.0.0/0 scram-sha-256' >> \"$PGDATA/pg_hba.conf\""));
        requireSuccess(primary.execInContainer("psql", "-U", primary.getUsername(), "-d", primary.getDatabaseName(),
                "-c", "SELECT pg_reload_conf()"));

        standby = new GenericContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
                .withNetwork(network).withExposedPorts(5432)
                .withCommand("tail", "-f", "/dev/null")
                .waitingFor(Wait.forSuccessfulCommand("test -d /var/lib/postgresql"));
        standby.start();
        requireSuccess(standby.execInContainer("sh", "-c", """
                set -eu
                mkdir -p %1$s
                chown postgres:postgres %1$s
                chmod 700 %1$s
                su-exec postgres env PGPASSWORD=sidecar_replication pg_basebackup \
                  -d "host=pg_primary port=5432 user=replicator" -D %1$s -Fp -Xs -R
                su-exec postgres pg_ctl -D %1$s -l /tmp/postgres.log \
                  -o "-c listen_addresses='*' -c hot_standby=on" -w start
                """.formatted(REPLICA_DATA)));
    }

    @AfterAll
    static void stopReplication() {
        if (standby != null) standby.stop();
        if (primary != null) primary.stop();
        if (network != null) network.close();
    }

    @Test
    @DisplayName("GET /primary on a standby answers 503 with body 'replica'")
    void standbyAnswers503AsAReplica(Vertx vertx, VertxTestContext ctx) {
        // The standby is a copy of the primary, so it accepts the primary's credentials.
        JsonObject config = new JsonObject()
                .put("pg.host", standby.getHost())
                .put("pg.port", standby.getMappedPort(5432))
                .put("pg.database", primary.getDatabaseName())
                .put("pg.user", primary.getUsername())
                .put("pg.password", primary.getPassword())
                .put("http.port", SIDECAR_HTTP_PORT);
        WebClient client = WebClient.create(vertx);

        vertx.deployVerticle(new PgPrimaryCheckVerticle(), new DeploymentOptions().setConfig(config))
                .compose(id -> client.get(SIDECAR_HTTP_PORT, "localhost", "/primary").send())
                .onSuccess(response -> ctx.verify(() -> {
                    assertEquals(503, response.statusCode(), "a standby must not be routed writes");
                    assertEquals("replica", response.bodyAsString(),
                            "the body must say the node is a replica, not that it is unreachable");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    private static void requireSuccess(Container.ExecResult result) {
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("PostgreSQL fixture command failed: "
                    + result.getStdout() + result.getStderr());
        }
    }
}
