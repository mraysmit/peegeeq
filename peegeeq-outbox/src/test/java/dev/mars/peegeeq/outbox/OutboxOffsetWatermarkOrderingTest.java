package dev.mars.peegeeq.outbox;

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
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Core verification tests for OFFSET_WATERMARK partitioned consumption in the outbox subsystem.
 *
 * <p>These tests live in {@code peegeeq-outbox} (alongside the other outbox integration tests)
 * and complement the higher-level event-sourcing/CQRS demo in
 * {@code peegeeq-examples/.../outbox/EventSourcingCQRSDemoTest}. They exercise the
 * OFFSET_WATERMARK contract directly against {@link MessageProducer} +
 * {@link ConsumerGroup}, without any aggregate/CQRS scaffolding:
 *
 * <ol>
 *   <li><b>Per-key ordering</b> messages with the same {@code messageGroup} are delivered
 *       to the consumer in production order, even when interleaved with sends for a
 *       different key.</li>
 *   <li><b>Per-key isolation across multiple partitions</b> three independent keys, each
 *       receives only its own messages in production order; total count matches sends.</li>
 *   <li><b>Documented misconfiguration: no {@code messageGroup}</b> when a topic is
 *       configured as OFFSET_WATERMARK but the producer does not set a
 *       {@code messageGroup}, all messages funnel through the {@code __default__} partition
 *       and are delivered serially in producer order. This locks in the documented trap
 *       described in
 *       {@code docs-design/analysis/GUARANTEED_ORDERING_CONCURRENT_CONSUMERS_ANALYSIS.md}.</li>
 * </ol>
 *
 * <p>Vert.x 5 reactive only no {@code CompletableFuture}, no blocking
 * {@code .get()}/{@code .join()}, no {@code Thread.sleep}.</p>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@DisplayName("Outbox OFFSET_WATERMARK core ordering verification")
class OutboxOffsetWatermarkOrderingTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxOffsetWatermarkOrderingTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private PgDatabaseService databaseService;
    private QueueFactory factory;

    @BeforeEach
    void setUp() {
        // Schema: outbox tables + the consumer-group/fanout tables that hold
        // outbox_topics and outbox_topic_subscriptions used by OFFSET_WATERMARK.
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres,
                SchemaComponent.QUEUE_ALL,
                SchemaComponent.CONSUMER_GROUP_FANOUT);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .property("peegeeq.queue.polling-interval", "PT0.5S")
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        factory = provider.createFactory("outbox", (DatabaseService) databaseService);
    }

    @AfterEach
    void tearDown(Vertx vertx) {
        if (factory != null) {
            try {
                factory.close();
            } catch (Exception e) {
                logger.warn("Error closing factory: {}", e.getMessage());
            }
        }
        if (manager != null) {
            try {
                manager.closeReactive().await();
            } catch (Exception e) {
                logger.warn("Error closing manager: {}", e.getMessage());
            }
        }
        // Brief settle so connection pools fully release before the next test.
        Promise<Void> delay = Promise.promise();
        vertx.setTimer(500, id -> delay.complete());
        delay.future().await();
    }

    // ------------------------------------------------------------------
    // Test 1: Per-key ordering with two interleaved keys.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("OFFSET_WATERMARK preserves per-key order when two keys are interleaved")
    void perKeyOrdering_twoKeysInterleaved(Vertx vertx, VertxTestContext testContext) throws Exception {
        String topic = "owm-pkorder-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "owm-pkorder-group";

        configureOffsetWatermarkTopic(topic, groupName).await();

        int perKey = 10;
        int total = perKey * 2;

        MessageProducer<String> producer = factory.createProducer(topic, String.class);
        ConsumerGroup<String> group = factory.createConsumerGroup(groupName, topic, String.class);

        Map<String, List<String>> received = new ConcurrentHashMap<>();
        received.put("A", Collections.synchronizedList(new ArrayList<>()));
        received.put("B", Collections.synchronizedList(new ArrayList<>()));
        Set<String> seen = ConcurrentHashMap.newKeySet();
        Checkpoint checkpoint = testContext.checkpoint(total);

        group.setMessageHandler(message -> {
            String payload = message.getPayload();
            if (!seen.add(payload)) {
                // OFFSET_WATERMARK is at-least-once dedupe redeliveries.
                return Future.succeededFuture();
            }
            String key = payload.substring(0, 1);
            received.get(key).add(payload);
            checkpoint.flag();
            return Future.succeededFuture();
        });

        // Send all messages first, interleaved across keys A and B, then start the
        // consumer group. With FROM_BEGINNING the group discovers existing partitions
        // at join time and replays from the start.
        Future<Void> sendChain = Future.succeededFuture();
        for (int i = 0; i < perKey; i++) {
            String aPayload = "A-" + i;
            String bPayload = "B-" + i;
            sendChain = sendChain
                    .compose(v -> producer.send(aPayload, null, null, "A"))
                    .compose(v -> producer.send(bPayload, null, null, "B"));
        }
        sendChain
                .compose(v -> group.start(SubscriptionOptions.fromBeginning()))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "Should receive all " + total + " messages within timeout");

        // Per-key ordering assertions.
        assertEquals(perKey, received.get("A").size(), "key A should receive all its messages");
        assertEquals(perKey, received.get("B").size(), "key B should receive all its messages");
        for (int i = 0; i < perKey; i++) {
            assertEquals("A-" + i, received.get("A").get(i),
                    "key A out-of-order at index " + i);
            assertEquals("B-" + i, received.get("B").get(i),
                    "key B out-of-order at index " + i);
        }

        producer.close();
        group.stopGracefully().await();
    }

    // ------------------------------------------------------------------
    // Test 2: Per-key isolation with multiple partitions in flight together.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("OFFSET_WATERMARK preserves per-key order across three independent keys")
    void perKeyOrdering_threeKeys(Vertx vertx, VertxTestContext testContext) throws Exception {
        String topic = "owm-multi-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "owm-multi-group";

        configureOffsetWatermarkTopic(topic, groupName).await();

        List<String> keys = List.of("X", "Y", "Z");
        int perKey = 8;
        int total = keys.size() * perKey;

        MessageProducer<String> producer = factory.createProducer(topic, String.class);
        ConsumerGroup<String> group = factory.createConsumerGroup(groupName, topic, String.class);

        Map<String, List<String>> received = new ConcurrentHashMap<>();
        for (String k : keys) {
            received.put(k, Collections.synchronizedList(new ArrayList<>()));
        }
        Set<String> seen = ConcurrentHashMap.newKeySet();
        Checkpoint checkpoint = testContext.checkpoint(total);

        group.setMessageHandler(message -> {
            String payload = message.getPayload();
            if (!seen.add(payload)) {
                return Future.succeededFuture();
            }
            String key = payload.substring(0, 1);
            received.get(key).add(payload);
            checkpoint.flag();
            return Future.succeededFuture();
        });

        // Round-robin sends across all three keys.
        Future<Void> sendChain = Future.succeededFuture();
        for (int i = 0; i < perKey; i++) {
            for (String k : keys) {
                String payload = k + "-" + i;
                sendChain = sendChain.compose(v -> producer.send(payload, null, null, k));
            }
        }
        sendChain
                .compose(v -> group.start(SubscriptionOptions.fromBeginning()))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "Should receive all " + total + " messages within timeout");

        for (String k : keys) {
            List<String> list = received.get(k);
            assertEquals(perKey, list.size(), "key " + k + " should receive all its messages");
            for (int i = 0; i < perKey; i++) {
                assertEquals(k + "-" + i, list.get(i),
                        "key " + k + " out-of-order at index " + i);
            }
        }

        producer.close();
        group.stopGracefully().await();
    }

    // ------------------------------------------------------------------
    // Test 3: Documented trap OFFSET_WATERMARK without messageGroup.
    // All messages are routed to the `__default__` partition and therefore
    // arrive serially in producer order. This test locks in that behavior.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("OFFSET_WATERMARK without messageGroup funnels to __default__ partition (serial order)")
    void noMessageGroup_funnelsToDefaultPartition(Vertx vertx, VertxTestContext testContext) throws Exception {
        String topic = "owm-default-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "owm-default-group";

        configureOffsetWatermarkTopic(topic, groupName).await();

        int total = 10;

        MessageProducer<String> producer = factory.createProducer(topic, String.class);
        ConsumerGroup<String> group = factory.createConsumerGroup(groupName, topic, String.class);

        List<String> received = Collections.synchronizedList(new ArrayList<>());
        Set<String> seen = ConcurrentHashMap.newKeySet();
        Checkpoint checkpoint = testContext.checkpoint(total);

        group.setMessageHandler(message -> {
            String payload = message.getPayload();
            if (!seen.add(payload)) {
                return Future.succeededFuture();
            }
            received.add(payload);
            checkpoint.flag();
            return Future.succeededFuture();
        });

        // No messageGroup → all rows funnel through the `__default__` partition.
        Future<Void> sendChain = Future.succeededFuture();
        for (int i = 0; i < total; i++) {
            String payload = "msg-" + i;
            sendChain = sendChain.compose(v -> producer.send(payload));
        }
        sendChain
                .compose(v -> group.start(SubscriptionOptions.fromBeginning()))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "Should receive all " + total + " messages within timeout");

        // Single-partition serial delivery → producer order is preserved.
        assertEquals(total, received.size(), "should receive all messages");
        for (int i = 0; i < total; i++) {
            assertEquals("msg-" + i, received.get(i),
                    "default-partition delivery out-of-order at index " + i);
        }

        producer.close();
        group.stopGracefully().await();
    }

    /**
     * Configure a topic for OFFSET_WATERMARK completion tracking and register a
     * consumer-group subscription. This is the canonical setup required by
     * {@code PartitionedConsumerEngine}.
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
