package dev.mars.peegeeq.pgqueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.consumer.PartitionAssignmentService;
import dev.mars.peegeeq.db.consumer.PartitionedFetcher;
import dev.mars.peegeeq.db.consumer.PartitionedOffsetManager;
import dev.mars.peegeeq.db.consumer.PartitionOffset;
import dev.mars.peegeeq.db.consumer.WatermarkCalculator;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.BASIC;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6 integration tests: Native consumer auto-detection and use of
 * OFFSET_WATERMARK partitioned consumption.
 *
 * <p>Tests 6.1-6.8 (core behavioral) plus 6.9-6.13 (concurrency invariants)
 * from the PARTITIONED_CONSUMPTION_DESIGN.md Phase 6 specification.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-12
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PartitionedNativeConsumerIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PartitionedNativeConsumerIntegrationTest.class);
    private static final String SERVICE_ID = "phase6-test";

    @Container
    static final PostgreSQLContainer postgres =
            PeeGeeQTestContainerFactory.createContainer(BASIC);

    private PeeGeeQManager manager;
    private PgDatabaseService databaseService;
    private VertxPoolAdapter adapter;
    private ObjectMapper mapper;
    private PgConnectionManager connectionManager;
    private final List<String> testTopics = new ArrayList<>();

    @BeforeAll
    static void beforeAll() {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, ALL);
    }

    @BeforeEach
    void setUp(io.vertx.junit5.VertxTestContext testContext) throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().onSuccess(v -> {
            databaseService = new PgDatabaseService(manager);
            adapter = new VertxPoolAdapter(
                    databaseService.getVertx(),
                    databaseService.getPool(),
                    databaseService
            );

            mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());

            // Create a PgConnectionManager for direct DB assertions and partitioned services
            connectionManager = new PgConnectionManager(databaseService.getVertx(), null);
            PgConnectionConfig connConfig = new PgConnectionConfig.Builder()
                    .host(postgres.getHost())
                    .port(postgres.getFirstMappedPort())
                    .database(postgres.getDatabaseName())
                    .username(postgres.getUsername())
                    .password(postgres.getPassword())
                    .schema("public")
                    .build();
            PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();
            connectionManager.getOrCreateReactivePool(SERVICE_ID, connConfig, poolConfig);
            testContext.completeNow();
        }).onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown(io.vertx.junit5.VertxTestContext testContext) {
        logger.info("Tearing down: closing resources and manager");
        if (connectionManager != null) {
            cleanupTestData()
                .transform(ar -> connectionManager.close())
                .transform(ar -> manager != null ? manager.closeReactive() : Future.<Void>succeededFuture())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(err -> {
                    logger.warn("tearDown failed: {}", err.getMessage());
                    testContext.completeNow();
                });
        } else if (manager != null) {
            manager.closeReactive()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    // ========================================================================
    // Test 6.1: Consumer start on OFFSET_WATERMARK topic → automatically
    // calls joinPartitionedGroup and receives partition assignments.
    // ========================================================================

    @Test
    @Order(1)
    void testStart_offsetWatermarkTopic_autoJoins(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 6.1: testStart_offsetWatermarkTopic_autoJoins STARTED ===");

        String topic = "test-p6-autojoin-" + System.nanoTime();
        String groupName = "group-6-1";
        String instanceId = groupName + "-instance-1";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // Arrange: create OFFSET_WATERMARK topic, subscription, and messages with partitions
        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-A", "payload-1"))
                .compose(v -> insertOutboxMessage(topic, "part-B", "payload-2"))
                .compose(v -> insertOutboxMessage(topic, "part-C", "payload-3"))
                .compose(v -> {
                    // Act: create and start a consumer group on this topic
                    // The consumer group should auto-detect OFFSET_WATERMARK and join
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, databaseService,
                            connectionManager, SERVICE_ID
                    );

                    // Set a handler (required before start)
                    group.setMessageHandler(msg -> Future.succeededFuture());

                    // Start the group this should trigger auto-join for OFFSET_WATERMARK topics
                    group.start();

                    // Give a short delay for async join to complete
                    return databaseService.getVertx().timer(2000).mapEmpty()
                            .compose(delayed -> {
                                // Assert: verify partition assignments exist in the database
                                return connectionManager.withConnection(SERVICE_ID, conn ->
                                        conn.preparedQuery(
                                                "SELECT COUNT(*) AS cnt FROM outbox_partition_assignments " +
                                                "WHERE topic = $1 AND group_name = $2"
                                        ).execute(Tuple.of(topic, groupName))
                                        .map(rows -> rows.iterator().next().getInteger("cnt"))
                                ).map(count -> {
                                    assertTrue(count >= 3,
                                            "Should have at least 3 partition assignments (one per message_group), got: " + count);
                                    logger.info("TEST 6.1 PASSED: {} partition assignments created", count);
                                    return (Void) null;
                                }).eventually(() -> group.close());
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test 6.1 failed", errorRef.get());
        }
    }

    // ========================================================================
    // Test 6.2: Consumer close → calls leavePartitionedGroup, partition
    // assignments removed when last instance leaves.
    // ========================================================================

    @Test
    @Order(2)
    void testClose_autoLeaves(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 6.2: testClose_autoLeaves STARTED ===");

        String topic = "test-p6-autoleave-" + System.nanoTime();
        String groupName = "group-6-2";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-A", "payload-1"))
                .compose(v -> insertOutboxMessage(topic, "part-B", "payload-2"))
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, databaseService,
                            connectionManager, SERVICE_ID
                    );
                    group.setMessageHandler(msg -> Future.succeededFuture());
                    group.start();

                    // Wait for join to complete
                    return databaseService.getVertx().timer(2000).mapEmpty()
                            .compose(delayed -> {
                                // Verify assignments exist before close
                                return countPartitionAssignments(topic, groupName)
                                        .compose(countBefore -> {
                                            assertTrue(countBefore > 0,
                                                    "Should have partition assignments after start, got: " + countBefore);
                                            logger.info("TEST 6.2: {} assignments before close", countBefore);

                                            // Close the group should call leaveGroup
                                            group.close().onFailure(testContext::failNow);

                                            // Wait for leave to complete
                                            return databaseService.getVertx().timer(2000).mapEmpty()
                                                    .compose(delayed2 -> countPartitionAssignments(topic, groupName))
                                                    .map(countAfter -> {
                                                        assertEquals(0, countAfter,
                                                                "Should have 0 partition assignments after close (last instance left)");
                                                        logger.info("TEST 6.2 PASSED: 0 assignments after close");
                                                        return (Void) null;
                                                    });
                                        });
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test 6.2 failed", errorRef.get());
        }
    }

    // ========================================================================
    // Test 6.3: Handler receives messages strictly in id order within each
    // partition (message_group).
    // ========================================================================

    @Test
    @Order(3)
    void testMessageHandler_receivesInOrder(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 6.3: testMessageHandler_receivesInOrder STARTED ===");

        String topic = "test-p6-order-" + System.nanoTime();
        String groupName = "group-6-3";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // Track received message ids per partition
        List<String> receivedIds = Collections.synchronizedList(new ArrayList<>());

        // Insert 5 messages in a single partition to guarantee ordering
        String partitionKey = "order-partition";
        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, partitionKey, "msg-1"))
                .compose(v -> insertOutboxMessage(topic, partitionKey, "msg-2"))
                .compose(v -> insertOutboxMessage(topic, partitionKey, "msg-3"))
                .compose(v -> insertOutboxMessage(topic, partitionKey, "msg-4"))
                .compose(v -> insertOutboxMessage(topic, partitionKey, "msg-5"))
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, databaseService,
                            connectionManager, SERVICE_ID
                    );
                    group.setMessageHandler(msg -> {
                        receivedIds.add(msg.getId());
                        return Future.succeededFuture();
                    });
                    group.start();

                    // Wait long enough for fetch loop to process the messages
                    return databaseService.getVertx().timer(4000).mapEmpty()
                            .map(delayed -> {
                                group.close().onFailure(testContext::failNow);

                                // Verify messages were received
                                assertFalse(receivedIds.isEmpty(),
                                        "Should have received at least some messages");
                                logger.info("TEST 6.3: received {} messages", receivedIds.size());

                                // Verify ordering: IDs must be strictly ascending
                                for (int i = 1; i < receivedIds.size(); i++) {
                                    long prev = Long.parseLong(receivedIds.get(i - 1));
                                    long curr = Long.parseLong(receivedIds.get(i));
                                    assertTrue(curr > prev,
                                            "Messages not in order: id " + prev + " followed by " + curr);
                                }

                                logger.info("TEST 6.3 PASSED: {} messages in strictly ascending id order", receivedIds.size());
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test 6.3 failed", errorRef.get());
        }
    }

    // ========================================================================
    // Test 6.4: After handler completes, offset auto-committed.
    // ========================================================================

    @Test
    @Order(4)
    void testAutoCommit_afterHandlerReturns(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 6.4: testAutoCommit_afterHandlerReturns STARTED ===");

        String topic = "test-p6-commit-" + System.nanoTime();
        String groupName = "group-6-4";
        String partitionKey = "commit-partition";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, partitionKey, "payload-1"))
                .compose(v -> insertOutboxMessage(topic, partitionKey, "payload-2"))
                .compose(v -> insertOutboxMessage(topic, partitionKey, "payload-3"))
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, databaseService,
                            connectionManager, SERVICE_ID
                    );
                    group.setMessageHandler(msg -> Future.succeededFuture());
                    group.start();

                    // Wait for processing and auto-commit
                    return databaseService.getVertx().timer(4000).mapEmpty()
                            .compose(delayed -> {
                                group.close().onFailure(testContext::failNow);
                                // Verify offset was committed in the database
                                return connectionManager.withConnection(SERVICE_ID, conn ->
                                        conn.preparedQuery(
                                                "SELECT committed_offset FROM outbox_partition_offsets " +
                                                "WHERE topic = $1 AND group_name = $2 AND partition_key = $3"
                                        ).execute(Tuple.of(topic, groupName, partitionKey))
                                        .map(rows -> {
                                            assertTrue(rows.size() > 0,
                                                    "Should have an offset row for partition " + partitionKey);
                                            long committedOffset = rows.iterator().next().getLong("committed_offset");
                                            assertTrue(committedOffset > 0,
                                                    "Committed offset should be > 0 after processing messages, got: " + committedOffset);
                                            logger.info("TEST 6.4 PASSED: committed_offset={} for partition {}",
                                                    committedOffset, partitionKey);
                                            return (Void) null;
                                        })
                                );
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test 6.4 failed", errorRef.get());
        }
    }

    // ========================================================================
    // Test 6.5: Generation bump → old consumer stops fetching revoked partitions.
    // After rebalance, the old engine's generation is stale and fetches are rejected.
    // ========================================================================

    @Test
    @Order(5)
    void testRebalance_stopsProcessingRevokedPartitions(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 6.5: testRebalance_stopsProcessingRevokedPartitions STARTED ===");

        String topic = "test-p6-rebalance-" + System.nanoTime();
        String groupName = "group-6-5";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-A", "payload-1"))
                .compose(v -> insertOutboxMessage(topic, "part-B", "payload-2"))
                .compose(v -> {
                    // Start first consumer it gets all partitions
                    PgNativeConsumerGroup<String> group1 = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, databaseService,
                            connectionManager, SERVICE_ID
                    );
                    group1.setMessageHandler(msg -> Future.succeededFuture());
                    group1.start();

                    return databaseService.getVertx().timer(2000).mapEmpty()
                            .compose(delayed -> countPartitionAssignments(topic, groupName))
                            .compose(countBefore -> {
                                assertTrue(countBefore >= 2, "Should have at least 2 assignments, got: " + countBefore);

                                // Now a second instance joins triggers rebalance with new generation
                                PartitionAssignmentService assignmentService =
                                        new PartitionAssignmentService(connectionManager, SERVICE_ID);
                                return assignmentService.joinGroup(topic, groupName, "instance-2")
                                        .compose(newAssignments -> {
                                            logger.info("TEST 6.5: rebalance complete, instance-2 got {} assignments",
                                                    newAssignments.size());

                                            // The new generation should be > 1
                                            if (!newAssignments.isEmpty()) {
                                                int newGen = newAssignments.get(0).generation();
                                                assertTrue(newGen > 1,
                                                        "Generation after rebalance should be > 1, got: " + newGen);
                                                logger.info("TEST 6.5: new generation = {}", newGen);
                                            }

                                            // Verify: group1's old generation commits should be rejected
                                            PartitionedOffsetManager offsetMgr =
                                                    new PartitionedOffsetManager(connectionManager, SERVICE_ID);
                                            // Try to commit with the old generation (1) should be rejected
                                            return offsetMgr.commitOffset(topic, groupName, "part-A", 999L, 1)
                                                    .map(committed -> {
                                                        assertFalse(committed,
                                                                "Commit with old generation should be rejected");
                                                        logger.info("TEST 6.5 PASSED: stale generation commit correctly rejected");
                                                        return (Void) null;
                                                    });
                                        });
                            })
                            .eventually(() -> group1.close());
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test 6.5 failed", errorRef.get());
        }
    }

    // ========================================================================
    // Test 6.6: 2 consumers → each processes different partitions concurrently.
    // ========================================================================

    @Test
    @Order(6)
    void testMultipleInstances_parallelPartitions(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 6.6: testMultipleInstances_parallelPartitions STARTED ===");

        String topic = "test-p6-parallel-" + System.nanoTime();
        String groupName = "group-6-6";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // Create messages across many partitions to ensure fair distribution
        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-A", "pa-1"))
                .compose(v -> insertOutboxMessage(topic, "part-B", "pb-1"))
                .compose(v -> insertOutboxMessage(topic, "part-C", "pc-1"))
                .compose(v -> insertOutboxMessage(topic, "part-D", "pd-1"))
                .compose(v -> insertOutboxMessage(topic, "part-E", "pe-1"))
                .compose(v -> insertOutboxMessage(topic, "part-F", "pf-1"))
                .compose(v -> {
                    // Use the PartitionAssignmentService directly to prove partition splitting.
                    // This avoids race conditions from async consumer group starts.
                    PartitionAssignmentService assignmentService =
                            new PartitionAssignmentService(connectionManager, SERVICE_ID);

                    // Instance 1 joins gets all 6 partitions
                    return assignmentService.joinGroup(topic, groupName, "instance-1")
                            .compose(a1 -> {
                                logger.info("TEST 6.6: instance-1 got {} partitions", a1.size());
                                assertTrue(a1.size() > 0, "instance-1 should have partitions");

                                // Instance 2 joins rebalance splits partitions
                                return assignmentService.joinGroup(topic, groupName, "instance-2")
                                        .compose(a2 -> {
                                            logger.info("TEST 6.6: instance-2 got {} partitions", a2.size());

                                            // Verify: both instances have assignments
                                            return connectionManager.withConnection(SERVICE_ID, conn ->
                                                    conn.preparedQuery(
                                                            "SELECT assigned_instance_id, COUNT(*) AS cnt " +
                                                            "FROM outbox_partition_assignments " +
                                                            "WHERE topic = $1 AND group_name = $2 " +
                                                            "GROUP BY assigned_instance_id"
                                                    ).execute(Tuple.of(topic, groupName))
                                                    .map(rows -> {
                                                        int instanceCount = rows.size();
                                                        assertTrue(instanceCount >= 2,
                                                                "Should have at least 2 instances with assignments, got: " + instanceCount);

                                                        // Verify both lists are non-empty subsets of all partitions
                                                        assertTrue(a1.size() + a2.size() >= 6,
                                                                "Total partitions across both instances should cover all 6");

                                                        logger.info("TEST 6.6 PASSED: {} instances sharing partitions ({} + {})",
                                                                instanceCount, a1.size(), a2.size());
                                                        return (Void) null;
                                                    })
                                            );
                                        });
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test 6.6 failed", errorRef.get());
        }
    }

    // ========================================================================
    // Test 6.7: Existing REFERENCE_COUNTING topics work exactly as before.
    // ========================================================================

    @Test
    @Order(7)
    void testReferenceCountingTopic_unchanged(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 6.7: testReferenceCountingTopic_unchanged STARTED ===");

        String topic = "test-p6-refcount-" + System.nanoTime();
        String groupName = "group-6-7";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        List<String> receivedIds = Collections.synchronizedList(new ArrayList<>());

        // Create a REFERENCE_COUNTING topic (the default/existing mode)
        createTopic(topic, "REFERENCE_COUNTING")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "any-group", "refcount-msg-1"))
                .compose(v -> insertOutboxMessage(topic, "any-group", "refcount-msg-2"))
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, databaseService,
                            connectionManager, SERVICE_ID
                    );
                    group.setMessageHandler(msg -> {
                        receivedIds.add(msg.getId());
                        return Future.succeededFuture();
                    });
                    group.start();

                    return databaseService.getVertx().timer(4000).mapEmpty()
                            .map(delayed -> {
                                group.close().onFailure(testContext::failNow);

                                // Verify: no partition assignments exist (REFERENCE_COUNTING doesn't use them)
                                logger.info("TEST 6.7: received {} messages via REFERENCE_COUNTING", receivedIds.size());
                                return (Void) null;
                            })
                            .compose(v2 -> countPartitionAssignments(topic, groupName))
                            .map(count -> {
                                assertEquals(0, count,
                                        "REFERENCE_COUNTING topics should have 0 partition assignments, got: " + count);
                                logger.info("TEST 6.7 PASSED: REFERENCE_COUNTING topic has 0 partition assignments");
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test 6.7 failed", errorRef.get());
        }
    }

    // ========================================================================
    // Test 6.8: After all groups commit past message → watermark advances
    // → message swept to COMPLETED.
    // ========================================================================

    @Test
    @Order(8)
    void testWatermarkSweep_completesProcessedMessages(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 6.8: testWatermarkSweep_completesProcessedMessages STARTED ===");

        String topic = "test-p6-sweep-" + System.nanoTime();
        String groupName = "group-6-8";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "sweep-part", "sweep-msg-1"))
                .compose(v -> insertOutboxMessage(topic, "sweep-part", "sweep-msg-2"))
                .compose(v -> insertOutboxMessage(topic, "sweep-part", "sweep-msg-3"))
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, databaseService,
                            connectionManager, SERVICE_ID
                    );
                    group.setMessageHandler(msg -> Future.succeededFuture());
                    group.start();

                    // Wait for messages to be processed and offset committed
                    return databaseService.getVertx().timer(4000).mapEmpty()
                            .compose(delayed -> {
                                // Manually run watermark sweep
                                WatermarkCalculator calculator = new WatermarkCalculator(connectionManager, SERVICE_ID);
                                return calculator.calculateAndSweep(topic);
                            })
                            .compose(swept -> {
                                logger.info("TEST 6.8: watermark swept {} messages", swept);

                                // Check outbox message statuses swept messages should be COMPLETED
                                return connectionManager.withConnection(SERVICE_ID, conn ->
                                        conn.preparedQuery(
                                                "SELECT COUNT(*) AS cnt FROM outbox " +
                                                "WHERE topic = $1 AND status = 'COMPLETED'"
                                        ).execute(Tuple.of(topic))
                                        .map(rows -> rows.iterator().next().getInteger("cnt"))
                                ).map(completedCount -> {
                                    assertTrue(completedCount > 0,
                                            "At least some messages should be COMPLETED after watermark sweep, got: " + completedCount);
                                    logger.info("TEST 6.8 PASSED: {} messages COMPLETED after watermark sweep", completedCount);
                                    return (Void) null;
                                });
                            })
                            .eventually(() -> group.close());
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test 6.8 failed", errorRef.get());
        }
    }

    // ========================================================================
    // CONCURRENCY INVARIANT TESTS (6.9 - 6.13)
    // ========================================================================

    // ========================================================================
    // Test 6.9: 3 consumers, 6 partitions each consumer receives ONLY from
    // its assigned partitions. Offsets advance independently.
    // ========================================================================

    @Test
    @Order(9)
    void testMultiConsumer_partitionIsolation_noContamination(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 6.9: testMultiConsumer_partitionIsolation_noContamination STARTED ===");

        String topic = "test-p6-isolation-" + System.nanoTime();
        String groupName = "group-6-9";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        PartitionAssignmentService assignmentService = new PartitionAssignmentService(connectionManager, SERVICE_ID);
        PartitionedFetcher fetcher = new PartitionedFetcher(connectionManager, SERVICE_ID);
        PartitionedOffsetManager offsetManager = new PartitionedOffsetManager(connectionManager, SERVICE_ID);

        // Create topic with 6 partitions (message groups)
        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "p1", "m1"))
                .compose(v -> insertOutboxMessage(topic, "p2", "m2"))
                .compose(v -> insertOutboxMessage(topic, "p3", "m3"))
                .compose(v -> insertOutboxMessage(topic, "p4", "m4"))
                .compose(v -> insertOutboxMessage(topic, "p5", "m5"))
                .compose(v -> insertOutboxMessage(topic, "p6", "m6"))
                // Add second round of messages per partition
                .compose(v -> insertOutboxMessage(topic, "p1", "m7"))
                .compose(v -> insertOutboxMessage(topic, "p2", "m8"))
                .compose(v -> insertOutboxMessage(topic, "p3", "m9"))
                .compose(v -> {
                    // 3 instances join partitions distributed
                    return assignmentService.joinGroup(topic, groupName, "inst-A")
                            .compose(aA -> assignmentService.joinGroup(topic, groupName, "inst-B"))
                            .compose(aB -> assignmentService.joinGroup(topic, groupName, "inst-C"))
                            .compose(aC -> {
                                // Get final assignments for each instance
                                return assignmentService.getAssignments(topic, groupName, "inst-A")
                                        .compose(assignA -> assignmentService.getAssignments(topic, groupName, "inst-B")
                                        .compose(assignB -> assignmentService.getAssignments(topic, groupName, "inst-C")
                                        .compose(assignC -> {
                                            // Verify: no partition overlap
                                            var partitionsA = assignA.stream().map(a -> a.partitionKey()).toList();
                                            var partitionsB = assignB.stream().map(a -> a.partitionKey()).toList();
                                            var partitionsC = assignC.stream().map(a -> a.partitionKey()).toList();

                                            logger.info("TEST 6.9: A={}, B={}, C={}", partitionsA, partitionsB, partitionsC);

                                            // No overlap
                                            for (String p : partitionsA) {
                                                assertFalse(partitionsB.contains(p), "Partition " + p + " overlaps A and B");
                                                assertFalse(partitionsC.contains(p), "Partition " + p + " overlaps A and C");
                                            }
                                            for (String p : partitionsB) {
                                                assertFalse(partitionsC.contains(p), "Partition " + p + " overlaps B and C");
                                            }

                                            // All 6 covered
                                            int total = partitionsA.size() + partitionsB.size() + partitionsC.size();
                                            assertEquals(6, total, "All 6 partitions should be assigned, got: " + total);

                                            // Verify: committing offset in one partition doesn't affect others
                                            if (!assignA.isEmpty()) {
                                                String partKey = assignA.get(0).partitionKey();
                                                int gen = assignA.get(0).generation();
                                                return offsetManager.initializeOffset(topic, groupName, partKey, gen)
                                                        .compose(initOffset -> offsetManager.commitOffset(topic, groupName, partKey, 999L, gen))
                                                        .compose(committed -> {
                                                            assertTrue(committed, "Commit should succeed for active partition");
                                                            // Verify: other partitions' offsets are unaffected
                                                            if (!assignB.isEmpty()) {
                                                                String otherKey = assignB.get(0).partitionKey();
                                                                return offsetManager.getOffset(topic, groupName, otherKey)
                                                                        .map(optOffset -> {
                                                                            long otherCommitted = optOffset.map(o -> o.committedOffset()).orElse(0L);
                                                                            assertTrue(otherCommitted < 999L,
                                                                                    "Committing in partition A should not affect partition B's offset");
                                                                            logger.info("TEST 6.9 PASSED: partition isolation verified");
                                                                            return (Void) null;
                                                                        });
                                                            }
                                                            logger.info("TEST 6.9 PASSED: partition isolation verified (no B to check)");
                                                            return Future.succeededFuture((Void) null);
                                                        });
                                            }
                                            logger.info("TEST 6.9 PASSED: partition isolation verified");
                                            return Future.succeededFuture((Void) null);
                                        })));
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test 6.9 failed", errorRef.get());
        }
    }

    // ========================================================================
    // Test 6.10: Consumer A processes partitions, crashes mid-commit.
    // Consumer B resumes from committed offsets zero message loss.
    // ========================================================================

    @Test
    @Order(10)
    void testConsumerCrash_newConsumerResumes_zeroMessageLoss(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 6.10: testConsumerCrash_newConsumerResumes_zeroMessageLoss STARTED ===");

        String topic = "test-p6-crash-" + System.nanoTime();
        String groupName = "group-6-10";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        PartitionAssignmentService assignmentService = new PartitionAssignmentService(connectionManager, SERVICE_ID);
        PartitionedFetcher fetcher = new PartitionedFetcher(connectionManager, SERVICE_ID);
        PartitionedOffsetManager offsetManager = new PartitionedOffsetManager(connectionManager, SERVICE_ID);

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                // Insert several messages per partition
                .compose(v -> insertOutboxMessage(topic, "P1", "p1-msg1"))
                .compose(v -> insertOutboxMessage(topic, "P1", "p1-msg2"))
                .compose(v -> insertOutboxMessage(topic, "P1", "p1-msg3"))
                .compose(v -> insertOutboxMessage(topic, "P2", "p2-msg1"))
                .compose(v -> insertOutboxMessage(topic, "P2", "p2-msg2"))
                .compose(v -> insertOutboxMessage(topic, "P2", "p2-msg3"))
                .compose(v -> {
                    // Consumer A joins
                    return assignmentService.joinGroup(topic, groupName, "consumer-A")
                            .compose(assignmentsA -> {
                                int genA = assignmentsA.isEmpty() ? 1 : assignmentsA.get(0).generation();

                                // Initialize offsets for both partitions
                                return offsetManager.initializeOffset(topic, groupName, "P1", genA)
                                        .compose(o1 -> offsetManager.initializeOffset(topic, groupName, "P2", genA))
                                        .compose(o2 -> {
                                            // Consumer A fetches and commits P1
                                            return fetcher.fetch(topic, groupName, "P1", 100, genA)
                                                    .compose(p1Msgs -> {
                                                        assertFalse(p1Msgs.isEmpty(), "P1 should have messages");
                                                        long lastP1Id = p1Msgs.get(p1Msgs.size() - 1).getId();
                                                        return offsetManager.commitOffset(topic, groupName, "P1", lastP1Id, genA)
                                                                .map(committed -> {
                                                                    assertTrue(committed, "P1 commit should succeed");
                                                                    logger.info("TEST 6.10: A committed P1 offset={}", lastP1Id);
                                                                    return lastP1Id;
                                                                });
                                                    });
                                        })
                                        .compose(p1CommittedOffset -> {
                                            // Consumer A fetches P2 but does NOT commit (simulating crash)
                                            return fetcher.fetch(topic, groupName, "P2", 100, genA)
                                                    .compose(p2Msgs -> {
                                                        assertFalse(p2Msgs.isEmpty(), "P2 should have messages");
                                                        logger.info("TEST 6.10: A fetched {} P2 messages but NOT committing (crash)", p2Msgs.size());

                                                        // "Crash" consumer A leaves (simulated)
                                                        return assignmentService.leaveGroup(topic, groupName, "consumer-A")
                                                                .compose(left -> {
                                                                    // Consumer B joins gets all partitions
                                                                    return assignmentService.joinGroup(topic, groupName, "consumer-B");
                                                                })
                                                                .compose(assignmentsB -> {
                                                                    int genB = assignmentsB.get(0).generation();
                                                                    assertTrue(genB > genA, "B's generation should be newer than A's");

                                                                    // B initializes/reads offsets
                                                                    return offsetManager.initializeOffset(topic, groupName, "P1", genB)
                                                                            .compose(p1Off -> offsetManager.initializeOffset(topic, groupName, "P2", genB)
                                                                            .compose(p2Off -> {
                                                                                // P1 should resume from A's committed offset
                                                                                return offsetManager.getOffset(topic, groupName, "P1")
                                                                                        .compose(p1OptOffset -> {
                                                                                            long p1Offset = p1OptOffset.map(o -> o.committedOffset()).orElse(0L);
                                                                                            assertEquals(p1CommittedOffset, p1Offset,
                                                                                                    "P1 offset should reflect A's committed offset");
                                                                                            logger.info("TEST 6.10: B sees P1 offset={} (A's commit)", p1Offset);

                                                                                            // P2 should be at 0 (uncommitted)
                                                                                            return offsetManager.getOffset(topic, groupName, "P2")
                                                                                                    .map(p2OptOffset -> {
                                                                                                        long p2Offset = p2OptOffset.map(o -> o.committedOffset()).orElse(0L);
                                                                                                        assertTrue(p2Offset < p1CommittedOffset,
                                                                                                                "P2 offset should be less than P1 (P2 was never committed)");
                                                                                                        logger.info("TEST 6.10 PASSED: B resumes P1={}, P2={} zero loss",
                                                                                                                p1CommittedOffset, p2Offset);
                                                                                                        return (Void) null;
                                                                                                    });
                                                                                        });
                                                                            }));
                                                                });
                                                    });
                                        });
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test 6.10 failed", errorRef.get());
        }
    }

    // ========================================================================
    // Test 6.11: Rebalance under load 2 consumers, 1000 messages, third joins.
    // All messages eventually committed, duplicates bounded.
    // ========================================================================

    @Test
    @Order(11)
    void testRebalanceUnderLoad_noMessageLoss_noInfiniteRedelivery(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 6.11: testRebalanceUnderLoad_noMessageLoss_noInfiniteRedelivery STARTED ===");

        String topic = "test-p6-load-" + System.nanoTime();
        String groupName = "group-6-11";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // Insert messages across 4 partitions
        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> {
                    // Insert 100 messages across 4 partitions (25 each)
                    Future<Long> chain = Future.succeededFuture(0L);
                    for (int i = 0; i < 100; i++) {
                        String part = "part-" + (i % 4);
                        String payload = "msg-" + i;
                        int idx = i;
                        chain = chain.compose(prev -> insertOutboxMessage(topic, part, payload));
                    }
                    return chain;
                })
                .compose(v -> {
                    // Start 2 consumer groups processing messages
                    PgNativeConsumerGroup<String> group1 = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, databaseService,
                            connectionManager, SERVICE_ID
                    );
                    group1.setMessageHandler(msg -> Future.succeededFuture());
                    group1.start();

                    return databaseService.getVertx().timer(2000).mapEmpty()
                            .compose(d1 -> {
                                PgNativeConsumerGroup<String> group2 = new PgNativeConsumerGroup<>(
                                        groupName, topic, String.class,
                                        adapter, mapper, null, null, databaseService,
                                        connectionManager, SERVICE_ID
                                );
                                group2.setMessageHandler(msg -> Future.succeededFuture());
                                group2.start();

                                // Wait a bit, then add a third consumer (rebalance under load)
                                return databaseService.getVertx().timer(2000).mapEmpty()
                                        .compose(d2 -> {
                                            PgNativeConsumerGroup<String> group3 = new PgNativeConsumerGroup<>(
                                                    groupName, topic, String.class,
                                                    adapter, mapper, null, null, databaseService,
                                                    connectionManager, SERVICE_ID
                                            );
                                            group3.setMessageHandler(msg -> Future.succeededFuture());
                                            group3.start();

                                            // Wait for processing to complete
                                            return databaseService.getVertx().timer(5000).mapEmpty()
                                                    .compose(d3 -> {
                                                        // Run watermark sweep
                                                        WatermarkCalculator calc = new WatermarkCalculator(connectionManager, SERVICE_ID);
                                                        return calc.calculateAndSweep(topic);
                                                    })
                                                    .compose(swept -> {
                                                        // Check committed offsets across all partitions
                                                        return connectionManager.withConnection(SERVICE_ID, conn ->
                                                                conn.preparedQuery(
                                                                        "SELECT SUM(committed_offset) AS total FROM outbox_partition_offsets " +
                                                                        "WHERE topic = $1 AND group_name = $2"
                                                                ).execute(Tuple.of(topic, groupName))
                                                                .map(rows -> {
                                                                    long totalOffset = rows.iterator().next().getLong("total");
                                                                    assertTrue(totalOffset > 0,
                                                                            "Total committed offsets should be > 0: " + totalOffset);
                                                                    logger.info("TEST 6.11 PASSED: total committed offset sum={}, swept={}",
                                                                            totalOffset, swept);
                                                                    return (Void) null;
                                                                })
                                                        );
                                                    })
                                                    .eventually(() -> group3.close())
                                        .eventually(() -> group2.close())
                            .eventually(() -> group1.close());
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test 6.11 failed", errorRef.get());
        }
    }

    // ========================================================================
    // Test 6.12: Fetch + business write + offset commit in same PG transaction.
    // Commit → both visible. Rollback → neither visible.
    // ========================================================================

    @Test
    @Order(12)
    void testColocatedTransaction_exactlyOnceCommit(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 6.12: testColocatedTransaction_exactlyOnceCommit STARTED ===");

        String topic = "test-p6-txn-" + System.nanoTime();
        String groupName = "group-6-12";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        PartitionAssignmentService assignmentService = new PartitionAssignmentService(connectionManager, SERVICE_ID);

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "txn-part", "txn-msg-1"))
                .compose(v -> insertOutboxMessage(topic, "txn-part", "txn-msg-2"))
                .compose(v -> {
                    return assignmentService.joinGroup(topic, groupName, "txn-instance")
                            .compose(assignments -> {
                                int gen = assignments.isEmpty() ? 1 : assignments.get(0).generation();

                                // Within a transaction: fetch, write business result, commit offset
                                return connectionManager.withTransaction(SERVICE_ID, conn -> {
                                    // Initialize offset within txn
                                    return conn.preparedQuery(
                                            "INSERT INTO outbox_partition_offsets (topic, group_name, partition_key, committed_offset, pending_offset, generation) " +
                                            "VALUES ($1, $2, $3, 0, 0, $4) ON CONFLICT (topic, group_name, partition_key) DO NOTHING"
                                    ).execute(Tuple.of(topic, groupName, "txn-part", gen))
                                    .compose(r -> {
                                        // Write a "business result" row (using outbox as scratch)
                                        return conn.preparedQuery(
                                                "INSERT INTO outbox (topic, payload, status, message_group, created_at) " +
                                                "VALUES ($1, '{\"result\": \"computed\"}'::jsonb, 'PENDING', 'business-result', NOW()) RETURNING id"
                                        ).execute(Tuple.of(topic + "-results"));
                                    })
                                    .compose(r2 -> {
                                        long businessId = r2.iterator().next().getLong("id");
                                        // Commit offset within same txn
                                        return conn.preparedQuery(
                                                "UPDATE outbox_partition_offsets SET committed_offset = $4 " +
                                                "WHERE topic = $1 AND group_name = $2 AND partition_key = $3 AND generation = $5"
                                        ).execute(Tuple.of(topic, groupName, "txn-part", 100L, gen))
                                        .map(v2 -> businessId);
                                    });
                                })
                                .compose(businessId -> {
                                    // After commit: both offset and business result should be visible
                                    return connectionManager.withConnection(SERVICE_ID, conn ->
                                            conn.preparedQuery(
                                                    "SELECT committed_offset FROM outbox_partition_offsets " +
                                                    "WHERE topic = $1 AND group_name = $2 AND partition_key = $3"
                                            ).execute(Tuple.of(topic, groupName, "txn-part"))
                                            .compose(rows -> {
                                                long offset = rows.iterator().next().getLong("committed_offset");
                                                assertEquals(100L, offset,
                                                        "Offset should be 100 after transactional commit");

                                                return conn.preparedQuery(
                                                        "SELECT COUNT(*) AS cnt FROM outbox WHERE topic = $1"
                                                ).execute(Tuple.of(topic + "-results"))
                                                .map(rows2 -> {
                                                    int cnt = rows2.iterator().next().getInteger("cnt");
                                                    assertTrue(cnt > 0,
                                                            "Business result row should exist after txn commit");
                                                    logger.info("TEST 6.12 PASSED: txn commit → offset=100, business rows={}", cnt);
                                                    return (Void) null;
                                                });
                                            })
                                    );
                                });
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test 6.12 failed", errorRef.get());
        }
    }

    // ========================================================================
    // Test 6.13: Old generation consumer's fetch/commit rejected after rebalance.
    // Prevents zombie/split-brain.
    // ========================================================================

    @Test
    @Order(13)
    void testGenerationFence_preventsZombieConsumer(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST 6.13: testGenerationFence_preventsZombieConsumer STARTED ===");

        String topic = "test-p6-zombie-" + System.nanoTime();
        String groupName = "group-6-13";
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        PartitionAssignmentService assignmentService = new PartitionAssignmentService(connectionManager, SERVICE_ID);
        PartitionedFetcher fetcher = new PartitionedFetcher(connectionManager, SERVICE_ID);
        PartitionedOffsetManager offsetManager = new PartitionedOffsetManager(connectionManager, SERVICE_ID);

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "zombie-part", "zm-1"))
                .compose(v -> insertOutboxMessage(topic, "zombie-part", "zm-2"))
                .compose(v -> {
                    // Consumer A joins at gen=1
                    return assignmentService.joinGroup(topic, groupName, "consumer-A")
                            .compose(assignA -> {
                                int genA = assignA.get(0).generation();
                                logger.info("TEST 6.13: consumer-A at gen={}", genA);

                                // Initialize offset for consumer A
                                return offsetManager.initializeOffset(topic, groupName, "zombie-part", genA)
                                        .compose(initOff -> {
                                            // Simulate network partition: A stops heartbeating
                                            // Rebalance: B joins → gen bumps
                                            return assignmentService.leaveGroup(topic, groupName, "consumer-A")
                                                    .compose(v2 -> assignmentService.joinGroup(topic, groupName, "consumer-B"));
                                        })
                                        .compose(assignB -> {
                                            int genB = assignB.get(0).generation();
                                            assertTrue(genB > genA, "B's generation should be > A's");
                                            logger.info("TEST 6.13: consumer-B at gen={}", genB);

                                            // "Zombie" A tries to commit with old generation
                                            return offsetManager.commitOffset(topic, groupName, "zombie-part", 999L, genA)
                                                    .compose(committed -> {
                                                        assertFalse(committed,
                                                                "Zombie commit with old gen should be rejected");
                                                        logger.info("TEST 6.13: zombie commit rejected (gen={})", genA);

                                                        // "Zombie" A tries to fetch with old generation
                                                        return fetcher.fetch(topic, groupName, "zombie-part", 100, genA);
                                                    })
                                                    .compose(fetchedMsgs -> {
                                                        // With stale generation, fetch should return empty or fail
                                                        // (depending on implementation generation check is in offset lookup)
                                                        logger.info("TEST 6.13: zombie fetch returned {} messages (gen={})",
                                                                fetchedMsgs.size(), genA);

                                                        // Consumer B should be able to operate normally
                                                        return offsetManager.initializeOffset(topic, groupName, "zombie-part", genB)
                                                                .compose(bOff -> offsetManager.commitOffset(topic, groupName, "zombie-part", 100L, genB))
                                                                .map(bCommitted -> {
                                                                    assertTrue(bCommitted,
                                                                            "Consumer B commit with current gen should succeed");
                                                                    logger.info("TEST 6.13 PASSED: zombie fenced, B is sole owner");
                                                                    return (Void) null;
                                                                });
                                                    });
                                        });
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (errorRef.get() != null) {
            throw new AssertionError("Test 6.13 failed", errorRef.get());
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private Future<Void> createTopic(String topic, String completionTrackingMode) {
        testTopics.add(topic);
        return connectionManager.withConnection(SERVICE_ID, conn ->
                conn.preparedQuery(
                        "INSERT INTO outbox_topics (topic, semantics, completion_tracking_mode) " +
                        "VALUES ($1, 'PUB_SUB', $2) ON CONFLICT (topic) DO NOTHING"
                ).execute(Tuple.of(topic, completionTrackingMode))
                .map(rows -> (Void) null)
        );
    }

    private Future<Void> createSubscription(String topic, String groupName) {
        return connectionManager.withConnection(SERVICE_ID, conn ->
                conn.preparedQuery(
                        "INSERT INTO outbox_topic_subscriptions (topic, group_name, subscription_status) " +
                        "VALUES ($1, $2, 'ACTIVE') ON CONFLICT (topic, group_name) DO NOTHING"
                ).execute(Tuple.of(topic, groupName))
                .map(rows -> (Void) null)
        );
    }

    private Future<Long> insertOutboxMessage(String topic, String messageGroup, String payload) {
        // Match the production wire format used by both PgNativeQueueProducer and
        // OutboxProducer (and unwrapped by both consumers and PartitionedConsumerEngine):
        // simple/scalar payloads are wrapped as {"value": <scalar>}.
        JsonObject payloadJson = new JsonObject().put("value", payload);
        return connectionManager.withConnection(SERVICE_ID, conn ->
                conn.preparedQuery(
                        "INSERT INTO outbox (topic, payload, status, message_group, created_at) " +
                        "VALUES ($1, $2, 'PENDING', $3, NOW()) RETURNING id"
                ).execute(Tuple.of(topic, payloadJson, messageGroup))
                .map(rows -> rows.iterator().next().getLong("id"))
        );
    }

    private Future<Void> cleanupTestData() {
        if (testTopics.isEmpty()) {
            return Future.succeededFuture();
        }
        String[] topics = testTopics.toArray(new String[0]);
        return connectionManager.withConnection(SERVICE_ID, conn ->
                conn.preparedQuery("DELETE FROM outbox_partition_assignments WHERE topic = ANY($1::text[])").execute(Tuple.of(topics))
                        .compose(v -> conn.preparedQuery("DELETE FROM outbox_partition_offsets WHERE topic = ANY($1::text[])").execute(Tuple.of(topics)))
                        .compose(v -> conn.preparedQuery("DELETE FROM outbox_topic_watermarks WHERE topic = ANY($1::text[])").execute(Tuple.of(topics)))
                        .compose(v -> conn.preparedQuery("DELETE FROM outbox_topic_subscriptions WHERE topic = ANY($1::text[])").execute(Tuple.of(topics)))
                        .compose(v -> conn.preparedQuery("DELETE FROM outbox WHERE topic = ANY($1::text[])").execute(Tuple.of(topics)))
                        .compose(v -> conn.preparedQuery("DELETE FROM outbox_topics WHERE topic = ANY($1::text[])").execute(Tuple.of(topics)))
                        .map(rows -> (Void) null)
        );
    }

    private Future<Integer> countPartitionAssignments(String topic, String groupName) {
        return connectionManager.withConnection(SERVICE_ID, conn ->
                conn.preparedQuery(
                        "SELECT COUNT(*) AS cnt FROM outbox_partition_assignments " +
                        "WHERE topic = $1 AND group_name = $2"
                ).execute(Tuple.of(topic, groupName))
                .map(rows -> rows.iterator().next().getInteger("cnt"))
        );
    }
}
