package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Performance tests for the outbox pattern implementation.
 * These tests are disabled by default and can be enabled with -Dpeegeeq.performance.tests=true
 */
@Tag(TestCategories.PERFORMANCE)
@Testcontainers
@ExtendWith(VertxExtension.class)
@EnabledIfSystemProperty(named = "peegeeq.performance.tests", matches = "true")
public class OutboxPerformanceTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxPerformanceTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        // Use unique topic for each test to avoid interference
        testTopic = "perf-test-topic-" + UUID.randomUUID().toString().substring(0, 8);

        // Set up database connection. Explicitly disable background jobs that the
        // antipatterns doc flags as cross-test polluters when PeeGeeQManager is
        // constructed directly (not via BaseIntegrationTest).
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.consumer.threads", "8")
                .property("peegeeq.queue.batch-size", "50")
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .property("peegeeq.connection.pool.size", "20")
                .property("peegeeq.queue.dead-consumer-detection.enabled", "false")
                .property("peegeeq.queue.consumer-group-retry.enabled", "false")
                .build();

        // Create and start manager
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
    void tearDown(VertxTestContext testContext) {
        if (consumer != null) {
            try { consumer.close(); } catch (Exception e) { logger.warn("Error closing consumer: {}", e.getMessage()); }
        }
        if (producer != null) {
            try { producer.close(); } catch (Exception e) { logger.warn("Error closing producer: {}", e.getMessage()); }
        }
        if (outboxFactory != null) {
            try { outboxFactory.close(); } catch (Exception e) { logger.warn("Error closing factory: {}", e.getMessage()); }
        }
        if (manager == null) {
            testContext.completeNow();
            return;
        }
        manager.closeReactive()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(err -> {
                    logger.warn("Error during manager cleanup: {}", err.getMessage());
                    testContext.completeNow();
                });
    }

    @Test
    void testThroughputPerformance(Vertx vertx, VertxTestContext testContext) throws Exception {
        int messageCount = 1000;
        Checkpoint allProcessed = testContext.checkpoint(messageCount);
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicLong totalProcessingTime = new AtomicLong(0);

        // Subscribe first; chain the send burst off the subscribe Future so we never race
        // the consumer start-up. The per-message allProcessed checkpoint plus the
        // sendsCompleted checkpoint together drive testContext.awaitCompletion.
        Checkpoint sendsCompleted = testContext.checkpoint(1);
        final AtomicReference<Instant> sendCompleteTimeRef = new AtomicReference<>();
        final AtomicReference<Instant> startTimeRef = new AtomicReference<>();

        consumer.subscribe(message -> {
            long startNs = System.nanoTime();
            int count = processedCount.incrementAndGet();
            if (count % 100 == 0) {
                logger.info("Processed {} messages...", count);
            }
            totalProcessingTime.addAndGet(System.nanoTime() - startNs);
            allProcessed.flag();
            return Future.succeededFuture();
        }).onComplete(testContext.succeeding(subscribed -> {
            logger.info("Starting throughput test with {} messages...", messageCount);
            startTimeRef.set(Instant.now());

            List<Future<Void>> sendFutures = new java.util.ArrayList<>(messageCount);
            for (int i = 0; i < messageCount; i++) {
                sendFutures.add(producer.send("Performance test message " + i));
            }
            Future.all(sendFutures)
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    sendCompleteTimeRef.set(Instant.now());
                    sendsCompleted.flag();
                })));
        }));

        // Wait for all messages to be processed (and for sends to have completed)
        assertTrue(testContext.awaitCompletion(120, TimeUnit.SECONDS),
            "All messages should be processed within timeout");
        Instant processCompleteTime = Instant.now();
        Instant sendCompleteTime = sendCompleteTimeRef.get();
        Instant startTime = startTimeRef.get();

        // Calculate performance metrics
        Duration sendDuration = Duration.between(startTime, sendCompleteTime);
        Duration totalDuration = Duration.between(startTime, processCompleteTime);
        Duration processingDuration = Duration.between(sendCompleteTime, processCompleteTime);

        double sendThroughput = messageCount / (sendDuration.toMillis() / 1000.0);
        double totalThroughput = messageCount / (totalDuration.toMillis() / 1000.0);
        double avgProcessingTimeNs = totalProcessingTime.get() / (double) messageCount;

        logger.info("=== THROUGHPUT PERFORMANCE RESULTS ===");
        logger.info("Messages: {}", messageCount);
        logger.info("Send duration: {}ms", sendDuration.toMillis());
        logger.info("Processing duration: {}ms", processingDuration.toMillis());
        logger.info("Total duration: {}ms", totalDuration.toMillis());
        logger.info("Send throughput: {} msg/sec", String.format("%.2f", sendThroughput));
        logger.info("Total throughput: {} msg/sec", String.format("%.2f", totalThroughput));
        logger.info("Avg processing time: {}ms", String.format("%.2f", avgProcessingTimeNs / 1_000_000));

        // Verify all messages were processed
        assertEquals(messageCount, processedCount.get(), "Should process all messages");

        // Performance assertions (adjust based on expected performance)
        assertTrue(sendThroughput > 100, "Send throughput should be > 100 msg/sec, was: " + sendThroughput);
        assertTrue(totalThroughput > 50, "Total throughput should be > 50 msg/sec, was: " + totalThroughput);
    }

    @Test
    void testLatencyPerformance(Vertx vertx, VertxTestContext testContext) throws Exception {
        int messageCount = 100;
        Checkpoint allProcessed = testContext.checkpoint(messageCount);
        AtomicLong totalLatency = new AtomicLong(0);
        AtomicLong minLatency = new AtomicLong(Long.MAX_VALUE);
        AtomicLong maxLatency = new AtomicLong(0);

        // Subscribe first; only start sending after subscribe completes so we don't race
        // the consumer start-up. Sends are paced 10 ms apart via vertx.timer()  no
        // blocking, no .await(), no LockSupport.parkNanos.
        consumer.subscribe(message -> {
            long receiveTime = System.nanoTime();
            long sendTime = Long.parseLong(message.getHeaders().get("sendTime"));
            long latency = receiveTime - sendTime;
            totalLatency.addAndGet(latency);
            minLatency.updateAndGet(current -> Math.min(current, latency));
            maxLatency.updateAndGet(current -> Math.max(current, latency));
            allProcessed.flag();
            return Future.succeededFuture();
        }).onComplete(testContext.succeeding(subscribed -> {
            logger.info("Starting latency test with {} messages...", messageCount);
            sendPacedLatency(vertx, 0, messageCount).onFailure(testContext::failNow);
        }));

        // Wait for all messages to be processed
        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS),
            "All messages should be processed within timeout");

        // Calculate latency metrics
        double avgLatencyMs = (totalLatency.get() / (double) messageCount) / 1_000_000;
        double minLatencyMs = minLatency.get() / 1_000_000.0;
        double maxLatencyMs = maxLatency.get() / 1_000_000.0;

        logger.info("=== LATENCY PERFORMANCE RESULTS ===");
        logger.info("Messages: {}", messageCount);
        logger.info("Average latency: {}ms", String.format("%.2f", avgLatencyMs));
        logger.info("Min latency: {}ms", String.format("%.2f", minLatencyMs));
        logger.info("Max latency: {}ms", String.format("%.2f", maxLatencyMs));

        // Performance assertions (adjust based on expected performance)
        assertTrue(avgLatencyMs < 1000, "Average latency should be < 1000ms, was: " + avgLatencyMs);
        assertTrue(minLatencyMs < 500, "Min latency should be < 500ms, was: " + minLatencyMs);
    }

    @Test
    void testConcurrentProducerPerformance(Vertx vertx, VertxTestContext testContext) throws Exception {
        int producerCount = 5;
        int messagesPerProducer = 200;
        int totalMessages = producerCount * messagesPerProducer;

        Checkpoint allProcessed = testContext.checkpoint(totalMessages);
        AtomicInteger processedCount = new AtomicInteger(0);
        Checkpoint sendsCompleted = testContext.checkpoint(1);
        final AtomicReference<Instant> sendCompleteTimeRef = new AtomicReference<>();
        final AtomicReference<Instant> startTimeRef = new AtomicReference<>();

        // Subscribe first; only kick off the producer chains after subscribe completes.
        consumer.subscribe(message -> {
            int count = processedCount.incrementAndGet();
            if (count % 100 == 0) {
                logger.info("Processed {} concurrent messages...", count);
            }
            allProcessed.flag();
            return Future.succeededFuture();
        }).onComplete(testContext.succeeding(subscribed -> {
            logger.info("Starting concurrent producer test: {} producers, {} messages each...",
                producerCount, messagesPerProducer);
            startTimeRef.set(Instant.now());

            // Drive concurrency through Vert.x reactive chains instead of an ExecutorService.
            // Each producer chain sends its messages serially via composed futures; the chains
            // themselves run in parallel and are aggregated with Future.all.
            List<Future<Void>> producerFutures = new java.util.ArrayList<>(producerCount);
            for (int p = 0; p < producerCount; p++) {
                final int producerId = p;
                final MessageProducer<String> concurrentProducer =
                    outboxFactory.createProducer(testTopic, String.class);
                Future<Void> producerFuture = sendConcurrent(concurrentProducer, producerId, 0, messagesPerProducer)
                    .eventually(() -> {
                        try { concurrentProducer.close(); }
                        catch (Exception e) { logger.warn("Producer {} close failed: {}", producerId, e.getMessage()); }
                        return Future.<Void>succeededFuture();
                    });
                producerFutures.add(producerFuture);
            }

            Future.all(producerFutures)
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    sendCompleteTimeRef.set(Instant.now());
                    sendsCompleted.flag();
                })));
        }));

        // Wait for all messages to be processed (and for all producer chains to finish)
        assertTrue(testContext.awaitCompletion(180, TimeUnit.SECONDS),
            "All concurrent messages should be processed within timeout");
        Instant processCompleteTime = Instant.now();
        Instant sendCompleteTime = sendCompleteTimeRef.get();
        Instant startTime = startTimeRef.get();

        // Calculate performance metrics
        Duration sendDuration = Duration.between(startTime, sendCompleteTime);
        Duration totalDuration = Duration.between(startTime, processCompleteTime);

        double sendThroughput = totalMessages / (sendDuration.toMillis() / 1000.0);
        double totalThroughput = totalMessages / (totalDuration.toMillis() / 1000.0);

        logger.info("=== CONCURRENT PRODUCER PERFORMANCE RESULTS ===");
        logger.info("Producers: {}", producerCount);
        logger.info("Messages per producer: {}", messagesPerProducer);
        logger.info("Total messages: {}", totalMessages);
        logger.info("Send duration: {}ms", sendDuration.toMillis());
        logger.info("Total duration: {}ms", totalDuration.toMillis());
        logger.info("Send throughput: {} msg/sec", String.format("%.2f", sendThroughput));
        logger.info("Total throughput: {} msg/sec", String.format("%.2f", totalThroughput));

        // Verify all messages were processed
        assertEquals(totalMessages, processedCount.get(), "Should process all concurrent messages");

        // Performance assertions
        assertTrue(sendThroughput > 50, "Concurrent send throughput should be > 50 msg/sec, was: " + sendThroughput);
        assertTrue(totalThroughput > 25, "Concurrent total throughput should be > 25 msg/sec, was: " + totalThroughput);
    }

    /**
     * Recursively sends {@code total} latency-tagged messages, each separated by a 10 ms
     * Vert.x timer. Replaces a blocking loop that used {@code LockSupport.parkNanos} and
     * {@code producer.send(...).await()}.
     */
    private Future<Void> sendPacedLatency(Vertx vertx, int i, int total) {
        if (i >= total) {
            return Future.succeededFuture();
        }
        long sendTime = System.nanoTime();
        return producer.send("Latency test message " + i,
                Map.of("sendTime", String.valueOf(sendTime)))
            .compose(v -> vertx.timer(10).mapEmpty())
            .compose(v -> sendPacedLatency(vertx, i + 1, total));
    }

    /**
     * Serially sends {@code total} messages from a single producer via composed futures.
     * Replaces a blocking per-producer loop that used {@code producer.send(...).await()}
     * inside an {@code ExecutorService.submit}.
     */
    private Future<Void> sendConcurrent(MessageProducer<String> p, int producerId, int i, int total) {
        if (i >= total) {
            return Future.succeededFuture();
        }
        return p.send("Concurrent-P" + producerId + "-M" + i)
            .compose(v -> sendConcurrent(p, producerId, i + 1, total));
    }
}


