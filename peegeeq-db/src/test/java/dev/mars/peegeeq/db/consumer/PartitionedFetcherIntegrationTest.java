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
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PartitionedFetcher}.
 *
 * <p>Tests message fetching per-partition in offset order, batch limiting,
 * generation fencing, SKIP LOCKED concurrent safety, pending offset tracking,
 * and crash recovery semantics against a real PostgreSQL instance.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-12
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = "partitioned-fetcher-database", mode = ResourceAccessMode.READ_WRITE)
public class PartitionedFetcherIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PartitionedFetcherIntegrationTest.class);

    private PgConnectionManager connectionManager;
    private PartitionedFetcher fetcher;
    private PartitionedOffsetManager offsetManager;

    @BeforeEach
    public void setUp(VertxTestContext testContext) {
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

        fetcher = new PartitionedFetcher(connectionManager, "peegeeq-main");
        offsetManager = new PartitionedOffsetManager(connectionManager, "peegeeq-main");

        // Clean up test data from prior runs
        cleanupTestData()
                .onSuccess(v -> {
                    logger.info("PartitionedFetcher test setup complete");
                    testContext.completeNow();
                })
                .onFailure(t -> testContext.completeNow());
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
    // Test 3.1: Fetch Returns Messages in ID Order
    // 10 messages inserted  fetch returns ids in ascending order.
    // ========================================================================

    @Test
    public void testFetch_returnsMessagesInIdOrder(VertxTestContext testContext) {
        logger.info("=== TEST: testFetch_returnsMessagesInIdOrder STARTED ===");

        String topic = "test-order-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partition = "part-A";
        int generation = 1;

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                // Insert 10 messages sequentially to guarantee id ordering
                .compose(v -> insertMessages(topic, partition, 10))
                // Initialize offset at 0 for this partition
                .compose(ids -> {
                    return offsetManager.initializeOffset(topic, groupName, partition, generation)
                            .map(o -> ids);
                })
                .compose(ids -> fetcher.fetch(topic, groupName, partition, 100, generation)
                        .map(messages -> {
                            assertEquals(10, messages.size(), "Must return all 10 messages");
                            // Verify ascending id order
                            List<Long> fetchedIds = messages.stream()
                                    .map(OutboxMessage::getId)
                                    .collect(Collectors.toList());
                            for (int i = 1; i < fetchedIds.size(); i++) {
                                assertTrue(fetchedIds.get(i) > fetchedIds.get(i - 1),
                                        "IDs must be strictly ascending: " + fetchedIds);
                            }
                            return (Void) null;
                        }))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        logger.info("=== TEST: testFetch_returnsMessagesInIdOrder COMPLETED ===");
    }

    // ========================================================================
    // Test 3.2: Fetch Starts After Committed Offset
    // Offset committed at id 5  fetch returns only messages with id > 5.
    // ========================================================================

    @Test
    public void testFetch_startsAfterCommittedOffset(VertxTestContext testContext) {
        logger.info("=== TEST: testFetch_startsAfterCommittedOffset STARTED ===");

        String topic = "test-offset-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partition = "part-A";
        int generation = 1;

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertMessages(topic, partition, 10))
                .compose(ids -> {
                    // Initialize offset then commit at the 5th message id
                    long fifthId = ids.get(4);
                    return offsetManager.initializeOffset(topic, groupName, partition, generation)
                            .compose(o -> offsetManager.commitOffset(topic, groupName, partition, fifthId, generation))
                            .map(committed -> ids);
                })
                .compose(ids -> {
                    long fifthId = ids.get(4);
                    return fetcher.fetch(topic, groupName, partition, 100, generation)
                            .map(messages -> {
                                assertEquals(5, messages.size(), "Must return 5 messages after offset");
                                // All returned message ids must be > fifthId
                                for (OutboxMessage msg : messages) {
                                    assertTrue(msg.getId() > fifthId,
                                            "Message id " + msg.getId() + " must be > committed offset " + fifthId);
                                }
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        logger.info("=== TEST: testFetch_startsAfterCommittedOffset COMPLETED ===");
    }

    // ========================================================================
    // Test 3.3: Fetch Respects Batch Size
    // 20 messages, batchSize=5  returns exactly 5.
    // ========================================================================

    @Test
    public void testFetch_respectsBatchSize(VertxTestContext testContext) {
        logger.info("=== TEST: testFetch_respectsBatchSize STARTED ===");

        String topic = "test-batch-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partition = "part-A";
        int generation = 1;

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertMessages(topic, partition, 20))
                .compose(ids -> offsetManager.initializeOffset(topic, groupName, partition, generation))
                .compose(o -> fetcher.fetch(topic, groupName, partition, 5, generation))
                .map(messages -> {
                    assertEquals(5, messages.size(), "Must return exactly 5 messages");
                    return (Void) null;
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        logger.info("=== TEST: testFetch_respectsBatchSize COMPLETED ===");
    }

    // ========================================================================
    // Test 3.4: Fetch Only Matching Partition
    // Messages in partition A and B  fetch(A) returns only A's messages.
    // ========================================================================

    @Test
    public void testFetch_onlyMatchingPartition(VertxTestContext testContext) {
        logger.info("=== TEST: testFetch_onlyMatchingPartition STARTED ===");

        String topic = "test-partition-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        int generation = 1;

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertMessages(topic, "part-A", 5))
                .compose(aIds -> insertMessages(topic, "part-B", 5).map(bIds -> aIds))
                .compose(aIds -> {
                    return offsetManager.initializeOffset(topic, groupName, "part-A", generation)
                            .compose(o -> offsetManager.initializeOffset(topic, groupName, "part-B", generation))
                            .map(o -> aIds);
                })
                .compose(aIds -> fetcher.fetch(topic, groupName, "part-A", 100, generation)
                        .map(messages -> {
                            assertEquals(5, messages.size(), "Must return only part-A messages");
                            for (OutboxMessage msg : messages) {
                                assertEquals("part-A", msg.getMessageGroup(),
                                        "All messages must belong to part-A");
                            }
                            return (Void) null;
                        }))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        logger.info("=== TEST: testFetch_onlyMatchingPartition COMPLETED ===");
    }

    // ========================================================================
    // Test 3.5: Fetch NULL Message Group Uses __default__ Partition
    // Messages with NULL message_group  fetchable via '__default__' partition.
    // ========================================================================

    @Test
    public void testFetch_nullMessageGroup_usesDefault(VertxTestContext testContext) {
        logger.info("=== TEST: testFetch_nullMessageGroup_usesDefault STARTED ===");

        String topic = "test-null-grp-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        int generation = 1;

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertMessagesNullGroup(topic, 3))
                .compose(ids -> offsetManager.initializeOffset(topic, groupName, "__default__", generation)
                        .map(o -> ids))
                .compose(ids -> fetcher.fetch(topic, groupName, "__default__", 100, generation)
                        .map(messages -> {
                            assertEquals(3, messages.size(), "Must return 3 null-group messages");
                            for (OutboxMessage msg : messages) {
                                assertNull(msg.getMessageGroup(),
                                        "message_group must be null for __default__ partition");
                            }
                            return (Void) null;
                        }))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        logger.info("=== TEST: testFetch_nullMessageGroup_usesDefault COMPLETED ===");
    }

    // ========================================================================
    // Test 3.6: Fetch Sets Pending Offset
    // After fetch, pending_offset = last fetched message id.
    // ========================================================================

    @Test
    public void testFetch_setsPendingOffset(VertxTestContext testContext) {
        logger.info("=== TEST: testFetch_setsPendingOffset STARTED ===");

        String topic = "test-pending-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partition = "part-A";
        int generation = 1;

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertMessages(topic, partition, 5))
                .compose(ids -> offsetManager.initializeOffset(topic, groupName, partition, generation)
                        .map(o -> ids))
                .compose(ids -> fetcher.fetch(topic, groupName, partition, 3, generation)
                        .map(messages -> {
                            long lastFetchedId = messages.get(messages.size() - 1).getId();
                            return lastFetchedId;
                        }))
                .compose(lastFetchedId -> offsetManager.getOffset(topic, groupName, partition)
                        .map(opt -> {
                            assertTrue(opt.isPresent(), "Offset row must exist");
                            PartitionOffset offset = opt.get();
                            assertNotNull(offset.pendingOffset(), "pending_offset must be set");
                            assertEquals(lastFetchedId, offset.pendingOffset(),
                                    "pending_offset must equal last fetched id");
                            assertNotNull(offset.pendingSince(), "pending_since must be set");
                            return (Void) null;
                        }))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        logger.info("=== TEST: testFetch_setsPendingOffset COMPLETED ===");
    }

    // ========================================================================
    // Test 3.7: Fetch Rejects Stale Generation
    // Fetch with old generation  empty result (fenced).
    // ========================================================================

    @Test
    public void testFetch_rejectsStaleGeneration(VertxTestContext testContext) {
        logger.info("=== TEST: testFetch_rejectsStaleGeneration STARTED ===");

        String topic = "test-stale-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partition = "part-A";

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertMessages(topic, partition, 5))
                // Initialize at generation 2
                .compose(ids -> offsetManager.initializeOffset(topic, groupName, partition, 2))
                // Bump to generation 3 (simulates rebalance)
                .compose(o -> offsetManager.bumpGeneration(topic, groupName, partition))
                // Fetch with stale generation 2
                .compose(newGen -> fetcher.fetch(topic, groupName, partition, 100, 2))
                .map(messages -> {
                    assertTrue(messages.isEmpty(),
                            "Fetch with stale generation must return empty, but got " + messages.size());
                    return (Void) null;
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        logger.info("=== TEST: testFetch_rejectsStaleGeneration COMPLETED ===");
    }

    // ========================================================================
    // Test 3.8: SKIP LOCKED Prevents Double Delivery
    // Manually lock some rows in one transaction, then fetch in another 
    // fetcher skips the locked rows.
    // ========================================================================

    @Test
    public void testFetch_skipLockedPreventsDoubleDelivery(VertxTestContext testContext) {
        logger.info("=== TEST: testFetch_skipLockedPreventsDoubleDelivery STARTED ===");

        String topic = "test-skiplock-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partition = "part-A";
        int generation = 1;

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertMessages(topic, partition, 10))
                .compose(ids -> offsetManager.initializeOffset(topic, groupName, partition, generation)
                        .map(o -> ids))
                .compose(ids -> {
                    // Manually open a transaction and lock the first 5 rows
                    // (simulating another consumer's in-flight fetch)
                    return connectionManager.withTransaction("peegeeq-main", lockConn -> {
                        String lockSql = """
                            SELECT o.id FROM outbox o
                            WHERE o.topic = $1
                              AND o.status IN ('PENDING', 'PROCESSING')
                              AND o.message_group = $2
                            ORDER BY o.id ASC
                            LIMIT 5
                            FOR UPDATE SKIP LOCKED
                            """;
                        return lockConn.preparedQuery(lockSql)
                                .execute(Tuple.of(topic, partition))
                                .compose(lockedRows -> {
                                    Set<Long> lockedIds = new HashSet<>();
                                    for (var row : lockedRows) {
                                        lockedIds.add(row.getLong("id"));
                                    }
                                    assertEquals(5, lockedIds.size(), "Must have locked 5 rows");

                                    // Now fetch via the fetcher it runs in its OWN transaction.
                                    // BUT we're inside lockConn's transaction which holds the locks.
                                    // The fetcher will open a new connection/transaction and
                                    // SKIP the locked rows.
                                    return fetcher.fetch(topic, groupName, partition, 10, generation)
                                            .map(messages -> {
                                                // Fetcher should get the OTHER 5 rows (not the locked ones)
                                                assertEquals(5, messages.size(),
                                                        "Fetcher must get exactly 5 unlocked messages");

                                                Set<Long> fetchedIds = messages.stream()
                                                        .map(OutboxMessage::getId)
                                                        .collect(Collectors.toSet());

                                                // No overlap between locked and fetched
                                                Set<Long> intersection = new HashSet<>(lockedIds);
                                                intersection.retainAll(fetchedIds);
                                                assertTrue(intersection.isEmpty(),
                                                        "Fetched messages must not overlap with locked rows: " + intersection);

                                                return (Void) null;
                                            });
                                });
                    });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        logger.info("=== TEST: testFetch_skipLockedPreventsDoubleDelivery COMPLETED ===");
    }

    // ========================================================================
    // Test 3.9: Fetch Empty Partition Returns Empty List
    // No pending messages  returns empty list, no error.
    // ========================================================================

    @Test
    public void testFetch_emptyPartition_returnsEmptyList(VertxTestContext testContext) {
        logger.info("=== TEST: testFetch_emptyPartition_returnsEmptyList STARTED ===");

        String topic = "test-empty-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partition = "part-A";
        int generation = 1;

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                // No messages inserted
                .compose(v -> offsetManager.initializeOffset(topic, groupName, partition, generation))
                .compose(o -> fetcher.fetch(topic, groupName, partition, 100, generation))
                .map(messages -> {
                    assertNotNull(messages, "Result must not be null");
                    assertTrue(messages.isEmpty(), "Must return empty list for empty partition");
                    return (Void) null;
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        logger.info("=== TEST: testFetch_emptyPartition_returnsEmptyList COMPLETED ===");
    }

    // ========================================================================
    // Test 3.10: Fetch Only PENDING/PROCESSING Status
    // COMPLETED/FAILED messages are skipped.
    // ========================================================================

    @Test
    public void testFetch_onlyPendingAndProcessingStatus(VertxTestContext testContext) {
        logger.info("=== TEST: testFetch_onlyPendingAndProcessingStatus STARTED ===");

        String topic = "test-status-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partition = "part-A";
        int generation = 1;

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                // Insert messages with various statuses
                .compose(v -> insertOutboxMessageWithStatus(topic, partition, "PENDING"))
                .compose(id1 -> insertOutboxMessageWithStatus(topic, partition, "PROCESSING").map(id2 -> id1))
                .compose(id1 -> insertOutboxMessageWithStatus(topic, partition, "COMPLETED"))
                .compose(id3 -> insertOutboxMessageWithStatus(topic, partition, "FAILED"))
                .compose(id4 -> insertOutboxMessageWithStatus(topic, partition, "DEAD_LETTER"))
                .compose(id5 -> offsetManager.initializeOffset(topic, groupName, partition, generation))
                .compose(o -> fetcher.fetch(topic, groupName, partition, 100, generation))
                .map(messages -> {
                    assertEquals(2, messages.size(),
                            "Must return only PENDING and PROCESSING messages, but got " + messages.size());
                    return (Void) null;
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        logger.info("=== TEST: testFetch_onlyPendingAndProcessingStatus COMPLETED ===");
    }

    // ========================================================================
    // Test 3.11: Sequential Delivery Within Partition
    // Fetch batch, commit, fetch next  no gaps, no reordering.
    // ========================================================================

    @Test
    public void testSequentialDelivery_withinPartition(VertxTestContext testContext) {
        logger.info("=== TEST: testSequentialDelivery_withinPartition STARTED ===");

        String topic = "test-seq-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partition = "part-A";
        int generation = 1;

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertMessages(topic, partition, 10))
                .compose(allIds -> offsetManager.initializeOffset(topic, groupName, partition, generation)
                        .map(o -> allIds))
                .compose(allIds -> {
                    // Fetch first batch of 4
                    return fetcher.fetch(topic, groupName, partition, 4, generation)
                            .compose(batch1 -> {
                                assertEquals(4, batch1.size(), "First batch must have 4");
                                long lastId1 = batch1.get(batch1.size() - 1).getId();
                                // Commit offset at last id
                                return offsetManager.commitOffset(topic, groupName, partition, lastId1, generation)
                                        .compose(committed -> {
                                            assertTrue(committed, "Commit must succeed");
                                            // Fetch second batch of 4
                                            return fetcher.fetch(topic, groupName, partition, 4, generation);
                                        })
                                        .map(batch2 -> {
                                            assertEquals(4, batch2.size(), "Second batch must have 4");
                                            // First id of batch2 must be > last id of batch1
                                            long firstId2 = batch2.get(0).getId();
                                            assertTrue(firstId2 > lastId1,
                                                    "Batch 2 first id " + firstId2 + " must be > batch 1 last id " + lastId1);

                                            // Verify no gap: batch2 starts exactly where batch1 left off
                                            // (all ids in allIds must be covered sequentially)
                                            List<Long> batch1Ids = batch1.stream()
                                                    .map(OutboxMessage::getId).collect(Collectors.toList());
                                            List<Long> batch2Ids = batch2.stream()
                                                    .map(OutboxMessage::getId).collect(Collectors.toList());

                                            // Batch1 last + Batch2 first should be consecutive in allIds
                                            int batch1LastIdx = allIds.indexOf(lastId1);
                                            assertEquals(allIds.get(batch1LastIdx + 1), firstId2,
                                                    "No gap between batches");

                                            return (Void) null;
                                        });
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        logger.info("=== TEST: testSequentialDelivery_withinPartition COMPLETED ===");
    }

    // ========================================================================
    // Test 3.12: Crash Recovery At-Least-Once Delivery
    // Consumer A fetches batch (pending_offset set), crashes (no commit).
    // New consumer B takes partition after rebalance. B fetches from
    // committed_offset (not pending), getting the same messages.
    // ========================================================================

    @Test
    public void testCrashRecovery_atLeastOnceDelivery(VertxTestContext testContext) {
        logger.info("=== TEST: testCrashRecovery_atLeastOnceDelivery STARTED ===");

        String topic = "test-crash-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partition = "part-A";
        int gen1 = 1;

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertMessages(topic, partition, 10))
                .compose(ids -> offsetManager.initializeOffset(topic, groupName, partition, gen1).map(o -> ids))
                .compose(ids -> {
                    // Consumer A fetches batch (sets pending_offset)
                    return fetcher.fetch(topic, groupName, partition, 10, gen1)
                            .map(batch -> {
                                assertEquals(10, batch.size(), "A must fetch all 10 messages");
                                return ids;
                            });
                })
                // A crashes no commit happens. Rebalance bumps generation.
                .compose(ids -> offsetManager.bumpGeneration(topic, groupName, partition))
                .compose(newGenOpt -> {
                    assertTrue(newGenOpt.isPresent(), "Generation must bump");
                    int gen2 = newGenOpt.get();
                    // Consumer B fetches with new generation
                    return fetcher.fetch(topic, groupName, partition, 10, gen2);
                })
                .map(batchB -> {
                    // B must get all 10 messages (committed_offset still 0)
                    assertEquals(10, batchB.size(),
                            "B must re-fetch all 10 messages after crash (committed_offset=0)");
                    return (Void) null;
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        logger.info("=== TEST: testCrashRecovery_atLeastOnceDelivery COMPLETED ===");
    }

    // ========================================================================
    // Test 3.13: Rebalance During Inflight Batch Old Owner Fenced
    // Consumer A fetches batch with gen=1, sets pending. Rebalance bumps gen
    // to 2. A tries to commit with gen=1  rejected. B fetches from
    // committed_offset  gets same messages.
    // ========================================================================

    @Test
    public void testRebalanceDuringInflightBatch_oldOwnerFenced(VertxTestContext testContext) {
        logger.info("=== TEST: testRebalanceDuringInflightBatch_oldOwnerFenced STARTED ===");

        String topic = "test-inflight-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partition = "part-A";
        int gen1 = 1;

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertMessages(topic, partition, 10))
                .compose(ids -> offsetManager.initializeOffset(topic, groupName, partition, gen1).map(o -> ids))
                // Consumer A fetches batch with gen=1
                .compose(ids -> fetcher.fetch(topic, groupName, partition, 5, gen1)
                        .map(batchA -> {
                            assertEquals(5, batchA.size(), "A fetches 5 messages");
                            return batchA.get(batchA.size() - 1).getId();
                        }))
                // Rebalance bumps generation (partition reassigned to B)
                .compose(lastIdA -> offsetManager.bumpGeneration(topic, groupName, partition)
                        .map(newGenOpt -> {
                            assertTrue(newGenOpt.isPresent(), "Generation must bump");
                            return new long[]{lastIdA, newGenOpt.get()};
                        }))
                .compose(pair -> {
                    long lastIdA = pair[0];
                    int gen2 = (int) pair[1];

                    // A tries to commit with stale gen=1  REJECTED
                    return offsetManager.commitOffset(topic, groupName, partition, lastIdA, gen1)
                            .compose(committed -> {
                                assertFalse(committed, "Stale commit with gen=1 must be rejected");

                                // B fetches with gen=2  starts from committed_offset=0
                                return fetcher.fetch(topic, groupName, partition, 10, gen2);
                            })
                            .map(batchB -> {
                                // B gets all 10 messages (committed_offset=0, pending cleared by bumpGeneration)
                                assertEquals(10, batchB.size(),
                                        "B must get all messages from committed_offset=0");
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        logger.info("=== TEST: testRebalanceDuringInflightBatch_oldOwnerFenced COMPLETED ===");
    }

    // ========================================================================
    // Test 3.14: No Gap No Skip Across Multiple Fetch-Commit Cycles
    // 100 messages, fetch in batches of 10, commit after each. Every message
    // delivered exactly once, strictly ascending, no gaps between batches.
    // ========================================================================

    @Test
    public void testNoGapNoSkip_acrossMultipleFetchCommitCycles(VertxTestContext testContext) {
        logger.info("=== TEST: testNoGapNoSkip_acrossMultipleFetchCommitCycles STARTED ===");

        String topic = "test-nogap-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partition = "part-A";
        int generation = 1;

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertMessages(topic, partition, 100))
                .compose(allIds -> offsetManager.initializeOffset(topic, groupName, partition, generation)
                        .map(o -> allIds))
                .compose(allIds -> {
                    // Fetch-commit loop: 10 batches of 10
                    List<Long> allDelivered = new ArrayList<>();
                    Future<Void> chain = Future.succeededFuture();

                    for (int batch = 0; batch < 10; batch++) {
                        chain = chain.compose(v ->
                                fetcher.fetch(topic, groupName, partition, 10, generation)
                                        .compose(messages -> {
                                            assertFalse(messages.isEmpty(), "Batch must not be empty");
                                            for (OutboxMessage msg : messages) {
                                                allDelivered.add(msg.getId());
                                            }
                                            long lastId = messages.get(messages.size() - 1).getId();
                                            return offsetManager.commitOffset(topic, groupName, partition, lastId, generation)
                                                    .map(committed -> {
                                                        assertTrue(committed, "Commit must succeed");
                                                        return (Void) null;
                                                    });
                                        })
                        );
                    }

                    return chain.map(v -> {
                        // Verify: all 100 messages delivered exactly once
                        assertEquals(100, allDelivered.size(), "Must have delivered exactly 100 messages");

                        // Strictly ascending order
                        for (int i = 1; i < allDelivered.size(); i++) {
                            assertTrue(allDelivered.get(i) > allDelivered.get(i - 1),
                                    "IDs must be strictly ascending at index " + i + ": "
                                            + allDelivered.get(i - 1) + " -> " + allDelivered.get(i));
                        }

                        // No duplicates
                        Set<Long> uniqueIds = new HashSet<>(allDelivered);
                        assertEquals(100, uniqueIds.size(), "No duplicates allowed");

                        // All original IDs delivered
                        assertEquals(new HashSet<>(allIds), uniqueIds, "All original IDs must be delivered");

                        return (Void) null;
                    });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        logger.info("=== TEST: testNoGapNoSkip_acrossMultipleFetchCommitCycles COMPLETED ===");
    }

    // ========================================================================
    // Test 3.15: Concurrent Fetch on Same Partition No Overlap
    // Rows locked by held-open transaction are skipped by fetcher.
    // Combined fetched sets cover the full range with zero intersection.
    // ========================================================================

    @Test
    public void testConcurrentFetchOnSamePartition_noOverlap(VertxTestContext testContext) {
        logger.info("=== TEST: testConcurrentFetchOnSamePartition_noOverlap STARTED ===");

        String topic = "test-nooverlap-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partition = "part-A";
        int generation = 1;

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertMessages(topic, partition, 20))
                .compose(ids -> offsetManager.initializeOffset(topic, groupName, partition, generation)
                        .map(o -> ids))
                .compose(ids -> {
                    // Hold 10 rows locked in a transaction
                    return connectionManager.withTransaction("peegeeq-main", lockConn -> {
                        String lockSql = """
                            SELECT o.id FROM outbox o
                            WHERE o.topic = $1
                              AND o.status IN ('PENDING', 'PROCESSING')
                              AND o.message_group = $2
                            ORDER BY o.id ASC
                            LIMIT 10
                            FOR UPDATE SKIP LOCKED
                            """;
                        return lockConn.preparedQuery(lockSql)
                                .execute(Tuple.of(topic, partition))
                                .compose(lockedRows -> {
                                    Set<Long> lockedIds = new HashSet<>();
                                    for (var row : lockedRows) {
                                        lockedIds.add(row.getLong("id"));
                                    }
                                    assertEquals(10, lockedIds.size(), "Must lock 10 rows");

                                    // Fetcher sees remaining 10 unlocked rows
                                    return fetcher.fetch(topic, groupName, partition, 20, generation)
                                            .map(messages -> {
                                                assertEquals(10, messages.size(),
                                                        "Fetcher must skip locked rows, getting other 10");
                                                Set<Long> fetchedIds = messages.stream()
                                                        .map(OutboxMessage::getId)
                                                        .collect(Collectors.toSet());

                                                // Zero intersection
                                                Set<Long> overlap = new HashSet<>(lockedIds);
                                                overlap.retainAll(fetchedIds);
                                                assertTrue(overlap.isEmpty(),
                                                        "Zero overlap between locked and fetched: " + overlap);

                                                // Combined covers all 20
                                                Set<Long> combined = new HashSet<>(lockedIds);
                                                combined.addAll(fetchedIds);
                                                assertEquals(20, combined.size(),
                                                        "Combined must cover all 20 messages");
                                                return (Void) null;
                                            });
                                });
                    });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        logger.info("=== TEST: testConcurrentFetchOnSamePartition_noOverlap COMPLETED ===");
    }

    // ========================================================================
    // Test 3.16: Fetch After Generation Bump Ignores Stale Pending
    // Consumer A fetches with gen=1, pending_offset=50. Generation bumped
    // to 2 (pending cleared). Consumer B fetches with gen=2  starts from
    // committed_offset (not stale pending_offset=50).
    // ========================================================================

    @Test
    public void testFetchAfterGenerationBump_ignoresStaleInFlightBatch(VertxTestContext testContext) {
        logger.info("=== TEST: testFetchAfterGenerationBump_ignoresStaleInFlightBatch STARTED ===");

        String topic = "test-stale-pending-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partition = "part-A";
        int gen1 = 1;

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertMessages(topic, partition, 20))
                .compose(ids -> {
                    return offsetManager.initializeOffset(topic, groupName, partition, gen1)
                            // Commit offset to id 5 (so B won't start from 0)
                            .compose(o -> offsetManager.commitOffset(topic, groupName, partition, ids.get(4), gen1))
                            .map(committed -> ids);
                })
                // Consumer A fetches a batch (this sets pending_offset to some value)
                .compose(ids -> fetcher.fetch(topic, groupName, partition, 10, gen1)
                        .map(batchA -> {
                            // A got messages after committed offset (ids[4])
                            assertFalse(batchA.isEmpty(), "A must fetch messages");
                            return ids;
                        }))
                // Verify pending_offset is set
                .compose(ids -> offsetManager.getOffset(topic, groupName, partition)
                        .map(opt -> {
                            assertTrue(opt.isPresent());
                            assertNotNull(opt.get().pendingOffset(),
                                    "pending_offset must be set after A's fetch");
                            return ids;
                        }))
                // Rebalance: bump generation (clears pending)
                .compose(ids -> offsetManager.bumpGeneration(topic, groupName, partition)
                        .map(newGenOpt -> {
                            assertTrue(newGenOpt.isPresent());
                            return new Object[]{ids, newGenOpt.get()};
                        }))
                .compose(pair -> {
                    @SuppressWarnings("unchecked")
                    List<Long> ids = (List<Long>) pair[0];
                    int gen2 = (int) pair[1];

                    // Verify pending was cleared
                    return offsetManager.getOffset(topic, groupName, partition)
                            .compose(opt -> {
                                assertTrue(opt.isPresent());
                                assertNull(opt.get().pendingOffset(),
                                        "pending_offset must be cleared by bumpGeneration");
                                assertEquals(ids.get(4).longValue(), opt.get().committedOffset(),
                                        "committed_offset must remain at 5th message id");

                                // Consumer B fetches with gen=2 must start from committed offset
                                return fetcher.fetch(topic, groupName, partition, 20, gen2);
                            })
                            .map(batchB -> {
                                // B should get 15 messages (ids[5] through ids[19])
                                assertEquals(15, batchB.size(),
                                        "B must get messages starting from committed_offset, not stale pending");
                                // First message must be after committed offset
                                assertTrue(batchB.get(0).getId() > ids.get(4),
                                        "B's first message must be after committed_offset");
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        logger.info("=== TEST: testFetchAfterGenerationBump_ignoresStaleInFlightBatch COMPLETED ===");
    }

    // ========================================================================
    // Helper: Insert N messages for a partition, return list of generated ids
    // ========================================================================

    private Future<List<Long>> insertMessages(String topic, String messageGroup, int count) {
        Future<List<Long>> result = Future.succeededFuture(new ArrayList<>());
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
    // Helper: Insert N messages with NULL message_group, return ids
    // ========================================================================

    private Future<List<Long>> insertMessagesNullGroup(String topic, int count) {
        Future<List<Long>> result = Future.succeededFuture(new ArrayList<>());
        for (int i = 0; i < count; i++) {
            result = result.compose(ids -> insertOutboxMessageNullGroup(topic)
                    .map(id -> {
                        ids.add(id);
                        return ids;
                    }));
        }
        return result;
    }

    // ========================================================================
    // Helper: Insert single outbox message
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
    // Helper: Insert single outbox message with NULL message_group
    // ========================================================================

    private Future<Long> insertOutboxMessageNullGroup(String topic) {
        String sql = """
            INSERT INTO outbox (topic, payload, status, message_group, created_at)
            VALUES ($1, $2, 'PENDING', NULL, $3)
            RETURNING id
            """;
        JsonObject payload = new JsonObject().put("test", "null-group");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return connectionManager.withConnection("peegeeq-main", connection ->
                connection.preparedQuery(sql)
                        .execute(Tuple.of(topic, payload, now))
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
    // Helper: Clean up test data
    // ========================================================================

    private Future<Void> cleanupTestData() {
        return connectionManager.withConnection("peegeeq-main", connection ->
                connection.preparedQuery("DELETE FROM outbox_partition_assignments WHERE topic LIKE 'test-%'")
                        .execute()
                        .compose(v -> connection.preparedQuery("DELETE FROM outbox_partition_offsets WHERE topic LIKE 'test-%'").execute())
                        .map(rows -> (Void) null)
        );
    }

    // ========================================================================
    // Helper: Create topic
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
    // Helper: Create subscription
    // ========================================================================

    private Future<Void> createSubscription(String topic, String groupName) {
        String sql = """
            INSERT INTO outbox_topic_subscriptions (topic, group_name, subscription_status)
            VALUES ($1, $2, 'ACTIVE')
            ON CONFLICT (topic, group_name) DO NOTHING
            """;
        return connectionManager.withConnection("peegeeq-main", connection ->
                connection.preparedQuery(sql)
                        .execute(Tuple.of(topic, groupName))
                        .map(rows -> (Void) null)
        );
    }

    // ========================================================================
    // Helper: Get pending offset from offsets table
    // ========================================================================

    private Future<Optional<Long>> getPendingOffset(String topic, String groupName, String partitionKey) {
        return offsetManager.getOffset(topic, groupName, partitionKey)
                .map(opt -> opt.map(PartitionOffset::pendingOffset));
    }
}
