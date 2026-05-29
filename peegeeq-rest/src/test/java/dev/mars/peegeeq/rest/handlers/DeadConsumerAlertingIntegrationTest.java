package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.deadletter.DeadLetterService;
import dev.mars.peegeeq.api.health.HealthService;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
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
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Dead Consumer Alerting REST API endpoints.
 *
 * Tests the alerting endpoints that provide programmatic access to dead consumer
 * detection data that was previously only available through log output:
 * - GET /api/v1/setups/:setupId/consumer-alerts/dead - List dead subscriptions
 * - GET /api/v1/setups/:setupId/consumer-alerts/summary - Subscription health summary
 * - GET /api/v1/setups/:setupId/consumer-alerts/blocked - Blocked message stats
 *
 * Classification: INTEGRATION TEST
 * - Uses real PostgreSQL database (TestContainers)
 * - Uses real Vert.x HTTP server
 * - Tests end-to-end dead consumer alerting flow
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DeadConsumerAlertingIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(DeadConsumerAlertingIntegrationTest.class);
    private static final int TEST_PORT = 18099;
    private static final String TOPIC_NAME = "alerting_test_topic";
    private static final String HEALTHY_GROUP = "healthy-consumer-group";
    private static final String DEAD_GROUP = "dead-consumer-group";

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_alerting_test");
        container.withUsername("peegeeq_test");
        container.withPassword("peegeeq_test");
        container.withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE);
        container.withReuse(false);
        return container;
    }

    private PeeGeeQRestServer server;
    private DatabaseSetupService setupService;
    private String deploymentId;
    private String setupId;
    private WebClient webClient;
    private PeeGeeQManager peeGeeQManager;

    @BeforeAll
    void setupServer(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Setting up Dead Consumer Alerting Integration Test ===");

        setupId = "alerting-test-setup";

        // Create PeeGeeQManager directly so we have access to subscription services
        java.util.Properties props = PeeGeeQTestConfig.builder().from(postgres).build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("alerting-test", props);
        peeGeeQManager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        // Schema init is blocking (Flyway/JDBC)  must run on a worker thread, not the event loop.
        vertx.executeBlocking(() -> {
            PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
            return null;
        })
        .compose(v -> peeGeeQManager.start())
        .compose(v -> {
            // Create a test DatabaseSetupService that delegates to our manager
            setupService = new TestDatabaseSetupService(setupId, peeGeeQManager);

            RestServerConfig testConfig = new RestServerConfig(TEST_PORT,
                RestServerConfig.MonitoringConfig.defaults(), java.util.List.of("*"));
            server = new PeeGeeQRestServer(testConfig, setupService);
            return vertx.deployVerticle(server);
        })
        .compose(id -> {
            deploymentId = id;
            logger.info("REST server deployed with ID: {}", deploymentId);
            webClient = WebClient.create(vertx);

            // Create subscriptions directly using the manager's subscription service
            SubscriptionService subscriptionService = peeGeeQManager.createSubscriptionService();

            SubscriptionOptions healthyOptions = SubscriptionOptions.builder()
                .heartbeatIntervalSeconds(60)
                .heartbeatTimeoutSeconds(120)
                .build();

            SubscriptionOptions deadOptions = SubscriptionOptions.builder()
                .heartbeatIntervalSeconds(1)
                .heartbeatTimeoutSeconds(2)
                .deadAfterMisses(1)
                .build();

            return subscriptionService.subscribe(TOPIC_NAME, HEALTHY_GROUP, healthyOptions)
                .compose(ignored -> subscriptionService.subscribe(TOPIC_NAME, DEAD_GROUP, deadOptions));
        })
        .compose(v -> makeGroupDead(vertx))
        .onSuccess(v -> {
            logger.info("Test setup complete with healthy and dead subscriptions");
            testContext.completeNow();
        })
        .onFailure(testContext::failNow);
    }

    /**
     * Forces the dead consumer group to become DEAD by using JDBC to expire the heartbeat
     * and run detection. This is test precondition setup, not testing the REST endpoint itself.
     */
    private Future<Void> makeGroupDead(Vertx vertx) {
        io.vertx.core.Promise<Void> promise = io.vertx.core.Promise.promise();
        vertx.setTimer(2000, timerId -> {
            try {
                String jdbcUrl = postgres.getJdbcUrl();
                try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                        jdbcUrl, postgres.getUsername(), postgres.getPassword())) {

                    // Phase 1: Increment consecutive_misses for expired subscriptions
                    try (java.sql.PreparedStatement stmt = conn.prepareStatement("""
                        UPDATE outbox_topic_subscriptions
                        SET consecutive_misses = consecutive_misses + 1,
                            last_active_at = NOW()
                        WHERE subscription_status IN ('ACTIVE', 'PAUSED')
                          AND last_heartbeat_at + (heartbeat_timeout_seconds || ' seconds')::INTERVAL < NOW()
                        """)) {
                        int count = stmt.executeUpdate();
                        logger.info("Incremented consecutive_misses for {} subscriptions", count);
                    }

                    // Phase 2: Mark DEAD those that reached the threshold
                    try (java.sql.PreparedStatement stmt = conn.prepareStatement("""
                        UPDATE outbox_topic_subscriptions
                        SET subscription_status = 'DEAD',
                            last_active_at = NOW()
                        WHERE subscription_status IN ('ACTIVE', 'PAUSED')
                          AND consecutive_misses >= dead_after_misses
                        """)) {
                        int count = stmt.executeUpdate();
                        logger.info("Marked {} subscriptions as DEAD", count);
                    }
                }
                promise.complete();
            } catch (Exception e) {
                logger.error("Failed to run detection via JDBC", e);
                promise.fail(e);
            }
        });
        return promise.future();
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Tearing down Dead Consumer Alerting Integration Test ===");

        Future<Void> closeFuture = Future.succeededFuture();
        if (peeGeeQManager != null) {
            closeFuture = peeGeeQManager.closeReactive();
        }
        closeFuture
            .compose(v -> deploymentId != null ? vertx.undeploy(deploymentId) : Future.succeededFuture())
            .onSuccess(v -> testContext.completeNow()).onFailure(testContext::failNow);
    }

    // ========== Alerting Endpoint Tests ==========

    @Test
    @Order(1)
    @DisplayName("GET /consumer-alerts/dead returns dead subscriptions")
    void testListDeadSubscriptions(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/consumer-alerts/dead", setupId);

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(),
                        "Expected 200, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response should be a JSON object");

                    JsonArray deadSubscriptions = body.getJsonArray("deadSubscriptions");
                    assertNotNull(deadSubscriptions, "Should contain deadSubscriptions array");
                    assertTrue(deadSubscriptions.size() >= 1,
                        "Should have at least 1 dead subscription, got: " + deadSubscriptions.size());

                    // Verify the dead group appears in the results
                    boolean foundDeadGroup = false;
                    for (int i = 0; i < deadSubscriptions.size(); i++) {
                        JsonObject sub = deadSubscriptions.getJsonObject(i);
                        assertNotNull(sub.getString("topic"), "Each dead sub should have a topic");
                        assertNotNull(sub.getString("groupName"), "Each dead sub should have a groupName");
                        assertEquals("DEAD", sub.getString("state"), "Each sub should be in DEAD state");
                        if (DEAD_GROUP.equals(sub.getString("groupName"))) {
                            foundDeadGroup = true;
                            assertEquals(TOPIC_NAME, sub.getString("topic"));
                        }
                    }
                    assertTrue(foundDeadGroup, "Should find the dead consumer group in results");

                    // Verify metadata
                    assertTrue(body.containsKey("totalDead"), "Should contain totalDead count");
                    assertEquals(deadSubscriptions.size(), body.getInteger("totalDead"));

                    logger.info("Dead subscriptions response: {}", body.encodePrettily());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(2)
    @DisplayName("GET /consumer-alerts/summary returns subscription health summary")
    void testSubscriptionHealthSummary(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/consumer-alerts/summary", setupId);

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(),
                        "Expected 200, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response should be a JSON object");

                    // Verify all expected fields
                    assertTrue(body.containsKey("activeCount"), "Should contain activeCount");
                    assertTrue(body.containsKey("pausedCount"), "Should contain pausedCount");
                    assertTrue(body.containsKey("deadCount"), "Should contain deadCount");
                    assertTrue(body.containsKey("cancelledCount"), "Should contain cancelledCount");
                    assertTrue(body.containsKey("topicCount"), "Should contain topicCount");
                    assertTrue(body.containsKey("totalCount"), "Should contain totalCount");
                    assertTrue(body.containsKey("hasDeadSubscriptions"), "Should contain hasDeadSubscriptions");

                    // We have 1 healthy (ACTIVE) and 1 dead (DEAD)
                    assertTrue(body.getInteger("activeCount") >= 1,
                        "Should have at least 1 active subscription");
                    assertTrue(body.getInteger("deadCount") >= 1,
                        "Should have at least 1 dead subscription");
                    assertTrue(body.getBoolean("hasDeadSubscriptions"),
                        "hasDeadSubscriptions should be true");
                    assertTrue(body.getInteger("totalCount") >= 2,
                        "Should have at least 2 total subscriptions");
                    assertTrue(body.getInteger("topicCount") >= 1,
                        "Should have at least 1 topic");

                    logger.info("Health summary response: {}", body.encodePrettily());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(3)
    @DisplayName("GET /consumer-alerts/blocked returns blocked message stats")
    void testBlockedMessageStats(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/consumer-alerts/blocked", setupId);

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode(),
                        "Expected 200, got: " + response.statusCode() + " - " + response.bodyAsString());
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response should be a JSON object");

                    JsonArray blockedStats = body.getJsonArray("blockedStats");
                    assertNotNull(blockedStats, "Should contain blockedStats array");

                    // The dead group may or may not have blocked messages depending on
                    // whether messages were published. With no messages, the stats list
                    // should be empty or contain entries with 0 blocked.
                    // Either way, the response structure should be correct.
                    for (int i = 0; i < blockedStats.size(); i++) {
                        JsonObject stat = blockedStats.getJsonObject(i);
                        assertNotNull(stat.getString("topic"), "Each stat should have a topic");
                        assertNotNull(stat.getString("groupName"), "Each stat should have a groupName");
                        assertTrue(stat.containsKey("blockedPending"), "Each stat should have blockedPending");
                        assertTrue(stat.containsKey("blockedProcessing"), "Each stat should have blockedProcessing");
                        assertTrue(stat.containsKey("totalBlocked"), "Each stat should have totalBlocked");
                    }

                    assertTrue(body.containsKey("totalBlockedGroups"), "Should contain totalBlockedGroups count");

                    logger.info("Blocked stats response: {}", body.encodePrettily());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(4)
    @DisplayName("GET /consumer-alerts/dead returns 404 for unknown setup")
    void testDeadSubscriptionsUnknownSetup(VertxTestContext testContext) {
        String path = "/api/v1/setups/nonexistent-setup/consumer-alerts/dead";

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(404, response.statusCode(),
                        "Expected 404 for unknown setup, got: " + response.statusCode());
                    logger.info("Correctly returned 404 for unknown setup");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(5)
    @DisplayName("GET /consumer-alerts/summary returns 404 for unknown setup")
    void testSummaryUnknownSetup(VertxTestContext testContext) {
        String path = "/api/v1/setups/nonexistent-setup/consumer-alerts/summary";

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(404, response.statusCode(),
                        "Expected 404 for unknown setup, got: " + response.statusCode());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(6)
    @DisplayName("GET /consumer-alerts/blocked returns 404 for unknown setup")
    void testBlockedUnknownSetup(VertxTestContext testContext) {
        String path = "/api/v1/setups/nonexistent-setup/consumer-alerts/blocked";

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(404, response.statusCode(),
                        "Expected 404 for unknown setup, got: " + response.statusCode());
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(7)
    @DisplayName("Dead subscription contains heartbeat timing details")
    void testDeadSubscriptionTimingDetails(VertxTestContext testContext) {
        String path = String.format("/api/v1/setups/%s/consumer-alerts/dead", setupId);

        webClient.get(TEST_PORT, "localhost", path)
            .send()
            .onSuccess(response -> {
                testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    JsonObject body = response.bodyAsJsonObject();
                    JsonArray deadSubscriptions = body.getJsonArray("deadSubscriptions");
                    assertFalse(deadSubscriptions.isEmpty(), "Should have dead subscriptions");

                    // Find our dead group and verify timing details
                    for (int i = 0; i < deadSubscriptions.size(); i++) {
                        JsonObject sub = deadSubscriptions.getJsonObject(i);
                        if (DEAD_GROUP.equals(sub.getString("groupName"))) {
                            assertNotNull(sub.getString("lastHeartbeatAt"),
                                "Dead subscription should have lastHeartbeatAt");
                            assertTrue(sub.containsKey("heartbeatTimeoutSeconds"),
                                "Dead subscription should have heartbeatTimeoutSeconds");
                            assertEquals(TOPIC_NAME, sub.getString("topic"));
                            return;
                        }
                    }
                    fail("Dead consumer group " + DEAD_GROUP + " not found in results");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    /**
     * Test-only DatabaseSetupService that delegates to a pre-built PeeGeeQManager
     * for a single setupId. This avoids the need to go through the full REST create
     * flow which has Flyway migration issues in the test environment.
     */
    private static class TestDatabaseSetupService implements DatabaseSetupService {

        private final String supportedSetupId;
        private final PeeGeeQManager manager;

        TestDatabaseSetupService(String setupId, PeeGeeQManager manager) {
            this.supportedSetupId = setupId;
            this.manager = manager;
        }

        @Override
        public Future<DatabaseSetupResult> createCompleteSetup(DatabaseSetupRequest request) {
            return Future.failedFuture(new UnsupportedOperationException("Not needed for alerting tests"));
        }

        @Override
        public Future<Void> destroySetup(String setupId) {
            return Future.failedFuture(new UnsupportedOperationException("Not needed for alerting tests"));
        }

        @Override
        public Future<DatabaseSetupStatus> getSetupStatus(String setupId) {
            return Future.failedFuture(new UnsupportedOperationException("Not needed for alerting tests"));
        }

        @Override
        public Future<DatabaseSetupResult> getSetupResult(String setupId) {
            return Future.failedFuture(new UnsupportedOperationException("Not needed for alerting tests"));
        }

        @Override
        public Future<Void> addQueue(String setupId, QueueConfig config) {
            return Future.failedFuture(new UnsupportedOperationException("Not needed for alerting tests"));
        }

        @Override
        public Future<Void> addEventStore(String setupId, EventStoreConfig config) {
            return Future.failedFuture(new UnsupportedOperationException("Not needed for alerting tests"));
        }

        @Override
        public Future<Set<String>> getAllActiveSetupIds() {
            return Future.succeededFuture(Set.of(supportedSetupId));
        }

        @Override
        public void addFactoryRegistration(Consumer<QueueFactoryRegistrar> registration) {
            // No-op for alerting tests
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
