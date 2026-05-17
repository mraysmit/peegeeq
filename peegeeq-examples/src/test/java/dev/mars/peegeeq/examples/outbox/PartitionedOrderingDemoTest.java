package dev.mars.peegeeq.examples.outbox;

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

import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import java.util.Properties;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demo example tests for OFFSET_WATERMARK partitioned consumption Phase 3 of the
 * ordering plan in
 * {@code docs-design/analysis/GUARANTEED_ORDERING_CONCURRENT_CONSUMERS_ANALYSIS.md}.
 *
 * <p>Per Phase 7 of that analysis, OFFSET_WATERMARK is supported end-to-end by the
 * outbox subsystem today (the {@code PartitionedConsumerEngine} reads from the
 * {@code outbox} table). These tests therefore live in the outbox examples package
 * and use the outbox factory.</p>
 *
 * <p>Tests:</p>
 * <ul>
 *   <li><b>2a</b> {@code testPartitionedOrdering_eventsPerAggregateInOrder} —
 *       3 aggregates × 5 events. Per-aggregate version order is strictly ascending
 *       at the consumer.</li>
 *   <li><b>2b</b> {@code testPartitionedOrdering_differentAggregatesProcessedConcurrently} —
 *       2 aggregates × 3 slow events each. Total elapsed time is well under the
 *       serialised baseline.</li>
 *   <li><b>2c</b> {@code testPartitionedOrdering_defaultPartition_noMessageGroup} —
 *       producer sends without {@code messageGroup}. Only the {@code __default__}
 *       partition assignment exists; messages still arrive in producer order.</li>
 *   <li><b>2d</b> {@code testPartitionedOrdering_idempotentRedelivery} consumer
 *       group processes a batch, then is stopped and restarted. The committed
 *       offset prevents re-delivery of already-processed messages.</li>
 * </ul>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@DisplayName("Partitioned ordering demo (OFFSET_WATERMARK)")
class PartitionedOrderingDemoTest {

