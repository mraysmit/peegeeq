package dev.mars.peegeeq.db.consumer;

import dev.mars.peegeeq.api.subscription.PartitionAssignmentInfo;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.subscription.SubscriptionManager;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for partitioned consumption methods on {@link SubscriptionManager}.
 *
 * <p>Tests the 5 new partitioned consumption API methods: joinPartitionedGroup,
 * leavePartitionedGroup, fetchPartitioned, commitOffset, getPartitionAssignments,
 * plus an end-to-end lifecycle test.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-12
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = "partitioned-subscription-database", mode = ResourceAccessMode.READ_WRITE)
public class PartitionedSubscriptionIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PartitionedSubscriptionIntegrationTest.class);

    private PgConnectionManager connectionManager;
    private SubscriptionManager subscriptionManager;

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

        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");

        // Wire up partitioned consumption services
        PartitionAssignmentService assignmentService = new PartitionAssignmentService(connectionManager, "peegeeq-main");
        PartitionedFetcher fetcher = new PartitionedFetcher(connectionManager, "peegeeq-main");
        PartitionedOffsetManager offsetManager = new PartitionedOffsetManager(connectionManager, "peegeeq-main");
        subscriptionManager.setPartitionedConsumptionServices(assignmentService, fetcher, offsetManager);

        // Clean up test data from prior runs
        VertxTestContext cleanupCtx = new VertxTestContext();
        cleanupTestData()
                .onSuccess(v -> cleanupCtx.completeNow())
                .onFailure(t -> cleanupCtx.completeNow());
        cleanupCtx.awaitCompletion(10, TimeUnit.SECONDS);

        logger.info("PartitionedSubscription test setup complete");
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
    // Test 5.1: Join Partitioned Group — Returns Assignments
    // joinPartitionedGroup() returns assignment list with correct generation.
    // ========================================================================

    @Test
    public void testJoinPartitionedGroup_returnsAssignments(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 5.1: testJoinPartitionedGroup_returnsAssignments STARTED ===");

        String topic = "test-ps-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group-A";
        String instanceId = "instance-1";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                // Insert messages with different message_groups to create partitions
                .compose(v -> insertOutboxMessage(topic, "part-1"))
                .compose(v -> insertOutboxMessage(topic, "part-2"))
                .compose(v -> insertOutboxMessage(topic, "part-3"))
                .compose(v -> subscriptionManager.joinPartitionedGroup(topic, groupName, instanceId))
                .compose(assignments -> {
                    assertFalse(assignments.isEmpty(), "Should have assignments");
                    // Single instance should own all partitions
                    assertEquals(3, assignments.size(), "Single instance should own all 3 partitions");
                    for (PartitionAssignmentInfo a : assignments) {
                        assertEquals(topic, a.topic());
                        assertEquals(groupName, a.groupName());
                        assertEquals(instanceId, a.instanceId());
                        assertTrue(a.generation() > 0, "Generation should be positive");
                    }
                    Set<String> partitions = assignments.stream()
                            .map(PartitionAssignmentInfo::partitionKey)
                            .collect(Collectors.toSet());
                    assertTrue(partitions.contains("part-1"));
                    assertTrue(partitions.contains("part-2"));
                    assertTrue(partitions.contains("part-3"));
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
    // Test 5.2: Join Partitioned Group — Rejects REFERENCE_COUNTING Topic
    // REFERENCE_COUNTING topic → IllegalArgumentException.
    // ========================================================================

    @Test
    public void testJoinPartitionedGroup_rejectsReferenceCountingTopic(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 5.2: testJoinPartitionedGroup_rejectsReferenceCountingTopic STARTED ===");

        String topic = "test-ps-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group-A";
        String instanceId = "instance-1";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopic(topic, "REFERENCE_COUNTING")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> subscriptionManager.joinPartitionedGroup(topic, groupName, instanceId))
                .compose(
                        assignments -> {
                            fail("Should have rejected REFERENCE_COUNTING topic");
                            return Future.<Void>succeededFuture();
                        },
                        throwable -> {
                            assertTrue(throwable instanceof IllegalArgumentException,
                                    "Expected IllegalArgumentException, got: " + throwable.getClass().getName());
                            assertTrue(throwable.getMessage().contains("OFFSET_WATERMARK"),
                                    "Error message should mention OFFSET_WATERMARK");
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
    // Test 5.3: Leave Partitioned Group — Triggers Rebalance
    // Leave → remaining instance gets all partitions.
    // ========================================================================

    @Test
    public void testLeavePartitionedGroup_triggersRebalance(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 5.3: testLeavePartitionedGroup_triggersRebalance STARTED ===");

        String topic = "test-ps-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group-A";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-1"))
                .compose(v -> insertOutboxMessage(topic, "part-2"))
                // Two instances join
                .compose(v -> subscriptionManager.joinPartitionedGroup(topic, groupName, "instance-1"))
                .compose(v -> subscriptionManager.joinPartitionedGroup(topic, groupName, "instance-2"))
                // instance-1 leaves
                .compose(v -> subscriptionManager.leavePartitionedGroup(topic, groupName, "instance-1"))
                // instance-2 should now own all partitions
                .compose(v -> subscriptionManager.getPartitionAssignments(topic, groupName, "instance-2"))
                .compose(assignments -> {
                    assertEquals(2, assignments.size(),
                            "After leave, remaining instance should own all 2 partitions");
                    for (PartitionAssignmentInfo a : assignments) {
                        assertEquals("instance-2", a.instanceId());
                    }
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
    // Test 5.4: Fetch Partitioned — Returns Ordered Messages
    // fetchPartitioned() returns messages in id order for partition.
    // ========================================================================

    @Test
    public void testFetchPartitioned_returnsOrderedMessages(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 5.4: testFetchPartitioned_returnsOrderedMessages STARTED ===");

        String topic = "test-ps-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group-A";
        String instanceId = "instance-1";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                // Insert 5 messages to same partition
                .compose(v -> insertMessages(topic, "part-1", 5))
                .compose(messageIds -> subscriptionManager.joinPartitionedGroup(topic, groupName, instanceId)
                        .compose(assignments -> {
                            int generation = assignments.get(0).generation();
                            return subscriptionManager.fetchPartitioned(topic, groupName, "part-1", 10, generation);
                        })
                        .compose(messages -> {
                            assertEquals(5, messages.size(), "Should fetch all 5 messages");
                            // Verify messages are in ascending id order
                            for (int i = 1; i < messages.size(); i++) {
                                long prevId = messages.get(i - 1).getLong("id");
                                long currId = messages.get(i).getLong("id");
                                assertTrue(currId > prevId,
                                        "Messages must be in ascending id order: " + prevId + " >= " + currId);
                            }
                            // Verify all messages are from the correct partition
                            for (JsonObject msg : messages) {
                                assertEquals("part-1", msg.getString("messageGroup"));
                            }
                            return Future.succeededFuture();
                        }))
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
    // Test 5.5: Fetch Partitioned — Rejects Unassigned Partition
    // Fetch partition not assigned to caller → error.
    // ========================================================================

    @Test
    public void testFetchPartitioned_rejectsUnassignedPartition(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 5.5: testFetchPartitioned_rejectsUnassignedPartition STARTED ===");

        String topic = "test-ps-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group-A";
        String instanceId = "instance-1";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-1"))
                .compose(v -> subscriptionManager.joinPartitionedGroup(topic, groupName, instanceId))
                .compose(assignments -> {
                    int generation = assignments.get(0).generation();
                    // Fetch a partition that was never assigned
                    return subscriptionManager.fetchPartitioned(topic, groupName, "nonexistent-partition", 10, generation);
                })
                .compose(
                        messages -> {
                            fail("Should have rejected unassigned partition");
                            return Future.<Void>succeededFuture();
                        },
                        throwable -> {
                            assertTrue(throwable instanceof IllegalArgumentException,
                                    "Expected IllegalArgumentException, got: " + throwable.getClass().getName());
                            assertTrue(throwable.getMessage().contains("not assigned"),
                                    "Error message should mention 'not assigned': " + throwable.getMessage());
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
    // Test 5.6: Commit Offset — Advances and Returns True
    // commitOffset() succeeds and returns true.
    // ========================================================================

    @Test
    public void testCommitOffset_advancesAndReturnsTrue(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 5.6: testCommitOffset_advancesAndReturnsTrue STARTED ===");

        String topic = "test-ps-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group-A";
        String instanceId = "instance-1";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertMessages(topic, "part-1", 5))
                .compose(messageIds -> subscriptionManager.joinPartitionedGroup(topic, groupName, instanceId)
                        .compose(assignments -> {
                            int generation = assignments.get(0).generation();
                            // Fetch to establish pending offset
                            return subscriptionManager.fetchPartitioned(topic, groupName, "part-1", 5, generation)
                                    .compose(messages -> {
                                        long lastId = messages.get(messages.size() - 1).getLong("id");
                                        // Now commit the offset
                                        return subscriptionManager.commitOffset(topic, groupName, "part-1", lastId, generation);
                                    });
                        })
                        .compose(committed -> {
                            assertTrue(committed, "commitOffset should return true");
                            return Future.succeededFuture();
                        }))
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
    // Test 5.7: Commit Offset — Stale Generation Returns False
    // Commit after rebalance with old gen → false.
    // ========================================================================

    @Test
    public void testCommitOffset_staleGeneration_returnsFalse(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 5.7: testCommitOffset_staleGeneration_returnsFalse STARTED ===");

        String topic = "test-ps-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group-A";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertMessages(topic, "part-1", 3))
                .compose(messageIds -> {
                    // First instance joins
                    return subscriptionManager.joinPartitionedGroup(topic, groupName, "instance-1")
                            .compose(assignments1 -> {
                                int oldGeneration = assignments1.get(0).generation();
                                // Fetch to establish pending offset
                                return subscriptionManager.fetchPartitioned(topic, groupName, "part-1", 3, oldGeneration)
                                        .compose(messages -> {
                                            long lastId = messages.get(messages.size() - 1).getLong("id");
                                            // Second instance joins → triggers rebalance → new generation
                                            return subscriptionManager.joinPartitionedGroup(topic, groupName, "instance-2")
                                                    .compose(assignments2 -> {
                                                        // Try to commit with the OLD generation
                                                        return subscriptionManager.commitOffset(
                                                                topic, groupName, "part-1", lastId, oldGeneration);
                                                    });
                                        });
                            });
                })
                .compose(committed -> {
                    assertFalse(committed, "commitOffset with stale generation should return false");
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
    // Test 5.8: Get Assignments — Returns Instance Partitions
    // Returns only partitions assigned to the specified instance.
    // ========================================================================

    @Test
    public void testGetAssignments_returnsInstancePartitions(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 5.8: testGetAssignments_returnsInstancePartitions STARTED ===");

        String topic = "test-ps-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group-A";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-1"))
                .compose(v -> insertOutboxMessage(topic, "part-2"))
                .compose(v -> insertOutboxMessage(topic, "part-3"))
                // Two instances join — partitions get distributed
                .compose(v -> subscriptionManager.joinPartitionedGroup(topic, groupName, "instance-1"))
                .compose(v -> subscriptionManager.joinPartitionedGroup(topic, groupName, "instance-2"))
                // Check assignments for each instance
                .compose(v -> subscriptionManager.getPartitionAssignments(topic, groupName, "instance-1"))
                .compose(instance1Assignments -> {
                    assertFalse(instance1Assignments.isEmpty(), "instance-1 should have at least 1 partition");
                    for (PartitionAssignmentInfo a : instance1Assignments) {
                        assertEquals("instance-1", a.instanceId(),
                                "All returned assignments should be for instance-1");
                    }
                    return subscriptionManager.getPartitionAssignments(topic, groupName, "instance-2");
                })
                .compose(instance2Assignments -> {
                    assertFalse(instance2Assignments.isEmpty(), "instance-2 should have at least 1 partition");
                    for (PartitionAssignmentInfo a : instance2Assignments) {
                        assertEquals("instance-2", a.instanceId(),
                                "All returned assignments should be for instance-2");
                    }
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
    // Test 5.9: End-to-End — Join, Fetch, Commit, Leave
    // Full lifecycle: join → fetch → commit → leave.
    // ========================================================================

    @Test
    public void testEndToEnd_joinFetchCommitLeave(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 5.9: testEndToEnd_joinFetchCommitLeave STARTED ===");

        String topic = "test-ps-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group-A";
        String instanceId = "instance-1";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertMessages(topic, "part-1", 3))
                .compose(messageIds ->
                    // 1. Join
                    subscriptionManager.joinPartitionedGroup(topic, groupName, instanceId)
                        .compose(assignments -> {
                            assertEquals(1, assignments.size(), "Should have 1 partition (part-1)");
                            int generation = assignments.get(0).generation();

                            // 2. Fetch
                            return subscriptionManager.fetchPartitioned(topic, groupName, "part-1", 10, generation)
                                    .compose(messages -> {
                                        assertEquals(3, messages.size(), "Should fetch all 3 messages");
                                        long lastId = messages.get(messages.size() - 1).getLong("id");

                                        // 3. Commit
                                        return subscriptionManager.commitOffset(topic, groupName, "part-1", lastId, generation);
                                    })
                                    .compose(committed -> {
                                        assertTrue(committed, "Commit should succeed");

                                        // 4. Fetch again — should return empty (all committed)
                                        return subscriptionManager.fetchPartitioned(topic, groupName, "part-1", 10, generation);
                                    })
                                    .compose(messages -> {
                                        assertTrue(messages.isEmpty(), "No more messages after commit");

                                        // 5. Leave
                                        return subscriptionManager.leavePartitionedGroup(topic, groupName, instanceId);
                                    });
                        }))
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
    // Helper: Create topic with specified completion tracking mode
    // ========================================================================

    private Future<Void> createTopic(String topic, String completionTrackingMode) {
        String sql = """
            INSERT INTO outbox_topics (topic, semantics, completion_tracking_mode)
            VALUES ($1, 'PUB_SUB', $2)
            ON CONFLICT (topic) DO NOTHING
            """;
        return connectionManager.withConnection("peegeeq-main", connection ->
                connection.preparedQuery(sql)
                        .execute(Tuple.of(topic, completionTrackingMode))
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
    // Helper: Insert outbox message
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
    // Helper: Insert N messages, return ids
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
}
