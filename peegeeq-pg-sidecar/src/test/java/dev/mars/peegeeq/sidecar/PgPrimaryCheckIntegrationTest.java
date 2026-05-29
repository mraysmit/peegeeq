package dev.mars.peegeeq.sidecar;

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration test for {@link PgPrimaryCheckVerticle}.
 *
 * <p>Starts a real PostgreSQL container and verifies that the /primary endpoint
 * returns the correct HTTP status code based on pg_is_in_recovery():
 *
 * <ul>
 *   <li>A standalone PostgreSQL instance (not a replica) returns HTTP 200.</li>
 *   <li>The endpoint returns HTTP 503 when PostgreSQL is unreachable.</li>
 * </ul>
 *
 * <p>Note: testing the replica case (pg_is_in_recovery() = true) requires a full
 * streaming replication setup. That scenario is covered by the HAProxy failover
 * integration test in peegeeq-db.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-05-10
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@DisplayName("PgPrimaryCheckVerticle Integration Tests")
class PgPrimaryCheckIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PgPrimaryCheckIntegrationTest.class);

    private static final int SIDECAR_HTTP_PORT = 18008;

    @SuppressWarnings("resource")
    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(PgSidecarTestImageConstant.POSTGRES_IMAGE)
                    .withDatabaseName("postgres")
                    .withUsername("haproxy_check")
                    .withPassword("haproxy_check");

    private WebClient webClient;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        webClient = WebClient.create(vertx, new WebClientOptions().setDefaultHost("localhost"));

        JsonObject config = new JsonObject()
                .put("pg.host",     postgres.getHost())
                .put("pg.port",     postgres.getFirstMappedPort())
                .put("pg.database", postgres.getDatabaseName())
                .put("pg.user",     postgres.getUsername())
                .put("pg.password", postgres.getPassword())
                .put("http.port",   SIDECAR_HTTP_PORT);

        vertx.deployVerticle(
                new PgPrimaryCheckVerticle(),
                new DeploymentOptions().setConfig(config)
        ).onSuccess(id -> {
            logger.info("PgPrimaryCheckVerticle deployed (id={})", id);
            ctx.completeNow();
        }).onFailure(ctx::failNow);
    }

    @AfterEach
    void tearDown(Vertx vertx, VertxTestContext ctx) {
        if (webClient != null) {
            webClient.close();
        }
        ctx.completeNow();
    }

    @Test
    @DisplayName("GET /primary returns 200 for a standalone (non-replica) PostgreSQL node")
    void primaryReturns200ForStandaloneNode(Vertx vertx, VertxTestContext ctx) {
        webClient.get(SIDECAR_HTTP_PORT, "localhost", "/primary")
                .send()
                .onSuccess(response -> ctx.verify(() -> {
                    logger.info("GET /primary  HTTP {}", response.statusCode());
                    assertEquals(200, response.statusCode(),
                            "Standalone PostgreSQL should report pg_is_in_recovery()=false  HTTP 200");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("GET /primary returns 404 for unknown paths")
    void unknownPathReturns404(Vertx vertx, VertxTestContext ctx) {
        webClient.get(SIDECAR_HTTP_PORT, "localhost", "/health")
                .send()
                .onSuccess(response -> ctx.verify(() -> {
                    assertEquals(404, response.statusCode());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("GET /primary returns 503 when PostgreSQL is unreachable")
    void primaryReturns503WhenPostgresUnreachable(Vertx vertx, VertxTestContext ctx) {
        // Deploy a second verticle pointing at a port where nothing is listening
        JsonObject badConfig = new JsonObject()
                .put("pg.host",     "localhost")
                .put("pg.port",     19999)   // nothing here
                .put("pg.database", "postgres")
                .put("pg.user",     "haproxy_check")
                .put("pg.password", "")
                .put("http.port",   18009);  // separate port

        vertx.deployVerticle(
                new PgPrimaryCheckVerticle(),
                new DeploymentOptions().setConfig(badConfig)
        ).compose(id -> {
            logger.info("Second verticle deployed on port 18009 (id={}), querying /primary", id);
            return webClient.get(18009, "localhost", "/primary").send();
        }).onSuccess(response -> ctx.verify(() -> {
            logger.info("GET /primary (unreachable pg)  HTTP {}", response.statusCode());
            assertEquals(503, response.statusCode(),
                    "Unreachable PostgreSQL should return HTTP 503");
            ctx.completeNow();
        })).onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("Concurrent /primary requests (simulating HAProxy polling frequency) all return 200")
    void concurrentRequestsAllReturn200(Vertx vertx, VertxTestContext ctx) {
        int requestCount = 10;
        Checkpoint checkpoint = ctx.checkpoint(requestCount);

        for (int i = 0; i < requestCount; i++) {
            webClient.get(SIDECAR_HTTP_PORT, "localhost", "/primary")
                    .send()
                    .onSuccess(response -> ctx.verify(() -> {
                        assertEquals(200, response.statusCode(),
                                "Every concurrent request to a standalone primary should return 200");
                        checkpoint.flag();
                    }))
                    .onFailure(ctx::failNow);
        }
    }

    @Test
    @DisplayName("GET / (root path) returns 404")
    void rootPathReturns404(Vertx vertx, VertxTestContext ctx) {
        webClient.get(SIDECAR_HTTP_PORT, "localhost", "/")
                .send()
                .onSuccess(response -> ctx.verify(() -> {
                    assertEquals(404, response.statusCode());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("GET /replica returns 404  only /primary is a recognised path")
    void replicaPathReturns404(Vertx vertx, VertxTestContext ctx) {
        webClient.get(SIDECAR_HTTP_PORT, "localhost", "/replica")
                .send()
                .onSuccess(response -> ctx.verify(() -> {
                    assertEquals(404, response.statusCode());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("GET /patroni returns 404  sidecar does not expose Patroni-compatible paths")
    void patroniPathReturns404(Vertx vertx, VertxTestContext ctx) {
        webClient.get(SIDECAR_HTTP_PORT, "localhost", "/patroni")
                .send()
                .onSuccess(response -> ctx.verify(() -> {
                    assertEquals(404, response.statusCode());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }
}
