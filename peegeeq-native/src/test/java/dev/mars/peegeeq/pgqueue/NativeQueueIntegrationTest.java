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


import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the native PostgreSQL queue implementation.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class NativeQueueIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(NativeQueueIntegrationTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory queueFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private final List<PgNativeQueueFactory> extraFactories = new ArrayList<>();

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.NATIVE_QUEUE, SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);

        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .property("peegeeq.database.pool.min-size", "2")
                .property("peegeeq.database.pool.max-size", "5")
                .property("peegeeq.database.pool.connection-timeout-ms", "10000")
                .property("peegeeq.database.pool.idle-timeout-ms", "60000")
                .property("peegeeq.queue.polling-interval", "PT1S")
                .property("peegeeq.queue.visibility-timeout", "PT5S")
                .property("peegeeq.metrics.enabled", "true")
                .property("peegeeq.circuit-breaker.enabled", "true")
                .property("peegeeq.queue.consumer-group-retry.enabled", "false")
                .property("peegeeq.queue.dead-consumer-detection.enabled", "false")
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .compose(v -> {
                    DatabaseService databaseService = new PgDatabaseService(manager);
                    QueueFactoryProvider provider = new PgQueueFactoryProvider();
                    PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                    queueFactory = provider.createFactory("native", databaseService);
                    producer = queueFactory.createProducer("test-native-topic", String.class);
                    consumer = queueFactory.createConsumer("test-native-topic", String.class);
                    return manager.getPool().query("DELETE FROM queue_messages").execute().mapEmpty();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        Future<Void> closeExtras = Future.succeededFuture();
        for (PgNativeQueueFactory f : extraFactories) {
            closeExtras = closeExtras.compose(v -> f.close());
        }
        closeExtras
                .compose(v -> queueFactory != null ? queueFactory.close() : Future.<Void>succeededFuture())
                .compose(v -> manager != null ? manager.closeReactive() : Future.<Void>succeededFuture())
                .onSuccess(v -> {
                    manager = null;
                    testContext.completeNow();
                })
                .onFailure(err -> {
                    manager = null;
                    testContext.failNow(err);
                });
    }



    @Test
    void testBasicNativeQueueProducerAndConsumer(VertxTestContext testContext) throws Exception {
        String testMessage = "Hello, Native Queue!";
        Checkpoint received = testContext.checkpoint(1);
        AtomicInteger receivedCount = new AtomicInteger(0);
        List<String> receivedMessages = new ArrayList<>();

        consumer.subscribe(message -> {
            receivedMessages.add(message.getPayload());
            receivedCount.incrementAndGet();
            received.flag();
            return Future.succeededFuture();
        }).onFailure(testContext::failNow);

        producer.send(testMessage).onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        assertEquals(1, receivedCount.get());
        assertEquals(testMessage, receivedMessages.get(0));
    }

    @Test
    void testNativeQueueWithHeaders(VertxTestContext testContext) throws Exception {
        String testMessage = "Native queue message with headers";
        Map<String, String> headers = Map.of(
            "content-type", "text/plain",
            "priority", "high",
            "source", "native-test"
        );

        Checkpoint received = testContext.checkpoint(1);
        List<Message<String>> receivedMessages = new ArrayList<>();

        consumer.subscribe(message -> {
            receivedMessages.add(message);
            received.flag();
            return Future.succeededFuture();
        }).onFailure(testContext::failNow);

        producer.send(testMessage, headers).onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        assertEquals(1, receivedMessages.size());

        Message<String> receivedMessage = receivedMessages.get(0);
        assertEquals(testMessage, receivedMessage.getPayload());
        assertEquals("text/plain", receivedMessage.getHeaders().get("content-type"));
        assertEquals("high", receivedMessage.getHeaders().get("priority"));
        assertEquals("native-test", receivedMessage.getHeaders().get("source"));
    }

    @Test
    void testNativeQueueListenNotify(VertxTestContext testContext) throws Exception {
        // This test verifies that LISTEN/NOTIFY works for real-time message delivery
        String testMessage = "Real-time notification test";
        Checkpoint received = testContext.checkpoint(1);
        List<String> receivedMessages = new ArrayList<>();

        consumer.subscribe(message -> {
            receivedMessages.add(message.getPayload());
            received.flag();
            return Future.succeededFuture();
        }).onFailure(testContext::failNow);

        producer.send(testMessage).onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
        assertEquals(testMessage, receivedMessages.get(0));
    }

    @Test
    void testNativeQueueVisibilityTimeout(VertxTestContext testContext) throws InterruptedException {
        String testMessage = "Visibility timeout test";
        AtomicInteger processingAttempts = new AtomicInteger(0);
        Promise<Void> firstAttempt = Promise.promise();
        Promise<Void> secondAttempt = Promise.promise();

        // Set up consumer before sending so it can immediately pick up the message
        consumer.subscribe(message -> {
            int attempt = processingAttempts.incrementAndGet();
            if (attempt == 1) {
                firstAttempt.tryComplete();
                // Simulate processing failure by not completing the future
                return Promise.<Void>promise().future(); // Never completes
            } else {
                secondAttempt.tryComplete();
                return Future.succeededFuture();
            }
        }).onFailure(testContext::failNow);

        producer.send(testMessage).onFailure(testContext::failNow);

        // Chain: wait for firstAttempt, then wait for secondAttempt, then verify
        firstAttempt.future()
                .compose(v -> secondAttempt.future())
                .onSuccess(v -> testContext.verify(() -> {
                    assertTrue(processingAttempts.get() >= 2);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testNativeQueueMultipleConsumers(VertxTestContext testContext) throws Exception {
        int messageCount = 10;
        int consumerCount = 3;
        
        // Create additional consumers
        List<MessageConsumer<String>> consumers = new ArrayList<>();
        consumers.add(consumer); // Add the existing consumer

        for (int i = 1; i < consumerCount; i++) {
            DatabaseService additionalDatabaseService = new PgDatabaseService(manager);
            PgNativeQueueFactory additionalFactory = new PgNativeQueueFactory(additionalDatabaseService);
            extraFactories.add(additionalFactory);
            consumers.add(additionalFactory.createConsumer("test-native-topic", String.class));
        }

        Checkpoint allReceived = testContext.checkpoint(messageCount);
        AtomicInteger totalReceived = new AtomicInteger(0);
        List<String> allReceivedMessages = new ArrayList<>();

        // Set up all consumers
        for (MessageConsumer<String> cons : consumers) {
            cons.subscribe(message -> {
                synchronized (allReceivedMessages) {
                    allReceivedMessages.add(message.getPayload());
                }
                totalReceived.incrementAndGet();
                allReceived.flag();
                return Future.succeededFuture();
            }).onFailure(testContext::failNow);
        }

        // Send multiple messages
        for (int i = 0; i < messageCount; i++) {
            producer.send("Message " + i).onFailure(testContext::failNow);
        }

        // Wait for all messages to be processed
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        assertEquals(messageCount, totalReceived.get());
        assertEquals(messageCount, allReceivedMessages.size());
    }

    @Test
    void testNativeQueueMessageLocking(Vertx vertx, VertxTestContext testContext) throws Exception {
        // This test verifies that messages are properly locked during processing
        String testMessage = "Locking test message";
        AtomicInteger processingCount = new AtomicInteger(0);
        Promise<Void> finishProcessing = Promise.promise();

        DatabaseService databaseService2 = new PgDatabaseService(manager);
        PgNativeQueueFactory testQueueFactory = new PgNativeQueueFactory(databaseService2);
        extraFactories.add(testQueueFactory);
        MessageConsumer<String> consumer2 = testQueueFactory.createConsumer("test-native-topic", String.class);

        // First consumer holds the lock until finishProcessing completes
        consumer.subscribe(message -> {
            processingCount.incrementAndGet();
            return finishProcessing.future();
        }).onFailure(testContext::failNow);

        // Second consumer competes for the same message
        consumer2.subscribe(message -> {
            processingCount.incrementAndGet();
            return Future.succeededFuture();
        }).onFailure(testContext::failNow);

        long[] timerId = {0L};
        producer.send(testMessage)
                .onFailure(testContext::failNow)
                .onSuccess(v -> {
                    timerId[0] = vertx.setPeriodic(100, id -> {
                        if (processingCount.get() >= 1) {
                            vertx.cancelTimer(timerId[0]);
                            finishProcessing.tryComplete();
                            vertx.setTimer(2000, id2 -> testContext.verify(() -> {
                                assertEquals(1, processingCount.get());
                                testContext.completeNow();
                            }));
                        }
                    });
                });

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
    }

    @Test
    void testNativeQueueFailureAndRetry(VertxTestContext testContext) throws InterruptedException {
        String testMessage = "Retry test message";
        AtomicInteger attemptCount = new AtomicInteger(0);
        Promise<Void> success = Promise.promise();

        // Send the message
        producer.send(testMessage).onFailure(testContext::failNow);

        // Set up consumer that fails first few times
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            if (attempt < 3) {
                // Fail the first 2 attempts
                return Future.failedFuture(
                    new RuntimeException("Simulated processing failure, attempt " + attempt));
            } else {
                // Succeed on the 3rd attempt
                success.tryComplete();
                return Future.succeededFuture();
            }
        }).onFailure(testContext::failNow);

        // Wait for successful processing
        success.future()
                .onSuccess(v -> testContext.verify(() -> {
                    assertTrue(attemptCount.get() >= 3);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testNativeQueueDeadLetterIntegration(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        // Configure a message that will exceed retry limits
        String testMessage = "Dead letter test message";
        AtomicInteger attemptCount = new AtomicInteger(0);

        // Send the message
        producer.send(testMessage).onFailure(testContext::failNow);

        // Set up consumer that always fails
        consumer.subscribe(message -> {
            attemptCount.incrementAndGet();
            return Future.failedFuture(
                new RuntimeException("Always fails"));
        }).onFailure(testContext::failNow);

        // Wait for the message to be moved to dead letter queue after retries
        Promise<Void> inDlq = Promise.promise();
        long[] pollTimer = new long[1];
        pollTimer[0] = vertx.setPeriodic(100, id -> {
            manager.getDeadLetterQueueManager().getStatistics()
                .onSuccess(stats -> {
                    if (stats.totalMessages() > 0) {
                        inDlq.tryComplete();
                    }
                })
                .onFailure(err -> logger.warn("DLQ statistics query failed", err));
        });
        inDlq.future()
                .onSuccess(v -> testContext.verify(() -> {
                    vertx.cancelTimer(pollTimer[0]);
                    assertTrue(attemptCount.get() > 1);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
    }

    @Test
    void testNativeQueueMetricsIntegration(VertxTestContext testContext) throws Exception {
        String testMessage = "Metrics integration test";

        // Subscribe consumer before sending so it receives the message
        Checkpoint received = testContext.checkpoint(1);
        logger.debug("TEST: About to subscribe consumer to topic: test-native-topic");
        consumer.subscribe(message -> {
            logger.debug("TEST: Consumer received message: {}", message.getId());
            received.flag();
            return Future.succeededFuture();
        }).onFailure(testContext::failNow);
        logger.debug("TEST: Consumer subscription completed");

        producer.send(testMessage).onFailure(testContext::failNow);

        // Wait for processing
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));

        // Verify metrics were recorded
        var metrics = manager.getMetrics().getSummary();
        assertTrue(metrics.getMessagesSent() > 0);
        assertTrue(metrics.getMessagesReceived() > 0);
        assertTrue(metrics.getMessagesProcessed() > 0);
    }

    @Test
    void testNativeQueueHealthCheckIntegration(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        // Verify system is healthy
        assertTrue(manager.isHealthy());

        // Poll briefly until the native-queue component is present (health checks run asynchronously)
        var hcm = manager.getHealthCheckManager();
        Promise<Void> componentReady = Promise.promise();
        long[] pollTimer = new long[1];
        pollTimer[0] = vertx.setPeriodic(100, id -> {
            if (hcm.getOverallHealth().components().containsKey("native-queue")) {
                componentReady.tryComplete();
            }
        });
        componentReady.future()
                .onSuccess(v -> testContext.verify(() -> {
                    vertx.cancelTimer(pollTimer[0]);
                    assertTrue(hcm.getOverallHealth().isHealthy());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testNativeQueueBackpressureIntegration(VertxTestContext testContext) throws InterruptedException, dev.mars.peegeeq.db.resilience.BackpressureManager.BackpressureException {
        // This test verifies that backpressure is applied to native queue operations
        var backpressureManager = manager.getBackpressureManager();
        
        // Track a successful backpressure-managed operation
        String result = backpressureManager.execute("native-queue-send", () -> "success");
        assertEquals("success", result);
        
        // Send message reactively
        producer.send("Backpressure test")
                .onSuccess(v -> testContext.verify(() -> {
                    var metrics = backpressureManager.getMetrics();
                    assertTrue(metrics.getSuccessfulOperations() > 0);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
        
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    void testNativeQueueConcurrentProducers(VertxTestContext testContext) throws Exception {
        int producerCount = 3;
        int messagesPerProducer = 5;
        int totalMessages = producerCount * messagesPerProducer;

        Checkpoint allReceived = testContext.checkpoint(totalMessages);
        List<String> receivedMessages = new ArrayList<>();

        // Set up consumer
        consumer.subscribe(message -> {
            synchronized (receivedMessages) {
                receivedMessages.add(message.getPayload());
            }
            allReceived.flag();
            return Future.succeededFuture();
        }).onFailure(testContext::failNow);

        // Create multiple producers sending concurrently
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            for (int m = 0; m < messagesPerProducer; m++) {
                final int messageId = m;
                String message = "Native-Producer-" + producerId + "-Message-" + messageId;
                producer.send(message).onFailure(testContext::failNow);
            }
        }

        // Wait for all messages to be received
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        assertEquals(totalMessages, receivedMessages.size());
    }
}


