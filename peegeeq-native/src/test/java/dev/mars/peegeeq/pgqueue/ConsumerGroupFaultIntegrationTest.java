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
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxException;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.ClosedConnectionException;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.BASIC;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent.ALL;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Live infrastructure and fault-injection integration tests for partitioned consumer groups.
 *
 * <p>Every fault is introduced at a real asynchronous boundary: handler Futures, live PostgreSQL
 * backend termination, a closed repository pool, or an exhausted repository pool.</p>
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConsumerGroupFaultIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupFaultIntegrationTest.class);
    private static final String SERVICE_ID = "fault-test";
    private static final String RECOVERY_SERVICE_ID = "fault-test-recovery";
    private static final String EXHAUSTED_SERVICE_ID = "fault-test-exhausted";
    private static final long ASYNC_TIMEOUT_MS = 20_000;

    @Container
    static final PostgreSQLContainer postgres = PeeGeeQTestContainerFactory.createContainer(BASIC);

    private PeeGeeQManager manager;
    private PgDatabaseService databaseService;
    private VertxPoolAdapter adapter;
    private ObjectMapper mapper;
    private PgConnectionManager connectionManager;
    private PgConnectionConfig connectionConfig;
    private final List<String> testTopics = new ArrayList<>();

    @BeforeAll
    static void beforeAll() {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, ALL);
    }

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .onSuccess(v -> {
                    databaseService = new PgDatabaseService(manager);
                    adapter = new VertxPoolAdapter(databaseService.getVertx(), databaseService.getPool(), databaseService);
                    mapper = new ObjectMapper().registerModule(new JavaTimeModule());
                    connectionManager = new PgConnectionManager(databaseService.getVertx(), null);
                    connectionConfig = new PgConnectionConfig.Builder()
                            .host(postgres.getHost())
                            .port(postgres.getFirstMappedPort())
                            .database(postgres.getDatabaseName())
                            .username(postgres.getUsername())
                            .password(postgres.getPassword())
                            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                            .build();
                    configurePool(SERVICE_ID, 10, Duration.ofSeconds(30));
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        Future<Void> teardown = databaseService == null ? Future.succeededFuture() : cleanupTestData();
        if (connectionManager != null) {
            teardown = teardown.eventually(connectionManager::close);
        }
        teardown
                .onSuccess(v -> finishManagerClose(testContext, null))
                .onFailure(err -> finishManagerClose(testContext, err));
    }

    private void finishManagerClose(VertxTestContext testContext, Throwable teardownFailure) {
        if (manager == null) {
            completeOrFail(testContext, teardownFailure);
            return;
        }
        manager.closeReactive()
                .onSuccess(v -> completeOrFail(testContext, teardownFailure))
                .onFailure(closeFailure -> {
                    if (teardownFailure == null) {
                        testContext.failNow(closeFailure);
                    } else {
                        teardownFailure.addSuppressed(closeFailure);
                        testContext.failNow(teardownFailure);
                    }
                });
    }

    private void completeOrFail(VertxTestContext testContext, Throwable failure) {
        if (failure == null) {
            testContext.completeNow();
        } else {
            logger.error("Consumer-group fault test cleanup failed", failure);
            testContext.failNow(failure);
        }
    }

    @Test
    @Order(1)
    @DisplayName("F1: handler failure leaves the failed suffix uncommitted and it is redelivered")
    void handlerFailsMidBatch_messagesRedelivered(VertxTestContext testContext) {
        String topic = "test-fault-midbatch-" + System.nanoTime();
        String groupName = "fault-1";
        AtomicInteger totalInvocations = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        AtomicBoolean failOnThird = new AtomicBoolean(true);
        Set<Long> processedIds = Collections.synchronizedSet(new HashSet<>());
        Promise<Void> allProcessed = Promise.promise();
        PgNativeConsumerGroup<String>[] groupHolder = new PgNativeConsumerGroup[1];

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertMessages(topic, 5))
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = newGroup(groupName, topic, SERVICE_ID);
                    groupHolder[0] = group;
                    group.setMessageHandler(msg -> {
                        long messageId = Long.parseLong(msg.getId());
                        int invocation = totalInvocations.incrementAndGet();
                        if (invocation == 3 && failOnThird.compareAndSet(true, false)) {
                            failCount.incrementAndGet();
                            return Future.failedFuture(new IllegalStateException(
                                    "Injected handler failure for message " + messageId));
                        }
                        processedIds.add(messageId);
                        if (processedIds.size() == 5 && failCount.getAcquire() == 1) {
                            allProcessed.tryComplete();
                        }
                        return Future.succeededFuture();
                    });
                    return group.start()
                            .compose(started -> withTimeout(allProcessed.future(), ASYNC_TIMEOUT_MS,
                                    "All messages were not redelivered after the handler failure"));
                })
                .map(v -> {
                    assertEquals(1, failCount.getAcquire(), "The injected failure must occur exactly once");
                    assertEquals(5, processedIds.size(), "Every message must eventually be handled successfully");
                    assertTrue(totalInvocations.getAcquire() > 5,
                            "At least one uncommitted message must be redelivered");
                    return (Void) null;
                })
                .eventually(() -> closeGroup(groupHolder[0]))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @Order(2)
    @DisplayName("F4: graceful stop waits for an in-flight handler and commit")
    void stopDuringActiveFetch_cleansUpGracefully(VertxTestContext testContext) {
        String topic = "test-fault-stopfetch-" + System.nanoTime();
        String groupName = "fault-4";
        Promise<Void> handlerStarted = Promise.promise();
        Promise<Void> handlerRelease = Promise.promise();
        PgNativeConsumerGroup<String>[] groupHolder = new PgNativeConsumerGroup[1];

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-A", "payload"))
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = newGroup(groupName, topic, SERVICE_ID);
                    groupHolder[0] = group;
                    group.setMessageHandler(msg -> {
                        handlerStarted.tryComplete();
                        return handlerRelease.future();
                    });
                    return group.start()
                            .compose(started -> withTimeout(handlerStarted.future(), ASYNC_TIMEOUT_MS,
                                    "Handler did not start"))
                            .compose(started -> {
                                Future<Void> stopFuture = group.stopGracefully();
                                return databaseService.getVertx().timer(100)
                                        .map(ignored -> {
                                            assertFalse(stopFuture.isComplete(),
                                                    "Graceful stop must remain pending while the handler is running");
                                            handlerRelease.tryComplete();
                                            return (Void) null;
                                        })
                                        .compose(ignored -> stopFuture);
                            });
                })
                .map(v -> {
                    assertFalse(groupHolder[0].isActive());
                    assertEquals(PgNativeConsumerGroup.State.NEW, groupHolder[0].getState());
                    return (Void) null;
                })
                .eventually(() -> {
                    handlerRelease.tryComplete();
                    return closeGroup(groupHolder[0]);
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @Order(3)
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.consumer.WatermarkJob",
            message = "Watermark sweep #1 failed: topic=test-fault-dbloss-",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = ClosedConnectionException.class)
    @DisplayName("F2: live PostgreSQL backend termination is recovered without losing delivery")
    void dbConnectionLoss_engineRecovers(VertxTestContext testContext) {
        String topic = "test-fault-dbloss-" + System.nanoTime();
        String groupName = "fault-2";
        Promise<Void> firstProcessed = Promise.promise();
        Promise<Void> postRecoveryProcessed = Promise.promise();
        AtomicLong postRecoveryMessageId = new AtomicLong(-1);
        Set<Long> processedIds = Collections.synchronizedSet(new HashSet<>());
        SqlConnection[] terminatedConnection = new SqlConnection[1];
        PgNativeConsumerGroup<String>[] groupHolder = new PgNativeConsumerGroup[1];

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-A", "before-kill"))
                .compose(v -> {
                    configurePool(RECOVERY_SERVICE_ID, 1, Duration.ofSeconds(30));
                    PgNativeConsumerGroup<String> group = newGroup(groupName, topic, RECOVERY_SERVICE_ID);
                    groupHolder[0] = group;
                    group.setMessageHandler(msg -> {
                        long messageId = Long.parseLong(msg.getId());
                        processedIds.add(messageId);
                        firstProcessed.tryComplete();
                        if (messageId == postRecoveryMessageId.getAcquire()) {
                            postRecoveryProcessed.tryComplete();
                        }
                        return Future.succeededFuture();
                    });
                    return group.start()
                            .compose(started -> withTimeout(firstProcessed.future(), ASYNC_TIMEOUT_MS,
                                    "The pre-fault message was not processed"))
                            .compose(processed -> connectionManager.getReactiveConnection(RECOVERY_SERVICE_ID))
                            .compose(connection -> {
                                terminatedConnection[0] = connection;
                                return connection.query("SELECT pg_backend_pid() AS pid")
                                        .execute()
                                        .map(rows -> rows.iterator().next().getInteger("pid"));
                            })
                            .compose(this::terminateBackend)
                            .compose(terminated -> {
                                assertTrue(terminated, "The consumer repository backend must be terminated");
                                return terminatedConnection[0].close();
                            })
                            .compose(closed -> insertOutboxMessage(topic, "part-A", "after-kill"))
                            .compose(messageId -> {
                                postRecoveryMessageId.setRelease(messageId);
                                if (processedIds.contains(messageId)) {
                                    postRecoveryProcessed.tryComplete();
                                }
                                return withTimeout(postRecoveryProcessed.future(), ASYNC_TIMEOUT_MS,
                                        "The post-fault message was not processed after reconnection");
                            })
                            .compose(delivered -> group.close())
                            .map(closed -> {
                                assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState());
                                return (Void) null;
                            });
                })
                .eventually(() -> terminatedConnection[0] == null
                        ? Future.<Void>succeededFuture()
                        : terminatedConnection[0].close())
                .eventually(() -> closeGroup(groupHolder[0]))
                .eventually(() -> connectionManager.closePool(RECOVERY_SERVICE_ID))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @Order(4)
    @DisplayName("F3: a pending handler prevents overlapping fetches for its partition")
    void hungHandler_blocksPartition(VertxTestContext testContext) {
        String topic = "test-fault-hung-" + System.nanoTime();
        String groupName = "fault-3";
        AtomicInteger handlerCalls = new AtomicInteger();
        Promise<Void> firstHandlerStarted = Promise.promise();
        Promise<Void> handlerRelease = Promise.promise();
        PgNativeConsumerGroup<String>[] groupHolder = new PgNativeConsumerGroup[1];

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertMessages(topic, 5))
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = newGroup(groupName, topic, SERVICE_ID);
                    groupHolder[0] = group;
                    group.setMessageHandler(msg -> {
                        handlerCalls.incrementAndGet();
                        firstHandlerStarted.tryComplete();
                        return handlerRelease.future();
                    });
                    return group.start()
                            .compose(started -> withTimeout(firstHandlerStarted.future(), ASYNC_TIMEOUT_MS,
                                    "The first handler did not start"))
                            .compose(started -> databaseService.getVertx().timer(2_500))
                            .map(ignored -> {
                                assertEquals(1, handlerCalls.getAcquire(),
                                        "The fetch guard must prevent overlapping delivery while the handler is pending");
                                handlerRelease.tryComplete();
                                return (Void) null;
                            });
                })
                .eventually(() -> {
                    handlerRelease.tryComplete();
                    return closeGroup(groupHolder[0]);
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @Order(5)
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.consumer.WatermarkJob",
            message = "Watermark sweep #1 failed: topic=test-fault-leave-",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = VertxException.class)
    @DisplayName("F8: repository loss during close is surfaced and a later rebalance removes the orphan")
    void leaveGroupFailure_isSurfacedAndRebalanceRepairsAssignment(VertxTestContext testContext) {
        String topic = "test-fault-leave-" + System.nanoTime();
        String groupName = "fault-8";
        String[] orphanInstance = new String[1];
        PgNativeConsumerGroup<String>[] replacementHolder = new PgNativeConsumerGroup[1];

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-A", "payload"))
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = newGroup(groupName, topic, SERVICE_ID);
                    group.setMessageHandler(msg -> Future.succeededFuture());
                    return group.start()
                            .compose(started -> queryAssignedInstance(topic, groupName))
                            .compose(instanceId -> {
                                orphanInstance[0] = instanceId;
                                return connectionManager.closePool(SERVICE_ID);
                            })
                            .compose(closed -> expectFailure(group.close()))
                            .map(closeFailure -> {
                                assertNotNull(closeFailure);
                                assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState());
                                assertFalse(group.isActive());
                                return (Void) null;
                            })
                            .compose(closed -> insertOutboxMessage(
                                    topic, orphanInstance[0] + "#0", "orphan-targeted-recovery"))
                            .compose(closed -> markAssignmentStale(topic, groupName));
                })
                .compose(v -> {
                    configurePool(SERVICE_ID, 10, Duration.ofSeconds(30));
                    PgNativeConsumerGroup<String> replacement = newGroup(groupName, topic, SERVICE_ID);
                    replacementHolder[0] = replacement;
                    replacement.setMessageHandler(msg -> Future.succeededFuture());
                    return replacement.start();
                })
                .compose(v -> queryAssignedInstance(
                        topic, groupName, orphanInstance[0] + "#0"))
                .map(reassignedInstance -> {
                    assertNotEquals(orphanInstance[0], reassignedInstance,
                            "The replacement rebalance must remove the failed consumer's orphan assignment");
                    return (Void) null;
                })
                .eventually(() -> closeGroup(replacementHolder[0]))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @Order(6)
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.pgqueue.PgNativeConsumerGroup",
            message = "Failed to detect completion-tracking mode for topic 'test-fault-exhausted-",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = VertxException.class)
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.pgqueue.PgNativeConsumerGroup",
            message = "Failed to start consumer group 'fault-9': Timeout",
            throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
    @DisplayName("F9: exhausted repository pool fails startup instead of silently falling back")
    void exhaustedRepositoryPool_failsStartup(VertxTestContext testContext) {
        String topic = "test-fault-exhausted-" + System.nanoTime();
        String groupName = "fault-9";
        SqlConnection[] heldConnection = new SqlConnection[1];
        PgNativeConsumerGroup<String>[] groupHolder = new PgNativeConsumerGroup[1];

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-A", "payload"))
                .compose(v -> {
                    configurePool(EXHAUSTED_SERVICE_ID, 1, Duration.ofSeconds(1));
                    return connectionManager.getReactiveConnection(EXHAUSTED_SERVICE_ID);
                })
                .compose(connection -> {
                    heldConnection[0] = connection;
                    PgNativeConsumerGroup<String> group = newGroup(groupName, topic, EXHAUSTED_SERVICE_ID);
                    groupHolder[0] = group;
                    group.setMessageHandler(msg -> Future.succeededFuture());
                    return expectFailure(group.start());
                })
                .map(startFailure -> {
                    assertNotNull(startFailure, "Pool exhaustion must be returned to the caller");
                    assertFalse(groupHolder[0].isActive());
                    assertEquals(PgNativeConsumerGroup.State.NEW, groupHolder[0].getState());
                    return (Void) null;
                })
                .eventually(() -> heldConnection[0] == null
                        ? Future.<Void>succeededFuture()
                        : heldConnection[0].close())
                .eventually(() -> closeGroup(groupHolder[0]))
                .eventually(() -> connectionManager.closePool(EXHAUSTED_SERVICE_ID))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @Order(7)
    @DisplayName("F10: active partition owners refresh their database heartbeat")
    void activePartitionOwner_refreshesHeartbeat(VertxTestContext testContext) {
        String topic = "test-fault-heartbeat-" + System.nanoTime();
        String groupName = "fault-10";
        PgNativeConsumerGroup<String>[] groupHolder = new PgNativeConsumerGroup[1];

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-A", "payload"))
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = newGroup(groupName, topic, SERVICE_ID);
                    groupHolder[0] = group;
                    group.setMessageHandler(msg -> Future.succeededFuture());
                    return group.start();
                })
                .compose(v -> queryAssignmentHeartbeat(topic, groupName))
                .compose(initialHeartbeat -> withTimeout(
                        awaitHeartbeatAfter(topic, groupName, initialHeartbeat),
                        ASYNC_TIMEOUT_MS,
                        "The active partition owner did not refresh its heartbeat"))
                .eventually(() -> closeGroup(groupHolder[0]))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    private PgNativeConsumerGroup<String> newGroup(String groupName, String topic, String serviceId) {
        return new PgNativeConsumerGroup<>(groupName, topic, String.class,
                adapter, mapper, null, null, databaseService, connectionManager, serviceId);
    }

    private void configurePool(String serviceId, int maxSize, Duration connectionTimeout) {
        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(maxSize)
                .connectionTimeout(connectionTimeout)
                .shared(false)
                .build();
        connectionManager.getOrCreateReactivePool(serviceId, connectionConfig, poolConfig);
    }

    private Future<Void> insertMessages(String topic, int count) {
        Future<Void> inserts = Future.succeededFuture();
        for (int i = 1; i <= count; i++) {
            int index = i;
            inserts = inserts.compose(v -> insertOutboxMessage(topic, "part-A", "payload-" + index).mapEmpty());
        }
        return inserts;
    }

    private Future<Boolean> terminateBackend(int backendPid) {
        return databaseService.getPool().withConnection(conn ->
                conn.preparedQuery("SELECT pg_terminate_backend($1) AS terminated")
                        .execute(Tuple.of(backendPid))
                        .map(rows -> rows.iterator().next().getBoolean("terminated"))
        );
    }

    private Future<String> queryAssignedInstance(String topic, String groupName) {
        return databaseService.getPool().withConnection(conn ->
                conn.preparedQuery("""
                        SELECT assigned_instance_id
                        FROM outbox_partition_assignments
                        WHERE topic = $1 AND group_name = $2
                        """)
                        .execute(Tuple.of(topic, groupName))
                        .map(rows -> {
                            assertEquals(1, rows.size(), "Expected exactly one partition assignment");
                            return rows.iterator().next().getString("assigned_instance_id");
                        })
        );
    }

    private Future<String> queryAssignedInstance(String topic, String groupName, String partitionKey) {
        return databaseService.getPool().withConnection(conn ->
                conn.preparedQuery("""
                        SELECT assigned_instance_id
                        FROM outbox_partition_assignments
                        WHERE topic = $1 AND group_name = $2 AND partition_key = $3
                        """)
                        .execute(Tuple.of(topic, groupName, partitionKey))
                        .map(rows -> {
                            assertEquals(1, rows.size(), "Expected exactly one assignment for " + partitionKey);
                            return rows.iterator().next().getString("assigned_instance_id");
                        })
        );
    }

    private Future<Void> markAssignmentStale(String topic, String groupName) {
        return databaseService.getPool().withTransaction(conn ->
                conn.preparedQuery("""
                        UPDATE outbox_partition_assignments
                        SET last_heartbeat_at = clock_timestamp() - INTERVAL '1 hour'
                        WHERE topic = $1 AND group_name = $2
                        """)
                        .execute(Tuple.of(topic, groupName))
                        .mapEmpty()
        );
    }

    private Future<OffsetDateTime> queryAssignmentHeartbeat(String topic, String groupName) {
        return databaseService.getPool().withConnection(conn ->
                conn.preparedQuery("""
                        SELECT last_heartbeat_at
                        FROM outbox_partition_assignments
                        WHERE topic = $1 AND group_name = $2
                        """)
                        .execute(Tuple.of(topic, groupName))
                        .map(rows -> {
                            assertEquals(1, rows.size(), "Expected exactly one partition assignment");
                            return rows.iterator().next().getOffsetDateTime("last_heartbeat_at");
                        })
        );
    }

    private Future<Void> awaitHeartbeatAfter(
            String topic, String groupName, OffsetDateTime initialHeartbeat) {
        Promise<Void> advanced = Promise.promise();
        pollHeartbeat(topic, groupName, initialHeartbeat, advanced);
        return advanced.future();
    }

    private void pollHeartbeat(
            String topic,
            String groupName,
            OffsetDateTime initialHeartbeat,
            Promise<Void> advanced) {
        queryAssignmentHeartbeat(topic, groupName)
                .onSuccess(currentHeartbeat -> {
                    if (currentHeartbeat.isAfter(initialHeartbeat)) {
                        advanced.tryComplete();
                        return;
                    }
                    databaseService.getVertx().timer(100)
                            .onSuccess(ignored -> pollHeartbeat(
                                    topic, groupName, initialHeartbeat, advanced))
                            .onFailure(advanced::tryFail);
                })
                .onFailure(advanced::tryFail);
    }

    private Future<Void> createTopic(String topic, String completionTrackingMode) {
        testTopics.add(topic);
        return databaseService.getPool().withTransaction(conn ->
                conn.preparedQuery("INSERT INTO outbox_topics (topic, semantics, completion_tracking_mode) " +
                                "VALUES ($1, 'PUB_SUB', $2) ON CONFLICT (topic) DO NOTHING")
                        .execute(Tuple.of(topic, completionTrackingMode))
                        .mapEmpty()
        );
    }

    private Future<Void> createSubscription(String topic, String groupName) {
        return databaseService.getPool().withTransaction(conn ->
                conn.preparedQuery("INSERT INTO outbox_topic_subscriptions " +
                                "(topic, group_name, subscription_status) " +
                                "VALUES ($1, $2, 'ACTIVE') ON CONFLICT (topic, group_name) DO NOTHING")
                        .execute(Tuple.of(topic, groupName))
                        .mapEmpty()
        );
    }

    private Future<Long> insertOutboxMessage(String topic, String messageGroup, String payload) {
        JsonObject payloadJson = new JsonObject().put("value", payload);
        return databaseService.getPool().withTransaction(conn ->
                conn.preparedQuery("INSERT INTO outbox (topic, payload, status, message_group, created_at) " +
                                "VALUES ($1, $2, 'PENDING', $3, NOW()) RETURNING id")
                        .execute(Tuple.of(topic, payloadJson, messageGroup))
                        .map(rows -> rows.iterator().next().getLong("id"))
        );
    }

    private Future<Void> cleanupTestData() {
        if (testTopics.isEmpty()) {
            return Future.succeededFuture();
        }
        String[] topics = testTopics.toArray(new String[0]);
        return databaseService.getPool().withTransaction(conn ->
                conn.preparedQuery("DELETE FROM outbox_partition_assignments WHERE topic = ANY($1::text[])")
                        .execute(Tuple.of(topics))
                        .compose(v -> conn.preparedQuery(
                                        "DELETE FROM outbox_partition_offsets WHERE topic = ANY($1::text[])")
                                .execute(Tuple.of(topics)))
                        .compose(v -> conn.preparedQuery(
                                        "DELETE FROM outbox_topic_watermarks WHERE topic = ANY($1::text[])")
                                .execute(Tuple.of(topics)))
                        .compose(v -> conn.preparedQuery(
                                        "DELETE FROM outbox_topic_subscriptions WHERE topic = ANY($1::text[])")
                                .execute(Tuple.of(topics)))
                        .compose(v -> conn.preparedQuery("DELETE FROM outbox WHERE topic = ANY($1::text[])")
                                .execute(Tuple.of(topics)))
                        .compose(v -> conn.preparedQuery("DELETE FROM outbox_topics WHERE topic = ANY($1::text[])")
                                .execute(Tuple.of(topics)))
                        .mapEmpty()
        );
    }

    private <T> Future<T> withTimeout(Future<T> operation, long timeoutMs, String message) {
        Promise<T> result = Promise.promise();
        Vertx vertx = databaseService.getVertx();
        long timerId = vertx.setTimer(timeoutMs, ignored ->
                result.tryFail(new AssertionError(message + " within " + timeoutMs + "ms")));
        operation
                .onSuccess(value -> {
                    if (result.tryComplete(value)) {
                        vertx.cancelTimer(timerId);
                    }
                })
                .onFailure(failure -> {
                    if (result.tryFail(failure)) {
                        vertx.cancelTimer(timerId);
                    }
                });
        return result.future();
    }

    private Future<Throwable> expectFailure(Future<?> operation) {
        Promise<Throwable> result = Promise.promise();
        operation
                .onSuccess(v -> result.tryFail(new AssertionError("Expected the operation to fail")))
                .onFailure(result::tryComplete);
        return result.future();
    }

    private Future<Void> closeGroup(PgNativeConsumerGroup<?> group) {
        return group == null ? Future.succeededFuture() : group.close();
    }
}
