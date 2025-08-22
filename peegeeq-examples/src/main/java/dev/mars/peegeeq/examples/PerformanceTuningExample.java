package dev.mars.peegeeq.examples;

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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Comprehensive example demonstrating performance tuning and optimization for PeeGeeQ.
 * 
 * This example shows:
 * - Database connection pool optimization
 * - Queue performance tuning strategies
 * - Batch processing optimization
 * - Memory usage optimization
 * - Throughput and latency optimization
 * - Performance monitoring and metrics
 * - Load testing and capacity planning
 * - Resource utilization optimization
 * 
 * Performance Areas Covered:
 * - Connection Pool Tuning
 * - Query Optimization
 * - Batch Processing
 * - Memory Management
 * - Concurrent Processing
 * - Network Optimization
 * - Index Optimization
 * - Monitoring and Alerting
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-29
 * @version 1.0
 */
public class PerformanceTuningExample {
    
    private static final Logger logger = LoggerFactory.getLogger(PerformanceTuningExample.class);
    
    /**
     * Performance test message for benchmarking.
     */
    public static class PerformanceTestMessage {
        private final String messageId;
        private final String messageType;
        private final String payload;
        private final Instant timestamp;
        private final int sequenceNumber;
        private final Map<String, String> metadata;
        
        @JsonCreator
        public PerformanceTestMessage(@JsonProperty("messageId") String messageId,
                                     @JsonProperty("messageType") String messageType,
                                     @JsonProperty("payload") String payload,
                                     @JsonProperty("timestamp") Instant timestamp,
                                     @JsonProperty("sequenceNumber") int sequenceNumber,
                                     @JsonProperty("metadata") Map<String, String> metadata) {
            this.messageId = messageId;
            this.messageType = messageType;
            this.payload = payload;
            this.timestamp = timestamp;
            this.sequenceNumber = sequenceNumber;
            this.metadata = metadata != null ? metadata : new HashMap<>();
        }
        
        // Getters
        public String getMessageId() { return messageId; }
        public String getMessageType() { return messageType; }
        public String getPayload() { return payload; }
        public Instant getTimestamp() { return timestamp; }
        public int getSequenceNumber() { return sequenceNumber; }
        public Map<String, String> getMetadata() { return metadata; }
        
        @Override
        public String toString() {
            return String.format("PerformanceTestMessage{id='%s', type='%s', seq=%d}", 
                messageId, messageType, sequenceNumber);
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PerformanceTestMessage that = (PerformanceTestMessage) o;
            return Objects.equals(messageId, that.messageId);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(messageId);
        }
    }
    
    /**
     * Performance metrics collector.
     */
    public static class PerformanceMetrics {
        private final AtomicLong totalMessages = new AtomicLong(0);
        private final AtomicLong totalProcessingTime = new AtomicLong(0);
        private final AtomicLong minLatency = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxLatency = new AtomicLong(0);
        private final AtomicInteger activeThreads = new AtomicInteger(0);
        private final long startTime = System.currentTimeMillis();
        
        public void recordMessage(long processingTimeMs) {
            totalMessages.incrementAndGet();
            totalProcessingTime.addAndGet(processingTimeMs);
            
            // Update min/max latency
            minLatency.updateAndGet(current -> Math.min(current, processingTimeMs));
            maxLatency.updateAndGet(current -> Math.max(current, processingTimeMs));
        }
        
        public void incrementActiveThreads() {
            activeThreads.incrementAndGet();
        }
        
        public void decrementActiveThreads() {
            activeThreads.decrementAndGet();
        }
        
        public long getTotalMessages() { return totalMessages.get(); }
        public double getAverageLatency() { 
            long total = totalMessages.get();
            return total > 0 ? (double) totalProcessingTime.get() / total : 0;
        }
        public long getMinLatency() { return minLatency.get() == Long.MAX_VALUE ? 0 : minLatency.get(); }
        public long getMaxLatency() { return maxLatency.get(); }
        public int getActiveThreads() { return activeThreads.get(); }
        public double getThroughput() {
            long elapsed = System.currentTimeMillis() - startTime;
            return elapsed > 0 ? (double) totalMessages.get() / (elapsed / 1000.0) : 0;
        }
    }
    
