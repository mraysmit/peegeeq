package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Consumer Group Subscription Options (Phase 3.2):
 * start position control (FROM_NOW, FROM_BEGINNING, FROM_MESSAGE_ID, FROM_TIMESTAMP),
 * heartbeat configuration, and CRUD on subscription options.
 *
 * <p>Rewritten 2026-08-10 on the ManagementApiIntegrationTest pattern (real server, real
 * database, real consumer groups). The previous version started NO server: every test hit a
 * hardcoded port, caught the connection failure, logged "skipping assertions" and returned
 * green — nine permanently vacuous tests. Two of its expectations were also fiction against
 * the real handler: update without an existing consumer group is 404 (the group must be
 * created first), and delete is idempotent 204 by design, never 404.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-22
 * @version 2.0
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SubscriptionOptionsCoreTest {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionOptionsCoreTest.class);
    private static final int TEST_PORT = 18085;
    private static final String QUEUE_NAME = "sub_options_queue";

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_sub_options_test");
        container.withUsername("peegeeq_test");
        container.withPassword("peegeeq_test");
        container.withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE);
        container.withReuse(false);
        return container;
    }

    private String deploymentId;
    private String setupId;
    private WebClient webClient;

    @BeforeAll
    void setupServer(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Setting up Subscription Options Integration Test ===");

        setupId = "sub-options-" + System.currentTimeMillis();

        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();

        RestServerConfig testConfig = new RestServerConfig(
                TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), java.util.List.of("*"));
        PeeGeeQRestServer server = new PeeGeeQRestServer(testConfig, setupService);
        vertx.deployVerticle(server)
            .compose(id -> {
                deploymentId = id;
                webClient = WebClient.create(vertx);
                return createSetupWithQueue();
            })
            .onSuccess(v -> {
                logger.info("Test setup complete: {}", setupId);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    private Future<Void> createSetupWithQueue() {
        JsonObject setupRequest = new JsonObject()
            .put("setupId", setupId)
            .put("databaseConfig", new JsonObject()
                .put("host", postgres.getHost())
                .put("port", postgres.getFirstMappedPort())
                .put("databaseName", "sub_options_db_" + System.currentTimeMillis())
                .put("username", postgres.getUsername())
                .put("password", postgres.getPassword())
                .put("schema", PostgreSQLTestConstants.TEST_SCHEMA)
                .put("templateDatabase", "template0")
                .put("encoding", "UTF8"))
            .put("queues", new JsonArray()
                .add(new JsonObject()
                    .put("queueName", QUEUE_NAME)
                    .put("maxRetries", 3)
                    .put("visibilityTimeout", 30)));

        return webClient.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .timeout(60000)
            .sendJsonObject(setupRequest)
            .compose(response -> {
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return Future.succeededFuture();
                }
                return Future.failedFuture("Failed to create setup: " + response.statusCode()
                        + " " + response.bodyAsString());
            });
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Tearing down Subscription Options Integration Test ===");
        (deploymentId != null ? vertx.undeploy(deploymentId) : Future.<Void>succeededFuture())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    /** The update/get/delete endpoints require the consumer group to exist in the handler. */
    private Future<Void> createConsumerGroup(String groupName) {
        return webClient.post(TEST_PORT, "localhost",
                "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/consumer-groups")
            .putHeader("content-type", "application/json")
            .sendJsonObject(new JsonObject().put("groupName", groupName))
            .compose(response -> {
                if (response.statusCode() == 201 || response.statusCode() == 200) {
                    return Future.succeededFuture();
                }
                return Future.failedFuture("Failed to create consumer group '" + groupName
                        + "': " + response.statusCode() + " " + response.bodyAsString());
            });
    }

    private String subscriptionUrl(String groupName) {
        return "/api/v1/consumer-groups/" + setupId + "/" + QUEUE_NAME + "/" + groupName + "/subscription";
    }

    @Test
    @Order(1)
    @DisplayName("Test 1: Update subscription options with FROM_NOW position")
    void testUpdateSubscriptionOptions_FromNow(VertxTestContext testContext) {
        JsonObject subscriptionOptions = new JsonObject()
            .put("startPosition", "FROM_NOW")
            .put("heartbeatIntervalSeconds", 30)
            .put("heartbeatTimeoutSeconds", 180);

        createConsumerGroup("group_from_now")
            .compose(v -> webClient.post(TEST_PORT, "localhost", subscriptionUrl("group_from_now"))
                .sendJsonObject(subscriptionOptions))
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode(), response.bodyAsString());
                JsonObject options = response.bodyAsJsonObject().getJsonObject("subscriptionOptions");
                assertNotNull(options, "response must carry subscriptionOptions");
                assertEquals("FROM_NOW", options.getString("startPosition"));
                assertEquals(30, options.getInteger("heartbeatIntervalSeconds"));
                assertEquals(180, options.getInteger("heartbeatTimeoutSeconds"));
                testContext.completeNow();
            })));
    }

    @Test
    @Order(2)
    @DisplayName("Test 2: Update subscription options with FROM_BEGINNING position")
    void testUpdateSubscriptionOptions_FromBeginning(VertxTestContext testContext) {
        JsonObject subscriptionOptions = new JsonObject()
            .put("startPosition", "FROM_BEGINNING")
            .put("heartbeatIntervalSeconds", 60)
            .put("heartbeatTimeoutSeconds", 300);

        createConsumerGroup("group_from_beginning")
            .compose(v -> webClient.post(TEST_PORT, "localhost", subscriptionUrl("group_from_beginning"))
                .sendJsonObject(subscriptionOptions))
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode(), response.bodyAsString());
                JsonObject options = response.bodyAsJsonObject().getJsonObject("subscriptionOptions");
                assertEquals("FROM_BEGINNING", options.getString("startPosition"));
                testContext.completeNow();
            })));
    }

    @Test
    @Order(3)
    @DisplayName("Test 3: Update subscription options with FROM_MESSAGE_ID position")
    void testUpdateSubscriptionOptions_FromMessageId(VertxTestContext testContext) {
        JsonObject subscriptionOptions = new JsonObject()
            .put("startPosition", "FROM_MESSAGE_ID")
            .put("startFromMessageId", 12345L)
            .put("heartbeatIntervalSeconds", 60)
            .put("heartbeatTimeoutSeconds", 300);

        createConsumerGroup("group_from_message_id")
            .compose(v -> webClient.post(TEST_PORT, "localhost", subscriptionUrl("group_from_message_id"))
                .sendJsonObject(subscriptionOptions))
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode(), response.bodyAsString());
                JsonObject options = response.bodyAsJsonObject().getJsonObject("subscriptionOptions");
                assertEquals("FROM_MESSAGE_ID", options.getString("startPosition"));
                assertEquals(12345L, options.getLong("startFromMessageId"));
                testContext.completeNow();
            })));
    }

    @Test
    @Order(4)
    @DisplayName("Test 4: Update subscription options with FROM_TIMESTAMP position")
    void testUpdateSubscriptionOptions_FromTimestamp(VertxTestContext testContext) {
        JsonObject subscriptionOptions = new JsonObject()
            .put("startPosition", "FROM_TIMESTAMP")
            .put("startFromTimestamp", "2025-11-22T00:00:00Z")
            .put("heartbeatIntervalSeconds", 60)
            .put("heartbeatTimeoutSeconds", 300);

        createConsumerGroup("group_from_timestamp")
            .compose(v -> webClient.post(TEST_PORT, "localhost", subscriptionUrl("group_from_timestamp"))
                .sendJsonObject(subscriptionOptions))
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode(), response.bodyAsString());
                JsonObject options = response.bodyAsJsonObject().getJsonObject("subscriptionOptions");
                assertEquals("FROM_TIMESTAMP", options.getString("startPosition"));
                assertNotNull(options.getString("startFromTimestamp"));
                testContext.completeNow();
            })));
    }

    @Test
    @Order(5)
    @DisplayName("Test 5: Get subscription options returns defaults when none configured")
    void testGetSubscriptionOptions_Defaults(VertxTestContext testContext) {
        // A group that exists but was never subscribed: the handler returns
        // NOT_CONFIGURED with SubscriptionOptions.defaults().
        createConsumerGroup("group_defaults")
            .compose(v -> webClient.get(TEST_PORT, "localhost", subscriptionUrl("group_defaults")).send())
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode(), response.bodyAsString());
                JsonObject body = response.bodyAsJsonObject();
                assertEquals("NOT_CONFIGURED", body.getString("status"));
                JsonObject options = body.getJsonObject("subscriptionOptions");
                assertNotNull(options, "response must carry default subscriptionOptions");
                assertEquals("FROM_NOW", options.getString("startPosition"));
                assertEquals(60, options.getInteger("heartbeatIntervalSeconds"));
                assertEquals(300, options.getInteger("heartbeatTimeoutSeconds"));
                testContext.completeNow();
            })));
    }

    @Test
    @Order(6)
    @DisplayName("Test 6: Get subscription options after update round-trips")
    void testGetSubscriptionOptions_AfterUpdate(VertxTestContext testContext) {
        // FROM_MESSAGE_ID round-trips exactly. FROM_NOW would not: the server
        // resolves it to a concrete message id at subscribe time.
        JsonObject subscriptionOptions = new JsonObject()
            .put("startPosition", "FROM_MESSAGE_ID")
            .put("startFromMessageId", 777L)
            .put("heartbeatIntervalSeconds", 45)
            .put("heartbeatTimeoutSeconds", 200);

        createConsumerGroup("group_round_trip")
            .compose(v -> webClient.post(TEST_PORT, "localhost", subscriptionUrl("group_round_trip"))
                .sendJsonObject(subscriptionOptions))
            .compose(updateResponse -> {
                testContext.verify(() ->
                    assertEquals(200, updateResponse.statusCode(), updateResponse.bodyAsString()));
                return webClient.get(TEST_PORT, "localhost", subscriptionUrl("group_round_trip")).send();
            })
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode(), response.bodyAsString());
                JsonObject options = response.bodyAsJsonObject().getJsonObject("subscriptionOptions");
                assertEquals("FROM_MESSAGE_ID", options.getString("startPosition"));
                assertEquals(777L, options.getLong("startFromMessageId"));
                assertEquals(45, options.getInteger("heartbeatIntervalSeconds"));
                assertEquals(200, options.getInteger("heartbeatTimeoutSeconds"));
                testContext.completeNow();
            })));
    }

    @Test
    @Order(7)
    @DisplayName("Test 7: Delete subscription options returns 204")
    void testDeleteSubscriptionOptions(VertxTestContext testContext) {
        JsonObject subscriptionOptions = new JsonObject()
            .put("startPosition", "FROM_BEGINNING");

        createConsumerGroup("group_delete")
            .compose(v -> webClient.post(TEST_PORT, "localhost", subscriptionUrl("group_delete"))
                .sendJsonObject(subscriptionOptions))
            .compose(updateResponse -> {
                testContext.verify(() ->
                    assertEquals(200, updateResponse.statusCode(), updateResponse.bodyAsString()));
                return webClient.delete(TEST_PORT, "localhost", subscriptionUrl("group_delete")).send();
            })
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(204, response.statusCode());
                testContext.completeNow();
            })));
    }

    @Test
    @Order(8)
    @DisplayName("Test 8: Delete non-existent subscription options is idempotent 204")
    void testDeleteSubscriptionOptions_Nonexistent(VertxTestContext testContext) {
        // The handler deliberately returns 204 for a missing subscription
        // (idempotent delete). The previous version of this test asserted an
        // imagined 404 — it never ran, so the fiction survived.
        webClient.delete(TEST_PORT, "localhost", subscriptionUrl("group_never_created"))
            .send()
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(204, response.statusCode());
                testContext.completeNow();
            })));
    }

    @Test
    @Order(9)
    @DisplayName("Test 9: Invalid start position returns 400")
    void testUpdateSubscriptionOptions_InvalidPosition(VertxTestContext testContext) {
        // The group must exist: the handler 404s on an unknown group BEFORE
        // parsing the body, so the 400 branch is only reachable past creation.
        JsonObject subscriptionOptions = new JsonObject()
            .put("startPosition", "INVALID_POSITION");

        createConsumerGroup("group_invalid_position")
            .compose(v -> webClient.post(TEST_PORT, "localhost", subscriptionUrl("group_invalid_position"))
                .sendJsonObject(subscriptionOptions))
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(400, response.statusCode(), response.bodyAsString());
                assertTrue(response.bodyAsJsonObject().containsKey("error"));
                testContext.completeNow();
            })));
    }

    @Test
    @Order(10)
    @DisplayName("Test 10: Update subscription options for an unknown consumer group returns 404")
    void testUpdateSubscriptionOptions_UnknownGroup(VertxTestContext testContext) {
        webClient.post(TEST_PORT, "localhost", subscriptionUrl("group_unknown"))
            .sendJsonObject(new JsonObject().put("startPosition", "FROM_NOW"))
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                assertEquals(404, response.statusCode(), response.bodyAsString());
                testContext.completeNow();
            })));
    }
}
