package dev.mars.peegeeq.sidecar;

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
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
 * Lifecycle tests for {@link PgPrimaryCheckVerticle}.
 *
 * <p>Verifies that the verticle undeploys cleanly (pool is closed without error) and
 * can be redeployed on the same port after a clean undeploy  a requirement for
 * rolling restarts in production.
 *
 * <p>Uses separate HTTP ports (18010, 18011) to avoid conflicts with
 * {@link PgPrimaryCheckIntegrationTest}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-05-10
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@DisplayName("PgPrimaryCheckVerticle Lifecycle Tests")
class PgPrimaryCheckLifecycleTest {

    private static final Logger logger = LoggerFactory.getLogger(PgPrimaryCheckLifecycleTest.class);

    @SuppressWarnings("resource")
    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(PgSidecarTestImageConstant.POSTGRES_IMAGE)
                    .withDatabaseName("postgres")
                    .withUsername("haproxy_check")
                    .withPassword("haproxy_check");

    private JsonObject configForPort(int httpPort) {
        return new JsonObject()
                .put("pg.host",     postgres.getHost())
                .put("pg.port",     postgres.getFirstMappedPort())
                .put("pg.database", postgres.getDatabaseName())
                .put("pg.user",     postgres.getUsername())
                .put("pg.password", postgres.getPassword())
                .put("http.port",   httpPort);
    }

    @Test
    @DisplayName("Verticle undeploys cleanly  pool close completes without error")
    void verticleUndeploysCleanly(Vertx vertx, VertxTestContext ctx) {
        vertx.deployVerticle(
                new PgPrimaryCheckVerticle(),
                new DeploymentOptions().setConfig(configForPort(18010))
        ).compose(id -> {
            logger.info("Deployed (id={}), now undeploying", id);
            return vertx.undeploy(id);
        }).onSuccess(v -> {
            logger.info("Undeploy completed cleanly");
            ctx.completeNow();
        }).onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("Verticle can be redeployed on the same port after a clean undeploy")
    void verticleCanBeRedeployedOnSamePort(Vertx vertx, VertxTestContext ctx) {
        int port = 18011;
        JsonObject config = configForPort(port);
        WebClient client = WebClient.create(vertx, new WebClientOptions().setDefaultHost("localhost"));

        vertx.deployVerticle(new PgPrimaryCheckVerticle(), new DeploymentOptions().setConfig(config))
                .compose(id -> {
                    logger.info("First deploy (id={}), undeploying", id);
                    return vertx.undeploy(id);
                })
                .compose(v -> {
                    logger.info("Redeploying on same port {}", port);
                    return vertx.deployVerticle(new PgPrimaryCheckVerticle(), new DeploymentOptions().setConfig(config));
                })
                .compose(id -> {
                    logger.info("Second deploy (id={}), querying /primary", id);
                    return client.get(port, "localhost", "/primary").send();
                })
                .onSuccess(response -> ctx.verify(() -> {
                    client.close();
                    assertEquals(200, response.statusCode(),
                            "Redeployed verticle should return 200 for a standalone primary");
                    ctx.completeNow();
                }))
                .onFailure(err -> {
                    client.close();
                    ctx.failNow(err);
                });
    }
}