    private static final Logger logger = LoggerFactory.getLogger(PartitionedOrderingDemoTest.class);

    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.configureSharedProperties(registry);
    }

    private PeeGeeQManager manager;
    private PgDatabaseService databaseService;
    private QueueFactory factory;

    /** Minimal aggregate event payload id + version + payload string. */
    static class AggEvent {
        private String aggregateId;
        private long version;
        private String data;

        public AggEvent() {}

        public AggEvent(String aggregateId, long version, String data) {
            this.aggregateId = aggregateId;
            this.version = version;
            this.data = data;
        }

        public String getAggregateId() { return aggregateId; }
        public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }
        public long getVersion() { return version; }
        public void setVersion(long version) { this.version = version; }
        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
    }

    @BeforeEach
    void setUp(VertxTestContext ctx) {
        // ALL covers outbox + consumer-group/fanout subscription tables.
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres).build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        manager.start()
            .onSuccess(v -> {
                databaseService = new PgDatabaseService(manager);
                QueueFactoryProvider provider = new PgQueueFactoryProvider();
                OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                factory = provider.createFactory("outbox", (DatabaseService) databaseService);
                ctx.completeNow();
            })
            .onFailure(ctx::failNow);
    }

    @AfterEach
    void tearDown(Vertx vertx, VertxTestContext ctx) {
        if (manager == null) {
            ctx.completeNow();
            return;
        }
        // Close manager, then settle 2s so connection pools fully release before the next test.
        Future<Void> closeChain = manager.closeReactive()
            .onFailure(err -> logger.warn("Error closing manager: {}", err.getMessage()))
            .eventually(() -> vertx.timer(2000).<Void>mapEmpty());
        closeChain.onSuccess(v -> ctx.completeNow());
        closeChain.onFailure(err -> ctx.completeNow());
    }

    // ------------------------------------------------------------------
    // Test 2a per-aggregate ordering across multiple aggregates.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("2a per-aggregate version order strictly ascending")
    void testPartitionedOrdering_eventsPerAggregateInOrder(Vertx vertx, VertxTestContext testContext) {
        String topic = "pod-2a-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "pod-2a-group";

        int aggregates = 3;
        int eventsPerAggregate = 5;
        int total = aggregates * eventsPerAggregate;

        List<String> aggIds = new ArrayList<>();
        for (int i = 0; i < aggregates; i++) {
            aggIds.add("agg-" + i);
        }

        MessageProducer<AggEvent> producer = factory.createProducer(topic, AggEvent.class);
        ConsumerGroup<AggEvent> group = factory.createConsumerGroup(groupName, topic, AggEvent.class);

        Map<String, List<Long>> received = new ConcurrentHashMap<>();
        for (String id : aggIds) {
            received.put(id, Collections.synchronizedList(new ArrayList<>()));
        }
        Set<String> seen = ConcurrentHashMap.newKeySet();
        Promise<Void> allReceived = Promise.promise();
        AtomicInteger arrivalCount = new AtomicInteger(0);

        group.setMessageHandler(message -> {
            AggEvent ev = message.getPayload();
            String dedupeKey = ev.getAggregateId() + ":v" + ev.getVersion();
            if (!seen.add(dedupeKey)) {
                // OFFSET_WATERMARK is at-least-once.
                return Future.succeededFuture();
            }
            received.get(ev.getAggregateId()).add(ev.getVersion());
            if (arrivalCount.incrementAndGet() == total) {
                allReceived.tryComplete();
            }
            return Future.succeededFuture();
        });

        // Send all events first (interleaved across aggregates), then start the group,
        // then await arrival, assert, and stop the group — all as one composed chain.
        configureOffsetWatermarkTopic(topic, groupName)
                .compose(cfg -> {
                    Future<Void> sendChain = Future.succeededFuture();
                    for (int v = 1; v <= eventsPerAggregate; v++) {
                        for (String aggId : aggIds) {
                            final long version = v;
                            AggEvent ev = new AggEvent(aggId, version, "payload-" + aggId + "-v" + version);
                            sendChain = sendChain.compose(x -> producer.send(ev, null, null, aggId));
                        }
                    }
                    return sendChain;
                })
                .compose(v -> group.start(SubscriptionOptions.fromBeginning()))
                .compose(v -> allReceived.future())
                .compose(v -> {
                    for (String aggId : aggIds) {
                        List<Long> versions = received.get(aggId);
                        assertEquals(eventsPerAggregate, versions.size(),
                                "aggregate " + aggId + " should receive every event");
                        for (int i = 0; i < eventsPerAggregate; i++) {
                            assertEquals((long) (i + 1), (long) versions.get(i),
                                    "aggregate " + aggId + " out-of-order at position " + i);
                        }
                    }
                    producer.close();
                    return group.stopGracefully()
                            .onFailure(err -> logger.warn("stopGracefully error: {}", err.getMessage()))
                            .transform(ar -> Future.<Void>succeededFuture());
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    // ------------------------------------------------------------------
    // Test 2b different aggregates processed concurrently.
    //
    // Strategy: assert temporal overlap between the two partitions. Under
    // concurrent per-partition dispatch the engine's fetch loop starts both
    // partitions on the same tick and their handlers run interleaved, so each
    // partition's first message arrives BEFORE the other's last message. Under
    // serial dispatch one partition would finish before the other started.
    // We deliberately avoid asserting absolute wall-clock windows because the
    // engine's 1 s fetch tick (DEFAULT_FETCH_INTERVAL_MS) dominates handler time.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("2b different aggregates processed concurrently")
    void testPartitionedOrdering_differentAggregatesProcessedConcurrently(
            Vertx vertx, VertxTestContext testContext) {
        String topic = "pod-2b-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "pod-2b-group";

        int eventsPerAggregate = 3;
        long handlerDelayMs = 50L;
        List<String> aggIds = List.of("agg-X", "agg-Y");
        int total = aggIds.size() * eventsPerAggregate;

        MessageProducer<AggEvent> producer = factory.createProducer(topic, AggEvent.class);
        ConsumerGroup<AggEvent> group = factory.createConsumerGroup(groupName, topic, AggEvent.class);

        Map<String, List<Long>> received = new ConcurrentHashMap<>();
        Map<String, AtomicLong> firstAt = new ConcurrentHashMap<>();
        Map<String, AtomicLong> lastAt = new ConcurrentHashMap<>();
        for (String id : aggIds) {
            received.put(id, Collections.synchronizedList(new ArrayList<>()));
            firstAt.put(id, new AtomicLong(0));
            lastAt.put(id, new AtomicLong(0));
        }
        Set<String> seen = ConcurrentHashMap.newKeySet();
        Promise<Void> allReceived = Promise.promise();
        AtomicInteger arrivalCount = new AtomicInteger(0);

        group.setMessageHandler(message -> {
            AggEvent ev = message.getPayload();
            String dedupeKey = ev.getAggregateId() + ":v" + ev.getVersion();
            if (!seen.add(dedupeKey)) {
                return Future.succeededFuture();
            }
            long now = System.currentTimeMillis();
            firstAt.get(ev.getAggregateId()).compareAndSet(0, now);
            received.get(ev.getAggregateId()).add(ev.getVersion());

            return vertx.timer(handlerDelayMs)
                    .onSuccess(id -> {
                        lastAt.get(ev.getAggregateId()).set(System.currentTimeMillis());
                        if (arrivalCount.incrementAndGet() == total) {
                            allReceived.tryComplete();
                        }
                    })
                    .mapEmpty();
        });

        Future<Void> sendChain2b = Future.succeededFuture();
        for (int v = 1; v <= eventsPerAggregate; v++) {
            for (String aggId : aggIds) {
                final long version = v;
                AggEvent ev = new AggEvent(aggId, version, "p-" + aggId + "-v" + version);
                sendChain2b = sendChain2b.compose(x -> producer.send(ev, null, null, aggId));
            }
        }
        final Future<Void> sendChain2bFinal = sendChain2b;
        configureOffsetWatermarkTopic(topic, groupName)
                .compose(cfg -> sendChain2bFinal)
                .compose(v -> group.start(SubscriptionOptions.fromBeginning()))
                .compose(v -> allReceived.future())
                .compose(v -> {
                    // Per-aggregate ordering still holds.
                    for (String aggId : aggIds) {
                        List<Long> versions = received.get(aggId);
                        assertEquals(eventsPerAggregate, versions.size(),
                                "aggregate " + aggId + " should receive every event");
                        for (int i = 0; i < eventsPerAggregate; i++) {
                            assertEquals((long) (i + 1), (long) versions.get(i),
                                    "aggregate " + aggId + " out-of-order at position " + i);
                        }
                    }

                    // Concurrency assertion: temporal overlap between the two partitions.
                    // Under serial dispatch, one partition would fully complete before the
                    // other started, i.e. lastAt(A) <= firstAt(B) (or vice versa).
                    long firstA = firstAt.get(aggIds.get(0)).get();
                    long lastA  = lastAt.get(aggIds.get(0)).get();
                    long firstB = firstAt.get(aggIds.get(1)).get();
                    long lastB  = lastAt.get(aggIds.get(1)).get();
                    logger.info("2b partition windows: {}=[{}..{}] (Δ={} ms), {}=[{}..{}] (Δ={} ms)",
                            aggIds.get(0), firstA, lastA, lastA - firstA,
                            aggIds.get(1), firstB, lastB, lastB - firstB);
                    boolean overlap = (firstA < lastB) && (firstB < lastA);
                    assertTrue(overlap,
                            "partitions should be processed concurrently (temporally overlapping); "
                                    + "got " + aggIds.get(0) + "=[" + firstA + ".." + lastA + "], "
                                    + aggIds.get(1) + "=[" + firstB + ".." + lastB + "]");

                    producer.close();
                    return group.stopGracefully()
                            .onFailure(err -> logger.warn("stopGracefully error: {}", err.getMessage()))
                            .transform(ar -> Future.<Void>succeededFuture());
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    // ------------------------------------------------------------------
    // Test 2c sending without messageGroup funnels to __default__ partition.
    //
    // Producer omits the messageGroup. Per the analysis doc's "__default__
    // Partition" section, all such messages share a single synthetic partition
    // and are processed serially. Asserts:
    //   1. outbox_partition_assignments has exactly 1 row for (topic, group)
    //      with partition_key = '__default__'.
    //   2. All 5 messages are received in send order.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("2c no messageGroup → __default__ partition serial order")
    void testPartitionedOrdering_defaultPartition_noMessageGroup(
            Vertx vertx, VertxTestContext testContext) {
        String topic = "pod-2c-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "pod-2c-group";

        int total = 5;
        MessageProducer<AggEvent> producer = factory.createProducer(topic, AggEvent.class);
        ConsumerGroup<AggEvent> group = factory.createConsumerGroup(groupName, topic, AggEvent.class);

        List<Long> received = Collections.synchronizedList(new ArrayList<>());
        Set<String> seen = ConcurrentHashMap.newKeySet();
        Promise<Void> allReceived = Promise.promise();
        AtomicInteger arrivalCount = new AtomicInteger(0);

        group.setMessageHandler(message -> {
            AggEvent ev = message.getPayload();
            String dedupeKey = "v" + ev.getVersion();
            if (!seen.add(dedupeKey)) {
                return Future.succeededFuture();
            }
            received.add(ev.getVersion());
            if (arrivalCount.incrementAndGet() == total) {
                allReceived.tryComplete();
            }
            return Future.succeededFuture();
        });

        // Send all 5 messages WITHOUT messageGroup, then start consumer.
        Future<Void> sendChain2c = Future.succeededFuture();
        for (long v = 1; v <= total; v++) {
            final long version = v;
            AggEvent ev = new AggEvent("default-agg", version, "p-default-v" + version);
            // Note: send(payload) no messageGroup, no headers, no correlationId.
            sendChain2c = sendChain2c.compose(x -> producer.send(ev));
        }
        final Future<Void> sendChain2cFinal = sendChain2c;
        configureOffsetWatermarkTopic(topic, groupName)
                .compose(cfg -> sendChain2cFinal)
                .compose(v -> group.start(SubscriptionOptions.fromBeginning()))
                .compose(v -> allReceived.future())
                .compose(v -> {
                    // Assertion 1: serial order preserved.
                    assertEquals(total, received.size(), "should receive every message exactly once");
                    for (int i = 0; i < total; i++) {
                        assertEquals((long) (i + 1), (long) received.get(i),
                                "__default__ partition messages out-of-order at position " + i);
                    }
                    // Assertion 2: outbox_partition_assignments has exactly one row
                    // with partition_key = '__default__' for this (topic, group).
                    return databaseService.getPool()
                            .withConnection(conn -> conn.preparedQuery(
                                    "SELECT COUNT(*) AS cnt FROM outbox_partition_assignments " +
                                            "WHERE topic = $1 AND group_name = $2 " +
                                            "AND partition_key = '__default__'")
                                    .execute(Tuple.of(topic, groupName))
                                    .map(rs -> rs.iterator().next().getLong("cnt")));
                })
                .compose(count -> {
                    assertEquals(1L, (long) count,
                            "should be exactly one __default__ partition assignment for (topic, group); got " + count);
                    producer.close();
                    return group.stopGracefully()
                            .onFailure(err -> logger.warn("stopGracefully error: {}", err.getMessage()))
                            .transform(ar -> Future.<Void>succeededFuture());
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    // ------------------------------------------------------------------
    // Test 2d idempotent redelivery: committed offset prevents re-delivery.
    //
    // Sends a first batch of 3 messages, starts the consumer group, processes
    // them, and stops it gracefully (committing the offset). Then sends a
    // second batch of 3 messages, starts a new consumer group with the same
    // (topic, groupName), and asserts:
    //   1. Only the second batch is delivered (versions 4–6); the first batch
    //      (versions 1–3) is not redelivered.
    //   2. Second batch arrives in send order.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("2d committed offset prevents redelivery after restart")
    void testPartitionedOrdering_idempotentRedelivery(
            Vertx vertx, VertxTestContext testContext) {
        String topic = "pod-2d-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "pod-2d-group";

        MessageProducer<AggEvent> producer = factory.createProducer(topic, AggEvent.class);

        // --- First batch: versions 1–3 ---
        int firstBatch = 3;
        ConsumerGroup<AggEvent> group1 = factory.createConsumerGroup(groupName, topic, AggEvent.class);
        List<Long> received1 = Collections.synchronizedList(new ArrayList<>());
        Set<String> seen1 = ConcurrentHashMap.newKeySet();
        Promise<Void> batch1Received = Promise.promise();
        AtomicInteger batch1Count = new AtomicInteger(0);

        group1.setMessageHandler(message -> {
            AggEvent ev = message.getPayload();
            if (seen1.add("v" + ev.getVersion())) {
                received1.add(ev.getVersion());
                if (batch1Count.incrementAndGet() == firstBatch) {
                    batch1Received.tryComplete();
                }
            }
            return Future.succeededFuture();
        });

        // --- Second batch: versions 4–6 ---
        int secondBatch = 3;
        ConsumerGroup<AggEvent> group2 = factory.createConsumerGroup(groupName, topic, AggEvent.class);
        List<Long> received2 = Collections.synchronizedList(new ArrayList<>());
        Set<String> seen2 = ConcurrentHashMap.newKeySet();
        Promise<Void> batch2Received = Promise.promise();
        AtomicInteger batch2Count = new AtomicInteger(0);

        group2.setMessageHandler(message -> {
            AggEvent ev = message.getPayload();
            if (seen2.add("v" + ev.getVersion())) {
                received2.add(ev.getVersion());
                if (batch2Count.incrementAndGet() == secondBatch) {
                    batch2Received.tryComplete();
                }
            }
            return Future.succeededFuture();
        });

        Future<Void> sendBatch1 = Future.succeededFuture();
        for (long v = 1; v <= firstBatch; v++) {
            final long version = v;
            AggEvent ev = new AggEvent("agg-2d", version, "batch1-v" + version);
            sendBatch1 = sendBatch1.compose(x -> producer.send(ev, null, null, "agg-2d"));
        }
        final Future<Void> sendBatch1Final = sendBatch1;

        configureOffsetWatermarkTopic(topic, groupName)
                .compose(cfg -> sendBatch1Final)
                .compose(v -> group1.start(SubscriptionOptions.fromBeginning()))
                .compose(v -> batch1Received.future())
                .compose(v -> {
                    assertEquals(firstBatch, received1.size(),
                            "first batch: should receive every message exactly once");
                    for (int i = 0; i < firstBatch; i++) {
                        assertEquals((long) (i + 1), (long) received1.get(i),
                                "first batch out-of-order at position " + i);
                    }
                    // Stop group1 gracefully — this commits the offset so a new consumer starts after message 3.
                    return group1.stopGracefully()
                            .onFailure(err -> logger.warn("group1 stopGracefully error: {}", err.getMessage()))
                            .transform(ar -> Future.<Void>succeededFuture());
                })
                .compose(v -> {
                    // Re-activate the subscription so group2 can resume from the committed offset.
                    // (stopGracefully cancels the subscription as part of lifecycle management;
                    // re-activation simulates a consumer restart without resetting committed offsets.)
                    return databaseService.getPool()
                            .withConnection(conn -> conn.preparedQuery(
                                    "UPDATE outbox_topic_subscriptions SET subscription_status = 'ACTIVE'" +
                                    " WHERE topic = $1 AND group_name = $2")
                                    .execute(Tuple.of(topic, groupName)))
                            .<Void>mapEmpty();
                })
                .compose(v -> {
                    Future<Void> sendBatch2 = Future.succeededFuture();
                    for (long ver = firstBatch + 1; ver <= firstBatch + secondBatch; ver++) {
                        final long version = ver;
                        AggEvent ev = new AggEvent("agg-2d", version, "batch2-v" + version);
                        sendBatch2 = sendBatch2.compose(x -> producer.send(ev, null, null, "agg-2d"));
                    }
                    return sendBatch2;
                })
                .compose(v -> group2.start(SubscriptionOptions.fromBeginning()))
                .compose(v -> batch2Received.future())
                .compose(v -> {
                    // Assertion 1: only second batch delivered (no redelivery of first batch).
                    assertEquals(secondBatch, received2.size(),
                            "second batch: should receive exactly " + secondBatch + " messages; got " + received2.size());
                    for (Long ver : received2) {
                        assertTrue(ver > firstBatch,
                                "redelivery detected: version " + ver + " is from the first batch");
                    }
                    // Assertion 2: second batch arrives in send order.
                    for (int i = 0; i < secondBatch; i++) {
                        assertEquals((long) (firstBatch + i + 1), (long) received2.get(i),
                                "second batch out-of-order at position " + i);
                    }
                    producer.close();
                    return group2.stopGracefully()
                            .onFailure(err -> logger.warn("group2 stopGracefully error: {}", err.getMessage()))
                            .transform(ar -> Future.<Void>succeededFuture());
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    /**
     * Configure a topic for OFFSET_WATERMARK and register a consumer-group subscription.
     * This is the canonical setup required by {@code PartitionedConsumerEngine}.
     */
    private Future<Void> configureOffsetWatermarkTopic(String topic, String groupName) {
        return databaseService.getPool()
                .withConnection(conn -> conn.preparedQuery(
                        "INSERT INTO outbox_topics (topic, semantics, completion_tracking_mode) " +
                        "VALUES ($1, 'PUB_SUB', 'OFFSET_WATERMARK') " +
                        "ON CONFLICT (topic) DO UPDATE SET completion_tracking_mode = 'OFFSET_WATERMARK'")
                        .execute(Tuple.of(topic))
                        .compose(r -> conn.preparedQuery(
                                "INSERT INTO outbox_topic_subscriptions (topic, group_name, subscription_status) " +
                                "VALUES ($1, $2, 'ACTIVE') " +
                                "ON CONFLICT (topic, group_name) DO NOTHING")
                                .execute(Tuple.of(topic, groupName)))
                        .mapEmpty());
    }
}
