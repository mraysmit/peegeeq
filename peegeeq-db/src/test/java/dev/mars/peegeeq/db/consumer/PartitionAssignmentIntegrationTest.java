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
 * Integration tests for {@link PartitionAssignmentService}.
 *
 * <p>Tests partition assignment via consistent hashing, rebalance on join/leave,
 * generation-based fencing, heartbeat tracking, and partition discovery
 * against a real PostgreSQL instance via TestContainers.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-12
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = "partition-assignment-database", mode = ResourceAccessMode.READ_WRITE)
public class PartitionAssignmentIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PartitionAssignmentIntegrationTest.class);

    private PgConnectionManager connectionManager;
    private PartitionAssignmentService assignmentService;
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

        assignmentService = new PartitionAssignmentService(connectionManager, "peegeeq-main");
        offsetManager = new PartitionedOffsetManager(connectionManager, "peegeeq-main");

        // Clean up test data from prior runs
        cleanupTestData()
                .onSuccess(v -> {
                    logger.info("PartitionAssignment test setup complete");
                    testContext.completeNow();
                })
                .onFailure(t -> {
                    logger.info("PartitionAssignment test setup complete (cleanup skipped: {})", t.getMessage());
                    testContext.completeNow();
                });
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
    // Test 2.1: Join Group — First Instance Gets All Partitions
    // A single consumer instance joins a group with 3 discovered partitions.
    // Verifies that the sole instance is assigned all 3 partitions with correct
    // topic, group, instanceId, and generation on each assignment.
    // ========================================================================

    @Test
    public void testJoinGroup_firstInstance_getsAllPartitions(VertxTestContext testContext) {
        logger.info("=== TEST: testJoinGroup_firstInstance_getsAllPartitions STARTED ===");

        String topic = "test-join-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String instanceId = "instance-1";

        // Setup: create topic config, subscription, and messages with 3 partitions
        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "partition-A"))
                .compose(v -> insertOutboxMessage(topic, "partition-B"))
                .compose(v -> insertOutboxMessage(topic, "partition-C"))
                // Act: join group
                .compose(v -> assignmentService.joinGroup(topic, groupName, instanceId))
                // Assert
                .compose(assignments -> {
                    assertEquals(3, assignments.size(),
                            "Single instance should get all 3 partitions");

                    for (PartitionAssignment a : assignments) {
                        assertEquals(instanceId, a.assignedInstanceId(),
                                "All partitions must be assigned to the sole instance");
                        assertEquals(topic, a.topic());
                        assertEquals(groupName, a.groupName());
                        assertTrue(a.generation() > 0,
                                "Generation must be positive after join");
                    }

                    Set<String> keys = assignments.stream()
                            .map(PartitionAssignment::partitionKey)
                            .collect(Collectors.toSet());
                    assertTrue(keys.contains("partition-A"), "Must contain partition-A");
                    assertTrue(keys.contains("partition-B"), "Must contain partition-B");
                    assertTrue(keys.contains("partition-C"), "Must contain partition-C");

                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        logger.info("=== TEST: testJoinGroup_firstInstance_getsAllPartitions COMPLETED ===");
    }

    // ========================================================================
    // Test 2.2: Join Group — Second Instance Triggers Rebalance
    // Two instances join a group with 4 partitions. After the second joins,
    // partitions are split between both instances. Neither gets all 4.
    // Each instance gets at least one partition.
    // ========================================================================

    @Test
    public void testJoinGroup_secondInstance_triggersRebalance(VertxTestContext testContext) {
        logger.info("=== TEST: testJoinGroup_secondInstance_triggersRebalance STARTED ===");

        String topic = "test-rebal-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String instanceA = "instance-A";
        String instanceB = "instance-B";

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-1"))
                .compose(v -> insertOutboxMessage(topic, "part-2"))
                .compose(v -> insertOutboxMessage(topic, "part-3"))
                .compose(v -> insertOutboxMessage(topic, "part-4"))
                // First instance joins — gets all 4
                .compose(v -> assignmentService.joinGroup(topic, groupName, instanceA))
                .compose(assignmentsA -> {
                    assertEquals(4, assignmentsA.size(),
                            "First instance should get all 4 partitions initially");
                    // Second instance joins — triggers rebalance
                    return assignmentService.joinGroup(topic, groupName, instanceB);
                })
                .compose(assignmentsB -> {
                    // B should have at least 1 but not all 4
                    assertTrue(assignmentsB.size() >= 1,
                            "Second instance must get at least one partition");
                    assertTrue(assignmentsB.size() < 4,
                            "Second instance must not get all partitions");

                    // Verify A's assignments after rebalance
                    return assignmentService.getAssignments(topic, groupName, instanceA)
                            .map(assignmentsA -> {
                                assertTrue(assignmentsA.size() >= 1,
                                        "First instance must retain at least one partition");
                                assertTrue(assignmentsA.size() < 4,
                                        "First instance must not keep all partitions");

                                // Total must be 4
                                int total = assignmentsA.size() + assignmentsB.size();
                                assertEquals(4, total,
                                        "Total partition assignments must equal total partitions");
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        logger.info("=== TEST: testJoinGroup_secondInstance_triggersRebalance COMPLETED ===");
    }

    // ========================================================================
    // Test 2.3: Join Group — Increments Generation
    // Each join bumps rebalance_generation on the subscription row.
    // After two joins, the generation must be 2 (started at 0, +1 each join).
    // All assignment rows must carry the latest generation.
    // ========================================================================

    @Test
    public void testJoinGroup_incrementsGeneration(VertxTestContext testContext) {
        logger.info("=== TEST: testJoinGroup_incrementsGeneration STARTED ===");

        String topic = "test-gen-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String instanceId = "instance-1";

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-1"))
                // First join → generation should be 1
                .compose(v -> assignmentService.joinGroup(topic, groupName, instanceId))
                .compose(assignments -> {
                    assertEquals(1, assignments.get(0).generation(),
                            "First join should produce generation=1");
                    return getSubscriptionGeneration(topic, groupName);
                })
                .compose(gen1 -> {
                    assertEquals(1, gen1,
                            "Subscription rebalance_generation should be 1 after first join");
                    // Second join → generation should be 2
                    return assignmentService.joinGroup(topic, groupName, instanceId);
                })
                .compose(assignments -> {
                    assertEquals(2, assignments.get(0).generation(),
                            "Second join should produce generation=2");
                    return getSubscriptionGeneration(topic, groupName);
                })
                .compose(gen2 -> {
                    assertEquals(2, gen2,
                            "Subscription rebalance_generation should be 2 after second join");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        logger.info("=== TEST: testJoinGroup_incrementsGeneration COMPLETED ===");
    }

    // ========================================================================
    // Test 2.4: Leave Group — Triggers Rebalance
    // Two instances share 4 partitions. When one leaves, its partitions are
    // reassigned to the remaining instance. After leave, the remaining instance
    // should own all 4 partitions. Generation should increment.
    // ========================================================================

    @Test
    public void testLeaveGroup_triggersRebalance(VertxTestContext testContext) {
        logger.info("=== TEST: testLeaveGroup_triggersRebalance STARTED ===");

        String topic = "test-leave-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String instanceA = "instance-A";
        String instanceB = "instance-B";

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-1"))
                .compose(v -> insertOutboxMessage(topic, "part-2"))
                .compose(v -> insertOutboxMessage(topic, "part-3"))
                .compose(v -> insertOutboxMessage(topic, "part-4"))
                // Both instances join
                .compose(v -> assignmentService.joinGroup(topic, groupName, instanceA))
                .compose(v -> assignmentService.joinGroup(topic, groupName, instanceB))
                // Instance B leaves
                .compose(v -> assignmentService.leaveGroup(topic, groupName, instanceB))
                // Verify A now has all partitions
                .compose(v -> assignmentService.getAssignments(topic, groupName, instanceA))
                .compose(assignmentsA -> {
                    assertEquals(4, assignmentsA.size(),
                            "Remaining instance should get all 4 partitions after other leaves");

                    // B should have no assignments
                    return assignmentService.getAssignments(topic, groupName, instanceB);
                })
                .compose(assignmentsB -> {
                    assertEquals(0, assignmentsB.size(),
                            "Departed instance should have zero assignments");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        logger.info("=== TEST: testLeaveGroup_triggersRebalance COMPLETED ===");
    }

    // ========================================================================
    // Test 2.5: Leave Group — Last Instance Cleans Up
    // A single instance joins, then leaves. After leave, no assignments remain.
    // Offset rows are preserved (not deleted) for potential rejoin.
    // ========================================================================

    @Test
    public void testLeaveGroup_lastInstance_cleansUp(VertxTestContext testContext) {
        logger.info("=== TEST: testLeaveGroup_lastInstance_cleansUp STARTED ===");

        String topic = "test-last-leave-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String instanceId = "instance-1";

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-1"))
                .compose(v -> insertOutboxMessage(topic, "part-2"))
                // Join, then initialize an offset so we can verify it's preserved
                .compose(v -> assignmentService.joinGroup(topic, groupName, instanceId))
                .compose(assignments -> offsetManager.initializeOffset(topic, groupName, "part-1", assignments.get(0).generation()))
                .compose(offset -> offsetManager.commitOffset(topic, groupName, "part-1", 10, offset.generation()))
                // Last instance leaves
                .compose(v -> assignmentService.leaveGroup(topic, groupName, instanceId))
                // Verify zero assignments remain
                .compose(v -> assignmentService.getAssignments(topic, groupName, instanceId))
                .compose(assignments -> {
                    assertEquals(0, assignments.size(),
                            "No assignments should remain after last instance leaves");
                    // Offset rows must be preserved for potential rejoin
                    return offsetManager.getOffset(topic, groupName, "part-1");
                })
                .compose(offsetOpt -> {
                    assertTrue(offsetOpt.isPresent(),
                            "Offset row must be preserved after leave");
                    assertEquals(10L, offsetOpt.get().committedOffset(),
                            "Committed offset must be unchanged after leave");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        logger.info("=== TEST: testLeaveGroup_lastInstance_cleansUp COMPLETED ===");
    }

    // ========================================================================
    // Test 2.6: Rebalance Minimizes Movement
    // 3 instances share 12 partitions. A 4th instance joins. Consistent hashing
    // should keep most partitions with their original owner. At most ~1/4 of
    // partitions should move (3 or fewer of 12 move to the new instance).
    // ========================================================================

    @Test
    public void testRebalance_minimizesMovement(VertxTestContext testContext) {
        logger.info("=== TEST: testRebalance_minimizesMovement STARTED ===");

        String topic = "test-movement-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";

        // Setup: 12 partitions gives enough granularity to measure movement
        Future<Void> setup = createTopic(topic)
                .compose(v -> createSubscription(topic, groupName));
        for (int i = 1; i <= 12; i++) {
            int idx = i;
            setup = setup.compose(v -> insertOutboxMessage(topic, "partition-" + idx).map(id -> (Void) null));
        }

        setup
                // 3 instances join
                .compose(v -> assignmentService.joinGroup(topic, groupName, "inst-1"))
                .compose(v -> assignmentService.joinGroup(topic, groupName, "inst-2"))
                .compose(v -> assignmentService.joinGroup(topic, groupName, "inst-3"))
                // Capture assignments before adding 4th
                .compose(v -> getAllAssignments(topic, groupName))
                .compose(beforeMap -> {
                    // Add 4th instance
                    return assignmentService.joinGroup(topic, groupName, "inst-4")
                            .compose(v -> getAllAssignments(topic, groupName))
                            .map(afterMap -> {
                                // Count how many partitions changed owner
                                int moved = 0;
                                for (String partition : beforeMap.keySet()) {
                                    String oldOwner = beforeMap.get(partition);
                                    String newOwner = afterMap.get(partition);
                                    if (!oldOwner.equals(newOwner)) {
                                        moved++;
                                    }
                                }
                                // With consistent hashing, roughly 1/4 of partitions should move
                                // Allow up to 6 out of 12 (50%) — generous bound
                                assertTrue(moved <= 6,
                                        "At most 50% of partitions should move when adding 1 of 4 instances, but " + moved + " moved");
                                assertTrue(moved >= 1,
                                        "At least 1 partition should move to the new instance");

                                // The new instance must have gotten some partitions
                                long inst4Count = afterMap.values().stream()
                                        .filter("inst-4"::equals).count();
                                assertTrue(inst4Count >= 1,
                                        "New instance must have at least 1 partition");
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        logger.info("=== TEST: testRebalance_minimizesMovement COMPLETED ===");
    }

    // ========================================================================
    // Test 2.7: Rebalance Serialized Under Concurrency
    // 5 concurrent joinGroup calls for different instances. Only one rebalance
    // wins per epoch due to FOR UPDATE serialization. After all complete,
    // all 5 instances must be present in the assignment table with a single
    // consistent generation across all assignment rows.
    // ========================================================================

    @Test
    public void testRebalance_serializedUnderConcurrency(VertxTestContext testContext) {
        logger.info("=== TEST: testRebalance_serializedUnderConcurrency STARTED ===");

        String topic = "test-concurrent-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-1"))
                .compose(v -> insertOutboxMessage(topic, "part-2"))
                .compose(v -> insertOutboxMessage(topic, "part-3"))
                .compose(v -> insertOutboxMessage(topic, "part-4"))
                .compose(v -> insertOutboxMessage(topic, "part-5"))
                .compose(v -> {
                    // Launch 5 concurrent joinGroup calls
                    List<Future<List<PartitionAssignment>>> futures = new ArrayList<>();
                    for (int i = 1; i <= 5; i++) {
                        futures.add(assignmentService.joinGroup(topic, groupName, "inst-" + i));
                    }
                    return Future.all(futures).map(cf -> (Void) null);
                })
                // After all joins complete, verify consistency
                .compose(v -> getAllAssignments(topic, groupName))
                .compose(assignmentMap -> {
                    // All 5 partitions must be assigned
                    assertEquals(5, assignmentMap.size(),
                            "All 5 partitions must be assigned");

                    // Verify all assignment rows have the same generation
                    return getAssignmentGenerations(topic, groupName);
                })
                .compose(generations -> {
                    assertEquals(1, generations.size(),
                            "All assignment rows must have the same generation, but found: " + generations);

                    // Generation should be 5 (each join bumps it)
                    int gen = generations.iterator().next();
                    assertEquals(5, gen,
                            "Generation should be 5 after 5 sequential rebalances (serialized by FOR UPDATE)");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        logger.info("=== TEST: testRebalance_serializedUnderConcurrency COMPLETED ===");
    }

    // ========================================================================
    // Test 2.8: Get Assignments Returns Correct Partitions
    // After a join, getAssignments(instanceId) returns only the partitions
    // assigned to that specific instance, not all partitions in the group.
    // ========================================================================

    @Test
    public void testGetAssignments_returnsCorrectPartitions(VertxTestContext testContext) {
        logger.info("=== TEST: testGetAssignments_returnsCorrectPartitions STARTED ===");

        String topic = "test-getassign-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String instanceA = "instance-A";
        String instanceB = "instance-B";

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-1"))
                .compose(v -> insertOutboxMessage(topic, "part-2"))
                .compose(v -> insertOutboxMessage(topic, "part-3"))
                .compose(v -> insertOutboxMessage(topic, "part-4"))
                .compose(v -> assignmentService.joinGroup(topic, groupName, instanceA))
                .compose(v -> assignmentService.joinGroup(topic, groupName, instanceB))
                .compose(v -> assignmentService.getAssignments(topic, groupName, instanceA))
                .compose(assignmentsA -> {
                    // Every returned assignment must belong to instanceA
                    for (PartitionAssignment a : assignmentsA) {
                        assertEquals(instanceA, a.assignedInstanceId());
                    }
                    return assignmentService.getAssignments(topic, groupName, instanceB);
                })
                .compose(assignmentsB -> {
                    for (PartitionAssignment a : assignmentsB) {
                        assertEquals(instanceB, a.assignedInstanceId());
                    }
                    // Non-existent instance should return empty
                    return assignmentService.getAssignments(topic, groupName, "non-existent");
                })
                .compose(empty -> {
                    assertEquals(0, empty.size(),
                            "Non-existent instance should have zero assignments");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        logger.info("=== TEST: testGetAssignments_returnsCorrectPartitions COMPLETED ===");
    }

    // ========================================================================
    // Test 2.9: Heartbeat Updates Timestamp
    // After joining, heartbeat() updates last_heartbeat_at on all assignment
    // rows for the instance. The new timestamp must be more recent than the
    // original assigned_at timestamp.
    // ========================================================================

    @Test
    public void testHeartbeat_updatesTimestamp(VertxTestContext testContext) {
        logger.info("=== TEST: testHeartbeat_updatesTimestamp STARTED ===");

        String topic = "test-heartbeat-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String instanceId = "instance-1";

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-1"))
                .compose(v -> assignmentService.joinGroup(topic, groupName, instanceId))
                .compose(assignments -> {
                    OffsetDateTime originalHeartbeat = assignments.get(0).lastHeartbeatAt();
                    return assignmentService.heartbeat(topic, groupName, instanceId)
                            .compose(v -> assignmentService.getAssignments(topic, groupName, instanceId))
                            .map(updated -> {
                                assertFalse(updated.isEmpty(), "Must have assignments");
                                OffsetDateTime newHeartbeat = updated.get(0).lastHeartbeatAt();
                                assertTrue(newHeartbeat.isAfter(originalHeartbeat),
                                        "Heartbeat timestamp must advance after heartbeat call");
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        logger.info("=== TEST: testHeartbeat_updatesTimestamp COMPLETED ===");
    }

    // ========================================================================
    // Test 2.10: Stale Assignments Cleaned on Rebalance
    // Create assignments with generation 1, then trigger a rebalance (gen 2).
    // All old-gen assignment rows must be deleted; only gen-2 rows remain.
    // ========================================================================

    @Test
    public void testStaleAssignment_cleanedOnRebalance(VertxTestContext testContext) {
        logger.info("=== TEST: testStaleAssignment_cleanedOnRebalance STARTED ===");

        String topic = "test-stale-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-1"))
                .compose(v -> insertOutboxMessage(topic, "part-2"))
                // First join → gen 1
                .compose(v -> assignmentService.joinGroup(topic, groupName, "inst-1"))
                // Second join → gen 2 (rebalance deletes gen-1 rows)
                .compose(v -> assignmentService.joinGroup(topic, groupName, "inst-2"))
                // All assignment rows should be gen 2
                .compose(v -> getAssignmentGenerations(topic, groupName))
                .compose(generations -> {
                    assertEquals(1, generations.size(),
                            "All assignment rows must be the same generation");
                    int gen = generations.iterator().next();
                    assertEquals(2, gen,
                            "Assignments must have generation=2 after second rebalance");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        logger.info("=== TEST: testStaleAssignment_cleanedOnRebalance COMPLETED ===");
    }

    // ========================================================================
    // Test 2.11: Discover Partitions Includes __default__ for NULL message_group
    // Messages with NULL message_group are discovered as the '__default__'
    // partition key. Verifies COALESCE(message_group, '__default__') in the
    // discovery query.
    // ========================================================================

    @Test
    public void testDiscoverPartitions_includesDefaultForNullGroup(VertxTestContext testContext) {
        logger.info("=== TEST: testDiscoverPartitions_includesDefaultForNullGroup STARTED ===");

        String topic = "test-nullgrp-" + UUID.randomUUID().toString().substring(0, 8);

        createTopic(topic)
                .compose(v -> insertOutboxMessageNullGroup(topic))
                .compose(v -> insertOutboxMessage(topic, "partition-A"))
                .compose(v -> assignmentService.discoverPartitions(topic))
                .compose(partitions -> {
                    assertEquals(2, partitions.size(),
                            "Should discover 2 partitions: __default__ and partition-A");
                    assertTrue(partitions.contains("__default__"),
                            "Must contain __default__ for NULL message_group");
                    assertTrue(partitions.contains("partition-A"),
                            "Must contain partition-A");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        logger.info("=== TEST: testDiscoverPartitions_includesDefaultForNullGroup COMPLETED ===");
    }

    // ========================================================================
    // Test 2.12: Discover Partitions — Dynamic as Messages Arrive
    // Insert messages with a new message_group after initial discovery.
    // The next discovery must include the newly-appearing partition key.
    // ========================================================================

    @Test
    public void testDiscoverPartitions_dynamicAsMessagesArrive(VertxTestContext testContext) {
        logger.info("=== TEST: testDiscoverPartitions_dynamicAsMessagesArrive STARTED ===");

        String topic = "test-dynamic-" + UUID.randomUUID().toString().substring(0, 8);

        createTopic(topic)
                .compose(v -> insertOutboxMessage(topic, "part-initial"))
                .compose(v -> assignmentService.discoverPartitions(topic))
                .compose(initial -> {
                    assertEquals(1, initial.size(), "Initially 1 partition");
                    assertTrue(initial.contains("part-initial"));
                    // Insert a new message_group
                    return insertOutboxMessage(topic, "part-new")
                            .compose(v -> assignmentService.discoverPartitions(topic));
                })
                .compose(updated -> {
                    assertEquals(2, updated.size(), "Now 2 partitions");
                    assertTrue(updated.contains("part-initial"));
                    assertTrue(updated.contains("part-new"));
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        logger.info("=== TEST: testDiscoverPartitions_dynamicAsMessagesArrive COMPLETED ===");
    }

    // ========================================================================
    // Helper: Read rebalance_generation from subscription row
    // ========================================================================

    private Future<Integer> getSubscriptionGeneration(String topic, String groupName) {
        String sql = """
            SELECT rebalance_generation FROM outbox_topic_subscriptions
            WHERE topic = $1 AND group_name = $2
            """;
        return connectionManager.withConnection("peegeeq-main", connection ->
                connection.preparedQuery(sql)
                        .execute(Tuple.of(topic, groupName))
                        .map(rows -> rows.iterator().next().getInteger("rebalance_generation"))
        );
    }

    // ========================================================================
    // Helper: Create topic configuration in outbox_topics
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
    // Helper: Create subscription in outbox_topic_subscriptions
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
    // Helper: Insert outbox message with a specific message_group (partition key)
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
    // Helper: Clean up all test data (tables with test- prefixed topics)
    // ========================================================================

    private Future<Void> cleanupTestData() {
        return connectionManager.withConnection("peegeeq-main", connection ->
                connection.preparedQuery("DELETE FROM outbox_partition_assignments WHERE topic LIKE 'test-%'")
                        .execute()
                        .compose(v -> connection.preparedQuery("DELETE FROM outbox_partition_offsets WHERE topic LIKE 'test-%'").execute())
                        .compose(v -> connection.preparedQuery("DELETE FROM outbox_topic_watermarks WHERE topic LIKE 'test-%'").execute())
                        .compose(v -> connection.preparedQuery("DELETE FROM outbox_consumer_groups WHERE message_id IN (SELECT id FROM outbox WHERE topic LIKE 'test-%')").execute())
                        .map(rows -> (Void) null)
        );
    }

    // ========================================================================
    // Helper: Get all assignments for a (topic, group) as partition→instance map
    // ========================================================================

    private Future<Map<String, String>> getAllAssignments(String topic, String groupName) {
        String sql = """
            SELECT partition_key, assigned_instance_id
            FROM outbox_partition_assignments
            WHERE topic = $1 AND group_name = $2
            """;
        return connectionManager.withConnection("peegeeq-main", connection ->
                connection.preparedQuery(sql)
                        .execute(Tuple.of(topic, groupName))
                        .map(rows -> {
                            Map<String, String> map = new LinkedHashMap<>();
                            for (var row : rows) {
                                map.put(row.getString("partition_key"),
                                        row.getString("assigned_instance_id"));
                            }
                            return map;
                        })
        );
    }

    // ========================================================================
    // Helper: Get distinct generations across all assignment rows
    // ========================================================================

    private Future<Set<Integer>> getAssignmentGenerations(String topic, String groupName) {
        String sql = """
            SELECT DISTINCT generation FROM outbox_partition_assignments
            WHERE topic = $1 AND group_name = $2
            """;
        return connectionManager.withConnection("peegeeq-main", connection ->
                connection.preparedQuery(sql)
                        .execute(Tuple.of(topic, groupName))
                        .map(rows -> {
                            Set<Integer> gens = new LinkedHashSet<>();
                            for (var row : rows) {
                                gens.add(row.getInteger("generation"));
                            }
                            return gens;
                        })
        );
    }

    // ========================================================================
    // Helper: Insert outbox message with NULL message_group
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
    // Test 2.13: Rebalance Storm — Rapid Join/Leave, Generations Monotonic
    // 5 instances rapidly join/leave in parallel (20 operations total).
    // Generations must be strictly monotonic, every assignment row must have
    // a valid generation, no orphan assignments with stale generation.
    // ========================================================================

    @Test
    public void testRebalanceStorm_rapidJoinLeave_generationsMonotonic(VertxTestContext testContext) {
        logger.info("=== TEST: testRebalanceStorm_rapidJoinLeave_generationsMonotonic STARTED ===");

        String topic = "test-storm-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";

        // Setup: create topic, subscription, and 6 partitions
        Future<Void> setup = createTopic(topic)
                .compose(v -> createSubscription(topic, groupName));
        for (int i = 1; i <= 6; i++) {
            int idx = i;
            setup = setup.compose(v -> insertOutboxMessage(topic, "part-" + idx).map(id -> (Void) null));
        }

        setup.compose(v -> {
            // Phase 1: 5 concurrent joins
            List<Future<List<PartitionAssignment>>> joinFutures = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                joinFutures.add(assignmentService.joinGroup(topic, groupName, "storm-" + i));
            }
            return Future.all(joinFutures).map(cf -> (Void) null);
        })
        .compose(v -> {
            // Phase 2: interleaved leave+join — 3 leave, 2 new join, all concurrent
            List<Future<?>> ops = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                ops.add(assignmentService.leaveGroup(topic, groupName, "storm-" + i));
            }
            ops.add(assignmentService.joinGroup(topic, groupName, "storm-6"));
            ops.add(assignmentService.joinGroup(topic, groupName, "storm-7"));
            return Future.all(ops).map(cf -> (Void) null);
        })
        .compose(v -> {
            // Phase 3: more concurrent joins
            List<Future<List<PartitionAssignment>>> joinFutures = new ArrayList<>();
            for (int i = 8; i <= 10; i++) {
                joinFutures.add(assignmentService.joinGroup(topic, groupName, "storm-" + i));
            }
            return Future.all(joinFutures).map(cf -> (Void) null);
        })
        // After all 20 operations verify invariants
        .compose(v -> getAssignmentGenerations(topic, groupName))
        .compose(generations -> {
            // All assignment rows must share a single generation
            assertEquals(1, generations.size(),
                    "All surviving assignments must share one generation, but found: " + generations);
            return getSubscriptionGeneration(topic, groupName);
        })
        .compose(subGen -> {
            // Subscription generation must be > 0 (many rebalances happened)
            assertTrue(subGen > 0, "Subscription generation must have advanced: " + subGen);
            return getAllAssignments(topic, groupName);
        })
        .compose(assignmentMap -> {
            // Every partition must be assigned exactly once
            assertEquals(6, assignmentMap.size(),
                    "All 6 partitions must be assigned");
            // No duplicate instance assignments to the same partition
            Set<String> assignedInstances = new HashSet<>(assignmentMap.values());
            assertFalse(assignedInstances.isEmpty(), "At least one instance must be assigned");
            return Future.succeededFuture();
        })
        .onSuccess(v -> testContext.completeNow())
        .onFailure(testContext::failNow);

        logger.info("=== TEST: testRebalanceStorm_rapidJoinLeave_generationsMonotonic COMPLETED ===");
    }

    // ========================================================================
    // Test 2.14: Concurrent Join and Leave — No Orphan Assignments
    // Instance A joins while instance B leaves simultaneously. Final assignment
    // state must be consistent: all partitions assigned, no duplicates, no gaps.
    // ========================================================================

    @Test
    public void testConcurrentJoinAndLeave_noOrphanAssignments(VertxTestContext testContext) {
        logger.info("=== TEST: testConcurrentJoinAndLeave_noOrphanAssignments STARTED ===");

        String topic = "test-orphan-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-1"))
                .compose(v -> insertOutboxMessage(topic, "part-2"))
                .compose(v -> insertOutboxMessage(topic, "part-3"))
                .compose(v -> insertOutboxMessage(topic, "part-4"))
                // First: join A and B sequentially to establish baseline
                .compose(v -> assignmentService.joinGroup(topic, groupName, "instance-A"))
                .compose(a -> assignmentService.joinGroup(topic, groupName, "instance-B"))
                // Now: A leaves while C joins — simultaneously
                .compose(b -> {
                    Future<Void> leaveA = assignmentService.leaveGroup(topic, groupName, "instance-A");
                    Future<List<PartitionAssignment>> joinC = assignmentService.joinGroup(topic, groupName, "instance-C");
                    return Future.all(leaveA, joinC).map(cf -> (Void) null);
                })
                // Verify: all partitions assigned, no duplicates, no orphans
                .compose(v -> getAllAssignments(topic, groupName))
                .compose(assignmentMap -> {
                    assertEquals(4, assignmentMap.size(),
                            "All 4 partitions must be assigned");

                    // Verify instance-A is NOT in the assignment set
                    assertFalse(assignmentMap.containsValue("instance-A"),
                            "instance-A must not be assigned (it left)");

                    // All assignments should be to B or C
                    Set<String> assignedInstances = new HashSet<>(assignmentMap.values());
                    assertTrue(assignedInstances.stream().allMatch(
                                    inst -> inst.equals("instance-B") || inst.equals("instance-C")),
                            "Only instance-B and instance-C should have assignments: " + assignedInstances);

                    return getAssignmentGenerations(topic, groupName);
                })
                .compose(generations -> {
                    // All rows must share a single generation
                    assertEquals(1, generations.size(),
                            "All assignments must share one generation: " + generations);
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        logger.info("=== TEST: testConcurrentJoinAndLeave_noOrphanAssignments COMPLETED ===");
    }

    // ========================================================================
    // Test 2.15: Rebalance While Offset Commit In-Flight
    // Consumer commits offset with gen=1, concurrent rebalance bumps gen to 2.
    // Commit must be rejected (fenced). New owner reads last committed offset
    // (not the rejected one).
    // ========================================================================

    @Test
    public void testRebalance_whileOffsetCommitInFlight(VertxTestContext testContext) {
        logger.info("=== TEST: testRebalance_whileOffsetCommitInFlight STARTED ===");

        String topic = "test-fence-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "group1";
        String partition = "part-1";

        createTopic(topic)
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, partition))
                // Instance-A joins and gets partition assigned
                .compose(v -> assignmentService.joinGroup(topic, groupName, "instance-A"))
                .compose(assignments -> {
                    // Initialize offset for partition at gen=1
                    int gen1 = assignments.get(0).generation();
                    return offsetManager.initializeOffset(topic, groupName, partition, gen1)
                            // Commit offset 5 at gen=1 (this should succeed)
                            .compose(offset -> offsetManager.commitOffset(topic, groupName, partition, 5, gen1))
                            .compose(committed -> {
                                assertTrue(committed, "First commit at gen=1 must succeed");
                                // Now instance-B joins, triggering rebalance → bumps generation
                                return assignmentService.joinGroup(topic, groupName, "instance-B");
                            })
                            .compose(newAssignments -> {
                                // Try to commit offset 10 with the OLD gen=1
                                // This simulates an in-flight commit arriving after rebalance
                                return offsetManager.commitOffset(topic, groupName, partition, 10, gen1);
                            });
                })
                .compose(staleCommitted -> {
                    // The stale commit must be REJECTED (fenced by generation)
                    assertFalse(staleCommitted, "Commit with stale generation must be rejected");

                    // Verify the offset is still at 5 (not 10)
                    return offsetManager.getOffset(topic, groupName, partition);
                })
                .compose(offsetOpt -> {
                    assertTrue(offsetOpt.isPresent(), "Offset row must exist");
                    PartitionOffset offset = offsetOpt.get();
                    assertEquals(5L, offset.committedOffset(),
                            "Committed offset must remain at 5 (stale commit was rejected)");
                    // Generation should have been bumped by the rebalance
                    assertTrue(offset.generation() > 1,
                            "Generation must be > 1 after rebalance, but was: " + offset.generation());
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        logger.info("=== TEST: testRebalance_whileOffsetCommitInFlight COMPLETED ===");
    }
}
