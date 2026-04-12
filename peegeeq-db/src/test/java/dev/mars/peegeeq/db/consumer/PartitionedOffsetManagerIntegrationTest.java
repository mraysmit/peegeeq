package dev.mars.peegeeq.db.consumer;

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
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

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PartitionedOffsetManager}.
 *
 * <p>Tests offset initialization, CAS-based commit, generation fencing,
 * pending offset tracking, multi-partition isolation, and generation bumping
 * against a real PostgreSQL instance via TestContainers.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-12
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = "partition-offset-database", mode = ResourceAccessMode.READ_WRITE)
public class PartitionedOffsetManagerIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PartitionedOffsetManagerIntegrationTest.class);

    private PgConnectionManager connectionManager;
    private PartitionedOffsetManager offsetManager;

    @BeforeEach
    public void setUp() throws Exception {
        super.setUpBaseIntegration();

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
                .maxSize(10)
                .build();

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        offsetManager = new PartitionedOffsetManager(connectionManager, "peegeeq-main");

        // Clean up offset data from prior test runs
        VertxTestContext cleanupCtx = new VertxTestContext();
        cleanupTestData()
                .onSuccess(v -> cleanupCtx.completeNow())
                .onFailure(t -> cleanupCtx.completeNow());
        cleanupCtx.awaitCompletion(10, TimeUnit.SECONDS);

        logger.info("PartitionedOffsetManager test setup complete");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    // ========================================================================
    // Test 1.1: Initialize Offset
    // Inserts a new offset row via INSERT ON CONFLICT for a (topic, group, partition) triple.
    // Verifies committed_offset=0, generation matches, committed_at is set, and pending fields are null.
    // ========================================================================

    @Test
    public void testInitializeOffset_createsRowWithZeroOffset(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testInitializeOffset_createsRowWithZeroOffset STARTED ===");

        String topic = "test-init-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partitionKey = "account-123";
        int generation = 1;
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        offsetManager.initializeOffset(topic, groupName, partitionKey, generation)
                .compose(offset -> {
                    assertEquals(topic, offset.topic());
                    assertEquals(groupName, offset.groupName());
                    assertEquals(partitionKey, offset.partitionKey());
                    assertEquals(0L, offset.committedOffset(),
                            "Initial committed_offset must be 0");
                    assertEquals(generation, offset.generation(),
                            "Generation must match the requested generation");
                    assertNotNull(offset.committedAt(),
                            "committed_at must be set");
                    assertNull(offset.pendingOffset(),
                            "No pending offset on initialization");
                    assertNull(offset.pendingSince(),
                            "No pending_since on initialization");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testInitializeOffset_createsRowWithZeroOffset COMPLETED ===");
    }

    // ========================================================================
    // Test 1.2: Initialize Offset Idempotent
    // Calls initializeOffset twice for the same (topic, group, partition).
    // The second call must return the existing row unchanged, not create a duplicate or reset state.
    // ========================================================================

    @Test
    public void testInitializeOffset_idempotent(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testInitializeOffset_idempotent STARTED ===");

        String topic = "test-idempotent-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partitionKey = "account-456";
        int generation = 1;
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        offsetManager.initializeOffset(topic, groupName, partitionKey, generation)
                .compose(first -> offsetManager.initializeOffset(topic, groupName, partitionKey, generation)
                        .map(second -> {
                            assertEquals(first.committedOffset(), second.committedOffset(),
                                    "Second init must return same committed_offset");
                            assertEquals(first.generation(), second.generation(),
                                    "Second init must return same generation");
                            return (Void) null;
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
        logger.info("=== TEST: testInitializeOffset_idempotent COMPLETED ===");
    }

    // ========================================================================
    // Test 1.3: Commit Offset Forward
    // Commits offset 10, then 20, both with matching generation.
    // Verifies both commits succeed and the final stored committed_offset is 20.
    // ========================================================================

    @Test
    public void testCommitOffset_advancesForward(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testCommitOffset_advancesForward STARTED ===");

        String topic = "test-commit-fwd-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partitionKey = "account-789";
        int generation = 1;
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        offsetManager.initializeOffset(topic, groupName, partitionKey, generation)
                .compose(init -> offsetManager.commitOffset(topic, groupName, partitionKey, 10L, generation))
                .compose(committed -> {
                    assertTrue(committed, "Commit of offset 10 must succeed");
                    return offsetManager.commitOffset(topic, groupName, partitionKey, 20L, generation);
                })
                .compose(committed -> {
                    assertTrue(committed, "Commit of offset 20 must succeed");
                    return offsetManager.getOffset(topic, groupName, partitionKey);
                })
                .compose(optOffset -> {
                    assertTrue(optOffset.isPresent(), "Offset row must exist");
                    PartitionOffset offset = optOffset.get();
                    assertEquals(20L, offset.committedOffset(),
                            "committed_offset must be 20 after two forward commits");
                    assertNotNull(offset.committedAt(),
                            "committed_at must be updated");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testCommitOffset_advancesForward COMPLETED ===");
    }

    // ========================================================================
    // Test 1.4: Commit Offset Rejects Backward Move
    // Commits to offset 20, then attempts to commit backward to 10.
    // The CAS WHERE committed_offset < $4 must reject the backward move and leave the offset at 20.
    // ========================================================================

    @Test
    public void testCommitOffset_rejectsBackwardMove(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testCommitOffset_rejectsBackwardMove STARTED ===");

        String topic = "test-commit-back-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partitionKey = "account-back";
        int generation = 1;
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        offsetManager.initializeOffset(topic, groupName, partitionKey, generation)
                .compose(init -> offsetManager.commitOffset(topic, groupName, partitionKey, 20L, generation))
                .compose(committed -> {
                    assertTrue(committed, "Initial commit to 20 must succeed");
                    // Now try to commit backward to 10
                    return offsetManager.commitOffset(topic, groupName, partitionKey, 10L, generation);
                })
                .compose(committed -> {
                    assertFalse(committed, "Backward commit from 20 to 10 must be rejected");
                    // Verify offset stayed at 20
                    return offsetManager.getOffset(topic, groupName, partitionKey);
                })
                .compose(optOffset -> {
                    assertTrue(optOffset.isPresent());
                    assertEquals(20L, optOffset.get().committedOffset(),
                            "Offset must remain at 20 after rejected backward commit");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testCommitOffset_rejectsBackwardMove COMPLETED ===");
    }

    // ========================================================================
    // Test 1.5: Commit Offset Rejects Stale Generation
    // Initializes with gen=1, externally bumps to gen=2 (simulating rebalance),
    // then attempts to commit with stale gen=1. The generation fence must reject the commit.
    // ========================================================================

    @Test
    public void testCommitOffset_rejectsStaleGeneration(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testCommitOffset_rejectsStaleGeneration STARTED ===");

        String topic = "test-commit-stale-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partitionKey = "account-stale";
        int generation = 1;
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        offsetManager.initializeOffset(topic, groupName, partitionKey, generation)
                // Externally bump generation to 2 (simulating a rebalance)
                .compose(init -> bumpGenerationDirectly(topic, groupName, partitionKey, 2))
                // Try to commit with stale generation=1
                .compose(v -> offsetManager.commitOffset(topic, groupName, partitionKey, 10L, 1))
                .compose(committed -> {
                    assertFalse(committed,
                            "Commit with stale generation=1 must be rejected when current=2");
                    // Verify offset stayed at 0
                    return offsetManager.getOffset(topic, groupName, partitionKey);
                })
                .compose(optOffset -> {
                    assertTrue(optOffset.isPresent());
                    assertEquals(0L, optOffset.get().committedOffset(),
                            "Offset must remain at 0 after stale generation commit rejected");
                    assertEquals(2, optOffset.get().generation(),
                            "Generation should be 2 after external bump");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testCommitOffset_rejectsStaleGeneration COMPLETED ===");
    }

    // ========================================================================
    // Test 1.6: Get Offset Returns Current State
    // Initializes and commits to offset 42, then reads via getOffset().
    // Verifies all 8 PartitionOffset fields are correct including null pending fields.
    // ========================================================================

    @Test
    public void testGetOffset_returnsCurrentState(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testGetOffset_returnsCurrentState STARTED ===");

        String topic = "test-getoffset-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partitionKey = "account-get";
        int generation = 1;
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        offsetManager.initializeOffset(topic, groupName, partitionKey, generation)
                .compose(init -> offsetManager.commitOffset(topic, groupName, partitionKey, 42L, generation))
                .compose(committed -> {
                    assertTrue(committed, "Commit must succeed");
                    return offsetManager.getOffset(topic, groupName, partitionKey);
                })
                .compose(optOffset -> {
                    assertTrue(optOffset.isPresent(), "Offset row must exist");
                    PartitionOffset offset = optOffset.get();
                    assertEquals(topic, offset.topic());
                    assertEquals(groupName, offset.groupName());
                    assertEquals(partitionKey, offset.partitionKey());
                    assertEquals(42L, offset.committedOffset(),
                            "committed_offset must be 42 after commit");
                    assertEquals(generation, offset.generation());
                    assertNotNull(offset.committedAt());
                    assertNull(offset.pendingOffset(),
                            "No pending offset after commit");
                    assertNull(offset.pendingSince());
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testGetOffset_returnsCurrentState COMPLETED ===");
    }

    // ========================================================================
    // Test 1.7: Get Offset Not Found Returns Empty
    // Calls getOffset() for a (topic, group, partition) that was never initialized.
    // Must return Optional.empty(), not throw or return a default-constructed object.
    // ========================================================================

    @Test
    public void testGetOffset_notFound_returnsEmpty(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testGetOffset_notFound_returnsEmpty STARTED ===");

        String topic = "test-nonexistent-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        offsetManager.getOffset(topic, "no-such-group", "no-such-partition")
                .compose(optOffset -> {
                    assertTrue(optOffset.isEmpty(),
                            "getOffset for non-existent row must return empty Optional");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testGetOffset_notFound_returnsEmpty COMPLETED ===");
    }

    // ========================================================================
    // Test 1.8: Set Pending Offset Tracks In-Flight Batch
    // Sets pending_offset=50 on a freshly initialized row with matching generation.
    // Verifies pending_offset and pending_since are populated while committed_offset remains 0.
    // ========================================================================

    @Test
    public void testSetPendingOffset_tracksInFlightBatch(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testSetPendingOffset_tracksInFlightBatch STARTED ===");

        String topic = "test-pending-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partitionKey = "account-pending";
        int generation = 1;
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        offsetManager.initializeOffset(topic, groupName, partitionKey, generation)
                .compose(init -> offsetManager.setPendingOffset(topic, groupName, partitionKey, 50L, generation))
                .compose(set -> {
                    assertTrue(set, "setPendingOffset must succeed with correct generation");
                    return offsetManager.getOffset(topic, groupName, partitionKey);
                })
                .compose(optOffset -> {
                    assertTrue(optOffset.isPresent());
                    PartitionOffset offset = optOffset.get();
                    assertEquals(50L, offset.pendingOffset(),
                            "pending_offset must be 50");
                    assertNotNull(offset.pendingSince(),
                            "pending_since must be set");
                    assertEquals(0L, offset.committedOffset(),
                            "committed_offset must still be 0");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testSetPendingOffset_tracksInFlightBatch COMPLETED ===");
    }

    // ========================================================================
    // Test 1.9: Commit Offset Clears Pending
    // Sets pending=50, then commits offset=50. The commit SQL sets pending_offset=NULL.
    // Verifies committed_offset advances to 50 and both pending fields are cleared.
    // ========================================================================

    @Test
    public void testCommitOffset_clearsPending(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testCommitOffset_clearsPending STARTED ===");

        String topic = "test-clr-pending-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partitionKey = "account-clrpend";
        int generation = 1;
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        offsetManager.initializeOffset(topic, groupName, partitionKey, generation)
                .compose(init -> offsetManager.setPendingOffset(topic, groupName, partitionKey, 50L, generation))
                .compose(set -> {
                    assertTrue(set, "setPendingOffset must succeed");
                    return offsetManager.commitOffset(topic, groupName, partitionKey, 50L, generation);
                })
                .compose(committed -> {
                    assertTrue(committed, "Commit to 50 must succeed");
                    return offsetManager.getOffset(topic, groupName, partitionKey);
                })
                .compose(optOffset -> {
                    assertTrue(optOffset.isPresent());
                    PartitionOffset offset = optOffset.get();
                    assertEquals(50L, offset.committedOffset(),
                            "committed_offset must be 50");
                    assertNull(offset.pendingOffset(),
                            "pending_offset must be NULL after commit");
                    assertNull(offset.pendingSince(),
                            "pending_since must be NULL after commit");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testCommitOffset_clearsPending COMPLETED ===");
    }

    // ========================================================================
    // Test 1.10: Set Pending Offset Rejects Stale Generation
    // Initializes with gen=1, externally bumps to gen=2, then attempts setPendingOffset with gen=1.
    // The generation fence must reject the update and leave pending_offset as NULL.
    // ========================================================================

    @Test
    public void testSetPendingOffset_rejectsStaleGeneration(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testSetPendingOffset_rejectsStaleGeneration STARTED ===");

        String topic = "test-pending-stale-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partitionKey = "account-pstale";
        int generation = 1;
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        offsetManager.initializeOffset(topic, groupName, partitionKey, generation)
                // Externally bump generation to 2
                .compose(init -> bumpGenerationDirectly(topic, groupName, partitionKey, 2))
                // Try to set pending with stale generation=1
                .compose(v -> offsetManager.setPendingOffset(topic, groupName, partitionKey, 50L, 1))
                .compose(set -> {
                    assertFalse(set,
                            "setPendingOffset with stale generation=1 must be rejected");
                    return offsetManager.getOffset(topic, groupName, partitionKey);
                })
                .compose(optOffset -> {
                    assertTrue(optOffset.isPresent());
                    assertNull(optOffset.get().pendingOffset(),
                            "pending_offset must remain NULL after stale-gen rejection");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testSetPendingOffset_rejectsStaleGeneration COMPLETED ===");
    }

    // ========================================================================
    // Test 1.11: Multiple Partitions Independent Offsets
    // Creates two partitions (A, B) under the same topic and group.
    // Commits A=100 and B=200, then verifies each partition's offset is independent.
    // ========================================================================

    @Test
    public void testMultiplePartitions_independentOffsets(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testMultiplePartitions_independentOffsets STARTED ===");

        String topic = "test-multi-part-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partA = "partition-A";
        String partB = "partition-B";
        int generation = 1;
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        offsetManager.initializeOffset(topic, groupName, partA, generation)
                .compose(a -> offsetManager.initializeOffset(topic, groupName, partB, generation))
                .compose(b -> offsetManager.commitOffset(topic, groupName, partA, 100L, generation))
                .compose(c1 -> offsetManager.commitOffset(topic, groupName, partB, 200L, generation))
                .compose(c2 -> offsetManager.getOffset(topic, groupName, partA))
                .compose(optA -> {
                    assertTrue(optA.isPresent());
                    assertEquals(100L, optA.get().committedOffset(),
                            "Partition A must be at offset 100");
                    return offsetManager.getOffset(topic, groupName, partB);
                })
                .compose(optB -> {
                    assertTrue(optB.isPresent());
                    assertEquals(200L, optB.get().committedOffset(),
                            "Partition B must be at offset 200, independent of A");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testMultiplePartitions_independentOffsets COMPLETED ===");
    }

    // ========================================================================
    // Test 1.12: Multiple Groups Independent Offsets
    // Creates two consumer groups (group-1, group-2) on the same topic and partition.
    // Commits group-1=100 and group-2=50, then verifies each group tracks its own offset.
    // ========================================================================

    @Test
    public void testMultipleGroups_independentOffsets(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testMultipleGroups_independentOffsets STARTED ===");

        String topic = "test-multi-grp-" + UUID.randomUUID().toString().substring(0, 8);
        String group1 = "group-1";
        String group2 = "group-2";
        String partitionKey = "shared-partition";
        int generation = 1;
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        offsetManager.initializeOffset(topic, group1, partitionKey, generation)
                .compose(a -> offsetManager.initializeOffset(topic, group2, partitionKey, generation))
                .compose(b -> offsetManager.commitOffset(topic, group1, partitionKey, 100L, generation))
                .compose(c1 -> offsetManager.commitOffset(topic, group2, partitionKey, 50L, generation))
                .compose(c2 -> offsetManager.getOffset(topic, group1, partitionKey))
                .compose(optG1 -> {
                    assertTrue(optG1.isPresent());
                    assertEquals(100L, optG1.get().committedOffset(),
                            "group-1 must be at offset 100");
                    return offsetManager.getOffset(topic, group2, partitionKey);
                })
                .compose(optG2 -> {
                    assertTrue(optG2.isPresent());
                    assertEquals(50L, optG2.get().committedOffset(),
                            "group-2 must be at offset 50, independent of group-1");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testMultipleGroups_independentOffsets COMPLETED ===");
    }

    // ========================================================================
    // Test 1.13: Bump Generation Increments and Returns
    // Initializes with gen=1, then calls bumpGeneration() which does generation=generation+1.
    // Verifies the RETURNING clause returns 2 and the stored row reflects the new generation.
    // ========================================================================

    @Test
    public void testBumpGeneration_incrementsAndReturns(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testBumpGeneration_incrementsAndReturns STARTED ===");

        String topic = "test-bumpgen-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partitionKey = "account-bump";
        int generation = 1;
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        offsetManager.initializeOffset(topic, groupName, partitionKey, generation)
                .compose(init -> {
                    assertEquals(1, init.generation());
                    return offsetManager.bumpGeneration(topic, groupName, partitionKey);
                })
                .compose(optNewGen -> {
                    assertTrue(optNewGen.isPresent(), "bumpGeneration must return a value");
                    assertEquals(2, optNewGen.get(), "bumpGeneration must return 2 after incrementing from 1");
                    return offsetManager.getOffset(topic, groupName, partitionKey);
                })
                .compose(optOffset -> {
                    assertTrue(optOffset.isPresent());
                    assertEquals(2, optOffset.get().generation(),
                            "Stored generation must be 2");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testBumpGeneration_incrementsAndReturns COMPLETED ===");
    }

    // ========================================================================
    // Test 1.14: Bump Generation Invalidates Stale Pending
    // Sets pending_offset=50 at gen=1, then bumps generation which clears pending fields.
    // Verifies pending_offset and pending_since are NULL and generation is 2 after the bump.
    // ========================================================================

    @Test
    public void testBumpGeneration_invalidatesStalePending(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testBumpGeneration_invalidatesStalePending STARTED ===");

        String topic = "test-bumpgen-pend-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partitionKey = "account-bp";
        int generation = 1;
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        offsetManager.initializeOffset(topic, groupName, partitionKey, generation)
                .compose(init -> offsetManager.setPendingOffset(topic, groupName, partitionKey, 50L, generation))
                .compose(set -> {
                    assertTrue(set, "setPendingOffset must succeed");
                    return offsetManager.bumpGeneration(topic, groupName, partitionKey);
                })
                .compose(optNewGen -> {
                    assertTrue(optNewGen.isPresent());
                    assertEquals(2, optNewGen.get());
                    return offsetManager.getOffset(topic, groupName, partitionKey);
                })
                .compose(optOffset -> {
                    assertTrue(optOffset.isPresent());
                    PartitionOffset offset = optOffset.get();
                    assertNull(offset.pendingOffset(),
                            "pending_offset must be cleared after generation bump");
                    assertNull(offset.pendingSince(),
                            "pending_since must be cleared after generation bump");
                    assertEquals(2, offset.generation());
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testBumpGeneration_invalidatesStalePending COMPLETED ===");
    }

    // ========================================================================
    // Edge Case A: Commit Same Offset (Equal, Not Greater)
    // Commits to 20, then attempts to recommit 20 (equal, not greater).
    // The CAS uses strict < so equal values are rejected — proves the boundary condition.
    // ========================================================================

    @Test
    public void testCommitOffset_rejectsSameOffset(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testCommitOffset_rejectsSameOffset STARTED ===");

        String topic = "test-same-offset-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partitionKey = "account-same";
        int generation = 1;
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        offsetManager.initializeOffset(topic, groupName, partitionKey, generation)
                .compose(init -> offsetManager.commitOffset(topic, groupName, partitionKey, 20L, generation))
                .compose(committed -> {
                    assertTrue(committed, "Initial commit to 20 must succeed");
                    // Try to recommit the same offset
                    return offsetManager.commitOffset(topic, groupName, partitionKey, 20L, generation);
                })
                .compose(committed -> {
                    assertFalse(committed,
                            "Recommit of same offset 20 must be rejected (strict < check, not <=)");
                    return offsetManager.getOffset(topic, groupName, partitionKey);
                })
                .compose(optOffset -> {
                    assertTrue(optOffset.isPresent());
                    assertEquals(20L, optOffset.get().committedOffset(),
                            "Offset must remain at 20");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testCommitOffset_rejectsSameOffset COMPLETED ===");
    }

    // ========================================================================
    // Edge Case B: initializeOffset After Prior Commit Preserves State
    // Commits to offset 42, then calls initializeOffset again on the same key.
    // The ON CONFLICT no-op must return the existing state (42), not reset to 0.
    // ========================================================================

    @Test
    public void testInitializeOffset_afterCommit_preservesExistingState(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testInitializeOffset_afterCommit_preservesExistingState STARTED ===");

        String topic = "test-init-after-commit-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partitionKey = "account-reinit";
        int generation = 1;
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        offsetManager.initializeOffset(topic, groupName, partitionKey, generation)
                .compose(init -> offsetManager.commitOffset(topic, groupName, partitionKey, 42L, generation))
                .compose(committed -> {
                    assertTrue(committed, "Commit to 42 must succeed");
                    // Re-initialize — must NOT reset offset back to 0
                    return offsetManager.initializeOffset(topic, groupName, partitionKey, generation);
                })
                .compose(reinit -> {
                    assertEquals(42L, reinit.committedOffset(),
                            "initializeOffset after commit must preserve committed_offset=42, not reset to 0");
                    assertEquals(generation, reinit.generation(),
                            "Generation must be preserved");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testInitializeOffset_afterCommit_preservesExistingState COMPLETED ===");
    }

    // ========================================================================
    // Edge Case C: commitOffset Without Prior Init Returns False
    // Calls commitOffset on a (topic, group, partition) that was never initialized.
    // The UPDATE matches zero rows, so rowCount()=0 returns false without throwing.
    // ========================================================================

    @Test
    public void testCommitOffset_withoutInit_returnsFalse(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testCommitOffset_withoutInit_returnsFalse STARTED ===");

        String topic = "test-no-init-commit-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        offsetManager.commitOffset(topic, "no-group", "no-partition", 10L, 1)
                .compose(committed -> {
                    assertFalse(committed,
                            "Commit on non-existent row must return false, not throw");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testCommitOffset_withoutInit_returnsFalse COMPLETED ===");
    }

    // ========================================================================
    // Edge Case D: bumpGeneration on Non-Existent Row Returns Empty
    // Calls bumpGeneration on a row that does not exist in the database.
    // Must return Optional.empty(), not throw NoSuchElementException on empty RETURNING.
    // ========================================================================

    @Test
    public void testBumpGeneration_nonExistentRow_returnsEmpty(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testBumpGeneration_nonExistentRow_returnsEmpty STARTED ===");

        String topic = "test-bump-norow-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        offsetManager.bumpGeneration(topic, "no-group", "no-partition")
                .compose(optGen -> {
                    assertTrue(optGen.isEmpty(),
                            "bumpGeneration on non-existent row must return empty Optional");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testBumpGeneration_nonExistentRow_returnsEmpty COMPLETED ===");
    }

    // ========================================================================
    // Edge Case E: Commit With New Generation After Bump Succeeds
    // Bumps generation from 1 to 2, then commits with gen=2 (the new owner).
    // Proves the ownership handoff path: old gen rejected (test 1.5) + new gen accepted.
    // ========================================================================

    @Test
    public void testCommitOffset_withNewGenerationAfterBump_succeeds(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testCommitOffset_withNewGenerationAfterBump_succeeds STARTED ===");

        String topic = "test-commit-newgen-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partitionKey = "account-newgen";
        int generation = 1;
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        offsetManager.initializeOffset(topic, groupName, partitionKey, generation)
                .compose(init -> offsetManager.bumpGeneration(topic, groupName, partitionKey))
                .compose(optNewGen -> {
                    assertTrue(optNewGen.isPresent());
                    assertEquals(2, optNewGen.get());
                    // New owner commits with gen=2
                    return offsetManager.commitOffset(topic, groupName, partitionKey, 10L, 2);
                })
                .compose(committed -> {
                    assertTrue(committed,
                            "Commit with the bumped generation=2 must succeed");
                    return offsetManager.getOffset(topic, groupName, partitionKey);
                })
                .compose(optOffset -> {
                    assertTrue(optOffset.isPresent());
                    assertEquals(10L, optOffset.get().committedOffset());
                    assertEquals(2, optOffset.get().generation());
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testCommitOffset_withNewGenerationAfterBump_succeeds COMPLETED ===");
    }

    // ========================================================================
    // Edge Case F: setPendingOffset Overwrite
    // Sets pending=50, then sets pending=100 on the same row with the same generation.
    // Verifies the second call overwrites the first — pending_offset must be 100, not 50.
    // ========================================================================

    @Test
    public void testSetPendingOffset_overwritesPreviousPending(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testSetPendingOffset_overwritesPreviousPending STARTED ===");

        String topic = "test-pend-overwrite-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partitionKey = "account-overwrite";
        int generation = 1;
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        offsetManager.initializeOffset(topic, groupName, partitionKey, generation)
                .compose(init -> offsetManager.setPendingOffset(topic, groupName, partitionKey, 50L, generation))
                .compose(set1 -> {
                    assertTrue(set1, "First setPendingOffset must succeed");
                    return offsetManager.setPendingOffset(topic, groupName, partitionKey, 100L, generation);
                })
                .compose(set2 -> {
                    assertTrue(set2, "Second setPendingOffset must succeed (overwrite)");
                    return offsetManager.getOffset(topic, groupName, partitionKey);
                })
                .compose(optOffset -> {
                    assertTrue(optOffset.isPresent());
                    assertEquals(100L, optOffset.get().pendingOffset(),
                            "pending_offset must be 100 after overwrite, not 50");
                    assertNotNull(optOffset.get().pendingSince(),
                            "pending_since must be updated");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testSetPendingOffset_overwritesPreviousPending COMPLETED ===");
    }

    // ========================================================================
    // Edge Case G: initializeOffset With Different Generation Returns Existing
    // Initializes with gen=1, then re-initializes with gen=5 on the same key.
    // The ON CONFLICT no-op ignores the new generation and returns the stored gen=1.
    // ========================================================================

    @Test
    public void testInitializeOffset_differentGeneration_returnsExistingGen(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testInitializeOffset_differentGeneration_returnsExistingGen STARTED ===");

        String topic = "test-init-diffgen-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partitionKey = "account-diffgen";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        offsetManager.initializeOffset(topic, groupName, partitionKey, 1)
                .compose(first -> {
                    assertEquals(1, first.generation());
                    // Re-init with different generation — ON CONFLICT no-op must return existing gen=1
                    return offsetManager.initializeOffset(topic, groupName, partitionKey, 5);
                })
                .compose(second -> {
                    assertEquals(1, second.generation(),
                            "initializeOffset with gen=5 on existing gen=1 row must return stored gen=1, not overwrite");
                    assertEquals(0L, second.committedOffset(),
                            "committed_offset must still be 0");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
        logger.info("=== TEST: testInitializeOffset_differentGeneration_returnsExistingGen COMPLETED ===");
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Directly bump the generation on a partition offset row (simulates a rebalance).
     */
    private Future<Void> bumpGenerationDirectly(String topic, String groupName,
                                                 String partitionKey, int newGeneration) {
        return connectionManager.withConnection("peegeeq-main", connection ->
            connection.preparedQuery(
                "UPDATE outbox_partition_offsets SET generation = $4 " +
                "WHERE topic = $1 AND group_name = $2 AND partition_key = $3")
                .execute(Tuple.of(topic, groupName, partitionKey, newGeneration))
                .mapEmpty()
        );
    }

    private Future<Void> cleanupTestData() {
        return connectionManager.withConnection("peegeeq-main", connection ->
            connection.query("DELETE FROM outbox_partition_offsets WHERE topic LIKE 'test-%'").execute()
                .compose(v -> connection.query("DELETE FROM outbox_partition_assignments WHERE topic LIKE 'test-%'").execute())
                .compose(v -> connection.query("DELETE FROM outbox_topic_watermarks WHERE topic LIKE 'test-%'").execute())
                .mapEmpty()
        );
    }
}
