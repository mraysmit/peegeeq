package dev.mars.peegeeq.db.consumer;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxTestContext;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link WatermarkCalculator}.
 *
 * <p>Tests watermark calculation (MIN across active groups), forward-only advancement,
 * sweep to COMPLETED status, idempotency, and topic isolation against a real
 * PostgreSQL instance via TestContainers.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-12
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = "watermark-calculator-database", mode = ResourceAccessMode.READ_WRITE)
public class WatermarkCalculatorIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(WatermarkCalculatorIntegrationTest.class);

    private PgConnectionManager connectionManager;
    private WatermarkCalculator calculator;
    private PartitionedOffsetManager offsetManager;

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

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        calculator = new WatermarkCalculator(connectionManager, "peegeeq-main");
        offsetManager = new PartitionedOffsetManager(connectionManager, "peegeeq-main");

        // Clean up test data from prior runs
        VertxTestContext cleanupCtx = new VertxTestContext();
        cleanupTestData()
                .onSuccess(v -> cleanupCtx.completeNow())
                .onFailure(t -> cleanupCtx.completeNow());
        cleanupCtx.awaitCompletion(10, TimeUnit.SECONDS);

        logger.info("WatermarkCalculator test setup complete");
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
    // Test 4.1: Calculate Watermark — Single Group
    // One group with 3 partitions at offsets 10/20/30 → watermark = 10 (minimum).
    // ========================================================================

    @Test
    public void testCalculateWatermark_singleGroup_returnsMinOffset(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 4.1: testCalculateWatermark_singleGroup_returnsMinOffset STARTED ===");

        String topic = "test-wm-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group-A";
        int generation = 1;
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName, "ACTIVE"))
                .compose(v -> offsetManager.initializeOffset(topic, groupName, "part-1", generation))
                .compose(v -> offsetManager.commitOffset(topic, groupName, "part-1", 10L, generation))
                .compose(v -> offsetManager.initializeOffset(topic, groupName, "part-2", generation))
                .compose(v -> offsetManager.commitOffset(topic, groupName, "part-2", 20L, generation))
                .compose(v -> offsetManager.initializeOffset(topic, groupName, "part-3", generation))
                .compose(v -> offsetManager.commitOffset(topic, groupName, "part-3", 30L, generation))
                .compose(v -> calculator.calculateWatermark(topic))
                .compose(watermark -> {
                    assertEquals(10L, watermark, "Watermark should be MIN(committed_offset) = 10");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test failed with error", errorRef.get());
        }
    }

    // ========================================================================
    // Test 4.2: Calculate Watermark — Multiple Groups → Global Min
    // Group A min=100, Group B min=50 → watermark = 50.
    // ========================================================================

    @Test
    public void testCalculateWatermark_multipleGroups_returnsGlobalMin(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 4.2: testCalculateWatermark_multipleGroups_returnsGlobalMin STARTED ===");

        String topic = "test-wm-" + UUID.randomUUID().toString().substring(0, 8);
        int generation = 1;
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopic(topic)
                // Group A: partitions at 100, 200 → min = 100
                .compose(v -> createSubscription(topic, "group-A", "ACTIVE"))
                .compose(v -> offsetManager.initializeOffset(topic, "group-A", "part-1", generation))
                .compose(v -> offsetManager.commitOffset(topic, "group-A", "part-1", 100L, generation))
                .compose(v -> offsetManager.initializeOffset(topic, "group-A", "part-2", generation))
                .compose(v -> offsetManager.commitOffset(topic, "group-A", "part-2", 200L, generation))
                // Group B: partitions at 50, 150 → min = 50
                .compose(v -> createSubscription(topic, "group-B", "ACTIVE"))
                .compose(v -> offsetManager.initializeOffset(topic, "group-B", "part-1", generation))
                .compose(v -> offsetManager.commitOffset(topic, "group-B", "part-1", 50L, generation))
                .compose(v -> offsetManager.initializeOffset(topic, "group-B", "part-2", generation))
                .compose(v -> offsetManager.commitOffset(topic, "group-B", "part-2", 150L, generation))
                .compose(v -> calculator.calculateWatermark(topic))
                .compose(watermark -> {
                    assertEquals(50L, watermark, "Watermark should be MIN across all active groups = 50");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test failed with error", errorRef.get());
        }
    }

    // ========================================================================
    // Test 4.3: Calculate Watermark — Excludes Dead Groups
    // Active group at 100, DEAD group at 10 → watermark = 100, not pinned by dead.
    // ========================================================================

    @Test
    public void testCalculateWatermark_excludesDeadGroups(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 4.3: testCalculateWatermark_excludesDeadGroups STARTED ===");

        String topic = "test-wm-" + UUID.randomUUID().toString().substring(0, 8);
        int generation = 1;
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopic(topic)
                // Active group at offset 100
                .compose(v -> createSubscription(topic, "active-group", "ACTIVE"))
                .compose(v -> offsetManager.initializeOffset(topic, "active-group", "part-1", generation))
                .compose(v -> offsetManager.commitOffset(topic, "active-group", "part-1", 100L, generation))
                // Dead group at offset 10 — should NOT pin the watermark
                .compose(v -> createSubscription(topic, "dead-group", "DEAD"))
                .compose(v -> offsetManager.initializeOffset(topic, "dead-group", "part-1", generation))
                .compose(v -> offsetManager.commitOffset(topic, "dead-group", "part-1", 10L, generation))
                .compose(v -> calculator.calculateWatermark(topic))
                .compose(watermark -> {
                    assertEquals(100L, watermark,
                            "Watermark should be 100 (dead group should not pin watermark)");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test failed with error", errorRef.get());
        }
    }

    // ========================================================================
    // Test 4.4: Calculate Watermark — No Active Groups → Returns Zero
    // No active subscriptions → watermark = 0.
    // ========================================================================

    @Test
    public void testCalculateWatermark_noActiveGroups_returnsZero(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 4.4: testCalculateWatermark_noActiveGroups_returnsZero STARTED ===");

        String topic = "test-wm-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopic(topic)
                .compose(v -> calculator.calculateWatermark(topic))
                .compose(watermark -> {
                    assertEquals(0L, watermark,
                            "Watermark should be 0 when no active groups exist");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test failed with error", errorRef.get());
        }
    }

    // ========================================================================
    // Test 4.5: Advance Watermark — Only Moves Forward
    // Watermark at 100, calculate returns 50 → stays 100.
    // ========================================================================

    @Test
    public void testAdvanceWatermark_onlyMovesForward(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 4.5: testAdvanceWatermark_onlyMovesForward STARTED ===");

        String topic = "test-wm-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopic(topic)
                // First, advance watermark to 100
                .compose(v -> calculator.advanceWatermark(topic, 100L))
                .compose(effectiveWm -> {
                    assertEquals(100L, effectiveWm, "First advance should set watermark to 100");
                    // Now try to advance to 50 — should stay at 100
                    return calculator.advanceWatermark(topic, 50L);
                })
                .compose(effectiveWm -> {
                    assertEquals(100L, effectiveWm, "Watermark must not retreat from 100 to 50");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test failed with error", errorRef.get());
        }
    }

    // ========================================================================
    // Test 4.6: Advance Watermark — Updates Timestamp
    // After advance, watermark_updated_at is recent.
    // ========================================================================

    @Test
    public void testAdvanceWatermark_updatesTimestamp(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 4.6: testAdvanceWatermark_updatesTimestamp STARTED ===");

        String topic = "test-wm-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        OffsetDateTime beforeAdvance = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5);

        createTopic(topic)
                .compose(v -> calculator.advanceWatermark(topic, 42L))
                .compose(effectiveWm -> {
                    assertEquals(42L, effectiveWm);
                    return getWatermarkUpdatedAt(topic);
                })
                .compose(updatedAt -> {
                    assertNotNull(updatedAt, "watermark_updated_at must be set");
                    assertTrue(updatedAt.isAfter(beforeAdvance),
                            "watermark_updated_at should be recent, was: " + updatedAt);
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test failed with error", errorRef.get());
        }
    }

    // ========================================================================
    // Test 4.7: Sweep Marks Messages Below Watermark as COMPLETED
    // 20 messages, watermark=10 → messages 1-10 become COMPLETED, 11-20 unchanged.
    // ========================================================================

    @Test
    public void testSweep_marksMessagesBelowWatermarkCompleted(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 4.7: testSweep_marksMessagesBelowWatermarkCompleted STARTED ===");

        String topic = "test-wm-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicReference<List<Long>> idsRef = new AtomicReference<>();

        createTopic(topic)
                .compose(v -> insertMessages(topic, "part-1", 20))
                .compose(ids -> {
                    idsRef.set(ids);
                    // Sweep up to the 10th message id
                    long watermark = ids.get(9); // 0-indexed → 10th message
                    return calculator.sweep(topic, watermark);
                })
                .compose(sweptCount -> {
                    assertEquals(10, sweptCount, "Should sweep exactly 10 messages");
                    // Verify first 10 are COMPLETED
                    List<Long> ids = idsRef.get();
                    Future<Void> chain = Future.succeededFuture();
                    for (int i = 0; i < 10; i++) {
                        final int idx = i;
                        chain = chain.compose(v -> getMessageStatus(ids.get(idx))
                                .compose(status -> {
                                    assertEquals("COMPLETED", status,
                                            "Message " + ids.get(idx) + " (index " + idx + ") should be COMPLETED");
                                    return Future.succeededFuture();
                                }));
                    }
                    // Verify remaining 10 are still PENDING
                    for (int i = 10; i < 20; i++) {
                        final int idx = i;
                        chain = chain.compose(v -> getMessageStatus(ids.get(idx))
                                .compose(status -> {
                                    assertEquals("PENDING", status,
                                            "Message " + ids.get(idx) + " (index " + idx + ") should still be PENDING");
                                    return Future.succeededFuture();
                                }));
                    }
                    return chain;
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test failed with error", errorRef.get());
        }
    }

    // ========================================================================
    // Test 4.8: Sweep Is Idempotent
    // Run sweep twice → same result, no errors.
    // ========================================================================

    @Test
    public void testSweep_idempotent(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 4.8: testSweep_idempotent STARTED ===");

        String topic = "test-wm-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopic(topic)
                .compose(v -> insertMessages(topic, "part-1", 10))
                .compose(ids -> {
                    long watermark = ids.get(4); // sweep first 5
                    return calculator.sweep(topic, watermark)
                            .compose(firstSweepCount -> {
                                assertEquals(5, firstSweepCount, "First sweep should mark 5 messages");
                                // Second sweep — same watermark, no new work
                                return calculator.sweep(topic, watermark);
                            })
                            .compose(secondSweepCount -> {
                                assertEquals(0, secondSweepCount,
                                        "Second sweep should find 0 (already COMPLETED)");
                                return Future.succeededFuture();
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test failed with error", errorRef.get());
        }
    }

    // ========================================================================
    // Test 4.9: Sweep Does Not Touch Other Topics
    // Topic A watermark=100, Topic B watermark=10 → only topic A's messages swept.
    // ========================================================================

    @Test
    public void testSweep_doesNotTouchOtherTopics(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 4.9: testSweep_doesNotTouchOtherTopics STARTED ===");

        String topicA = "test-wm-A-" + UUID.randomUUID().toString().substring(0, 8);
        String topicB = "test-wm-B-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicReference<List<Long>> topicBIdsRef = new AtomicReference<>();

        createTopic(topicA)
                .compose(v -> createTopic(topicB))
                .compose(v -> insertMessages(topicA, "part-1", 10))
                .compose(idsA -> insertMessages(topicB, "part-1", 10)
                        .compose(idsB -> {
                            topicBIdsRef.set(idsB);
                            // Sweep topic A with high watermark
                            return calculator.sweep(topicA, idsA.get(9));
                        }))
                .compose(sweptCount -> {
                    assertEquals(10, sweptCount, "Should sweep 10 messages from topic A");
                    // Verify topic B messages are all still PENDING
                    List<Long> idsB = topicBIdsRef.get();
                    Future<Void> chain = Future.succeededFuture();
                    for (int i = 0; i < idsB.size(); i++) {
                        final long id = idsB.get(i);
                        chain = chain.compose(v -> getMessageStatus(id)
                                .compose(status -> {
                                    assertEquals("PENDING", status,
                                            "Topic B message " + id + " should still be PENDING");
                                    return Future.succeededFuture();
                                }));
                    }
                    return chain;
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test failed with error", errorRef.get());
        }
    }

    // ========================================================================
    // Test 4.10: Sweep Preserves Already-COMPLETED Messages
    // Already-COMPLETED messages are not re-updated (no unnecessary writes).
    // ========================================================================

    @Test
    public void testSweep_preservesCompletedMessages(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 4.10: testSweep_preservesCompletedMessages STARTED ===");

        String topic = "test-wm-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopic(topic)
                // Insert 5 COMPLETED messages and 5 PENDING messages
                .compose(v -> {
                    Future<List<Long>> chain = Future.succeededFuture(new java.util.ArrayList<>());
                    for (int i = 0; i < 5; i++) {
                        chain = chain.compose(ids ->
                                insertOutboxMessageWithStatus(topic, "part-1", "COMPLETED")
                                        .map(id -> { ids.add(id); return ids; }));
                    }
                    for (int i = 0; i < 5; i++) {
                        chain = chain.compose(ids ->
                                insertOutboxMessageWithStatus(topic, "part-1", "PENDING")
                                        .map(id -> { ids.add(id); return ids; }));
                    }
                    return chain;
                })
                .compose(allIds -> {
                    // Sweep with watermark covering all 10 messages
                    long watermark = allIds.get(allIds.size() - 1);
                    return calculator.sweep(topic, watermark);
                })
                .compose(sweptCount -> {
                    // Only the 5 PENDING messages should be swept (not the 5 already COMPLETED)
                    assertEquals(5, sweptCount,
                            "Should only sweep 5 PENDING messages, not the 5 already COMPLETED");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test failed with error", errorRef.get());
        }
    }

    // ========================================================================
    // Test 4.11: WatermarkJob Runs Periodic Sweep
    // Start job → after interval, watermark advances and messages swept.
    // ========================================================================

    @Test
    public void testWatermarkJob_runsPeriodicSweep(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 4.11: testWatermarkJob_runsPeriodicSweep STARTED ===");

        String topic = "test-wm-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group-A";
        int generation = 1;
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName, "ACTIVE"))
                .compose(v -> insertMessages(topic, "part-1", 10))
                .compose(ids -> {
                    // Commit offset past all 10 messages
                    return offsetManager.initializeOffset(topic, groupName, "part-1", generation)
                            .compose(v -> offsetManager.commitOffset(topic, groupName, "part-1", ids.get(9), generation))
                            .map(ids);
                })
                .compose(ids -> {
                    // Start job with short interval (200ms)
                    WatermarkJob job = new WatermarkJob(manager.getVertx(), calculator, topic, 200L);
                    job.start();
                    assertTrue(job.isRunning(), "Job should be running");

                    // Wait for at least 2 runs (initial + 1 periodic)
                    io.vertx.core.Promise<Void> promise = io.vertx.core.Promise.promise();
                    manager.getVertx().setTimer(600L, timerId -> {
                        try {
                            assertTrue(job.getTotalRunCount() >= 2,
                                    "Job should have run at least 2 times, ran: " + job.getTotalRunCount());
                            assertEquals(10L, job.getTotalSwept(),
                                    "Should have swept 10 messages total");
                            job.stop();
                            assertFalse(job.isRunning(), "Job should be stopped");
                            promise.complete();
                        } catch (Throwable t) {
                            job.stop();
                            promise.fail(t);
                        }
                    });
                    return promise.future();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test failed with error", errorRef.get());
        }
    }

    // ========================================================================
    // Test 4.12: WatermarkJob Stop Cancels Timer
    // Start then stop → no more sweeps fire after stop.
    // ========================================================================

    @Test
    public void testWatermarkJob_stopCancelsTimer(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 4.12: testWatermarkJob_stopCancelsTimer STARTED ===");

        String topic = "test-wm-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopic(topic)
                .compose(v -> {
                    WatermarkJob job = new WatermarkJob(manager.getVertx(), calculator, topic, 5000L);
                    job.start();
                    assertTrue(job.isRunning());

                    // Wait for initial run to complete (interval is 5s so no periodic fires yet), then stop
                    io.vertx.core.Promise<Void> promise = io.vertx.core.Promise.promise();
                    manager.getVertx().setTimer(300L, id1 -> {
                        try {
                            job.stop();
                            assertFalse(job.isRunning(), "Job should be stopped");
                            long runsAtStop = job.getTotalRunCount();
                            assertTrue(runsAtStop >= 1,
                                    "Should have completed at least the initial run, got: " + runsAtStop);

                            // Wait and verify no more runs happened
                            manager.getVertx().setTimer(400L, id2 -> {
                                try {
                                    assertEquals(runsAtStop, job.getTotalRunCount(),
                                            "No additional runs should occur after stop");
                                    promise.complete();
                                } catch (Throwable t) {
                                    promise.fail(t);
                                }
                            });
                        } catch (Throwable t) {
                            promise.fail(t);
                        }
                    });
                    return promise.future();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test failed with error", errorRef.get());
        }
    }

    // ========================================================================
    // Helper: Insert N messages, return ids
    // ========================================================================

    private Future<List<Long>> insertMessages(String topic, String messageGroup, int count) {
        Future<List<Long>> result = Future.succeededFuture(new java.util.ArrayList<>());
        for (int i = 0; i < count; i++) {
            result = result.compose(ids -> insertOutboxMessage(topic, messageGroup)
                    .map(id -> {
                        ids.add(id);
                        return ids;
                    }));
        }
        return result;
    }

    // ========================================================================
    // Helper: Clean up test data
    // ========================================================================

    private Future<Void> cleanupTestData() {
        return connectionManager.withConnection("peegeeq-main", connection ->
                connection.preparedQuery("DELETE FROM outbox_topic_watermarks WHERE topic LIKE 'test-%'")
                        .execute()
                        .compose(v -> connection.preparedQuery("DELETE FROM outbox_partition_offsets WHERE topic LIKE 'test-%'").execute())
                        .compose(v -> connection.preparedQuery("DELETE FROM outbox_partition_assignments WHERE topic LIKE 'test-%'").execute())
                        .compose(v -> connection.preparedQuery("DELETE FROM outbox WHERE topic LIKE 'test-%'").execute())
                        .compose(v -> connection.preparedQuery("DELETE FROM outbox_topic_subscriptions WHERE topic LIKE 'test-%'").execute())
                        .compose(v -> connection.preparedQuery("DELETE FROM outbox_topics WHERE topic LIKE 'test-%'").execute())
                        .map(rows -> (Void) null)
        );
    }

    // ========================================================================
    // Helper: Create topic with OFFSET_WATERMARK mode
    // ========================================================================

    private Future<Void> createTopic(String topic) {
        String sql = """
            INSERT INTO outbox_topics (topic, semantics, completion_tracking_mode)
            VALUES ($1, 'PUB_SUB', 'OFFSET_WATERMARK')
            ON CONFLICT (topic) DO NOTHING
            """;
        return connectionManager.withConnection("peegeeq-main", connection ->
                connection.preparedQuery(sql)
                        .execute(Tuple.of(topic))
                        .map(rows -> (Void) null)
        );
    }

    // ========================================================================
    // Helper: Create subscription with specified status
    // ========================================================================

    private Future<Void> createSubscription(String topic, String groupName, String status) {
        String sql = """
            INSERT INTO outbox_topic_subscriptions (topic, group_name, subscription_status)
            VALUES ($1, $2, $3)
            ON CONFLICT (topic, group_name) DO UPDATE SET subscription_status = $3
            """;
        return connectionManager.withConnection("peegeeq-main", connection ->
                connection.preparedQuery(sql)
                        .execute(Tuple.of(topic, groupName, status))
                        .map(rows -> (Void) null)
        );
    }

    // ========================================================================
    // Helper: Insert outbox message with default PENDING status
    // ========================================================================

    private Future<Long> insertOutboxMessage(String topic, String messageGroup) {
        String sql = """
            INSERT INTO outbox (topic, payload, status, message_group, created_at)
            VALUES ($1, $2, 'PENDING', $3, $4)
            RETURNING id
            """;
        JsonObject payload = new JsonObject().put("test", "data");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return connectionManager.withConnection("peegeeq-main", connection ->
                connection.preparedQuery(sql)
                        .execute(Tuple.of(topic, payload, messageGroup, now))
                        .map(rows -> rows.iterator().next().getLong("id"))
        );
    }

    // ========================================================================
    // Helper: Insert outbox message with specific status
    // ========================================================================

    private Future<Long> insertOutboxMessageWithStatus(String topic, String messageGroup, String status) {
        String sql = """
            INSERT INTO outbox (topic, payload, status, message_group, created_at)
            VALUES ($1, $2, $3, $4, $5)
            RETURNING id
            """;
        JsonObject payload = new JsonObject().put("test", "data");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return connectionManager.withConnection("peegeeq-main", connection ->
                connection.preparedQuery(sql)
                        .execute(Tuple.of(topic, payload, status, messageGroup, now))
                        .map(rows -> rows.iterator().next().getLong("id"))
        );
    }

    // ========================================================================
    // Helper: Get watermark from the watermarks table
    // ========================================================================

    private Future<Long> getStoredWatermark(String topic) {
        String sql = "SELECT watermark_id FROM outbox_topic_watermarks WHERE topic = $1";
        return connectionManager.withConnection("peegeeq-main", connection ->
                connection.preparedQuery(sql)
                        .execute(Tuple.of(topic))
                        .map(rows -> {
                            if (rows.size() == 0) return 0L;
                            return rows.iterator().next().getLong("watermark_id");
                        })
        );
    }

    // ========================================================================
    // Helper: Get watermark updated_at timestamp
    // ========================================================================

    private Future<OffsetDateTime> getWatermarkUpdatedAt(String topic) {
        String sql = "SELECT watermark_updated_at FROM outbox_topic_watermarks WHERE topic = $1";
        return connectionManager.withConnection("peegeeq-main", connection ->
                connection.preparedQuery(sql)
                        .execute(Tuple.of(topic))
                        .map(rows -> {
                            if (rows.size() == 0) return null;
                            return rows.iterator().next().getOffsetDateTime("watermark_updated_at");
                        })
        );
    }

    // ========================================================================
    // Helper: Get message status
    // ========================================================================

    private Future<String> getMessageStatus(long messageId) {
        String sql = "SELECT status FROM outbox WHERE id = $1";
        return connectionManager.withConnection("peegeeq-main", connection ->
                connection.preparedQuery(sql)
                        .execute(Tuple.of(messageId))
                        .map(rows -> rows.iterator().next().getString("status"))
        );
    }
}
