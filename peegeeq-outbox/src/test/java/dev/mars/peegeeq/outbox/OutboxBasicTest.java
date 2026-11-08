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
import io.vertx.junit5.Timeout;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.mars.peegeeq.test.vertx.TestCleanup;

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
            .onSuccess(v -> testContext.verify(() -> {
                DatabaseService databaseService = new PgDatabaseService(manager);
                outboxFactory = new OutboxFactory(databaseService, config);
                producer = outboxFactory.createProducer(testTopic, String.class);
                consumer = outboxFactory.createConsumer(testTopic, String.class);
                testContext.completeNow();
            }))
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
        Future<Void> factoryClose = outboxFactory != null
                ? outboxFactory.close()
                : Future.succeededFuture();
        factoryClose
            .transform(TestCleanup.cleanup(() -> manager != null
                    ? manager.closeReactive()
                    : Future.succeededFuture()))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    void testBasicMessageProducerAndConsumer(VertxTestContext testContext) {
        logger.info("Test: basic message producer and consumer");
        String testMessage = "Hello, Basic Outbox Test!";

        // A strict checkpoint: a second delivery fails the test.
        Checkpoint messageReceived = testContext.checkpoint();

        consumer.subscribe(message -> {
            testContext.verify(() ->
                assertEquals(testMessage, message.getPayload(), "Should receive the correct message"));
            messageReceived.flag();
            return Future.succeededFuture();
        })
            .compose(v -> producer.send(testMessage))
            .onFailure(testContext::failNow);
    }

    @Test
    void testProcessingTimePercentilesExposedOnStats(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        // Telemetry G1 (Phase T.1) end to end: real consumption feeds the
        // per-topic histogram, and getStats carries the derived distribution —
        // the outbox mirror of the native test, same seam, same contract.
        logger.info("Test: processing-time percentiles exposed on stats");
        int messageCount = 20;

        consumer.subscribe(message -> Future.succeededFuture())
                .compose(v -> {
                    List<Future<Void>> sends = new ArrayList<>();
                    for (int i = 0; i < messageCount; i++) {
                        sends.add(producer.send("Percentile message " + i));
                    }
                    return Future.all(sends).mapEmpty();
                })
                .onFailure(testContext::failNow);

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
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    void testMessageWithHeaders(VertxTestContext testContext) {
        logger.info("Test: message with headers");
        String testMessage = "Message with headers test";
        Map<String, String> headers = Map.of(
            "content-type", "text/plain",
            "source", "basic-test",
            "version", "1.0"
        );

        // A strict checkpoint: a second delivery fails the test.
        Checkpoint messageReceived = testContext.checkpoint();

        consumer.subscribe(message -> {
            testContext.verify(() -> {
                assertEquals(testMessage, message.getPayload(), "Should receive correct payload");
                assertEquals("text/plain", message.getHeaders().get("content-type"), "Should preserve content-type header");
                assertEquals("basic-test", message.getHeaders().get("source"), "Should preserve source header");
                assertEquals("1.0", message.getHeaders().get("version"), "Should preserve version header");
            });
            messageReceived.flag();
            return Future.succeededFuture();
        })
            .compose(v -> producer.send(testMessage, headers))
            .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
    void testMultipleMessages(VertxTestContext testContext) {
        logger.info("Test: multiple messages");
        int messageCount = 5;
        // A strict checkpoint: a sixth delivery fails the test; a repeated payload fails it too.
        Checkpoint messagesReceived = testContext.checkpoint(messageCount);
        Set<String> receivedMessages = ConcurrentHashMap.newKeySet();

        consumer.subscribe(message -> {
            testContext.verify(() -> {
                assertTrue(message.getPayload().startsWith("Basic Test Message "),
                    "Unexpected payload: " + message.getPayload());
                assertTrue(receivedMessages.add(message.getPayload()),
                    "Delivered twice: " + message.getPayload());
            });
            messagesReceived.flag();
            return Future.succeededFuture();
        })
            .compose(v -> {
                List<Future<Void>> sends = new ArrayList<>();
                for (int i = 0; i < messageCount; i++) {
                    sends.add(producer.send("Basic Test Message " + i));
                }
                return Future.all(sends).mapEmpty();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    void testCorrelationId(VertxTestContext testContext) {
        logger.info("Test: correlation id");
        String testMessage = "Correlation ID test";
        String correlationId = "test-correlation-" + UUID.randomUUID();

        // A strict checkpoint: a second delivery fails the test.
        Checkpoint messageReceived = testContext.checkpoint();

        consumer.subscribe(message -> {
            testContext.verify(() -> {
                assertEquals(testMessage, message.getPayload(), "Should receive correct payload");
                // OutboxConsumer stores correlationId in message headers, not the OutboxMessage field
                assertEquals(correlationId, message.getHeaders().get("correlationId"),
                    "Should preserve correlation ID in headers");
            });
            messageReceived.flag();
            return Future.succeededFuture();
        })
            .compose(v -> producer.send(testMessage, Map.of(), correlationId))
            .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    void testMessageGroup(VertxTestContext testContext) {
        logger.info("Test: message group");
        String testMessage = "Message group test";
        String messageGroup = "test-group-" + UUID.randomUUID();

        // A strict checkpoint: a second delivery fails the test.
        Checkpoint messageReceived = testContext.checkpoint();

        consumer.subscribe(message -> {
            testContext.verify(() ->
                assertEquals(testMessage, message.getPayload(), "Should receive correct message"));
            messageReceived.flag();
            return Future.succeededFuture();
        })
            .compose(v -> producer.send(testMessage, Map.of(), null, messageGroup))
            .onFailure(testContext::failNow);
    }
}

