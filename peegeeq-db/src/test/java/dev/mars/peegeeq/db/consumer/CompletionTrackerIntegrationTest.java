package dev.mars.peegeeq.db.consumer;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.db.subscription.TopicConfig;
import dev.mars.peegeeq.db.subscription.TopicConfigService;
import dev.mars.peegeeq.db.subscription.TopicSemantics;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.vertx.junit5.VertxTestContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for CompletionTracker.
 * 
 * <p>Tests the completion tracking logic for consumer groups including:
 * <ul>
 *   <li>Marking messages as completed</li>
 *   <li>Incrementing completion counters</li>
 *   <li>Marking messages as fully completed when all groups finish</li>
 *   <li>Idempotent completion</li>
 *   <li>Failure tracking</li>
 * </ul>
 * </p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-12
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Tag(TestCategories.FLAKY)  // Tests are unstable in parallel execution - needs investigation
public class CompletionTrackerIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(CompletionTrackerIntegrationTest.class);

    private PgConnectionManager connectionManager;
    private CompletionTracker tracker;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;

    @BeforeEach
    public void setUp() throws Exception {
        super.setUpBaseIntegration();

        // Create connection manager using the shared Vertx instance
        connectionManager = new PgConnectionManager(manager.getVertx(), null);

        // Get PostgreSQL container and create pool
        var postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema("public")
                .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(10)
                .build();

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        tracker = new CompletionTracker(connectionManager, "peegeeq-main");
        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");

        logger.info("CompletionTracker test setup complete");
    }

    @Test
    public void testMarkCompletedSingleGroup(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testMarkCompletedSingleGroup STARTED ===");

        String topic = "test-completion-single-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";

        // Create topic with QUEUE semantics (required_consumer_groups = 1)
        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.QUEUE)
                .build();
        SubscriptionOptions subscriptionOptions = SubscriptionOptions.builder().build();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, subscriptionOptions))
                .compose(v -> insertMessage(topic, new JsonObject().put("test", "message1")))
                .compose(messageId -> {
                    // Mark as completed
                    return tracker.markCompleted(messageId, groupName, topic)
                            .compose(v -> getMessageStatus(messageId));
                })
                .onSuccess(status -> {
                    try {
                        logger.info("Message status: {}", status);
                        assertEquals("COMPLETED", status.getString("status"),
                                "Message should be COMPLETED after single group completes");
                        assertEquals(1, status.getInteger("completed_consumer_groups"),
                                "Completed counter should be 1");
                        assertEquals(1, status.getInteger("required_consumer_groups"),
                                "Required counter should be 1");
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                })
                .onFailure(throwable -> {
                    logger.error("Test failed", throwable);
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testMarkCompletedSingleGroup COMPLETED ===");
    }

    @Test
    public void testMarkCompletedMultipleGroups(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testMarkCompletedMultipleGroups STARTED ===");

        String topic = "test-completion-multiple-" + UUID.randomUUID().toString().substring(0, 8);
        String group1 = "group1";
        String group2 = "group2";
        String group3 = "group3";

        // Create topic with PUB_SUB semantics
        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build();

        SubscriptionOptions subscriptionOptions = SubscriptionOptions.builder()
                .build();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        topicConfigService.createTopic(topicConfig)
                .compose(v -> subscriptionManager.subscribe(topic, group1, subscriptionOptions))
                .compose(v -> subscriptionManager.subscribe(topic, group2, subscriptionOptions))
                .compose(v -> subscriptionManager.subscribe(topic, group3, subscriptionOptions))
                .compose(v -> insertMessage(topic, new JsonObject().put("test", "message1")))
                .compose(messageId -> {
                    // Mark group1 as completed
                    return tracker.markCompleted(messageId, group1, topic)
                            .compose(v -> getMessageStatus(messageId))
                            .compose(status -> {
                                logger.info("After group1: {}", status);
                                assertEquals("PENDING", status.getString("status"),
                                        "Message should still be PENDING after 1/3 groups");
                                assertEquals(1, status.getInteger("completed_consumer_groups"));
                                assertEquals(3, status.getInteger("required_consumer_groups"));

                                // Mark group2 as completed
                                return tracker.markCompleted(messageId, group2, topic);
                            })
                            .compose(v -> getMessageStatus(messageId))
                            .compose(status -> {
                                logger.info("After group2: {}", status);
                                assertEquals("PENDING", status.getString("status"),
                                        "Message should still be PENDING after 2/3 groups");
                                assertEquals(2, status.getInteger("completed_consumer_groups"));

                                // Mark group3 as completed
                                return tracker.markCompleted(messageId, group3, topic);
                            })
                            .compose(v -> getMessageStatus(messageId));
                })
                .onSuccess(status -> {
                    try {
                        logger.info("After group3: {}", status);
                        assertEquals("COMPLETED", status.getString("status"),
                                "Message should be COMPLETED after all 3 groups finish");
                        assertEquals(3, status.getInteger("completed_consumer_groups"));
                        assertEquals(3, status.getInteger("required_consumer_groups"));
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                })
                .onFailure(throwable -> {
                    logger.error("Test failed", throwable);
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testMarkCompletedMultipleGroups COMPLETED ===");
    }

    @Test
    public void testMarkCompletedIdempotent(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testMarkCompletedIdempotent STARTED ===");

        String topic = "test-completion-idempotent-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";

        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.QUEUE)
                .build();
        SubscriptionOptions subscriptionOptions = SubscriptionOptions.builder().build();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, subscriptionOptions))
                .compose(v -> insertMessage(topic, new JsonObject().put("test", "message1")))
                .compose(messageId -> {
                    // Mark as completed twice
                    return tracker.markCompleted(messageId, groupName, topic)
                            .compose(v -> tracker.markCompleted(messageId, groupName, topic))
                            .compose(v -> getMessageStatus(messageId));
                })
                .onSuccess(status -> {
                    try {
                        logger.info("Message status after double completion: {}", status);
                        assertEquals("COMPLETED", status.getString("status"));
                        assertEquals(1, status.getInteger("completed_consumer_groups"),
                                "Counter should not increment twice (idempotent)");
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                })
                .onFailure(throwable -> {
                    logger.error("Test failed", throwable);
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testMarkCompletedIdempotent COMPLETED ===");
    }

    @Test
    public void testMarkCompletedIdempotentDoesNotOvercountInMultiGroupTopic(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testMarkCompletedIdempotentDoesNotOvercountInMultiGroupTopic STARTED ===");

        String topic = "test-completion-idempotent-multigroup-" + UUID.randomUUID().toString().substring(0, 8);
        String group1 = "group1";
        String group2 = "group2";

        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build();

        SubscriptionOptions subscriptionOptions = SubscriptionOptions.builder().build();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        topicConfigService.createTopic(topicConfig)
                .compose(v -> subscriptionManager.subscribe(topic, group1, subscriptionOptions))
                .compose(v -> subscriptionManager.subscribe(topic, group2, subscriptionOptions))
                .compose(v -> insertMessage(topic, new JsonObject().put("test", "message1")))
                .compose(messageId -> tracker.markCompleted(messageId, group1, topic)
                        .compose(v -> tracker.markCompleted(messageId, group1, topic))
                        .compose(v -> getMessageStatus(messageId)))
                .onSuccess(status -> {
                    try {
                        assertEquals("PENDING", status.getString("status"),
                                "Message must remain PENDING after duplicate completion from same group");
                        assertEquals(1, status.getInteger("completed_consumer_groups"),
                                "Duplicate completion for same group must not increment counter twice");
                        assertEquals(2, status.getInteger("required_consumer_groups"));
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                })
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testMarkCompletedIdempotentDoesNotOvercountInMultiGroupTopic COMPLETED ===");
    }

    @Test
    public void testMarkFailed(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testMarkFailed STARTED ===");

        String topic = "test-completion-failed-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String errorMessage = "Test error message";

        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.QUEUE)
                .build();
        SubscriptionOptions subscriptionOptions = SubscriptionOptions.builder().build();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, subscriptionOptions))
                .compose(v -> insertMessage(topic, new JsonObject().put("test", "message1")))
                .compose(messageId -> {
                    // Mark as failed
                    return tracker.markFailed(messageId, groupName, topic, errorMessage)
                            .compose(v -> getTrackingRowStatus(messageId, groupName))
                            .compose(trackingStatus -> {
                                logger.info("Tracking row status: {}", trackingStatus);
                                assertEquals("FAILED", trackingStatus.getString("status"));
                                assertEquals(errorMessage, trackingStatus.getString("error_message"));

                                // Verify message status is still PENDING (not completed)
                                return getMessageStatus(messageId);
                            });
                })
                .onSuccess(messageStatus -> {
                    try {
                        logger.info("Message status: {}", messageStatus);
                        assertEquals("PENDING", messageStatus.getString("status"),
                                "Message should still be PENDING when group fails");
                        assertEquals(0, messageStatus.getInteger("completed_consumer_groups"),
                                "Completed counter should not increment on failure");
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                })
                .onFailure(throwable -> {
                    logger.error("Test failed", throwable);
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testMarkFailed COMPLETED ===");
    }

    @Test
    public void testMarkFailedDoesNotOverrideCompletedState(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testMarkFailedDoesNotOverrideCompletedState STARTED ===");

        String topic = "test-failed-after-completed-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";

        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.QUEUE)
                .build();
                
        SubscriptionOptions subscriptionOptions = SubscriptionOptions.builder().build();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        topicConfigService.createTopic(topicConfig)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, subscriptionOptions))
                .compose(v -> insertMessage(topic, new JsonObject().put("test", "message1")))
                .compose(messageId -> tracker.markCompleted(messageId, groupName, topic)
                        .compose(v -> tracker.markFailed(messageId, groupName, topic, "late error"))
                        .compose(v -> getTrackingRowStatus(messageId, groupName)
                                .compose(trackingStatus -> getMessageStatus(messageId)
                                        .map(messageStatus -> new JsonObject()
                                                .put("tracking", trackingStatus)
                                                .put("message", messageStatus)))))
                .onSuccess(statuses -> {
                    try {
                        JsonObject tracking = statuses.getJsonObject("tracking");
                        JsonObject message = statuses.getJsonObject("message");

                        assertEquals("COMPLETED", tracking.getString("status"),
                                "Tracking row must stay COMPLETED when failure is reported late");
                        assertEquals("COMPLETED", message.getString("status"));
                        assertEquals(1, message.getInteger("completed_consumer_groups"));
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                })
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testMarkFailedDoesNotOverrideCompletedState COMPLETED ===");
    }

    @Test
    public void testMarkCompletedRejectsUnknownGroup(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testMarkCompletedRejectsUnknownGroup STARTED ===");

        String topic = "test-completion-unknown-group-" + UUID.randomUUID().toString().substring(0, 8);
        String validGroup = "group1";
        String invalidGroup = "group-does-not-exist";

        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.QUEUE)
                .build();

        SubscriptionOptions subscriptionOptions = SubscriptionOptions.builder().build();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        topicConfigService.createTopic(topicConfig)
                .compose(v -> subscriptionManager.subscribe(topic, validGroup, subscriptionOptions))
                .compose(v -> insertMessage(topic, new JsonObject().put("test", "message1")))
                .compose(messageId -> tracker.markCompleted(messageId, invalidGroup, topic)
                        .compose(v -> Future.failedFuture(new AssertionError("Expected markCompleted to reject unknown group")))
                        .recover(throwable -> {
                            if (throwable instanceof IllegalArgumentException) {
                                return Future.succeededFuture();
                            }
                            return Future.failedFuture(throwable);
                        }))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testMarkCompletedRejectsUnknownGroup COMPLETED ===");
    }

    @Test
    public void testLateFailureInMultiGroupDoesNotCreateInconsistentState(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testLateFailureInMultiGroupDoesNotCreateInconsistentState STARTED ===");

        String topic = "test-late-failure-multigroup-" + UUID.randomUUID().toString().substring(0, 8);
        String group1 = "group1";
        String group2 = "group2";

        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build();

        SubscriptionOptions subscriptionOptions = SubscriptionOptions.builder().build();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        topicConfigService.createTopic(topicConfig)
                .compose(v -> subscriptionManager.subscribe(topic, group1, subscriptionOptions))
                .compose(v -> subscriptionManager.subscribe(topic, group2, subscriptionOptions))
                .compose(v -> insertMessage(topic, new JsonObject().put("test", "message1")))
                .compose(messageId -> tracker.markCompleted(messageId, group1, topic)
                        .compose(v -> tracker.markFailed(messageId, group1, topic, "late group1 failure"))
                        .compose(v -> tracker.markCompleted(messageId, group2, topic))
                        .compose(v -> getMessageStatus(messageId)
                                .compose(messageStatus -> getTrackingRowStatus(messageId, group1)
                                        .compose(group1Status -> getTrackingRowStatus(messageId, group2)
                                                .map(group2Status -> new JsonObject()
                                                        .put("message", messageStatus)
                                                        .put("group1", group1Status)
                                                        .put("group2", group2Status))))))
                .onSuccess(statuses -> {
                    try {
                        JsonObject message = statuses.getJsonObject("message");
                        JsonObject group1Status = statuses.getJsonObject("group1");
                        JsonObject group2Status = statuses.getJsonObject("group2");

                        assertEquals("COMPLETED", message.getString("status"));
                        assertEquals(2, message.getInteger("completed_consumer_groups"));
                        assertEquals(2, message.getInteger("required_consumer_groups"));
                        assertEquals("COMPLETED", group1Status.getString("status"));
                        assertEquals("COMPLETED", group2Status.getString("status"));
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                })
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testLateFailureInMultiGroupDoesNotCreateInconsistentState COMPLETED ===");
    }

    // Helper method to insert a message
    private Future<Long> insertMessage(String topic, JsonObject payload) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                INSERT INTO outbox (topic, payload, created_at, status)
                VALUES ($1, $2::jsonb, $3, 'PENDING')
                RETURNING id
                """;
            Tuple params = Tuple.of(topic, payload, OffsetDateTime.now(ZoneOffset.UTC));
            return connection.preparedQuery(sql).execute(params)
                    .map(rows -> rows.iterator().next().getLong("id"));
        });
    }

    // Helper method to get message status
    private Future<JsonObject> getMessageStatus(Long messageId) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                SELECT status, completed_consumer_groups, required_consumer_groups
                FROM outbox
                WHERE id = $1
                """;
            return connection.preparedQuery(sql).execute(Tuple.of(messageId))
                    .map(rows -> {
                        Row row = rows.iterator().next();
                        return new JsonObject()
                                .put("status", row.getString("status"))
                                .put("completed_consumer_groups", row.getInteger("completed_consumer_groups"))
                                .put("required_consumer_groups", row.getInteger("required_consumer_groups"));
                    });
        });
    }

    // Helper method to get tracking row status
    private Future<JsonObject> getTrackingRowStatus(Long messageId, String groupName) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                SELECT status, error_message, retry_count
                FROM outbox_consumer_groups
                WHERE message_id = $1 AND group_name = $2
                """;
            return connection.preparedQuery(sql).execute(Tuple.of(messageId, groupName))
                    .map(rows -> {
                        Row row = rows.iterator().next();
                        return new JsonObject()
                                .put("status", row.getString("status"))
                                .put("error_message", row.getString("error_message"))
                                .put("retry_count", row.getInteger("retry_count"));
                    });
        });
    }
}

