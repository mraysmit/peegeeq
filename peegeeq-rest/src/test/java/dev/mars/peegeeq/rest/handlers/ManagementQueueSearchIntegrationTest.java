package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Real HTTP/PostgreSQL regression for Jenkins #45: a queue-name search must
 * reduce the response, not merely appear in a request that returns every queue.
 * Twelve queues reproduce the more-than-one-UI-page condition independently
 * of queues left behind by other test classes.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManagementQueueSearchIntegrationTest {

    private static final String TARGET_QUEUE = "search_target";
    private static final int QUEUE_COUNT = 12;

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private String deploymentId;
    private WebClient webClient;
    private int port;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext testContext) {
        webClient = WebClient.create(vertx);
        PeeGeeQRestServer server = new PeeGeeQRestServer(
                new RestServerConfig(0, RestServerConfig.MonitoringConfig.defaults(), List.of("*")),
                PeeGeeQRuntime.createDatabaseSetupService());
        JsonArray queues = new JsonArray().add(new JsonObject().put("queueName", TARGET_QUEUE));
        for (int i = 1; i < QUEUE_COUNT; i++) {
            queues.add(new JsonObject().put("queueName", "unrelated_" + i));
        }

        vertx.deployVerticle(server)
                .compose(id -> {
                    deploymentId = id;
                    port = server.actualPort();
                    return webClient.post(port, "localhost", "/api/v1/database-setup/create")
                            .timeout(60000)
                            .sendJsonObject(new JsonObject()
                                    .put("setupId", "queue-search-test")
                                    .put("databaseConfig", new JsonObject()
                                            .put("host", postgres.getHost())
                                            .put("port", postgres.getFirstMappedPort())
                                            .put("databaseName", "queue_search_db")
                                            .put("username", postgres.getUsername())
                                            .put("password", postgres.getPassword())
                                            .put("schema", PostgreSQLTestConstants.TEST_SCHEMA)
                                            .put("templateDatabase", "template0")
                                            .put("encoding", "UTF8"))
                                    .put("queues", queues)
                                    .put("eventStores", new JsonArray()))
                            .compose(response -> response.statusCode() == 201 || response.statusCode() == 200
                                    ? Future.<Void>succeededFuture()
                                    : Future.failedFuture("Setup failed: " + response.statusCode()
                                            + " " + response.bodyAsString()));
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        if (webClient != null) {
            webClient.close();
        }
        Future<Void> undeploy = deploymentId == null
                ? Future.succeededFuture() : vertx.undeploy(deploymentId);
        undeploy.onSuccess(v -> testContext.completeNow()).onFailure(testContext::failNow);
    }

    @ParameterizedTest
    @CsvSource({"target,1", "SEARCH_TARGET,1", "missing,0", "search%target,0"})
    void searchFiltersReturnedQueuesAndCount(String search, int expectedCount, VertxTestContext testContext) {
        webClient.get(port, "localhost", "/api/v1/management/queues")
                .addQueryParam("search", search)
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    JsonObject body = response.bodyAsJsonObject();
                    assertEquals(expectedCount, body.getInteger("queueCount"));
                    JsonArray queues = body.getJsonArray("queues");
                    assertEquals(expectedCount, queues.size());
                    if (expectedCount == 1) {
                        assertEquals(TARGET_QUEUE, queues.getJsonObject(0).getString("queueName"));
                    }
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankSearchPreservesAllQueues(String search, VertxTestContext testContext) {
        webClient.get(port, "localhost", "/api/v1/management/queues")
                .addQueryParam("search", search)
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    assertEquals(QUEUE_COUNT, response.bodyAsJsonObject().getInteger("queueCount"));
                    assertEquals(QUEUE_COUNT, response.bodyAsJsonObject().getJsonArray("queues").size());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void omittedSearchPreservesAllQueues(VertxTestContext testContext) {
        webClient.get(port, "localhost", "/api/v1/management/queues")
                .send()
                .onSuccess(response -> testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    assertEquals(QUEUE_COUNT, response.bodyAsJsonObject().getInteger("queueCount"));
                    assertEquals(QUEUE_COUNT, response.bodyAsJsonObject().getJsonArray("queues").size());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
}
