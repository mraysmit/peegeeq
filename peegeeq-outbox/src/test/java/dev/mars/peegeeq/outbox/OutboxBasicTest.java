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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Basic integration tests for outbox producer and consumer functionality.
 * Tests fundamental message sending and receiving capabilities.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class OutboxBasicTest {
    private static final Logger logger = LoggerFactory.getLogger(OutboxBasicTest.class);


    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        testTopic = "test-topic-" + UUID.randomUUID().toString().substring(0, 8);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.5S")
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
            .onSuccess(v -> {
                DatabaseService databaseService = new PgDatabaseService(manager);
                outboxFactory = new OutboxFactory(databaseService, config);
                producer = outboxFactory.createProducer(testTopic, String.class);
                consumer = outboxFactory.createConsumer(testTopic, String.class);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        if (consumer != null) {
            consumer.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
        }
        if (manager != null) {
            manager.closeReactive()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    void testBasicMessageProducerAndConsumer(VertxTestContext testContext) throws Exception {
        logger.info("Test: basic message producer and consumer");
        String testMessage = "Hello, Basic Outbox Test!";

        Checkpoint messageReceived = testContext.checkpoint();
        AtomicInteger receivedCount = new AtomicInteger(0);
        List<String> receivedMessages = new ArrayList<>();

        consumer.subscribe(message -> {
            receivedMessages.add(message.getPayload());
            receivedCount.incrementAndGet();
            messageReceived.flag();
            return Future.succeededFuture();
        });

        producer.send(testMessage).onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Message should be received within timeout");
        assertEquals(1, receivedCount.get(), "Should receive exactly one message");
        assertEquals(testMessage, receivedMessages.get(0), "Should receive the correct message");
    }

    @Test
    void testProcessingTimePercentilesExposedOnStats(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        // Telemetry G1 (Phase T.1) end to end: real consumption feeds the
        // per-topic histogram, and getStats carries the derived distribution —
        // the outbox mirror of the native test, same seam, same contract.
        logger.info("Test: processing-time percentiles exposed on stats");
        int messageCount = 20;

        // subscribe returns Future<Void>; observed, unlike the older tests in
        // this file whose bare calls are a pre-existing fire-and-forget
        // violation (flagged, not copied).
        consumer.subscribe(message -> Future.succeededFuture())
                .onFailure(testContext::failNow);

        for (int i = 0; i < messageCount; i++) {
            producer.send("Percentile message " + i).onFailure(testContext::failNow);
        }

        // The consumer records processing time at ack, after the handler
        // future settles — poll the stats until the distribution covers every
        // message, then verify its shape.
        io.vertx.core.Promise<dev.mars.peegeeq.api.messaging.QueueStats> covered =
                io.vertx.core.Promise.promise();
        long[] pollTimer = new long[1];
        pollTimer[0] = vertx.setPeriodic(100, id ->
            outboxFactory.getStats(testTopic)
                .onSuccess(stats -> {
                    var percentiles = stats.getProcessingTimePercentiles();
                    var delivery = stats.getDeliveryLatencyPercentiles();
                    // >= not ==: a retry legitimately adds a sample beyond the
                    // message count.
                    if (percentiles != null && percentiles.sampleCount() >= messageCount
                            && delivery != null && delivery.sampleCount() >= messageCount) {
                        covered.tryComplete(stats);
                    }
                })
                .onFailure(err -> logger.warn("Stats query failed while polling", err)));

        covered.future()
                .onSuccess(stats -> testContext.verify(() -> {
                    vertx.cancelTimer(pollTimer[0]);
                    var percentiles = stats.getProcessingTimePercentiles();
                    assertNotNull(percentiles);
                    assertTrue(percentiles.sampleCount() >= messageCount);
                    // Near-instant handlers can legitimately measure 0 ms, so
                    // the shape assertions are ordering, not magnitude.
                    assertTrue(percentiles.p50Ms() >= 0);
                    assertTrue(percentiles.p50Ms() <= percentiles.p95Ms());
                    assertTrue(percentiles.p95Ms() <= percentiles.p99Ms());
                    // The previously hardcoded 0.0 average is now the
                    // histogram mean — the two must agree exactly.
                    assertEquals(percentiles.meanMs(), stats.getAvgProcessingTimeMs(), 0.0001);
                    // Delivery latency (T.2): enqueue → claim on the DB clock.
                    // Outbox delivery is polling-based, so the tail reflects
                    // the polling interval — magnitude is not asserted, shape is.
                    var delivery = stats.getDeliveryLatencyPercentiles();
                    assertNotNull(delivery);
                    assertTrue(delivery.sampleCount() >= messageCount);
                    assertTrue(delivery.p50Ms() >= 0);
                    assertTrue(delivery.p50Ms() <= delivery.p95Ms());
                    assertTrue(delivery.p95Ms() <= delivery.p99Ms());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testMessageWithHeaders(VertxTestContext testContext) throws Exception {
        logger.info("Test: message with headers");
        String testMessage = "Message with headers test";
        Map<String, String> headers = Map.of(
            "content-type", "text/plain",
            "source", "basic-test",
            "version", "1.0"
        );

        // Set up consumer
        Checkpoint messageReceived = testContext.checkpoint();
        List<Message<String>> receivedMessages = new ArrayList<>();

        consumer.subscribe(message -> {
            receivedMessages.add(message);
            messageReceived.flag();
            return Future.succeededFuture();
        });

        producer.send(testMessage, headers).onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Message should be received within timeout");
        assertEquals(1, receivedMessages.size(), "Should receive exactly one message");

        Message<String> receivedMessage = receivedMessages.get(0);
        assertEquals(testMessage, receivedMessage.getPayload(), "Should receive correct payload");
        assertEquals("text/plain", receivedMessage.getHeaders().get("content-type"), "Should preserve content-type header");
        assertEquals("basic-test", receivedMessage.getHeaders().get("source"), "Should preserve source header");
        assertEquals("1.0", receivedMessage.getHeaders().get("version"), "Should preserve version header");
    }

    @Test
    void testMultipleMessages(VertxTestContext testContext) throws Exception {
        logger.info("Test: multiple messages");
        int messageCount = 5;
        Checkpoint messagesReceived = testContext.checkpoint(messageCount);
        List<String> receivedMessages = new ArrayList<>();

        consumer.subscribe(message -> {
            synchronized (receivedMessages) {
                receivedMessages.add(message.getPayload());
            }
            messagesReceived.flag();
            return Future.succeededFuture();
        });

        for (int i = 0; i < messageCount; i++) {
            String message = "Basic Test Message " + i;
            producer.send(message).onFailure(testContext::failNow);
        }

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "All messages should be received within timeout");
        assertEquals(messageCount, receivedMessages.size(), "Should receive all sent messages");
    }

    @Test
    void testCorrelationId(VertxTestContext testContext) throws Exception {
        logger.info("Test: correlation id");
        String testMessage = "Correlation ID test";
        String correlationId = "test-correlation-" + UUID.randomUUID();

        // Set up consumer
        Checkpoint messageReceived = testContext.checkpoint();
        List<Message<String>> receivedMessages = new ArrayList<>();

        consumer.subscribe(message -> {
            receivedMessages.add(message);
            messageReceived.flag();
            return Future.succeededFuture();
        });

        producer.send(testMessage, Map.of(), correlationId).onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Message should be received within timeout");
        assertEquals(1, receivedMessages.size(), "Should receive exactly one message");

        Message<String> receivedMessage = receivedMessages.get(0);
        assertEquals(testMessage, receivedMessage.getPayload(), "Should receive correct payload");

        // OutboxConsumer stores correlationId in message headers, not the OutboxMessage field
        assertEquals(correlationId, receivedMessage.getHeaders().get("correlationId"),
            "Should preserve correlation ID in headers");
    }

    @Test
    void testMessageGroup(VertxTestContext testContext) throws Exception {
        logger.info("Test: message group");
        String testMessage = "Message group test";
        String messageGroup = "test-group-" + UUID.randomUUID();

        // Set up consumer
        Checkpoint messageReceived = testContext.checkpoint();
        List<String> receivedMessages = new ArrayList<>();

        consumer.subscribe(message -> {
            receivedMessages.add(message.getPayload());
            messageReceived.flag();
            return Future.succeededFuture();
        });

        producer.send(testMessage, Map.of(), null, messageGroup).onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Message should be received within timeout");
        assertEquals(1, receivedMessages.size(), "Should receive exactly one message");
        assertEquals(testMessage, receivedMessages.get(0), "Should receive correct message");
    }
}


