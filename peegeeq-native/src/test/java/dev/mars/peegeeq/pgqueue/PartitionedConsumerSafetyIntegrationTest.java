package dev.mars.peegeeq.pgqueue;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
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
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.BASIC;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Safety and edge-case integration tests for partitioned consumption.
 *
 * <p>These tests cover the concurrency and error-path gaps identified in the
 * code review that happy-path integration tests did not exercise:</p>
 * <ul>
 *   <li>Item 1: close() during async partitioned startup CAS guard prevents state corruption</li>
 *   <li>Item 2: stop() failure logging stopGracefully() completes cleanly in partitioned mode</li>
 *   <li>Item 3: overlapping fetch guard slow handler does not cause duplicate message processing</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-12
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PartitionedConsumerSafetyIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PartitionedConsumerSafetyIntegrationTest.class);
    private static final String SERVICE_ID = "safety-test";

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
    void setUp() {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        databaseService = new PgDatabaseService(manager);
        adapter = new VertxPoolAdapter(
                databaseService.getVertx(),
                databaseService.getPool(),
                databaseService
        );

        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

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
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.info("Tearing down: closing resources and manager");
        if (connectionManager != null) {
            try {
                cleanupTestData().await();
            } catch (Exception ignore) {}
            connectionManager.close();
        }
        if (manager != null) {
            manager.closeReactive().await();
        }
    }

    // ========================================================================
    // Item 1: close() during async partitioned startup
    //
    // Covers the CAS guard in startPartitioned(): if close() sets state to
    // CLOSED between start() and the async engine.start() callback, the CAS
    // compareAndSet(STARTING, ACTIVE) fails and the engine is stopped cleanly.
    // Without the fix, state.set(ACTIVE) would overwrite CLOSED.
    // ========================================================================

    @Test
    @Order(1)
    @DisplayName("close() during partitioned startup leaves state CLOSED, not ACTIVE")
    void testCloseDuringPartitionedStart_stateIsClosed(VertxTestContext testContext) throws Exception {
        logger.info("=== SAFETY 1: testCloseDuringPartitionedStart_stateIsClosed STARTED ===");

        String topic = "test-safety-close-start-" + System.nanoTime();
        String groupName = "safety-1";

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-A", "payload-1"))
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, databaseService,
                            connectionManager, SERVICE_ID
                    );
                    group.setMessageHandler(msg -> Future.succeededFuture());

                    // start() is fire-and-forget; transitions to STARTING then begins async work
                    group.start();

                    // Immediately close should set state to CLOSED before async callback
                    group.close();

                    // State must be CLOSED immediately after close()
                    assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState(),
                            "State should be CLOSED immediately after close(), not overwritten by async start");

                    // Wait to ensure the async start callback doesn't resurrect state to ACTIVE
                    return databaseService.getVertx().timer(3000).mapEmpty()
                            .map(delayed -> {
                                assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState(),
                                        "State should still be CLOSED after async start callback completes");
                                assertFalse(group.isActive(),
                                        "Group should not be active after close()");
                                logger.info("SAFETY 1 PASSED: state remained CLOSED throughout async startup");
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Test timed out");
    }

    // ========================================================================
    // Item 2: stopGracefully() completes cleanly in partitioned mode
    //
    // Covers the .onFailure() logging added to stop(): verifies that
    // stopGracefully() returns a completed future and transitions state to NEW
    // when the partitioned engine is active. This path was previously untested
    // because CORE tests only exercise reference-counting mode.
    // ========================================================================

    @Test
    @Order(2)
    @DisplayName("stopGracefully() in partitioned mode returns to NEW state")
    void testStopGracefully_partitionedMode_returnsToNew(VertxTestContext testContext) throws Exception {
        logger.info("=== SAFETY 2: testStopGracefully_partitionedMode_returnsToNew STARTED ===");

        String topic = "test-safety-stop-" + System.nanoTime();
        String groupName = "safety-2";

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-A", "payload-1"))
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, databaseService,
                            connectionManager, SERVICE_ID
                    );
                    group.setMessageHandler(msg -> Future.succeededFuture());
                    group.start();

                    // Wait for the group to become ACTIVE (async join + engine start)
                    // Partitioned startup involves: topic mode query → engine.start() → joinGroup
                    // → initializeOffsets → startFetchLoop can take 5+ seconds on cold Testcontainers
                    return databaseService.getVertx().timer(6000).mapEmpty()
                            .compose(delayed -> {
                                assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState(),
                                        "Group should be ACTIVE after startup completes");

                                // stopGracefully returns a future verify it completes and state is NEW
                                return group.stopGracefully();
                            })
                            .map(v2 -> {
                                assertEquals(PgNativeConsumerGroup.State.NEW, group.getState(),
                                        "State should be NEW after stopGracefully()");
                                assertFalse(group.isActive(),
                                        "Group should not be active after stopGracefully()");
                                logger.info("SAFETY 2 PASSED: stopGracefully() completed, state=NEW");
                                return (Void) null;
                            })
                            .eventually(() -> {
                                group.close();
                                return Future.succeededFuture();
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
    }

    // ========================================================================
    // Item 3: overlapping fetch guard prevents duplicate message processing
    //
    // Covers the fetchInProgress CAS guard in PartitionedConsumerEngine:
    // with a slow message handler, multiple fetch timer ticks fire while a
    // previous fetch+process+commit cycle is still running. The guard skips
    // overlapping fetches, preventing the same messages from being delivered
    // twice. Without the guard, concurrent fetches starting from the same
    // uncommitted offset would process duplicates.
    // ========================================================================

    @Test
    @Order(3)
    @DisplayName("slow handler with overlapping fetch ticks does not produce duplicate messages")
    void testSlowHandler_noDuplicateMessages(VertxTestContext testContext) throws Exception {
        logger.info("=== SAFETY 3: testSlowHandler_noDuplicateMessages STARTED ===");

        String topic = "test-safety-overlap-" + System.nanoTime();
        String groupName = "safety-3";

        // Track received message IDs use synchronized set to detect duplicates
        Set<String> receivedIds = Collections.synchronizedSet(new HashSet<>());
        AtomicBoolean duplicateDetected = new AtomicBoolean(false);
        AtomicInteger totalInvocations = new AtomicInteger(0);

        // Insert several messages in one partition
        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "slow-part", "msg-1"))
                .compose(v -> insertOutboxMessage(topic, "slow-part", "msg-2"))
                .compose(v -> insertOutboxMessage(topic, "slow-part", "msg-3"))
                .compose(v -> insertOutboxMessage(topic, "slow-part", "msg-4"))
                .compose(v -> insertOutboxMessage(topic, "slow-part", "msg-5"))
                .compose(v -> {
                    Vertx vtx = databaseService.getVertx();

                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, databaseService,
                            connectionManager, SERVICE_ID
                    );

                    // Slow handler: 300ms per message via promise + timer
                    // With 5 messages, one fetch cycle takes ~1500ms
                    // Fetch interval is 1000ms, so the second tick fires while processing
                    group.setMessageHandler(msg -> {
                        totalInvocations.incrementAndGet();
                        if (!receivedIds.add(msg.getId())) {
                            duplicateDetected.set(true);
                            logger.error("DUPLICATE detected: id={}", msg.getId());
                        }
                        Promise<Void> p = Promise.promise();
                        vtx.setTimer(300, id -> p.complete());
                        return p.future();
                    });

                    group.start();

                    // Wait for startup (5s on cold Testcontainers) + multiple fetch cycles
                    // (1st fetch at ~0ms, 2nd at ~1000ms while 1st still processing,
                    //  guard should skip the 2nd, 3rd fetch at ~2000ms picks up from committed offset)
                    return vtx.timer(15000).mapEmpty()
                            .map(delayed -> {
                                group.close();

                                int total = totalInvocations.get();
                                logger.info("SAFETY 3: {} handler invocations, {} unique IDs, duplicate={}",
                                        total, receivedIds.size(), duplicateDetected.get());

                                assertFalse(duplicateDetected.get(),
                                        "No duplicate message IDs should be delivered; " +
                                        "the fetch overlap guard should prevent concurrent fetches on the same partition");

                                assertTrue(receivedIds.size() > 0,
                                        "Should have received at least some messages");

                                // Each message should have been delivered exactly once
                                assertEquals(total, receivedIds.size(),
                                        "Total invocations should equal unique IDs (no duplicates)");

                                logger.info("SAFETY 3 PASSED: {} messages processed, zero duplicates", receivedIds.size());
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(45, TimeUnit.SECONDS), "Test timed out");
    }

    // ========================================================================
    // Item 4: discovery gap consumer joins before any messages exist.
    //
    // When discoverPartitionsInternal() finds no PENDING/PROCESSING rows,
    // joinGroup() returns an empty list. The engine must start cleanly with
    // zero assigned partitions, reach ACTIVE state, and run its no-op fetch
    // loop without crashing. No partition assignments are written to the DB.
    // ========================================================================

    @Test
    @Order(4)
    @DisplayName("empty topic: engine starts cleanly, reaches ACTIVE, no partition assignments")
    void testDiscoveryGap_emptyTopic_engineStartsCleanly(VertxTestContext testContext) throws Exception {
        logger.info("=== SAFETY 4: testDiscoveryGap_emptyTopic_engineStartsCleanly STARTED ===");

        String topic = "test-safety-empty-" + System.nanoTime();
        String groupName = "safety-4";

        // No messages inserted topic is empty when the group joins.
        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, databaseService,
                            connectionManager, SERVICE_ID
                    );
                    group.setMessageHandler(msg -> Future.succeededFuture());
                    group.start();

                    // Wait long enough for async partitioned startup to complete.
                    // (topic-mode detection → engine.start() → joinGroup → empty → fetch loop)
                    return databaseService.getVertx().timer(5000).mapEmpty()
                            .compose(delayed -> {
                                // Engine should reach ACTIVE despite zero discovered partitions.
                                assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState(),
                                        "Group should reach ACTIVE even when topic has no messages");

                                // No rows in outbox_partition_assignments: nothing to assign.
                                return connectionManager.withConnection(SERVICE_ID, conn ->
                                        conn.preparedQuery(
                                                "SELECT COUNT(*) AS cnt FROM outbox_partition_assignments " +
                                                "WHERE topic = $1 AND group_name = $2"
                                        ).execute(Tuple.of(topic, groupName))
                                        .map(rows -> rows.iterator().next().getInteger("cnt"))
                                ).map(count -> {
                                    assertEquals(0, (int) count,
                                            "No partition assignments should exist when topic has no messages; got: " + count);
                                    logger.info("SAFETY 4 PASSED: ACTIVE state reached, 0 partition assignments");
                                    return (Void) null;
                                }).eventually(() -> {
                                    group.close();
                                    return Future.succeededFuture();
                                });
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(20, TimeUnit.SECONDS), "Test timed out");
    }

    // ========================================================================
    // Item 5: handler failure mid-batch offset must NOT advance.
    //
    // When the message handler returns a failed Future, processAndCommit()
    // short-circuits via .compose() before commitOffset() is reached. The
    // committed_offset in outbox_partition_offsets stays at 0, and the next
    // fetch tick re-delivers the same message (at-least-once guarantee).
    // ========================================================================

    @Test
    @Order(5)
    @DisplayName("failing handler: committed_offset stays 0, message eligible for redelivery")
    void testHandlerFailure_offsetNotAdvanced(VertxTestContext testContext) throws Exception {
        logger.info("=== SAFETY 5: testHandlerFailure_offsetNotAdvanced STARTED ===");

        String topic = "test-safety-fail-" + System.nanoTime();
        String groupName = "safety-5";
        String partition = "fail-part";

        AtomicInteger handlerInvocations = new AtomicInteger(0);

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, partition, "failing-msg"))
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, databaseService,
                            connectionManager, SERVICE_ID
                    );
                    // Handler always fails offset must never advance.
                    group.setMessageHandler(msg -> {
                        handlerInvocations.incrementAndGet();
                        return Future.failedFuture(new RuntimeException("intentional handler failure"));
                    });
                    group.start();

                    // Wait for startup (~4s on cold Testcontainers) plus several fetch ticks
                    // (DEFAULT_FETCH_INTERVAL_MS = 1000 ms). Each tick re-delivers the same
                    // message because the offset was never committed.
                    return databaseService.getVertx().timer(9000).mapEmpty()
                            .compose(delayed -> {
                                group.close();

                                int invocations = handlerInvocations.get();
                                logger.info("SAFETY 5: handler invoked {} times (all failures)", invocations);
                                assertTrue(invocations >= 1,
                                        "Handler should have been invoked at least once; got: " + invocations);

                                // committed_offset must still be 0: the commit chain was
                                // short-circuited by the failed handler Future.
                                return connectionManager.withConnection(SERVICE_ID, conn ->
                                        conn.preparedQuery(
                                                "SELECT committed_offset FROM outbox_partition_offsets " +
                                                "WHERE topic = $1 AND group_name = $2 AND partition_key = $3"
                                        ).execute(Tuple.of(topic, groupName, partition))
                                        .map(rows -> {
                                            assertTrue(rows.size() > 0,
                                                    "Offset row should exist once the partition was assigned");
                                            long committedOffset = rows.iterator().next().getLong("committed_offset");
                                            assertEquals(0L, committedOffset,
                                                    "committed_offset must remain 0 after handler failures; got: "
                                                    + committedOffset);
                                            logger.info("SAFETY 5 PASSED: {} handler invocations, committed_offset=0",
                                                    invocations);
                                            return (Void) null;
                                        })
                                );
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
    }

    // ========================================================================
    // Item 6: new message_group after join not assigned until rebalance (Test A)
    //
    // When joinGroup() runs against an empty topic it returns an empty partition
    // list and the engine's assignedPartitions map stays empty.  A message
    // inserted afterwards for a new messageGroup is NOT processed by the already-
    // running engine there is no periodic rediscovery.  Only a second instance
    // joining (which triggers a full rebalance) causes the new partition to be
    // discovered and assigned, after which the message is processed.
    //
    // This test locks in the documented operational caveat from:
    //   docs-design/analysis/GUARANTEED_ORDERING_CONCURRENT_CONSUMERS_ANALYSIS.md
    //   §"Operational Caveats & Lifecycle §1 and §3"
    // If periodic rediscovery is added in future, this test must be updated
    // rather than silently passing under the new behaviour.
    // ========================================================================

    @Test
    @Order(6)
    @DisplayName("new messageGroup after join: not assigned until second instance triggers rebalance")
    void testNewMessageGroupAfterJoin_notAssignedUntilRebalance(VertxTestContext testContext) throws Exception {
        logger.info("=== SAFETY 6: testNewMessageGroupAfterJoin_notAssignedUntilRebalance STARTED ===");

        String topic = "test-safety-disc-" + System.nanoTime();
        String groupName = "safety-6";
        String newPartition = "acct-new";

        // Completed by B's message handler when the late-join partition is processed.
        Promise<Void> messageLatch = Promise.promise();

        // groupA is declared here so it is accessible inside both the compose chain
        // (to check its state) and the cleanup compose at the end.
        PgNativeConsumerGroup<String>[] groupAHolder = new PgNativeConsumerGroup[1];

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> {
                    // Start instance A after the topic and subscription rows exist so that
                    // isOffsetWatermarkTopic() and joinGroup() can run successfully.
                    PgNativeConsumerGroup<String> groupA = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, databaseService,
                            connectionManager, SERVICE_ID
                    );
                    groupAHolder[0] = groupA;
                    // A should never receive any messages it will have no assigned partitions.
                    groupA.setMessageHandler(msg -> Future.succeededFuture());
                    groupA.start();

                    // Wait for async partitioned startup to complete
                    // (topic-mode detection → engine.start() → joinGroup → empty → fetch loop).
                    return databaseService.getVertx().timer(5000).mapEmpty();
                })
                .compose(v -> {
                    assertEquals(PgNativeConsumerGroup.State.ACTIVE, groupAHolder[0].getState(),
                            "Instance A should reach ACTIVE even with empty topic");

                    // No partition assignments: topic had no PENDING rows at A's join time.
                    return connectionManager.withConnection(SERVICE_ID, conn ->
                            conn.preparedQuery(
                                    "SELECT COUNT(*) AS cnt FROM outbox_partition_assignments " +
                                    "WHERE topic = $1 AND group_name = $2")
                                    .execute(Tuple.of(topic, groupName))
                                    .map(rows -> rows.iterator().next().getInteger("cnt")));
                })
                .compose(count -> {
                    assertEquals(0, (int) count,
                            "0 assignment rows expected after A joins empty topic; got: " + count);

                    // Insert a message AFTER A has already joined this is the late-arrival case.
                    return insertOutboxMessage(topic, newPartition, "late-msg");
                })
                // Wait 3 fetch ticks (3 × DEFAULT_FETCH_INTERVAL_MS = 1 s).
                // A's engine iterates an empty assignedPartitions map it cannot fetch
                // for "acct-new" because that partition was never discovered at join time.
                .compose(msgId -> databaseService.getVertx().timer(3000).map(msgId))
                .compose(msgId -> connectionManager.withConnection(SERVICE_ID, conn ->
                        conn.preparedQuery("SELECT status FROM outbox WHERE id = $1")
                                .execute(Tuple.of(msgId))
                                .map(rows -> rows.iterator().next().getString("status")))
                        .compose(status -> {
                            assertEquals("PENDING", status,
                                    "Message should remain PENDING A has no partitions assigned; actual: " + status);

                            // Zero assignment rows still: no rebalance has occurred.
                            return connectionManager.withConnection(SERVICE_ID, conn ->
                                    conn.preparedQuery(
                                            "SELECT COUNT(*) AS cnt FROM outbox_partition_assignments " +
                                            "WHERE topic = $1 AND group_name = $2")
                                            .execute(Tuple.of(topic, groupName))
                                            .map(rows -> rows.iterator().next().getInteger("cnt")));
                        })
                        .compose(cnt2 -> {
                            assertEquals(0, (int) cnt2,
                                    "Still 0 assignment rows before rebalance; got: " + cnt2);

                            // Start instance B its joinGroup() triggers a rebalance.
                            // B discovers "acct-new", gets it assigned, and processes the message.
                            PgNativeConsumerGroup<String> groupB = new PgNativeConsumerGroup<>(
                                    groupName, topic, String.class,
                                    adapter, mapper, null, null, databaseService,
                                    connectionManager, SERVICE_ID
                            );
                            groupB.setMessageHandler(msg -> {
                                logger.info("SAFETY 6: B processed message for partition '{}'", newPartition);
                                messageLatch.tryComplete();
                                return Future.succeededFuture();
                            });
                            groupB.start();

                            // Wait for B to process the late-arrival message.
                            return messageLatch.future()
                                    .compose(done -> {
                                        logger.info("SAFETY 6 PASSED: late-join partition processed after rebalance");
                                        groupAHolder[0].close();
                                        return groupB.stopGracefully();
                                    });
                        })
                )
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(45, TimeUnit.SECONDS), "Test timed out");
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
}
