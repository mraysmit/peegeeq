package dev.mars.peegeeq.pgqueue.examples;

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

import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.pgqueue.ConsumerConfig;
import dev.mars.peegeeq.pgqueue.ConsumerMode;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance comparison test showing the impact of different system property configurations.
 * Migrated from PerformanceComparisonExample.java to proper JUnit test.
 *
 * This test demonstrates how different combinations of:
 * - peegeeq.consumer.threads (concurrency)
 * - peegeeq.queue.batch-size (batching)
 * - peegeeq.queue.polling-interval (polling frequency)
 *
 * affect overall system performance and throughput.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-21
 * @version 1.0
 */
@Tag(TestCategories.PERFORMANCE)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PerformanceComparisonExampleTest {

    private static final Logger logger = LoggerFactory.getLogger(PerformanceComparisonExampleTest.class);
    private static final int MESSAGE_COUNT = 50; // Number of messages to process in each test

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_performance_demo");
        container.withUsername("postgres");
        container.withPassword("password");
        container.withSharedMemorySize(256 * 1024 * 1024L);
        container.withReuse(false);
        return container;
    }

    private PeeGeeQManager manager;
    private QueueFactory nativeFactory;

    @BeforeEach
    void setUp() throws Exception {
        logger.info("=== Setting up Performance Comparison Test ===");

        // Configure PeeGeeQ to use container database
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.schema", "public");
        System.setProperty("peegeeq.database.ssl.enabled", "false");

        // Configure for performance testing
        System.setProperty("peegeeq.database.pool.min-size", "5");
        System.setProperty("peegeeq.database.pool.max-size", "20");
        System.setProperty("peegeeq.metrics.enabled", "true");
        System.setProperty("peegeeq.migration.enabled", "true");
        System.setProperty("peegeeq.migration.auto-migrate", "true");

        // Ensure required schema exists before starting PeeGeeQ
        PeeGeeQTestSchemaInitializer.initializeSchema(
            postgres,
            SchemaComponent.NATIVE_QUEUE,
            SchemaComponent.OUTBOX,
            SchemaComponent.DEAD_LETTER_QUEUE
        );

        // Initialize PeeGeeQ Manager
        manager = new PeeGeeQManager(
                new PeeGeeQConfiguration("development"),
                new SimpleMeterRegistry());

        manager.start();
        logger.info("PeeGeeQ Manager started successfully");

        // Create database service and factory provider
        DatabaseService databaseService = new PgDatabaseService(manager);
        // Provide live PeeGeeQConfiguration so consumers can read threads/batch/polling settings
        QueueFactoryProvider provider = new PgQueueFactoryProvider(manager.getConfiguration());

        // Register native queue factory implementation
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        // Create native queue factory
        nativeFactory = provider.createFactory("native", databaseService);

        logger.info("Performance Comparison Test setup completed");
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.info("🧹 Cleaning up Performance Comparison Test");

        if (nativeFactory != null) {
            nativeFactory.close();
        }

        if (manager != null) {
            CountDownLatch closeLatch = new CountDownLatch(1);
            manager.closeReactive().onComplete(ar -> closeLatch.countDown());
            closeLatch.await(10, TimeUnit.SECONDS);
        }

        // Clear system properties
        clearSystemProperties();

        logger.info("Performance Comparison Test cleanup completed");
    }

    @Test
    void testSingleThreadedConfiguration(Vertx vertx) throws Exception {
        logger.info("=== Testing Single-Threaded Configuration ===");

        PerformanceResult result = testConfiguration("Single-Threaded", 1, 1, "PT1S", vertx);

        // Verify single-threaded configuration worked
        assertNotNull(result, "Performance result should not be null");
        assertEquals("Single-Threaded", result.configName);
        assertEquals(1, result.threads);
        assertEquals(1, result.batchSize);
        assertTrue(result.completed, "Single-threaded test should complete");
        assertTrue(result.processedMessages > 0, "Should process some messages");

        logger.info("Single-threaded configuration test completed successfully!");
    }

    @Test
    void testMultiThreadedConfiguration(Vertx vertx) throws Exception {
        logger.info("=== Testing Multi-Threaded Configuration ===");

        PerformanceResult result = testConfiguration("Multi-Threaded", 4, 1, "PT1S", vertx);

        // Verify multi-threaded configuration worked
        assertNotNull(result, "Performance result should not be null");
        assertEquals("Multi-Threaded", result.configName);
        assertEquals(4, result.threads);
        assertEquals(1, result.batchSize);
        assertTrue(result.completed, "Multi-threaded test should complete");
        assertTrue(result.processedMessages > 0, "Should process some messages");

        logger.info("Multi-threaded configuration test completed successfully!");
    }

    @Test
    void testBatchedProcessingConfiguration(Vertx vertx) throws Exception {
        logger.info("=== Testing Batched Processing Configuration ===");

        PerformanceResult result = testConfiguration("Batched Processing", 2, 25, "PT1S", vertx);

        // Verify batched processing configuration worked
        assertNotNull(result, "Performance result should not be null");
        assertEquals("Batched Processing", result.configName);
        assertEquals(2, result.threads);
        assertEquals(25, result.batchSize);
        assertTrue(result.completed, "Batched processing test should complete");
        assertTrue(result.processedMessages > 0, "Should process some messages");

        logger.info("Batched processing configuration test completed successfully!");
    }

    @Test
    void testFastPollingConfiguration(Vertx vertx) throws Exception {
        logger.info("=== Testing Fast Polling Configuration ===");

        PerformanceResult result = testConfiguration("Fast Polling", 2, 10, "PT0.1S", vertx);

        // Verify fast polling configuration worked
        assertNotNull(result, "Performance result should not be null");
        assertEquals("Fast Polling", result.configName);
        assertEquals(2, result.threads);
        assertEquals(10, result.batchSize);
        assertEquals("PT0.1S", result.pollingInterval);
        assertTrue(result.completed, "Fast polling test should complete");
        assertTrue(result.processedMessages > 0, "Should process some messages");

        logger.info("Fast polling configuration test completed successfully!");
    }

    @Test
    void testOptimizedConfiguration(Vertx vertx) throws Exception {
        logger.info("=== Testing Optimized Configuration ===");

        PerformanceResult result = testConfiguration("Optimized", 6, 50, "PT0.2S", vertx);

        // Verify optimized configuration worked
        assertNotNull(result, "Performance result should not be null");
        assertEquals("Optimized", result.configName);
        assertEquals(6, result.threads);
        assertEquals(50, result.batchSize);
        assertEquals("PT0.2S", result.pollingInterval);
        assertTrue(result.completed, "Optimized test should complete");
        assertTrue(result.processedMessages > 0, "Should process some messages");

        logger.info("Optimized configuration test completed successfully!");
    }

    @Test
    void testPerformanceComparison(Vertx vertx) throws Exception {
        logger.info("=== Testing Complete Performance Comparison ===");

        // Test all configurations and compare performance
        PerformanceResult singleThreaded = testConfiguration("Single-Threaded", 1, 1, "PT1S", vertx);

        CountDownLatch delay1 = new CountDownLatch(1);
        vertx.setTimer(2000, id -> delay1.countDown());
        delay1.await(5, TimeUnit.SECONDS);

        PerformanceResult multiThreaded = testConfiguration("Multi-Threaded", 4, 1, "PT1S", vertx);

        CountDownLatch delay2 = new CountDownLatch(1);
        vertx.setTimer(2000, id -> delay2.countDown());
        delay2.await(5, TimeUnit.SECONDS);

        PerformanceResult batched = testConfiguration("Batched Processing", 2, 25, "PT1S", vertx);

        CountDownLatch delay3 = new CountDownLatch(1);
        vertx.setTimer(2000, id -> delay3.countDown());
        delay3.await(5, TimeUnit.SECONDS);

        PerformanceResult fastPolling = testConfiguration("Fast Polling", 2, 10, "PT0.1S", vertx);

        CountDownLatch delay4 = new CountDownLatch(1);
        vertx.setTimer(2000, id -> delay4.countDown());
        delay4.await(5, TimeUnit.SECONDS);

        PerformanceResult optimized = testConfiguration("Optimized", 6, 50, "PT0.2S", vertx);

        // Display comparison results
        displayPerformanceComparison(singleThreaded, multiThreaded, batched, fastPolling, optimized);

        // Verify all tests completed
        assertTrue(singleThreaded.completed, "Single-threaded should complete");
        assertTrue(multiThreaded.completed, "Multi-threaded should complete");
        assertTrue(batched.completed, "Batched processing should complete");
        assertTrue(fastPolling.completed, "Fast polling should complete");
        assertTrue(optimized.completed, "Optimized should complete");

        // Verify performance metrics are reasonable
        assertTrue(singleThreaded.throughputMsgPerSec > 0, "Single-threaded should have positive throughput");
        assertTrue(multiThreaded.throughputMsgPerSec > 0, "Multi-threaded should have positive throughput");
        assertTrue(batched.throughputMsgPerSec > 0, "Batched should have positive throughput");
        assertTrue(fastPolling.throughputMsgPerSec > 0, "Fast polling should have positive throughput");
        assertTrue(optimized.throughputMsgPerSec > 0, "Optimized should have positive throughput");

        logger.info("Performance comparison test completed successfully!");
    }

    /**
     * Clears all system properties set for testing.
     */
    private void clearSystemProperties() {
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        System.clearProperty("peegeeq.database.schema");
        System.clearProperty("peegeeq.database.ssl.enabled");
        System.clearProperty("peegeeq.database.pool.min-size");
        System.clearProperty("peegeeq.database.pool.max-size");
        System.clearProperty("peegeeq.metrics.enabled");
        System.clearProperty("peegeeq.migration.enabled");
        System.clearProperty("peegeeq.migration.auto-migrate");
        System.clearProperty("peegeeq.queue.max-retries");
        System.clearProperty("peegeeq.consumer.threads");
        System.clearProperty("peegeeq.queue.batch-size");
        System.clearProperty("peegeeq.queue.polling-interval");
    }

    /**
     * Tests a specific configuration and measures performance.
     */
    private PerformanceResult testConfiguration(String configName, int threads, int batchSize, String pollingInterval, Vertx vertx) throws Exception {
        logger.info("\n=== Testing Configuration: {} ===", configName);
        logger.info("🔧 Threads: {}, Batch Size: {}, Polling Interval: {}", threads, batchSize, pollingInterval);

        // Set system properties for this configuration
        System.setProperty("peegeeq.queue.max-retries", "3");
        System.setProperty("peegeeq.consumer.threads", String.valueOf(threads));
        System.setProperty("peegeeq.queue.batch-size", String.valueOf(batchSize));
        System.setProperty("peegeeq.queue.polling-interval", pollingInterval);

        Instant startTime = Instant.now();

        try {
            // Use the native factory from setup
            assertNotNull(nativeFactory, "Native queue factory should be available");

            // Create producer and consumer with explicit ConsumerConfig to ensure per-test overrides
            MessageProducer<PerformanceTestMessage> producer =
                nativeFactory.createProducer("performance-test", PerformanceTestMessage.class);

            ConsumerConfig consumerConfig = ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .consumerThreads(threads)
                .batchSize(batchSize)
                .pollingInterval(Duration.parse(pollingInterval))
                .build();

            MessageConsumer<PerformanceTestMessage> consumer =
                nativeFactory.createConsumer("performance-test", PerformanceTestMessage.class, consumerConfig);

            // Performance tracking
            AtomicInteger processedCount = new AtomicInteger(0);
            AtomicLong totalProcessingTime = new AtomicLong(0);
            CountDownLatch allProcessed = new CountDownLatch(1);

            // Start consumer
            Instant consumerStartTime = Instant.now();
            consumer.subscribe(message -> {
                Instant processingStart = Instant.now();
                Promise<Void> result = Promise.promise();

                // Simulate some processing work (1ms via timer)
                vertx.setTimer(1, tid -> {
                    int count = processedCount.incrementAndGet();

                    long processingTime = Duration.between(processingStart, Instant.now()).toMillis();
                    totalProcessingTime.addAndGet(processingTime);

                    logger.debug("Processed message {} for config {}", message.getId(), configName);

                    if (count >= MESSAGE_COUNT) {
                        allProcessed.countDown();
                    }
                    result.complete();
                });

                return result.future();
            });

            // Send messages
            Instant sendingStartTime = Instant.now();
            for (int i = 0; i < MESSAGE_COUNT; i++) {
                PerformanceTestMessage testMessage = new PerformanceTestMessage(
                    "msg-" + i,
                    "Performance test message " + i + " for " + configName,
                    configName
                );

                producer.send(testMessage);
                logger.debug("Sent message {} for config {}", i, configName);
            }
            Instant sendingEndTime = Instant.now();
            long sendingTimeMs = Duration.between(sendingStartTime, sendingEndTime).toMillis();

            logger.info("📤 Sent {} messages in {}ms", MESSAGE_COUNT, sendingTimeMs);

            // Wait for processing to complete (with timeout)
            boolean completed = allProcessed.await(30, TimeUnit.SECONDS);
            Instant endTime = Instant.now();

            long totalTimeMs = Duration.between(startTime, endTime).toMillis();
            long processingTimeMs = Duration.between(consumerStartTime, endTime).toMillis();

            int processed = processedCount.get();
            double throughputMsgPerSec = processed > 0 ? (processed * 1000.0) / totalTimeMs : 0.0;
            double avgProcessingTimeMs = processed > 0 ? (double) totalProcessingTime.get() / processed : 0.0;

            logger.info("📊 Performance Results for {}:", configName);
            logger.info("   Completed: {}", completed);
            logger.info("   📈 Processed: {}/{} messages", processed, MESSAGE_COUNT);
            logger.info("   ⏱️ Total Time: {}ms", totalTimeMs);
            logger.info("   📤 Sending Time: {}ms", sendingTimeMs);
            logger.info("   🔄 Processing Time: {}ms", processingTimeMs);
            logger.info("   🚀 Throughput: {:.2f} messages/second", throughputMsgPerSec);
            logger.info("   ⚡ Avg Processing Time: {:.2f}ms per message", avgProcessingTimeMs);

            // Close resources
            consumer.close();
            producer.close();

            return new PerformanceResult(
                configName, threads, batchSize, pollingInterval, completed,
                processed, totalTimeMs, sendingTimeMs, processingTimeMs,
                throughputMsgPerSec, avgProcessingTimeMs
            );

        } catch (Exception e) {
            logger.error("Configuration test failed for {}: {}", configName, e.getMessage(), e);

            long totalTimeMs = Duration.between(startTime, Instant.now()).toMillis();
            return new PerformanceResult(
                configName, threads, batchSize, pollingInterval, false,
                0, totalTimeMs, 0, 0, 0.0, 0.0
            );
        }
    }

    /**
     * Displays a comparison of all performance results.
     */
    private void displayPerformanceComparison(PerformanceResult... results) {
        logger.info("\n" + "=".repeat(80));
        logger.info("📊 PERFORMANCE COMPARISON RESULTS");
        logger.info("=".repeat(80));

        logger.info(String.format("%-20s %-8s %-10s %-12s %-10s %-12s %-10s",
            "Configuration", "Threads", "BatchSize", "Polling", "Processed", "TotalTime", "Throughput"));
        logger.info("-".repeat(80));

        for (PerformanceResult result : results) {
            logger.info(String.format("%-20s %-8d %-10d %-12s %-10d %-12dms %-10.2f",
                result.configName, result.threads, result.batchSize, result.pollingInterval,
                result.processedMessages, result.totalTimeMs, result.throughputMsgPerSec));
        }

        logger.info("-".repeat(80));

        // Find best performing configuration
        PerformanceResult best = null;
        for (PerformanceResult result : results) {
            if (result.completed && (best == null || result.throughputMsgPerSec > best.throughputMsgPerSec)) {
                best = result;
            }
        }

        if (best != null) {
            logger.info("🏆 Best Performance: {} with {} messages/second",
                best.configName, String.format("%.2f", best.throughputMsgPerSec));
            logger.info("🔧 Optimal Settings: {} threads, batch size {}, polling interval {}",
                best.threads, best.batchSize, best.pollingInterval);
        }

        logger.info("=".repeat(80));
    }

    /**
     * Performance test result data.
     */
    static class PerformanceResult {
        final String configName;
        final int threads;
        final int batchSize;
        final String pollingInterval;
        final boolean completed;
        final int processedMessages;
        final long totalTimeMs;
        final long sendingTimeMs;
        final long processingTimeMs;
        final double throughputMsgPerSec;
        final double avgProcessingTimeMs;

        PerformanceResult(String configName, int threads, int batchSize, String pollingInterval,
                         boolean completed, int processedMessages, long totalTimeMs, long sendingTimeMs,
                         long processingTimeMs, double throughputMsgPerSec, double avgProcessingTimeMs) {
            this.configName = configName;
            this.threads = threads;
            this.batchSize = batchSize;
            this.pollingInterval = pollingInterval;
            this.completed = completed;
            this.processedMessages = processedMessages;
            this.totalTimeMs = totalTimeMs;
            this.sendingTimeMs = sendingTimeMs;
            this.processingTimeMs = processingTimeMs;
            this.throughputMsgPerSec = throughputMsgPerSec;
            this.avgProcessingTimeMs = avgProcessingTimeMs;
        }
    }

    /**
     * Test message for performance testing.
     */
    public static class PerformanceTestMessage {
        public String id;
        public String content;
        public String configName;
        public long timestamp;

        public PerformanceTestMessage() {}

        public PerformanceTestMessage(String id, String content, String configName) {
            this.id = id;
            this.content = content;
            this.configName = configName;
            this.timestamp = System.currentTimeMillis();
        }

        public String getId() { return id; }
        public String getContent() { return content; }
        public String getConfigName() { return configName; }
        public long getTimestamp() { return timestamp; }
    }
}


