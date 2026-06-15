package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.deadletter.DeadLetterService;
import dev.mars.peegeeq.api.health.HealthService;
import dev.mars.peegeeq.api.setup.*;
import dev.mars.peegeeq.api.subscription.SubscriptionService;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Partitioned Consumption REST API endpoints.
 *
 * <p>Tests the 5 new partitioned consumption endpoints:</p>
 * <ul>
 *   <li>POST .../partitions/join</li>
 *   <li>DELETE .../partitions/leave</li>
 *   <li>GET .../partitions</li>
 *   <li>POST .../partitions/fetch</li>
 *   <li>POST .../partitions/commit</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-12
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PartitionedConsumptionRestIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PartitionedConsumptionRestIntegrationTest.class);
    private static final int TEST_PORT = 18105;
    private static final String TOPIC_NAME = "partitioned_rest_test_topic";
    private static final String GROUP_NAME = "test-partitioned-group";

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_partitioned_rest_test");
        container.withUsername("peegeeq_test");
        container.withPassword("peegeeq_test");
        container.withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE);
        container.withReuse(false);
        return container;
    }

    private PeeGeeQRestServer server;
    private PeeGeeQManager peeGeeQManager;
    private String deploymentId;
    private String setupId;
    private WebClient webClient;

    // Stored from join test for use in fetch/commit/leave tests
    private int generation;

    @BeforeAll
    void setupServer(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Setting up Partitioned Consumption REST Integration Test ===");

        // Initialize schema directly on the container's default database (clean DB  Flyway runs all migrations)
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);

        setupId = "partitioned-test-setup";

        java.util.Properties props = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA).build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("partitioned-rest-test", props);
        peeGeeQManager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        peeGeeQManager.start()
            .compose(v -> {
                DatabaseSetupService setupService = new TestDatabaseSetupService(setupId, peeGeeQManager);

                RestServerConfig testConfig = new RestServerConfig(TEST_PORT,
                    RestServerConfig.MonitoringConfig.defaults(), java.util.List.of("*"));
                server = new PeeGeeQRestServer(testConfig, setupService);
                return vertx.deployVerticle(server);
            })
            .compose(id -> {
                deploymentId = id;
                webClient = WebClient.create(vertx);
                return setupTestData();
            })
            .onSuccess(v -> {
                logger.info("Test setup complete");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    private Future<Void> setupTestData() {
        try {
            String jdbcUrl = postgres.getJdbcUrl();
            try (Connection conn = DriverManager.getConnection(jdbcUrl,
                    postgres.getUsername(), postgres.getPassword())) {

                // Update the topic to OFFSET_WATERMARK mode
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE outbox_topics SET completion_tracking_mode = 'OFFSET_WATERMARK' WHERE topic = ?")) {
                    stmt.setString(1, TOPIC_NAME);
                    int updated = stmt.executeUpdate();
                    if (updated == 0) {
                        // Topic doesn't exist yet insert it
                        try (PreparedStatement ins = conn.prepareStatement(
                                "INSERT INTO outbox_topics (topic, completion_tracking_mode) VALUES (?, 'OFFSET_WATERMARK')")) {
                            ins.setString(1, TOPIC_NAME);
                            ins.executeUpdate();
                        }
                    }
                }

                // Create subscription
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO outbox_topic_subscriptions (topic, group_name, subscription_status) " +
                        "VALUES (?, ?, 'ACTIVE') ON CONFLICT (topic, group_name) DO NOTHING")) {
                    stmt.setString(1, TOPIC_NAME);
                    stmt.setString(2, GROUP_NAME);
                    stmt.executeUpdate();
                }

                // Insert messages with different message_group for partitions
                String insertSql = "INSERT INTO outbox (topic, payload, status, message_group, created_at) " +
                        "VALUES (?, ?::jsonb, 'PENDING', ?, NOW())";
                for (int i = 0; i < 3; i++) {
                    try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                        stmt.setString(1, TOPIC_NAME);
                        stmt.setString(2, "{\"index\":" + i + "}");
                        stmt.setString(3, "partition-A");
                        stmt.executeUpdate();
                    }
                }
                for (int i = 0; i < 2; i++) {
                    try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                        stmt.setString(1, TOPIC_NAME);
                        stmt.setString(2, "{\"index\":" + i + "}");
                        stmt.setString(3, "partition-B");
                        stmt.executeUpdate();
                    }
                }

                logger.info("Test data inserted: 5 messages (3 in partition-A, 2 in partition-B)");
            }
            return Future.succeededFuture();
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        Future<Void> closeFuture = Future.succeededFuture();
        if (peeGeeQManager != null) {
            closeFuture = peeGeeQManager.closeReactive();
        }
        closeFuture
            .compose(v -> deploymentId != null ? vertx.undeploy(deploymentId) : Future.succeededFuture())
            .onSuccess(v -> testContext.completeNow()).onFailure(testContext::failNow);
    }

    // ========================================================================
    // Test 5.10: POST join returns assignments
    // ========================================================================

    @Test
    @Order(1)
    @DisplayName("POST join returns assignments")
    void testPostJoin_returnsAssignments(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s/%s/partitions/join",
                setupId, TOPIC_NAME, GROUP_NAME);

        JsonObject body = new JsonObject().put("instanceId", "rest-instance-1");

        webClient.post(TEST_PORT, "localhost", path)
            .putHeader("content-type", "application/json")
            .sendJsonObject(body)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(),
                        "Expected 200, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonArray assignments = response.bodyAsJsonArray();
                    assertNotNull(assignments, "Response should be a JSON array");
                    assertEquals(2, assignments.size(), "Single instance should own both partitions");

                    // Store generation for use in fetch/commit tests
                    generation = assignments.getJsonObject(0).getInteger("generation");
                    assertTrue(generation > 0, "Generation should be positive");

                    logger.info("Join successful: {} partitions, generation={}", assignments.size(), generation);
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    // ========================================================================
    // Test 5.11: DELETE leave triggers rebalance (join second instance, leave first)
    // ========================================================================

    @Test
    @Order(2)
    @DisplayName("DELETE leave triggers rebalance")
    void testDeleteLeave_triggersRebalance(VertxTestContext testContext) {
        // First join instance-2
        String joinPath = String.format("/api/v1/setups/%s/subscriptions/%s/%s/partitions/join",
                setupId, TOPIC_NAME, GROUP_NAME);
        JsonObject joinBody = new JsonObject().put("instanceId", "rest-instance-2");

        webClient.post(TEST_PORT, "localhost", joinPath)
            .putHeader("content-type", "application/json")
            .sendJsonObject(joinBody)
            .compose(joinResponse -> {
                assertEquals(200, joinResponse.statusCode());

                // Now leave instance-2
                String leavePath = String.format(
                        "/api/v1/setups/%s/subscriptions/%s/%s/partitions/leave?instanceId=rest-instance-2",
                        setupId, TOPIC_NAME, GROUP_NAME);

                return webClient.delete(TEST_PORT, "localhost", leavePath).send();
            })
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(),
                        "Expected 200, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonObject result = response.bodyAsJsonObject();
                    assertTrue(result.getBoolean("success"), "Leave should succeed");
                    assertEquals("left", result.getString("action"));
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    // ========================================================================
    // Test 5.12: GET partitions returns assignment list
    // ========================================================================

    @Test
    @Order(3)
    @DisplayName("GET partitions returns assignments")
    void testGetPartitions_returnsAssignments(VertxTestContext testContext) {
        // Re-join to get fresh generation
        String joinPath = String.format("/api/v1/setups/%s/subscriptions/%s/%s/partitions/join",
                setupId, TOPIC_NAME, GROUP_NAME);
        JsonObject body = new JsonObject().put("instanceId", "rest-instance-1");

        webClient.post(TEST_PORT, "localhost", joinPath)
            .putHeader("content-type", "application/json")
            .sendJsonObject(body)
            .compose(joinResponse -> {
                assertEquals(200, joinResponse.statusCode());
                JsonArray assignments = joinResponse.bodyAsJsonArray();
                generation = assignments.getJsonObject(0).getInteger("generation");

                String path = String.format(
                        "/api/v1/setups/%s/subscriptions/%s/%s/partitions?instanceId=rest-instance-1",
                        setupId, TOPIC_NAME, GROUP_NAME);
                return webClient.get(TEST_PORT, "localhost", path).send();
            })
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(),
                        "Expected 200, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonArray assignments = response.bodyAsJsonArray();
                    assertNotNull(assignments);
                    assertEquals(2, assignments.size(), "Should have 2 partitions");
                    for (int i = 0; i < assignments.size(); i++) {
                        assertEquals("rest-instance-1", assignments.getJsonObject(i).getString("instanceId"));
                    }
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    // ========================================================================
    // Test 5.13: POST fetch returns messages in order
    // ========================================================================

    @Test
    @Order(4)
    @DisplayName("POST fetch returns messages in order")
    void testPostFetch_returnsMessages(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s/%s/partitions/fetch",
                setupId, TOPIC_NAME, GROUP_NAME);

        JsonObject body = new JsonObject()
            .put("partitionKey", "partition-A")
            .put("batchSize", 10)
            .put("generation", generation);

        webClient.post(TEST_PORT, "localhost", path)
            .putHeader("content-type", "application/json")
            .sendJsonObject(body)
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(),
                        "Expected 200, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonArray messages = response.bodyAsJsonArray();
                    assertNotNull(messages);
                    assertEquals(3, messages.size(), "Should fetch all 3 messages in partition-A");

                    // Verify ordering
                    for (int i = 1; i < messages.size(); i++) {
                        long prevId = messages.getJsonObject(i - 1).getLong("id");
                        long currId = messages.getJsonObject(i).getLong("id");
                        assertTrue(currId > prevId, "Messages must be in ascending id order");
                    }
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    // ========================================================================
    // Test 5.14: POST commit returns committed result
    // ========================================================================

    @Test
    @Order(5)
    @DisplayName("POST commit returns result")
    void testPostCommit_returnsResult(VertxTestContext testContext) {
        // First fetch to get a message ID to commit
        String fetchPath = String.format("/api/v1/setups/%s/subscriptions/%s/%s/partitions/fetch",
                setupId, TOPIC_NAME, GROUP_NAME);

        JsonObject fetchBody = new JsonObject()
            .put("partitionKey", "partition-B")
            .put("batchSize", 10)
            .put("generation", generation);

        webClient.post(TEST_PORT, "localhost", fetchPath)
            .putHeader("content-type", "application/json")
            .sendJsonObject(fetchBody)
            .compose(fetchResponse -> {
                assertEquals(200, fetchResponse.statusCode());
                JsonArray messages = fetchResponse.bodyAsJsonArray();
                assertTrue(messages.size() > 0, "Should have messages to commit");
                long lastId = messages.getJsonObject(messages.size() - 1).getLong("id");

                String commitPath = String.format("/api/v1/setups/%s/subscriptions/%s/%s/partitions/commit",
                        setupId, TOPIC_NAME, GROUP_NAME);

                JsonObject commitBody = new JsonObject()
                    .put("partitionKey", "partition-B")
                    .put("offset", lastId)
                    .put("generation", generation);

                return webClient.post(TEST_PORT, "localhost", commitPath)
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(commitBody);
            })
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(),
                        "Expected 200, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonObject result = response.bodyAsJsonObject();
                    assertTrue(result.getBoolean("committed"), "Commit should return true");
                    assertEquals(TOPIC_NAME, result.getString("topic"));
                    assertEquals(GROUP_NAME, result.getString("groupName"));
                    assertEquals("partition-B", result.getString("partitionKey"));
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    // ========================================================================
    // Test DatabaseSetupService implementation
    // ========================================================================

    private static class TestDatabaseSetupService implements DatabaseSetupService {

        private final String supportedSetupId;
        private final PeeGeeQManager manager;

        TestDatabaseSetupService(String setupId, PeeGeeQManager manager) {
            this.supportedSetupId = setupId;
            this.manager = manager;
        }

        @Override
        public Future<DatabaseSetupResult> createCompleteSetup(DatabaseSetupRequest request) {
            return Future.failedFuture(new UnsupportedOperationException("Not needed for partitioned tests"));
        }

        @Override
        public Future<Void> destroySetup(String setupId) {
            return Future.failedFuture(new UnsupportedOperationException("Not needed for partitioned tests"));
        }

        @Override
        public Future<DatabaseSetupStatus> getSetupStatus(String setupId) {
            return Future.failedFuture(new UnsupportedOperationException("Not needed for partitioned tests"));
        }

        @Override
        public Future<DatabaseSetupResult> getSetupResult(String setupId) {
            return Future.failedFuture(new UnsupportedOperationException("Not needed for partitioned tests"));
        }

        @Override
        public Future<Void> addQueue(String setupId, QueueConfig config) {
            return Future.failedFuture(new UnsupportedOperationException("Not needed for partitioned tests"));
        }

        @Override
        public Future<Void> addEventStore(String setupId, EventStoreConfig config) {
            return Future.failedFuture(new UnsupportedOperationException("Not needed for partitioned tests"));
        }

        @Override
        public Future<Void> removeEventStore(String setupId, String storeName) {
            return Future.failedFuture(new UnsupportedOperationException("Not needed for partitioned tests"));
        }

        @Override
        public Future<Set<String>> getAllActiveSetupIds() {
            return Future.succeededFuture(Set.of(supportedSetupId));
        }

        @Override
        public void addFactoryRegistration(Consumer<QueueFactoryRegistrar> registration) {
            // No-op
        }

        @Override
        public SubscriptionService getSubscriptionServiceForSetup(String setupId) {
            if (supportedSetupId.equals(setupId)) {
                return manager.createSubscriptionService();
            }
            return null;
        }

        @Override
        public DeadLetterService getDeadLetterServiceForSetup(String setupId) {
            return null;
        }

        @Override
        public HealthService getHealthServiceForSetup(String setupId) {
            return null;
        }

        @Override
        public QueueFactoryProvider getQueueFactoryProviderForSetup(String setupId) {
            return null;
        }
    }
}