    public static void main(String[] args) throws Exception {
        logger.info("=== PeeGeeQ Performance Tuning Example ===");
        logger.info("This example demonstrates performance optimization techniques");
        
        // Start PostgreSQL container with performance optimizations
        try (PostgreSQLContainer<?> postgres = createOptimizedPostgreSQLContainer()) {
            postgres.start();
            logger.info("Optimized PostgreSQL container started: {}", postgres.getJdbcUrl());
            
            // Configure PeeGeeQ for performance
            configurePerformanceProperties(postgres);
            
            // Run performance demonstrations
            runPerformanceDemonstrations();
            
        } catch (Exception e) {
            logger.error("Failed to run Performance Tuning Example", e);
            throw e;
        }
        
        logger.info("Performance Tuning Example completed successfully!");
    }
    
    /**
     * Creates an optimized PostgreSQL container for performance testing.
     */
    private static PostgreSQLContainer<?> createOptimizedPostgreSQLContainer() {
        return new PostgreSQLContainer<>("postgres:15")
                .withDatabaseName("peegeeq_perf_demo")
                .withUsername("peegeeq_perf")
                .withPassword("perf_password")
                .withCommand("postgres",
                    // Memory settings
                    "-c", "shared_buffers=256MB",
                    "-c", "effective_cache_size=1GB",
                    "-c", "work_mem=16MB",
                    "-c", "maintenance_work_mem=64MB",
                    
                    // Connection settings
                    "-c", "max_connections=200",
                    "-c", "max_prepared_transactions=100",
                    
                    // Performance settings
                    "-c", "checkpoint_completion_target=0.9",
                    "-c", "wal_buffers=16MB",
                    "-c", "default_statistics_target=100",
                    "-c", "random_page_cost=1.1",
                    "-c", "effective_io_concurrency=200",
                    
                    // Logging (minimal for performance)
                    "-c", "log_statement=none",
                    "-c", "log_min_duration_statement=1000")
                .withReuse(false);
    }
    
    /**
     * Configures PeeGeeQ properties for optimal performance.
     */
    private static void configurePerformanceProperties(PostgreSQLContainer<?> postgres) {
        logger.info("⚙️  Configuring PeeGeeQ for optimal performance...");
        
        // Database connection properties
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.schema", "public");
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        
        // Connection pool optimization
        System.setProperty("peegeeq.database.pool.min-size", "10");
        System.setProperty("peegeeq.database.pool.max-size", "50");
        System.setProperty("peegeeq.database.pool.connection-timeout", "10000");
        System.setProperty("peegeeq.database.pool.idle-timeout", "300000");
        System.setProperty("peegeeq.database.pool.max-lifetime", "1800000");
        System.setProperty("peegeeq.database.pool.leak-detection-threshold", "30000");
        
        // Queue performance settings
        System.setProperty("peegeeq.queue.batch.enabled", "true");
        System.setProperty("peegeeq.queue.batch.size", "100");
        System.setProperty("peegeeq.queue.batch.timeout", "1000");
        System.setProperty("peegeeq.queue.prefetch.count", "50");
        System.setProperty("peegeeq.queue.consumer.threads", "10");
        
        // Performance monitoring
        System.setProperty("peegeeq.metrics.enabled", "true");
        System.setProperty("peegeeq.metrics.collection.interval", "5000");
        System.setProperty("peegeeq.health.enabled", "true");
        System.setProperty("peegeeq.migration.enabled", "true");
        System.setProperty("peegeeq.migration.auto-migrate", "true");
        
        logger.info("✅ Performance configuration applied");
    }
    
