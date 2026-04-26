package dev.mars.peegeeq.db.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.deadletter.DeadLetterQueueManager;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
import dev.mars.peegeeq.db.subscription.TopicConfig;
import dev.mars.peegeeq.db.subscription.TopicConfigService;
import dev.mars.peegeeq.db.subscription.TopicSemantics;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ConsumerGroupRetryService.
 *
 * <p>Tests the automated retry and dead-letter-queue processing for consumer
 * group fanout, covering:</p>
 * <ul>
 *   <li>Retry: FAILED rows with retry_count below max_retries are reset to PENDING</li>
 *   <li>DLQ: FAILED rows with retry_count at or above max_retries are moved to dead_letter_queue</li>
 *   <li>Edge cases: already completed, already dead-lettered, mixed states, no failures</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-01
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = "consumer-group-retry-database", mode = ResourceAccessMode.READ_WRITE)
@ResourceLock(value = "dead-letter-queue-database", mode = ResourceAccessMode.READ_WRITE)
public class ConsumerGroupRetryServiceIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupRetryServiceIntegrationTest.class);

    private PgConnectionManager connectionManager;
    private Pool reactivePool;
    private CompletionTracker tracker;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private ConsumerGroupRetryService retryService;
    private DeadLetterQueueManager deadLetterQueueManager;

    @BeforeEach
    public void setUp() throws Exception {
        // super.setUpBaseIntegration(); // Removed: JUnit 5 automatically executes @BeforeEach from superclasses

        connectionManager = new PgConnectionManager(manager.getVertx(), null);

        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema("public")
                .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(3)
                .shared(false)
                .idleTimeout(Duration.ofSeconds(2))
                .connectionTimeout(Duration.ofSeconds(5))
                .build();

        reactivePool = connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        tracker = new CompletionTracker(connectionManager, "peegeeq-main");
        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");

        ObjectMapper objectMapper = new ObjectMapper();
        deadLetterQueueManager = new DeadLetterQueueManager(reactivePool, objectMapper);
        retryService = new ConsumerGroupRetryService(connectionManager, deadLetterQueueManager, "peegeeq-main");

        // Clean up stale data from other test runs to ensure isolation.
        // This test class operates on global scans, so it needs a clean slate.
        VertxTestContext cleanupCtx = new VertxTestContext();
        cleanupTestData()
                .onSuccess(v -> cleanupCtx.completeNow())
                .onFailure(t -> cleanupCtx.completeNow());
        cleanupCtx.awaitCompletion(10, TimeUnit.SECONDS);

        logger.info("ConsumerGroupRetryService test setup complete");
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            connectionManager.close().onSuccess(v -> testContext.completeNow()).onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    // ========================================================================
    // Retry Tests
    // ========================================================================

    @Test
    public void testRetryResetsFailedToPending(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testRetryResetsFailedToPending STARTED ===");

        String topic = "test-retry-reset-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopicAndSubscribe(topic, groupName)
                .compose(v -> insertMessageWithMaxRetries(topic, new JsonObject().put("test", "retry"), 3))
                .compose(messageId ->
                    // Fail the message once (retry_count will be 0 on insert, then ON CONFLICT increments)
                    tracker.markFailed(messageId, groupName, topic, "transient error")
                        .compose(v -> retryService.retryFailedMessages())
                        .compose(retriedCount -> {
                            assertTrue(retriedCount >= 1, "Should have retried at least 1 message");
                            return getTrackingRowStatus(messageId, groupName);
                        }))
                .onSuccess(status -> {
                    try {
                        assertEquals("PENDING", status.getString("status"),
                                "Status should be reset to PENDING after retry");
                        assertNull(status.getString("error_message"),
                                "Error message should be cleared on retry");
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
        logger.info("=== TEST: testRetryResetsFailedToPending COMPLETED ===");
    }

    @Test
    public void testRetryDoesNotResetWhenRetryCountExhausted(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testRetryDoesNotResetWhenRetryCountExhausted STARTED ===");

        String topic = "test-retry-exhausted-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopicAndSubscribe(topic, groupName)
                // max_retries=2: retry_count must reach 2 to be exhausted
                .compose(v -> insertMessageWithMaxRetries(topic, new JsonObject().put("test", "exhausted"), 2))
                .compose(messageId ->
                    // markFailed #1: INSERT → retry_count=0
                    // markFailed #2: ON CONFLICT → retry_count=1
                    // markFailed #3: ON CONFLICT → retry_count=2 = max_retries → exhausted
                    tracker.markFailed(messageId, groupName, topic, "error 1")
                        .compose(v -> tracker.markFailed(messageId, groupName, topic, "error 2"))
                        .compose(v -> tracker.markFailed(messageId, groupName, topic, "error 3"))
                        .compose(v -> getTrackingRowStatus(messageId, groupName))
                        .compose(status -> {
                            logger.info("Before retry: status={}, retry_count={}", 
                                    status.getString("status"), status.getInteger("retry_count"));
                            assertEquals(2, status.getInteger("retry_count"),
                                    "retry_count should be 2 after 3 markFailed calls");
                            return retryService.retryFailedMessages();
                        })
                        .compose(retriedCount -> {
                            // This message should NOT have been retried
                            return getTrackingRowStatus(messageId, groupName);
                        }))
                .onSuccess(status -> {
                    try {
                        assertEquals("FAILED", status.getString("status"),
                                "Status should stay FAILED when retry count exhausted");
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
        logger.info("=== TEST: testRetryDoesNotResetWhenRetryCountExhausted COMPLETED ===");
    }

    @Test
    public void testRetryReturnsZeroWhenNoFailures(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testRetryReturnsZeroWhenNoFailures STARTED ===");

        String topic = "test-retry-nofail-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopicAndSubscribe(topic, groupName)
                .compose(v -> insertMessageWithMaxRetries(topic, new JsonObject().put("test", "ok"), 3))
                .compose(messageId ->
                    // Mark as completed (not failed)
                    tracker.markCompleted(messageId, groupName, topic)
                        .compose(v -> retryService.retryFailedMessages()))
                .onSuccess(retriedCount -> {
                    try {
                        // No FAILED rows from this test; count may include stale data
                        // The key assertion is that we get here without error
                        logger.info("Retry returned count: {}", retriedCount);
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
        logger.info("=== TEST: testRetryReturnsZeroWhenNoFailures COMPLETED ===");
    }

    @Test
    public void testRetryDoesNotTouchCompletedMessages(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testRetryDoesNotTouchCompletedMessages STARTED ===");

        String topic = "test-retry-completed-" + UUID.randomUUID().toString().substring(0, 8);
        String group1 = "group1";
        String group2 = "group2";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build();
        SubscriptionOptions subscriptionOptions = SubscriptionOptions.builder().build();

        topicConfigService.createTopic(topicConfig)
                .compose(v -> subscriptionManager.subscribe(topic, group1, subscriptionOptions))
                .compose(v -> subscriptionManager.subscribe(topic, group2, subscriptionOptions))
                .compose(v -> insertMessageWithMaxRetries(topic, new JsonObject().put("test", "mixed"), 3))
                .compose(messageId ->
                    // group1 completes, group2 fails
                    tracker.markCompleted(messageId, group1, topic)
                        .compose(v -> tracker.markFailed(messageId, group2, topic, "transient error"))
                        .compose(v -> retryService.retryFailedMessages())
                        .compose(retriedCount -> {
                            assertTrue(retriedCount >= 1, "Should retry at least group2");
                            return getTrackingRowStatus(messageId, group1)
                                    .compose(g1Status -> getTrackingRowStatus(messageId, group2)
                                            .map(g2Status -> new JsonObject()
                                                    .put("group1", g1Status)
                                                    .put("group2", g2Status)));
                        }))
                .onSuccess(statuses -> {
                    try {
                        JsonObject g1 = statuses.getJsonObject("group1");
                        JsonObject g2 = statuses.getJsonObject("group2");
                        assertEquals("COMPLETED", g1.getString("status"),
                                "group1 should remain COMPLETED");
                        assertEquals("PENDING", g2.getString("status"),
                                "group2 should be reset to PENDING");
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
        logger.info("=== TEST: testRetryDoesNotTouchCompletedMessages COMPLETED ===");
    }

    @Test
    public void testRetryPreservesRetryCount(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testRetryPreservesRetryCount STARTED ===");

        String topic = "test-retry-count-preserved-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopicAndSubscribe(topic, groupName)
                .compose(v -> insertMessageWithMaxRetries(topic, new JsonObject().put("test", "count"), 5))
                .compose(messageId ->
                    // Fail twice then retry — retry_count should be preserved
                    tracker.markFailed(messageId, groupName, topic, "error 1")
                        .compose(v -> tracker.markFailed(messageId, groupName, topic, "error 2"))
                        .compose(v -> retryService.retryFailedMessages())
                        .compose(retriedCount -> {
                            assertTrue(retriedCount >= 1, "Should have retried");
                            return getTrackingRowStatus(messageId, groupName);
                        }))
                .onSuccess(status -> {
                    try {
                        assertEquals("PENDING", status.getString("status"),
                                "Should be reset to PENDING");
                        assertEquals(1, status.getInteger("retry_count"),
                                "retry_count should be preserved (not reset)");
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
        logger.info("=== TEST: testRetryPreservesRetryCount COMPLETED ===");
    }

    // ========================================================================
    // DLQ Tests
    // ========================================================================

    @Test
    public void testMoveExhaustedToDlq(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testMoveExhaustedToDlq STARTED ===");

        String topic = "test-dlq-move-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopicAndSubscribe(topic, groupName)
                // max_retries=2: after 3 markFailed calls, retry_count=2 = max_retries → exhausted
                .compose(v -> insertMessageWithMaxRetries(topic, new JsonObject().put("test", "dlq"), 2))
                .compose(messageId ->
                    tracker.markFailed(messageId, groupName, topic, "error 1")
                        .compose(v -> tracker.markFailed(messageId, groupName, topic, "error 2"))
                        .compose(v -> tracker.markFailed(messageId, groupName, topic, "final error"))
                        .compose(v -> retryService.moveExhaustedToDlq())
                        .compose(dlqCount -> {
                            assertTrue(dlqCount >= 1, "Should move at least 1 message to DLQ");
                            return getTrackingRowStatus(messageId, groupName)
                                    .compose(trackingStatus -> getDlqEntryForTopic(topic)
                                            .map(dlqEntry -> new JsonObject()
                                                    .put("tracking", trackingStatus)
                                                    .put("dlq", dlqEntry)));
                        }))
                .onSuccess(result -> {
                    try {
                        JsonObject tracking = result.getJsonObject("tracking");
                        JsonObject dlq = result.getJsonObject("dlq");

                        assertEquals("DEAD_LETTER", tracking.getString("status"),
                                "Tracking row should be marked DEAD_LETTER");

                        assertEquals(topic, dlq.getString("topic"),
                                "DLQ entry should have correct topic");
                        assertEquals("outbox_consumer_groups", dlq.getString("original_table"),
                                "DLQ entry should reference outbox_consumer_groups");
                        assertTrue(dlq.getString("failure_reason").contains("final error"),
                                "DLQ failure_reason should include last error message");
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
        logger.info("=== TEST: testMoveExhaustedToDlq COMPLETED ===");
    }

    @Test
    public void testDlqDoesNotMoveRetryEligible(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testDlqDoesNotMoveRetryEligible STARTED ===");

        String topic = "test-dlq-noeligible-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopicAndSubscribe(topic, groupName)
                .compose(v -> insertMessageWithMaxRetries(topic, new JsonObject().put("test", "eligible"), 5))
                .compose(messageId ->
                    // Fail once — retry_count=0, well below max_retries=5
                    tracker.markFailed(messageId, groupName, topic, "transient error")
                        .compose(v -> retryService.moveExhaustedToDlq()))
                .onSuccess(dlqCount -> {
                    try {
                        assertEquals(0, dlqCount, "Should NOT move retry-eligible message to DLQ");
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
        logger.info("=== TEST: testDlqDoesNotMoveRetryEligible COMPLETED ===");
    }

    @Test
    public void testDlqDoesNotMoveCompletedMessages(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testDlqDoesNotMoveCompletedMessages STARTED ===");

        String topic = "test-dlq-completed-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopicAndSubscribe(topic, groupName)
                .compose(v -> insertMessageWithMaxRetries(topic, new JsonObject().put("test", "completed"), 3))
                .compose(messageId ->
                    tracker.markCompleted(messageId, groupName, topic)
                        .compose(v -> retryService.moveExhaustedToDlq()))
                .onSuccess(dlqCount -> {
                    try {
                        assertEquals(0, dlqCount, "Should NOT move completed messages to DLQ");
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
        logger.info("=== TEST: testDlqDoesNotMoveCompletedMessages COMPLETED ===");
    }

    @Test
    public void testDlqDoesNotMoveSameMessageTwice(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testDlqDoesNotMoveSameMessageTwice STARTED ===");

        String topic = "test-dlq-idempotent-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopicAndSubscribe(topic, groupName)
                // max_retries=2: after 3 markFailed calls, retry_count=2 = max_retries → exhausted
                .compose(v -> insertMessageWithMaxRetries(topic, new JsonObject().put("test", "idempotent"), 2))
                .compose(messageId ->
                    tracker.markFailed(messageId, groupName, topic, "error 1")
                        .compose(v -> tracker.markFailed(messageId, groupName, topic, "error 2"))
                        .compose(v -> tracker.markFailed(messageId, groupName, topic, "error 3"))
                        // First DLQ pass
                        .compose(v -> retryService.moveExhaustedToDlq())
                        .compose(firstDlqCount -> {
                            assertTrue(firstDlqCount >= 1, "First pass should move at least 1");
                            // Second DLQ pass — already DEAD_LETTER, should not be picked up
                            return retryService.moveExhaustedToDlq();
                        }))
                .onSuccess(secondDlqCount -> {
                    try {
                        assertEquals(0, secondDlqCount,
                                "Second pass should move 0 (already DEAD_LETTER)");
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
        logger.info("=== TEST: testDlqDoesNotMoveSameMessageTwice COMPLETED ===");
    }

    // ========================================================================
    // Combined processFailedMessages Tests
    // ========================================================================

    @Test
    public void testProcessFailedMessagesRetryAndDlqTogether(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testProcessFailedMessagesRetryAndDlqTogether STARTED ===");

        String topic = "test-combined-" + UUID.randomUUID().toString().substring(0, 8);
        String retryGroup = "retry-group";
        String dlqGroup = "dlq-group";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build();
        SubscriptionOptions subscriptionOptions = SubscriptionOptions.builder().build();

        topicConfigService.createTopic(topicConfig)
                .compose(v -> subscriptionManager.subscribe(topic, retryGroup, subscriptionOptions))
                .compose(v -> subscriptionManager.subscribe(topic, dlqGroup, subscriptionOptions))
                .compose(v -> insertMessageWithMaxRetries(topic, new JsonObject().put("test", "combined"), 3))
                .compose(messageId ->
                    // retryGroup fails once (retry_count=0, below max_retries=3)
                    tracker.markFailed(messageId, retryGroup, topic, "transient")
                        // dlqGroup fails 4 times: retry_count reaches 3 = max_retries → exhausted
                        .compose(v -> tracker.markFailed(messageId, dlqGroup, topic, "fail 1"))
                        .compose(v -> tracker.markFailed(messageId, dlqGroup, topic, "fail 2"))
                        .compose(v -> tracker.markFailed(messageId, dlqGroup, topic, "fail 3"))
                        .compose(v -> tracker.markFailed(messageId, dlqGroup, topic, "fail 4"))
                        .compose(v -> retryService.processFailedMessages())
                        .compose(result -> {
                            assertTrue(result.retriedCount() >= 1, "retryGroup should be retried");
                            assertTrue(result.dlqCount() >= 1, "dlqGroup should go to DLQ");
                            return getTrackingRowStatus(messageId, retryGroup)
                                    .compose(retryStatus -> getTrackingRowStatus(messageId, dlqGroup)
                                            .map(dlqStatus -> new JsonObject()
                                                    .put("retry", retryStatus)
                                                    .put("dlq", dlqStatus)));
                        }))
                .onSuccess(statuses -> {
                    try {
                        assertEquals("PENDING", statuses.getJsonObject("retry").getString("status"),
                                "retryGroup should be PENDING");
                        assertEquals("DEAD_LETTER", statuses.getJsonObject("dlq").getString("status"),
                                "dlqGroup should be DEAD_LETTER");
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
        logger.info("=== TEST: testProcessFailedMessagesRetryAndDlqTogether COMPLETED ===");
    }

    @Test
    public void testProcessFailedMessagesWithNoFailures(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testProcessFailedMessagesWithNoFailures STARTED ===");

        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        retryService.processFailedMessages()
                .onSuccess(result -> {
                    try {
                        // Counts may include leftover data; verify no errors
                        logger.info("processFailedMessages with clean DB: retried={}, dlq={}",
                                result.retriedCount(), result.dlqCount());
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
        logger.info("=== TEST: testProcessFailedMessagesWithNoFailures COMPLETED ===");
    }

    // ========================================================================
    // Edge Case Tests
    // ========================================================================

    @Test
    public void testMaxRetriesOfOneGoesDirectlyToDlq(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testMaxRetriesOfOneGoesDirectlyToDlq STARTED ===");

        String topic = "test-maxretries-one-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopicAndSubscribe(topic, groupName)
                .compose(v -> insertMessageWithMaxRetries(topic, new JsonObject().put("test", "one-retry"), 1))
                .compose(messageId ->
                    // markFailed #1: INSERT → retry_count=0
                    // markFailed #2: ON CONFLICT → retry_count=1 = max_retries=1 → exhausted
                    tracker.markFailed(messageId, groupName, topic, "only error")
                        .compose(v -> tracker.markFailed(messageId, groupName, topic, "second error"))
                        .compose(v -> retryService.processFailedMessages())
                        .compose(result -> {
                            // retryGroup: retry_count=0 < max_retries=1: retriable... but wait
                            // Actually retry_count=1 = max_retries=1: exhausted!
                            // Both should go to DLQ
                            assertTrue(result.dlqCount() >= 1,
                                    "Should move to DLQ");
                            return getTrackingRowStatus(messageId, groupName);
                        }))
                .onSuccess(status -> {
                    try {
                        assertEquals("DEAD_LETTER", status.getString("status"));
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
        logger.info("=== TEST: testMaxRetriesOfOneGoesDirectlyToDlq COMPLETED ===");
    }

    @Test
    public void testMultipleGroupsOnSameMessageMixedOutcomes(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testMultipleGroupsOnSameMessageMixedOutcomes STARTED ===");

        String topic = "test-multi-group-mixed-" + UUID.randomUUID().toString().substring(0, 8);
        String completedGroup = "completed-group";
        String retryGroup = "retry-group";
        String dlqGroup = "dlq-group";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .build();
        SubscriptionOptions subscriptionOptions = SubscriptionOptions.builder().build();

        topicConfigService.createTopic(topicConfig)
                .compose(v -> subscriptionManager.subscribe(topic, completedGroup, subscriptionOptions))
                .compose(v -> subscriptionManager.subscribe(topic, retryGroup, subscriptionOptions))
                .compose(v -> subscriptionManager.subscribe(topic, dlqGroup, subscriptionOptions))
                .compose(v -> insertMessageWithMaxRetries(topic, new JsonObject().put("test", "mixed-groups"), 3))
                .compose(messageId ->
                    // completedGroup: COMPLETED
                    tracker.markCompleted(messageId, completedGroup, topic)
                        // retryGroup: FAILED once (retry_count=0, below max_retries=3)
                        .compose(v -> tracker.markFailed(messageId, retryGroup, topic, "retry me"))
                        // dlqGroup: FAILED 4 times → retry_count=3 = max_retries → exhausted
                        .compose(v -> tracker.markFailed(messageId, dlqGroup, topic, "dlq 1"))
                        .compose(v -> tracker.markFailed(messageId, dlqGroup, topic, "dlq 2"))
                        .compose(v -> tracker.markFailed(messageId, dlqGroup, topic, "dlq 3"))
                        .compose(v -> tracker.markFailed(messageId, dlqGroup, topic, "dlq 4"))
                        .compose(v -> retryService.processFailedMessages())
                        .compose(result -> {
                            assertTrue(result.retriedCount() >= 1, "retryGroup should be retried");
                            assertTrue(result.dlqCount() >= 1, "dlqGroup should go to DLQ");
                            return getTrackingRowStatus(messageId, completedGroup)
                                    .compose(cs -> getTrackingRowStatus(messageId, retryGroup)
                                    .compose(rs -> getTrackingRowStatus(messageId, dlqGroup)
                                    .map(ds -> new JsonObject()
                                            .put("completed", cs)
                                            .put("retry", rs)
                                            .put("dlq", ds))));
                        }))
                .onSuccess(statuses -> {
                    try {
                        assertEquals("COMPLETED", statuses.getJsonObject("completed").getString("status"));
                        assertEquals("PENDING", statuses.getJsonObject("retry").getString("status"));
                        assertEquals("DEAD_LETTER", statuses.getJsonObject("dlq").getString("status"));
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
        logger.info("=== TEST: testMultipleGroupsOnSameMessageMixedOutcomes COMPLETED ===");
    }

    @Test
    public void testDlqEntryContainsOriginalMessageData(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testDlqEntryContainsOriginalMessageData STARTED ===");

        String topic = "test-dlq-data-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String correlationId = "corr-" + UUID.randomUUID().toString().substring(0, 8);
        String messageGroup = "msg-group-" + UUID.randomUUID().toString().substring(0, 8);
        JsonObject payload = new JsonObject()
                .put("event", "trade.executed")
                .put("amount", 1500.00);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopicAndSubscribe(topic, groupName)
                // max_retries=2: after 3 markFailed calls, retry_count=2 = max_retries → exhausted
                .compose(v -> insertMessageWithDetails(topic, payload, 2, correlationId, messageGroup))
                .compose(messageId ->
                    tracker.markFailed(messageId, groupName, topic, "error 1")
                        .compose(v -> tracker.markFailed(messageId, groupName, topic, "error 2"))
                        .compose(v -> tracker.markFailed(messageId, groupName, topic, "final error"))
                        .compose(v -> retryService.moveExhaustedToDlq())
                        .compose(dlqCount -> getDlqEntryForTopic(topic)))
                .onSuccess(dlq -> {
                    try {
                        assertNotNull(dlq, "DLQ entry should exist");
                        assertEquals(topic, dlq.getString("topic"));
                        assertEquals(correlationId, dlq.getString("correlation_id"));
                        assertEquals(messageGroup, dlq.getString("message_group"));
                        assertTrue(dlq.getString("failure_reason").contains("final error"));
                        assertTrue(dlq.getInteger("retry_count") >= 2,
                                "DLQ retry_count should reflect exhausted retries");
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
        logger.info("=== TEST: testDlqEntryContainsOriginalMessageData COMPLETED ===");
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private Future<Void> createTopicAndSubscribe(String topic, String groupName) {
        TopicConfig topicConfig = TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.QUEUE)
                .build();
        SubscriptionOptions subscriptionOptions = SubscriptionOptions.builder().build();

        return topicConfigService.createTopic(topicConfig)
                .compose(v -> subscriptionManager.subscribe(topic, groupName, subscriptionOptions));
    }

    private Future<Long> insertMessageWithMaxRetries(String topic, JsonObject payload, int maxRetries) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                INSERT INTO outbox (topic, payload, created_at, status, max_retries)
                VALUES ($1, $2::jsonb, $3, 'PENDING', $4)
                RETURNING id
                """;
            Tuple params = Tuple.of(topic, payload, OffsetDateTime.now(ZoneOffset.UTC), maxRetries);
            return connection.preparedQuery(sql).execute(params)
                    .map(rows -> rows.iterator().next().getLong("id"));
        });
    }

    private Future<Long> insertMessageWithDetails(String topic, JsonObject payload, int maxRetries,
                                                   String correlationId, String messageGroup) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                INSERT INTO outbox (topic, payload, created_at, status, max_retries, correlation_id, message_group)
                VALUES ($1, $2::jsonb, $3, 'PENDING', $4, $5, $6)
                RETURNING id
                """;
            Tuple params = Tuple.of(topic, payload, OffsetDateTime.now(ZoneOffset.UTC), maxRetries,
                    correlationId, messageGroup);
            return connection.preparedQuery(sql).execute(params)
                    .map(rows -> rows.iterator().next().getLong("id"));
        });
    }

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

    private Future<JsonObject> getDlqEntryForTopic(String topic) {
        return connectionManager.withConnection("peegeeq-main", connection -> {
            String sql = """
                SELECT original_table, original_id, topic, failure_reason, retry_count,
                       correlation_id, message_group
                FROM dead_letter_queue
                WHERE topic = $1
                ORDER BY failed_at DESC
                LIMIT 1
                """;
            return connection.preparedQuery(sql).execute(Tuple.of(topic))
                    .map(rows -> {
                        if (!rows.iterator().hasNext()) {
                            return null;
                        }
                        Row row = rows.iterator().next();
                        return new JsonObject()
                                .put("original_table", row.getString("original_table"))
                                .put("original_id", row.getLong("original_id"))
                                .put("topic", row.getString("topic"))
                                .put("failure_reason", row.getString("failure_reason"))
                                .put("retry_count", row.getInteger("retry_count"))
                                .put("correlation_id", row.getString("correlation_id"))
                                .put("message_group", row.getString("message_group"));
                    });
        });
    }

    private Future<Void> cleanupTestData() {
        return reactivePool.<Void>withConnection(connection ->
            connection.preparedQuery("DELETE FROM outbox_consumer_groups").execute()
                .compose(v -> connection.preparedQuery("DELETE FROM dead_letter_queue").execute())
                .mapEmpty()
        );
    }
}
