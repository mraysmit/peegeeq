package dev.mars.peegeeq.db.resilience;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import dev.mars.peegeeq.db.PgTestImageConstant;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
class HaProxyStreamingReplicationFailoverTest {

    private static final int HAPROXY_PORT = 5400;
    private static final String DATABASE = "peegeeq_replication";
    private static final String USERNAME = "peegeeq_test";
    private static final String PASSWORD = "peegeeq_test";
    private static final String REPLICATION_PASSWORD = "peegeeq_replication";
    private static final String REPLICA_DATA = "/var/lib/postgresql/replica";
    private static final String SERVICE_ID = "streaming-replication";
    private static final String MARKER = "written-before-primary-failure";
    private static final String POST_PROMOTION_MARKER = "written-after-promotion";
    private static final int MAX_QUERY_ATTEMPTS = 12;
    private static final long QUERY_RETRY_DELAY_MS = 250L;

    private static Network network;

    @SuppressWarnings("resource")
    private static PostgreSQLContainer primary;

    @SuppressWarnings("resource")
    private static GenericContainer<?> secondary;

    @SuppressWarnings("resource")
    private static GenericContainer<?> haproxy;

    private PgConnectionManager connectionManager;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        network = Network.newNetwork();
        primary = new PostgreSQLContainer(PgTestImageConstant.POSTGRES_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("pg_primary")
            .withDatabaseName(DATABASE)
            .withUsername(USERNAME)
            .withPassword(PASSWORD)
            .withInitScript("streaming-replication-primary-init.sql")
            .withCommand(
                "postgres",
                "-c", "wal_level=replica",
                "-c", "max_wal_senders=5",
                "-c", "wal_keep_size=64MB");
        primary.start();

        requireSuccess("primary replication HBA configuration", primary.execInContainer(
            "sh", "-c",
            "echo 'host replication replicator 0.0.0.0/0 scram-sha-256' >> \"$PGDATA/pg_hba.conf\""));
        requireSuccess("primary HBA reload", primary.execInContainer(
            "psql", "-U", USERNAME, "-d", DATABASE, "-c", "SELECT pg_reload_conf()"));

        secondary = new GenericContainer<>(PgTestImageConstant.POSTGRES_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("pg_secondary")
            .withCommand("tail", "-f", "/dev/null");
        secondary.start();
        configureAndStartStandby();

        requireSuccess("streaming replication readiness", primary.execInContainer(
            "psql", "-U", USERNAME, "-d", DATABASE, "-Atc",
            "SELECT state FROM pg_stat_replication WHERE state = 'streaming'"));

        haproxy = new GenericContainer<>("haproxy:2.8-alpine")
            .withNetwork(network)
            .withClasspathResourceMapping(
                "haproxy-streaming-replication.cfg",
                "/usr/local/etc/haproxy/haproxy.cfg",
                BindMode.READ_ONLY)
            .withExposedPorts(HAPROXY_PORT)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(30)));
        haproxy.start();
    }

    @AfterAll
    static void stopInfrastructure() {
        if (haproxy != null && haproxy.isRunning()) haproxy.stop();
        if (secondary != null && secondary.isRunning()) secondary.stop();
        if (primary != null && primary.isRunning()) primary.stop();
        if (network != null) network.close();
    }

    @AfterEach
    void closePool(VertxTestContext testContext) {
        if (connectionManager == null) {
            testContext.completeNow();
            return;
        }
        connectionManager.close()
            .onSuccess(ignored -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void replicatedRowSurvivesPromotionAndPoolReconnect(Vertx vertx, VertxTestContext testContext) {
        connectionManager = new PgConnectionManager(vertx);
        connectionManager.getOrCreateReactivePool(
            SERVICE_ID,
            new PgConnectionConfig.Builder()
                .host(haproxy.getHost())
                .port(haproxy.getMappedPort(HAPROXY_PORT))
                .database(DATABASE)
                .username(USERNAME)
                .password(PASSWORD)
                .schema("public")
                .build(),
            new PgPoolConfig.Builder()
                .maxSize(2)
                .shared(false)
                .connectionTimeout(Duration.ofSeconds(2))
                .idleTimeout(Duration.ofSeconds(5))
                .build());

        connectionManager.withTransaction(SERVICE_ID, connection ->
                connection.query("""
                    CREATE TABLE IF NOT EXISTS replication_failover_probe (
                        id INTEGER PRIMARY KEY,
                        marker TEXT NOT NULL
                    )
                    """).execute()
                    .compose(ignored -> connection.query(
                        "DELETE FROM replication_failover_probe").execute())
                    .compose(ignored -> connection.preparedQuery(
                        "INSERT INTO replication_failover_probe (id, marker) VALUES ($1, $2)")
                        .execute(Tuple.of(1, MARKER)))
                    .mapEmpty())
            .compose(ignored -> waitForStandbyMarker(vertx, MAX_QUERY_ATTEMPTS))
            .compose(ignored -> stopPrimary(vertx))
            .compose(ignored -> promoteStandby(vertx))
            .compose(ignored -> queryMarkerWithRetry(vertx, MAX_QUERY_ATTEMPTS))
            .compose(marker -> {
                assertEquals(MARKER, marker,
                    "The row committed on primary must exist after failover");
                return connectionManager.withTransaction(SERVICE_ID, connection ->
                    connection.preparedQuery(
                        "INSERT INTO replication_failover_probe (id, marker) VALUES ($1, $2)")
                        .execute(Tuple.of(2, POST_PROMOTION_MARKER))
                        .compose(ignored -> connection.query(
                            "SELECT pg_is_in_recovery() AS in_recovery").execute())
                        .map(rows -> rows.iterator().next().getBoolean("in_recovery")));
            })
            .compose(inRecovery -> {
                assertEquals(Boolean.FALSE, inRecovery,
                    "HAProxy must route to the promoted writable standby");
                return connectionManager.withConnection(SERVICE_ID, connection ->
                    connection.preparedQuery(
                        "SELECT marker FROM replication_failover_probe WHERE id = $1")
                        .execute(Tuple.of(2))
                        .map(rows -> rows.iterator().next().getString("marker")));
            })
            .onSuccess(marker -> testContext.verify(() -> {
                assertEquals(POST_PROMOTION_MARKER, marker);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    private static void configureAndStartStandby() throws Exception {
        String script = """
            set -eu
            rm -rf '%s'
            mkdir -p '%s'
            chown postgres:postgres '%s'
            chmod 700 '%s'
            printf 'pg_primary:5432:*:replicator:%s\n' > /var/lib/postgresql/.pgpass
            chown postgres:postgres /var/lib/postgresql/.pgpass
            chmod 600 /var/lib/postgresql/.pgpass
            su-exec postgres env PGPASSWORD='%s' pg_basebackup \
                -h pg_primary -p 5432 -U replicator -D '%s' -Fp -Xs -R
            su-exec postgres pg_ctl -D '%s' \
                -l /tmp/postgres.log \
                -o "-c listen_addresses='*' -c port=5432 -c hot_standby=on" \
                -w start
            """.formatted(
                REPLICA_DATA,
                REPLICA_DATA,
                REPLICA_DATA,
                REPLICA_DATA,
                REPLICATION_PASSWORD,
                REPLICATION_PASSWORD,
                REPLICA_DATA,
                REPLICA_DATA);
        requireSuccess("standby base backup and startup",
            secondary.execInContainer("sh", "-c", script));
    }

    private static void requireSuccess(String operation, Container.ExecResult result) {
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(operation + " failed: "
                + result.getStdout() + result.getStderr());
        }
    }

    private static Future<Void> stopPrimary(Vertx vertx) {
        return vertx.<Void>executeBlocking(() -> {
            primary.stop();
            return null;
        });
    }

    private static Future<Void> promoteStandby(Vertx vertx) {
        return vertx.<Void>executeBlocking(() -> {
            requireSuccess("standby promotion", secondary.execInContainer(
                "su-exec", "postgres", "pg_ctl", "-D", REPLICA_DATA, "-w", "promote"));
            return null;
        });
    }

    private static Future<String> waitForStandbyMarker(Vertx vertx, int maxAttempts) {
        Promise<String> result = Promise.promise();
        attemptStandbyMarkerQuery(vertx, 1, maxAttempts, result);
        return result.future();
    }

    private static void attemptStandbyMarkerQuery(
            Vertx vertx, int attempt, int maxAttempts, Promise<String> result) {
        vertx.<String>executeBlocking(() -> {
            Container.ExecResult query = secondary.execInContainer(
                "su-exec", "postgres", "psql", "-h", "127.0.0.1",
                "-U", USERNAME, "-d", DATABASE, "-Atc",
                "SELECT marker FROM replication_failover_probe WHERE id = 1");
            requireSuccess("standby marker query", query);
            return query.getStdout().trim();
        })
        .onSuccess(marker -> {
            if (MARKER.equals(marker)) {
                result.complete(marker);
            } else {
                scheduleStandbyMarkerRetry(vertx, attempt, maxAttempts, result,
                    new AssertionError("Standby marker was: " + marker));
            }
        })
        .onFailure(failure ->
            scheduleStandbyMarkerRetry(vertx, attempt, maxAttempts, result, failure));
    }

    private static void scheduleStandbyMarkerRetry(
            Vertx vertx,
            int attempt,
            int maxAttempts,
            Promise<String> result,
            Throwable failure) {
        if (attempt >= maxAttempts) {
            result.fail(failure);
            return;
        }
        vertx.setTimer(QUERY_RETRY_DELAY_MS, ignored ->
            attemptStandbyMarkerQuery(vertx, attempt + 1, maxAttempts, result));
    }

    private Future<String> queryMarkerWithRetry(Vertx vertx, int maxAttempts) {
        Promise<String> result = Promise.promise();
        attemptMarkerQuery(vertx, 1, maxAttempts, result);
        return result.future();
    }

    private void attemptMarkerQuery(
            Vertx vertx, int attempt, int maxAttempts, Promise<String> result) {
        connectionManager.withConnection(SERVICE_ID, connection ->
                connection.preparedQuery(
                    "SELECT marker FROM replication_failover_probe WHERE id = $1")
                    .execute(Tuple.of(1))
                    .map(rows -> rows.iterator().next().getString("marker")))
            .onSuccess(result::complete)
            .onFailure(failure -> {
                if (attempt >= maxAttempts) {
                    result.fail(failure);
                    return;
                }
                vertx.setTimer(QUERY_RETRY_DELAY_MS, ignored ->
                    attemptMarkerQuery(vertx, attempt + 1, maxAttempts, result));
            });
    }
}