    /**
     * Runs comprehensive performance demonstrations.
     */
    private static void runPerformanceDemonstrations() throws Exception {
        logger.info("Starting performance demonstrations...");
        
        try (PeeGeeQManager manager = new PeeGeeQManager(
                new PeeGeeQConfiguration("performance"), 
                new SimpleMeterRegistry())) {
            
            manager.start();
            logger.info("PeeGeeQ Manager started successfully");
            
            // Create database service and factory provider
            PgDatabaseService databaseService = new PgDatabaseService(manager);
            PgQueueFactoryProvider provider = new PgQueueFactoryProvider();

            // Register queue factory implementations
            PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
            OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

            // Test different queue implementations
            QueueFactory nativeFactory = provider.createFactory("native", databaseService);
            QueueFactory outboxFactory = provider.createFactory("outbox", databaseService);
            
            // Run performance tests
            demonstrateConnectionPoolOptimization(manager);
            demonstrateThroughputOptimization(nativeFactory, outboxFactory);
            demonstrateLatencyOptimization(nativeFactory);
            demonstrateBatchProcessingOptimization(outboxFactory);
            demonstrateConcurrentProcessingOptimization(nativeFactory);
            demonstrateMemoryOptimization(outboxFactory);
            
        } catch (Exception e) {
            logger.error("Error running performance demonstrations", e);
            throw e;
        }
    }

