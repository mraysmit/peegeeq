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
import io.vertx.junit5.Checkpoint;
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
import java.util.concurrent.TimeUnit;
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
    void setUp() {
        // ALL covers outbox + consumer-group/fanout subscription tables.
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);

        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("development");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        factory = provider.createFactory("outbox", (DatabaseService) databaseService);
    }

    @AfterEach
    void tearDown(Vertx vertx) {
        if (manager != null) {
            try {
                manager.closeReactive().await();
            } catch (Exception e) {
                logger.warn("Error closing manager: {}", e.getMessage());
            }
            // Settle so connection pools fully release before the next test.
            Promise<Void> delay = Promise.promise();
            vertx.setTimer(2000, id -> delay.complete());
            delay.future().await();
        }
        System.clearProperty("peegeeq.database.url");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
    }

    // ------------------------------------------------------------------
    // Test 2a per-aggregate ordering across multiple aggregates.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("2a per-aggregate version order strictly ascending")
    void testPartitionedOrdering_eventsPerAggregateInOrder(Vertx vertx, VertxTestContext testContext) throws Exception {
        String topic = "pod-2a-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "pod-2a-group";
        configureOffsetWatermarkTopic(topic, groupName).await();

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
        Checkpoint checkpoint = testContext.checkpoint(total);

        group.setMessageHandler(message -> {
            AggEvent ev = message.getPayload();
            String dedupeKey = ev.getAggregateId() + ":v" + ev.getVersion();
            if (!seen.add(dedupeKey)) {
                // OFFSET_WATERMARK is at-least-once.
                return Future.succeededFuture();
            }
            received.get(ev.getAggregateId()).add(ev.getVersion());
            checkpoint.flag();
            return Future.succeededFuture();
        });

        // Send all events first (interleaved across aggregates), then start the group.
        Future<Void> sendChain = Future.succeededFuture();
        for (int v = 1; v <= eventsPerAggregate; v++) {
            for (String aggId : aggIds) {
                final long version = v;
                AggEvent ev = new AggEvent(aggId, version, "payload-" + aggId + "-v" + version);
                sendChain = sendChain.compose(x -> producer.send(ev, null, null, aggId));
            }
        }
        sendChain
                .compose(v -> group.start(SubscriptionOptions.fromBeginning()))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "should process all " + total + " events within timeout");

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
        group.stopGracefully().await();
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
            Vertx vertx, VertxTestContext testContext) throws Exception {
        String topic = "pod-2b-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "pod-2b-group";
        configureOffsetWatermarkTopic(topic, groupName).await();

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
        Checkpoint checkpoint = testContext.checkpoint(total);

        group.setMessageHandler(message -> {
            AggEvent ev = message.getPayload();
            String dedupeKey = ev.getAggregateId() + ":v" + ev.getVersion();
            if (!seen.add(dedupeKey)) {
                return Future.succeededFuture();
            }
            long now = System.currentTimeMillis();
            firstAt.get(ev.getAggregateId()).compareAndSet(0, now);
            received.get(ev.getAggregateId()).add(ev.getVersion());

            Promise<Void> p = Promise.promise();
            vertx.setTimer(handlerDelayMs, id -> {
                lastAt.get(ev.getAggregateId()).set(System.currentTimeMillis());
                checkpoint.flag();
                p.complete();
            });
            return p.future();
        });

        Future<Void> sendChain = Future.succeededFuture();
        for (int v = 1; v <= eventsPerAggregate; v++) {
            for (String aggId : aggIds) {
                final long version = v;
                AggEvent ev = new AggEvent(aggId, version, "p-" + aggId + "-v" + version);
                sendChain = sendChain.compose(x -> producer.send(ev, null, null, aggId));
            }
        }
        sendChain
                .compose(v -> group.start(SubscriptionOptions.fromBeginning()))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "should process all " + total + " events within timeout");

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
        group.stopGracefully().await();
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
            Vertx vertx, VertxTestContext testContext) throws Exception {
        String topic = "pod-2c-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "pod-2c-group";
        configureOffsetWatermarkTopic(topic, groupName).await();

        int total = 5;
        MessageProducer<AggEvent> producer = factory.createProducer(topic, AggEvent.class);
        ConsumerGroup<AggEvent> group = factory.createConsumerGroup(groupName, topic, AggEvent.class);

        List<Long> received = Collections.synchronizedList(new ArrayList<>());
        Set<String> seen = ConcurrentHashMap.newKeySet();
        Checkpoint checkpoint = testContext.checkpoint(total);

        group.setMessageHandler(message -> {
            AggEvent ev = message.getPayload();
            String dedupeKey = "v" + ev.getVersion();
            if (!seen.add(dedupeKey)) {
                return Future.succeededFuture();
            }
            received.add(ev.getVersion());
            checkpoint.flag();
            return Future.succeededFuture();
        });

        // Send all 5 messages WITHOUT messageGroup, then start consumer.
        Future<Void> sendChain = Future.succeededFuture();
        for (long v = 1; v <= total; v++) {
            final long version = v;
            AggEvent ev = new AggEvent("default-agg", version, "p-default-v" + version);
            // Note: send(payload) no messageGroup, no headers, no correlationId.
            sendChain = sendChain.compose(x -> producer.send(ev));
        }
        sendChain
                .compose(v -> group.start(SubscriptionOptions.fromBeginning()))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "should process all " + total + " events within timeout");

        // Assertion 1: serial order preserved.
        assertEquals(total, received.size(), "should receive every message exactly once");
        for (int i = 0; i < total; i++) {
            assertEquals((long) (i + 1), (long) received.get(i),
                    "__default__ partition messages out-of-order at position " + i);
        }

        // Assertion 2: outbox_partition_assignments has exactly one row
        // with partition_key = '__default__' for this (topic, group).
        Long count = databaseService.getPool()
                .withConnection(conn -> conn.preparedQuery(
                        "SELECT COUNT(*) AS cnt FROM outbox_partition_assignments " +
                                "WHERE topic = $1 AND group_name = $2 " +
                                "AND partition_key = '__default__'")
                        .execute(Tuple.of(topic, groupName))
                        .map(rs -> rs.iterator().next().getLong("cnt")))
                .await();
        assertEquals(1L, (long) count,
                "should be exactly one __default__ partition assignment for (topic, group); got " + count);

        producer.close();
        group.stopGracefully().await();
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
            Vertx vertx, VertxTestContext testContext) throws Exception {
        String topic = "pod-2d-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "pod-2d-group";
        configureOffsetWatermarkTopic(topic, groupName).await();

        MessageProducer<AggEvent> producer = factory.createProducer(topic, AggEvent.class);

        // --- First batch: versions 1–3 ---
        int firstBatch = 3;
        ConsumerGroup<AggEvent> group1 = factory.createConsumerGroup(groupName, topic, AggEvent.class);
        List<Long> received1 = Collections.synchronizedList(new ArrayList<>());
        Set<String> seen1 = ConcurrentHashMap.newKeySet();
        Checkpoint checkpoint1 = testContext.checkpoint(firstBatch);

        group1.setMessageHandler(message -> {
            AggEvent ev = message.getPayload();
            if (seen1.add("v" + ev.getVersion())) {
                received1.add(ev.getVersion());
                checkpoint1.flag();
            }
            return Future.succeededFuture();
        });

        Future<Void> sendBatch1 = Future.succeededFuture();
        for (long v = 1; v <= firstBatch; v++) {
            final long version = v;
            AggEvent ev = new AggEvent("agg-2d", version, "batch1-v" + version);
            sendBatch1 = sendBatch1.compose(x -> producer.send(ev, null, null, "agg-2d"));
        }
        sendBatch1
                .compose(v -> group1.start(SubscriptionOptions.fromBeginning()))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "first batch should be processed within timeout");
        assertEquals(firstBatch, received1.size(), "first batch: should receive every message exactly once");
        for (int i = 0; i < firstBatch; i++) {
            assertEquals((long) (i + 1), (long) received1.get(i),
                    "first batch out-of-order at position " + i);
        }

        // Stop group1 gracefully this commits the offset so a new consumer starts after message 3.
        group1.stopGracefully().await();

        // Re-activate the subscription so group2 can resume from the committed offset.
        // (stopGracefully cancels the subscription as part of lifecycle management;
        // re-activation simulates a consumer restart without resetting committed offsets.)
        databaseService.getPool()
                .withConnection(conn -> conn.preparedQuery(
                        "UPDATE outbox_topic_subscriptions SET subscription_status = 'ACTIVE'" +
                        " WHERE topic = $1 AND group_name = $2")
                        .execute(Tuple.of(topic, groupName)))
                .await();

        // --- Second batch: versions 4–6 ---
        int secondBatch = 3;
        ConsumerGroup<AggEvent> group2 = factory.createConsumerGroup(groupName, topic, AggEvent.class);
        List<Long> received2 = Collections.synchronizedList(new ArrayList<>());
        Set<String> seen2 = ConcurrentHashMap.newKeySet();
        // Reset the test context for the second round using a fresh VertxTestContext is not
        // possible mid-test; use an AtomicInteger latch pattern instead.
        java.util.concurrent.CountDownLatch latch2 = new java.util.concurrent.CountDownLatch(secondBatch);

        group2.setMessageHandler(message -> {
            AggEvent ev = message.getPayload();
            if (seen2.add("v" + ev.getVersion())) {
                received2.add(ev.getVersion());
                latch2.countDown();
            }
            return Future.succeededFuture();
        });

        Future<Void> sendBatch2 = Future.succeededFuture();
        for (long v = firstBatch + 1; v <= firstBatch + secondBatch; v++) {
            final long version = v;
            AggEvent ev = new AggEvent("agg-2d", version, "batch2-v" + version);
            sendBatch2 = sendBatch2.compose(x -> producer.send(ev, null, null, "agg-2d"));
        }
        sendBatch2
                .compose(v -> group2.start(SubscriptionOptions.fromBeginning()))
                .onFailure(err -> {
                    logger.error("group2 start failed: {}", err.getMessage());
                    // drain the latch on failure so the test terminates
                    while (latch2.getCount() > 0) latch2.countDown();
                });

        assertTrue(latch2.await(30, TimeUnit.SECONDS),
                "second batch should be processed within timeout");

        // Assertion 1: only second batch delivered (no redelivery of first batch).
        assertEquals(secondBatch, received2.size(),
                "second batch: should receive exactly " + secondBatch + " messages; got " + received2.size());
        for (Long v : received2) {
            assertTrue(v > firstBatch,
                    "redelivery detected: version " + v + " is from the first batch");
        }

        // Assertion 2: second batch arrives in send order.
        for (int i = 0; i < secondBatch; i++) {
            assertEquals((long) (firstBatch + i + 1), (long) received2.get(i),
                    "second batch out-of-order at position " + i);
        }

        producer.close();
        group2.stopGracefully().await();
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
