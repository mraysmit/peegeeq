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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.BASIC;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent.ALL;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Infrastructure and fault-injection integration tests for partitioned consumer groups.
 *
 * <p>Covers crash/fault scenarios identified in the code review:</p>
 * <ul>
 *   <li>F1: Handler fails mid-batch remaining messages re-delivered on next fetch</li>
 *   <li>F4: stop() while fetch is in-progress clean shutdown, no stale commit</li>
 *   <li>F2: Database connection loss during fetch loop engine self-heals</li>
 *   <li>F3: Hung handler permanently blocks single partition</li>
 *   <li>F8: leaveGroup fails on hard stop orphaned assignment cleaned by rebalance</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-13
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConsumerGroupFaultIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupFaultIntegrationTest.class);
    private static final String SERVICE_ID = "fault-test";

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
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
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
            } catch (Exception ignore) { }
            connectionManager.close();
        }
        if (manager != null) {
            manager.closeReactive().await();
        }
    }

    // ========================================================================
    // F1: Handler fails mid-batch remaining messages re-delivered
    //
    // Send 5 messages on a single partition. Handler fails on message #3.
    // processAndCommit chains break → offset never committed → next fetch
    // re-delivers messages 3-5.
    // ========================================================================

    @Test
    @Order(1)
    @DisplayName("F1: handler fails mid-batch uncommitted messages re-delivered on next fetch")
    void handlerFailsMidBatch_messagesRedelivered(VertxTestContext testContext) throws Exception {
        logger.info("=== FAULT 1: handlerFailsMidBatch_messagesRedelivered STARTED ===");

        String topic = "test-fault-midbatch-" + System.nanoTime();
        String groupName = "fault-1";

        AtomicInteger totalInvocations = new AtomicInteger(0);
        Set<Long> processedIds = Collections.synchronizedSet(new HashSet<>());
        AtomicBoolean failOnThird = new AtomicBoolean(true);
        AtomicInteger failCount = new AtomicInteger(0);

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> {
                    // Insert 5 messages all in the same partition (same message_group)
                    Future<Void> inserts = Future.succeededFuture();
                    for (int i = 1; i <= 5; i++) {
                        int idx = i;
                        inserts = inserts.compose(x -> insertOutboxMessage(topic, "part-A", "payload-" + idx).map(id -> (Void) null));
                    }
                    return inserts;
                })
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, databaseService,
                            connectionManager, SERVICE_ID
                    );

                    group.setMessageHandler(msg -> {
                        long msgId = Long.parseLong(msg.getId());
                        int count = totalInvocations.incrementAndGet();
                        logger.info("FAULT 1: handling msg {} (invocation #{})", msgId, count);

                        // Fail on the 3rd invocation in the first batch only
                        if (failOnThird.get() && count == 3) {
                            failCount.incrementAndGet();
                            failOnThird.set(false); // Only fail once
                            return Future.failedFuture(new RuntimeException("Simulated handler crash on message " + msgId));
                        }
                        processedIds.add(msgId);
                        return Future.succeededFuture();
                    });

                    group.start();

                    // Wait for multiple fetch cycles to exercise re-delivery
                    Vertx vtx = databaseService.getVertx();
                    return vtx.timer(12000).mapEmpty()
                            .map(delayed -> {
                                group.close();

                                logger.info("FAULT 1: totalInvocations={}, processedIds={}, failCount={}",
                                        totalInvocations.get(), processedIds.size(), failCount.get());

                                assertEquals(1, failCount.get(), "Handler should have failed exactly once");
                                assertTrue(processedIds.size() >= 5,
                                        "All 5 messages should eventually be processed after re-delivery; got " + processedIds.size());

                                logger.info("FAULT 1 PASSED: mid-batch failure with re-delivery verified");
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
    }

    // ========================================================================
    // F4: stop() while fetch is in-progress clean shutdown
    //
    // Start with a slow handler (500ms per message), then call stop()
    // during processing. The engine should shut down cleanly; the running
    // flag prevents new fetches but the in-flight batch finishes.
    // ========================================================================

    @Test
    @Order(2)
    @DisplayName("F4: stop during active fetch engine stops cleanly")
    void stopDuringActiveFetch_cleansUpGracefully(VertxTestContext testContext) throws Exception {
        logger.info("=== FAULT 4: stopDuringActiveFetch_cleansUpGracefully STARTED ===");

        String topic = "test-fault-stopfetch-" + System.nanoTime();
        String groupName = "fault-4";

        AtomicInteger handlerCalls = new AtomicInteger(0);
        AtomicBoolean handlerRunning = new AtomicBoolean(false);

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> {
                    // Insert multiple messages
                    Future<Void> inserts = Future.succeededFuture();
                    for (int i = 1; i <= 10; i++) {
                        int idx = i;
                        inserts = inserts.compose(x -> insertOutboxMessage(topic, "part-A", "payload-" + idx).map(id -> (Void) null));
                    }
                    return inserts;
                })
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, databaseService,
                            connectionManager, SERVICE_ID
                    );

                    Vertx vtx = databaseService.getVertx();

                    // Slow handler: 500ms per message
                    group.setMessageHandler(msg -> {
                        handlerRunning.set(true);
                        handlerCalls.incrementAndGet();
                        Promise<Void> p = Promise.promise();
                        vtx.setTimer(500, id -> {
                            handlerRunning.set(false);
                            p.complete();
                        });
                        return p.future();
                    });

                    group.start();

                    // Wait for fetch to start processing, then stop
                    return vtx.timer(5000).mapEmpty()
                            .compose(delayed -> {
                                logger.info("FAULT 4: stopping group while handler running={}, calls={}",
                                        handlerRunning.get(), handlerCalls.get());
                                return group.stopGracefully();
                            })
                            .map(stopped -> {
                                // After stop completes, group should be in NEW state
                                assertFalse(group.isActive(),
                                        "Group should not be active after stopGracefully");
                                assertEquals(PgNativeConsumerGroup.State.NEW, group.getState(),
                                        "Group should be in NEW state after stop");

                                logger.info("FAULT 4 PASSED: clean shutdown during active fetch, {} messages processed",
                                        handlerCalls.get());
                                group.close();
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
    }

    // ========================================================================
    // F2: Database connection loss during fetch loop engine self-heals
    //
    // Start the partitioned consumer, terminate all backend connections via
    // pg_terminate_backend, wait for fetch errors, then verify the engine
    // recovers automatically when the pool reconnects.
    // ========================================================================

    @Test
    @Order(3)
    @DisplayName("F2: DB connection terminated engine recovers and resumes processing")
    void dbConnectionLoss_engineRecovers(VertxTestContext testContext) throws Exception {
        logger.info("=== FAULT 2: dbConnectionLoss_engineRecovers STARTED ===");

        String topic = "test-fault-dbloss-" + System.nanoTime();
        String groupName = "fault-2";

        AtomicInteger handlerCalls = new AtomicInteger(0);
        AtomicLong firstMessageTime = new AtomicLong(0);
        AtomicLong postRecoveryMessageTime = new AtomicLong(0);

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-A", "before-kill"))
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, databaseService,
                            connectionManager, SERVICE_ID
                    );

                    group.setMessageHandler(msg -> {
                        int call = handlerCalls.incrementAndGet();
                        if (call == 1) {
                            firstMessageTime.set(System.currentTimeMillis());
                        }
                        if (call > 1 && postRecoveryMessageTime.get() == 0) {
                            postRecoveryMessageTime.set(System.currentTimeMillis());
                        }
                        logger.info("FAULT 2: handler call #{}, msg={}", call, msg.getId());
                        return Future.succeededFuture();
                    });

                    group.start();
                    Vertx vtx = databaseService.getVertx();

                    // Wait for first message to be processed
                    return vtx.timer(8000).mapEmpty()
                            .compose(delayed -> {
                                logger.info("FAULT 2: before kill, handlerCalls={}", handlerCalls.get());

                                // Terminate all non-superuser backend connections
                                return connectionManager.withConnection(SERVICE_ID, conn ->
                                        conn.query(
                                                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
                                                "WHERE pid <> pg_backend_pid() " +
                                                "AND datname = current_database() " +
                                                "AND state = 'idle'"
                                        ).execute().map(rows -> {
                                            int terminated = rows.size();
                                            logger.info("FAULT 2: terminated {} idle connections", terminated);
                                            assertTrue(terminated > 0,
                                                    "At least one idle connection should have been terminated");
                                            return (Void) null;
                                        })
                                );
                            })
                            .compose(killed -> {
                                // Insert a new message after killing connections
                                return insertOutboxMessage(topic, "part-A", "after-kill");
                            })
                            .compose(id -> {
                                // Wait for the engine to recover and process the new message
                                return vtx.timer(10000).mapEmpty();
                            })
                            .map(delayed -> {
                                int totalCalls = handlerCalls.get();
                                logger.info("FAULT 2: after recovery, handlerCalls={}", totalCalls);

                                // The engine should have processed messages both before and after kill
                                assertTrue(totalCalls >= 2,
                                        "Engine should process messages after connection recovery; got " + totalCalls);

                                logger.info("FAULT 2 PASSED: engine recovered after connection termination");
                                group.close();
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(45, TimeUnit.SECONDS), "Test timed out");
    }

    // ========================================================================
    // F3: Hung handler permanently blocks partition
    //
    // Handler returns a Future that never completes for one partition.
    // The fetchInProgress guard prevents overlapping fetches, so that
    // partition stays blocked. Other partitions (if any) continue normally.
    // This test documents the current behavior (no timeout on dispatch).
    // ========================================================================

    @Test
    @Order(4)
    @DisplayName("F3: hung handler blocks partition fetch guard prevents overlapping fetches")
    void hungHandler_blocksPartition(VertxTestContext testContext) throws Exception {
        logger.info("=== FAULT 3: hungHandler_blocksPartition STARTED ===");

        String topic = "test-fault-hung-" + System.nanoTime();
        String groupName = "fault-3";

        AtomicInteger handlerCalls = new AtomicInteger(0);
        AtomicBoolean firstCallHung = new AtomicBoolean(false);

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> {
                    // Insert 5 messages
                    Future<Void> inserts = Future.succeededFuture();
                    for (int i = 1; i <= 5; i++) {
                        int idx = i;
                        inserts = inserts.compose(x -> insertOutboxMessage(topic, "part-A", "payload-" + idx).map(id -> (Void) null));
                    }
                    return inserts;
                })
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, databaseService,
                            connectionManager, SERVICE_ID
                    );

                    group.setMessageHandler(msg -> {
                        int call = handlerCalls.incrementAndGet();
                        logger.info("FAULT 3: handler call #{}, msg={}", call, msg.getId());

                        if (call == 1) {
                            // First message: return a Future that NEVER completes
                            firstCallHung.set(true);
                            logger.info("FAULT 3: hanging on first message");
                            return Promise.<Void>promise().future(); // never completes
                        }
                        return Future.succeededFuture();
                    });

                    group.start();
                    Vertx vtx = databaseService.getVertx();

                    // Wait for multiple fetch intervals
                    return vtx.timer(10000).mapEmpty()
                            .map(delayed -> {
                                int totalCalls = handlerCalls.get();
                                logger.info("FAULT 3: totalCalls={}, firstCallHung={}",
                                        totalCalls, firstCallHung.get());

                                assertTrue(firstCallHung.get(),
                                        "First handler call should have been made");

                                // The partition is blocked because the first Future never completed.
                                // The fetchInProgress guard prevents subsequent fetches on the same partition.
                                // So only 1 message was dispatched the rest are blocked.
                                assertEquals(1, totalCalls,
                                        "Only 1 handler invocation expected partition is blocked by hung Future");

                                logger.info("FAULT 3 PASSED: hung handler blocks partition (documented behavior)");
                                group.close();
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
    }

    // ========================================================================
    // F8: leaveGroup fails on hard stop
    //
    // Start partitioned consumer, then kill all DB connections immediately
    // before calling close(). The leaveGroup() call will fail but the
    // engine .transform() swallows the error and stop completes.
    // ========================================================================

    @Test
    @Order(5)
    @DisplayName("F8: leaveGroup fails on hard close engine still stops cleanly")
    void leaveGroupFails_engineStopsCleanly(VertxTestContext testContext) throws Exception {
        logger.info("=== FAULT 8: leaveGroupFails_engineStopsCleanly STARTED ===");

        String topic = "test-fault-leave-" + System.nanoTime();
        String groupName = "fault-8";

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

                    Vertx vtx = databaseService.getVertx();

                    // Wait for startup to complete
                    return vtx.timer(5000).mapEmpty()
                            .compose(delayed -> {
                                logger.info("FAULT 8: group state={}, active={}", group.getState(), group.isActive());

                                // Kill ALL non-self connections to make leaveGroup fail
                                return connectionManager.withConnection(SERVICE_ID, conn ->
                                        conn.query(
                                                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
                                                "WHERE pid <> pg_backend_pid() " +
                                                "AND datname = current_database() " +
                                                "AND state = 'idle'"
                                        ).execute().map(rows -> {
                                            int terminated = rows.size();
                                            logger.info("FAULT 8: terminated {} connections", terminated);
                                            assertTrue(terminated > 0,
                                                    "At least one idle connection should have been terminated");
                                            return (Void) null;
                                        })
                                );
                            })
                            .map(killed -> {
                                // close() calls engine.stop() which calls leaveGroup()
                                // leaveGroup may fail due to terminated connections,
                                // but the .transform() in engine.stop() swallows the error
                                group.close();

                                assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState(),
                                        "Group should be CLOSED even when leaveGroup fails");
                                assertFalse(group.isActive(),
                                        "Group should not be active after close");

                                logger.info("FAULT 8 PASSED: engine stopped cleanly despite leaveGroup failure");
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
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
        JsonObject payloadJson = new JsonObject().put("data", payload);
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