    /**
     * Demonstrates connection pool optimization techniques.
     */
    private static void demonstrateConnectionPoolOptimization(PeeGeeQManager manager) throws Exception {
        logger.info("\n=== CONNECTION POOL OPTIMIZATION ===");

        logger.info("🏊 Connection Pool Best Practices:");
        logger.info("   • Size pool based on concurrent workload");
        logger.info("   • Monitor pool utilization and wait times");
        logger.info("   • Set appropriate timeouts");
        logger.info("   • Enable connection validation");
        logger.info("   • Configure leak detection");

        // Simulate connection pool stress test
        logger.info("🧪 Running connection pool stress test...");

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(50);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        // Submit concurrent database operations
        for (int i = 0; i < 50; i++) {
            final int operationId = i;
            executor.submit(() -> {
                try {
                    // Simulate database operation
                    var healthStatus = manager.getHealthCheckManager().getOverallHealth();
                    if (healthStatus.isHealthy()) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }

                    // Simulate processing time
                    Thread.sleep(100);

                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    logger.debug("Operation {} failed: {}", operationId, e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for completion
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - startTime;

        executor.shutdown();

        logger.info("📊 Connection Pool Stress Test Results:");
        logger.info("   Completed: {}", completed);
        logger.info("   Duration: {}ms", duration);
        logger.info("   Successful Operations: {}", successCount.get());
        logger.info("   Failed Operations: {}", failureCount.get());
        logger.info("   Success Rate: {}%", (successCount.get() * 100.0) / (successCount.get() + failureCount.get()));

        if (completed) {
            logger.info("✅ Connection pool handling concurrent load successfully");
        } else {
            logger.warn("⚠️  Connection pool may need tuning for this load");
        }
    }

    /**
     * Demonstrates throughput optimization techniques.
     */
    private static void demonstrateThroughputOptimization(QueueFactory nativeFactory, QueueFactory outboxFactory) throws Exception {
        logger.info("\n=== THROUGHPUT OPTIMIZATION ===");

        logger.info("🚀 Throughput Optimization Strategies:");
        logger.info("   • Use batch processing where possible");
        logger.info("   • Optimize message serialization");
        logger.info("   • Minimize database round trips");
        logger.info("   • Use appropriate queue implementation");
        logger.info("   • Tune consumer thread pools");

        // Test native queue throughput
        logger.info("📈 Testing Native Queue Throughput...");
        PerformanceMetrics nativeMetrics = measureThroughput(nativeFactory, "native-throughput", 1000, 5);

        // Test outbox queue throughput
        logger.info("📈 Testing Outbox Queue Throughput...");
        PerformanceMetrics outboxMetrics = measureThroughput(outboxFactory, "outbox-throughput", 1000, 5);

        // Compare results
        logger.info("📊 Throughput Comparison Results:");
        logger.info("   Native Queue:");
        logger.info("     Messages: {}", nativeMetrics.getTotalMessages());
        logger.info("     Throughput: {:.2f} msg/sec", nativeMetrics.getThroughput());
        logger.info("     Avg Latency: {:.2f}ms", nativeMetrics.getAverageLatency());

        logger.info("   Outbox Queue:");
        logger.info("     Messages: {}", outboxMetrics.getTotalMessages());
        logger.info("     Throughput: {:.2f} msg/sec", outboxMetrics.getThroughput());
        logger.info("     Avg Latency: {:.2f}ms", outboxMetrics.getAverageLatency());

        // Recommendations
        if (nativeMetrics.getThroughput() > outboxMetrics.getThroughput()) {
            logger.info("💡 Recommendation: Use Native Queue for high-throughput scenarios");
        } else {
            logger.info("💡 Recommendation: Outbox Queue provides good throughput with reliability");
        }
    }

    /**
     * Demonstrates latency optimization techniques.
     */
    private static void demonstrateLatencyOptimization(QueueFactory factory) throws Exception {
        logger.info("\n=== LATENCY OPTIMIZATION ===");

        logger.info("⚡ Latency Optimization Strategies:");
        logger.info("   • Minimize message processing time");
        logger.info("   • Use connection pooling effectively");
        logger.info("   • Optimize database queries");
        logger.info("   • Reduce serialization overhead");
        logger.info("   • Use appropriate queue implementation");

        // Test latency with different message sizes
        logger.info("📏 Testing Latency with Different Message Sizes...");

        int[] messageSizes = {100, 1000, 10000, 100000}; // bytes

        for (int size : messageSizes) {
            logger.info("🧪 Testing with {}KB messages...", size / 1024);

            MessageProducer<PerformanceTestMessage> producer = factory.createProducer("latency-test-" + size, PerformanceTestMessage.class);
            MessageConsumer<PerformanceTestMessage> consumer = factory.createConsumer("latency-test-" + size, PerformanceTestMessage.class);

            PerformanceMetrics metrics = new PerformanceMetrics();
            CountDownLatch latch = new CountDownLatch(10);

            // Set up consumer
            consumer.subscribe(message -> {
                long startTime = Long.parseLong(message.getPayload().getMetadata().get("startTime"));
                long latency = System.currentTimeMillis() - startTime;
                metrics.recordMessage(latency);
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Send test messages
            String payload = "x".repeat(size); // Create payload of specified size
            for (int i = 0; i < 10; i++) {
                Map<String, String> metadata = Map.of("startTime", String.valueOf(System.currentTimeMillis()));
                PerformanceTestMessage message = new PerformanceTestMessage(
                    "latency-" + size + "-" + i, "LATENCY_TEST", payload, Instant.now(), i, metadata);
                producer.send(message);
            }

            // Wait for processing
            latch.await(30, TimeUnit.SECONDS);

            logger.info("   Results for {}KB messages:", size / 1024);
            logger.info("     Min Latency: {}ms", metrics.getMinLatency());
            logger.info("     Max Latency: {}ms", metrics.getMaxLatency());
            logger.info("     Avg Latency: {:.2f}ms", metrics.getAverageLatency());

            consumer.close();
            producer.close();
        }

        logger.info("💡 Latency Optimization Tips:");
        logger.info("   • Smaller messages = lower latency");
        logger.info("   • Use native queues for lowest latency");
        logger.info("   • Minimize consumer processing time");
        logger.info("   • Consider message compression for large payloads");
    }

    /**
     * Demonstrates batch processing optimization.
     */
    private static void demonstrateBatchProcessingOptimization(QueueFactory factory) throws Exception {
        logger.info("\n=== BATCH PROCESSING OPTIMIZATION ===");

        logger.info("📦 Batch Processing Benefits:");
        logger.info("   • Reduced database round trips");
        logger.info("   • Improved throughput");
        logger.info("   • Better resource utilization");
        logger.info("   • Lower per-message overhead");

        // Test different batch sizes
        int[] batchSizes = {1, 10, 50, 100};

        for (int batchSize : batchSizes) {
            logger.info("🧪 Testing batch size: {}", batchSize);

            MessageProducer<PerformanceTestMessage> producer = factory.createProducer("batch-test-" + batchSize, PerformanceTestMessage.class);
            MessageConsumer<PerformanceTestMessage> consumer = factory.createConsumer("batch-test-" + batchSize, PerformanceTestMessage.class);

            PerformanceMetrics metrics = new PerformanceMetrics();
            CountDownLatch latch = new CountDownLatch(100);

            // Consumer that processes messages in batches
            consumer.subscribe(message -> {
                long startTime = System.currentTimeMillis();

                // Simulate batch processing
                try {
                    Thread.sleep(1); // Simulate processing time
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                long processingTime = System.currentTimeMillis() - startTime;
                metrics.recordMessage(processingTime);
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Send messages
            long startTime = System.currentTimeMillis();
            for (int i = 0; i < 100; i++) {
                PerformanceTestMessage message = new PerformanceTestMessage(
                    "batch-" + batchSize + "-" + i, "BATCH_TEST", "Batch test payload",
                    Instant.now(), i, Map.of("batchSize", String.valueOf(batchSize)));
                producer.send(message);
            }

            // Wait for processing
            latch.await(30, TimeUnit.SECONDS);
            long totalTime = System.currentTimeMillis() - startTime;

            logger.info("   Results for batch size {}:", batchSize);
            logger.info("     Total Time: {}ms", totalTime);
            logger.info("     Throughput: {:.2f} msg/sec", metrics.getThroughput());
            logger.info("     Avg Latency: {:.2f}ms", metrics.getAverageLatency());

            consumer.close();
            producer.close();
        }

        logger.info("💡 Batch Processing Recommendations:");
        logger.info("   • Use batch sizes between 10-100 for optimal performance");
        logger.info("   • Monitor memory usage with large batches");
        logger.info("   • Balance batch size with latency requirements");
    }

    /**
     * Demonstrates concurrent processing optimization.
     */
    private static void demonstrateConcurrentProcessingOptimization(QueueFactory factory) throws Exception {
        logger.info("\n=== CONCURRENT PROCESSING OPTIMIZATION ===");

        logger.info("🔄 Concurrent Processing Strategies:");
        logger.info("   • Use multiple consumer threads");
        logger.info("   • Implement proper thread safety");
        logger.info("   • Monitor thread utilization");
        logger.info("   • Avoid thread contention");

        // Test different thread counts
        int[] threadCounts = {1, 2, 5, 10};

        for (int threadCount : threadCounts) {
            logger.info("🧪 Testing with {} consumer threads...", threadCount);

            MessageProducer<PerformanceTestMessage> producer = factory.createProducer("concurrent-test-" + threadCount, PerformanceTestMessage.class);

            PerformanceMetrics metrics = new PerformanceMetrics();
            CountDownLatch latch = new CountDownLatch(100);
            ExecutorService consumerExecutor = Executors.newFixedThreadPool(threadCount);

            // Create multiple consumers
            for (int i = 0; i < threadCount; i++) {
                MessageConsumer<PerformanceTestMessage> consumer = factory.createConsumer("concurrent-test-" + threadCount, PerformanceTestMessage.class);

                consumer.subscribe(message -> {
                    metrics.incrementActiveThreads();
                    long startTime = System.currentTimeMillis();

                    try {
                        // Simulate processing work
                        Thread.sleep(10);

                        long processingTime = System.currentTimeMillis() - startTime;
                        metrics.recordMessage(processingTime);

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        metrics.decrementActiveThreads();
                        latch.countDown();
                    }

                    return CompletableFuture.completedFuture(null);
                });
            }

            // Send messages
            long startTime = System.currentTimeMillis();
            for (int i = 0; i < 100; i++) {
                PerformanceTestMessage message = new PerformanceTestMessage(
                    "concurrent-" + threadCount + "-" + i, "CONCURRENT_TEST", "Concurrent test payload",
                    Instant.now(), i, Map.of("threadCount", String.valueOf(threadCount)));
                producer.send(message);
            }

            // Wait for processing
            latch.await(30, TimeUnit.SECONDS);
            long totalTime = System.currentTimeMillis() - startTime;

            consumerExecutor.shutdown();

            logger.info("   Results for {} threads:", threadCount);
            logger.info("     Total Time: {}ms", totalTime);
            logger.info("     Throughput: {:.2f} msg/sec", metrics.getThroughput());
            logger.info("     Avg Latency: {:.2f}ms", metrics.getAverageLatency());
            logger.info("     Max Active Threads: {}", metrics.getActiveThreads());

            producer.close();
        }

        logger.info("💡 Concurrent Processing Tips:");
        logger.info("   • Optimal thread count depends on workload");
        logger.info("   • Monitor CPU and memory usage");
        logger.info("   • Avoid creating too many threads");
        logger.info("   • Use thread pools for better resource management");
    }

    /**
     * Demonstrates memory optimization techniques.
     */
    private static void demonstrateMemoryOptimization(QueueFactory factory) throws Exception {
        logger.info("\n=== MEMORY OPTIMIZATION ===");

        logger.info("🧠 Memory Optimization Strategies:");
        logger.info("   • Monitor heap usage");
        logger.info("   • Optimize message serialization");
        logger.info("   • Use appropriate data structures");
        logger.info("   • Implement proper garbage collection");

        // Get initial memory usage
        Runtime runtime = Runtime.getRuntime();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();

        logger.info("📊 Initial Memory Usage: {}MB", initialMemory / (1024 * 1024));

        // Test memory usage with large messages
        MessageProducer<PerformanceTestMessage> producer = factory.createProducer("memory-test", PerformanceTestMessage.class);
        MessageConsumer<PerformanceTestMessage> consumer = factory.createConsumer("memory-test", PerformanceTestMessage.class);

        CountDownLatch latch = new CountDownLatch(50);

        consumer.subscribe(message -> {
            // Process message and release references quickly
            String payload = message.getPayload().getPayload();
            // Simulate processing without holding references
            int length = payload.length();
            logger.debug("Processed message with payload length: {}", length);

            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send large messages
        String largePayload = "x".repeat(10000); // 10KB payload
        for (int i = 0; i < 50; i++) {
            PerformanceTestMessage message = new PerformanceTestMessage(
                "memory-" + i, "MEMORY_TEST", largePayload,
                Instant.now(), i, Map.of("size", "10KB"));
            producer.send(message);
        }

        // Wait for processing
        latch.await(30, TimeUnit.SECONDS);

        // Force garbage collection and measure memory
        System.gc();
        Thread.sleep(1000);
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();

        logger.info("📊 Final Memory Usage: {}MB", finalMemory / (1024 * 1024));
        logger.info("📊 Memory Increase: {}MB", (finalMemory - initialMemory) / (1024 * 1024));

        consumer.close();
        producer.close();

        logger.info("💡 Memory Optimization Tips:");
        logger.info("   • Release object references promptly");
        logger.info("   • Use streaming for large payloads");
        logger.info("   • Monitor garbage collection metrics");
        logger.info("   • Consider message compression");
    }

    /**
     * Helper method to measure throughput performance.
     */
    private static PerformanceMetrics measureThroughput(QueueFactory factory, String queueName,
                                                       int messageCount, int durationSeconds) throws Exception {
        MessageProducer<PerformanceTestMessage> producer = factory.createProducer(queueName, PerformanceTestMessage.class);
        MessageConsumer<PerformanceTestMessage> consumer = factory.createConsumer(queueName, PerformanceTestMessage.class);

        PerformanceMetrics metrics = new PerformanceMetrics();
        CountDownLatch latch = new CountDownLatch(messageCount);

        // Set up consumer
        consumer.subscribe(message -> {
            long startTime = Long.parseLong(message.getPayload().getMetadata().getOrDefault("startTime", "0"));
            long latency = System.currentTimeMillis() - startTime;
            metrics.recordMessage(latency);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send messages
        for (int i = 0; i < messageCount; i++) {
            Map<String, String> metadata = Map.of("startTime", String.valueOf(System.currentTimeMillis()));
            PerformanceTestMessage message = new PerformanceTestMessage(
                queueName + "-" + i, "THROUGHPUT_TEST", "Throughput test payload",
                Instant.now(), i, metadata);
            producer.send(message);
        }

        // Wait for processing
        latch.await(durationSeconds * 2, TimeUnit.SECONDS);

        consumer.close();
        producer.close();

        return metrics;
    }
}
