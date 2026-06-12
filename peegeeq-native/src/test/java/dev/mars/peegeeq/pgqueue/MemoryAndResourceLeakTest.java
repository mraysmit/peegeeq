package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
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
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Memory and Resource Leak Detection Tests for PeeGeeQ Native Queue.
 *
 * Tests critical memory and resource management scenarios:
 * - Sustained high-load memory leak testing
 * - Thread leak detection for rapid consumer creation/destruction
 * - Resource cleanup validation under stress conditions
 * - Memory usage monitoring during intensive operations
 *
 * These tests use real PostgreSQL with TestContainers and monitor actual
 * JVM memory and thread usage to detect leaks that could occur in production.
 *
 * Following established coding principles:
 * - Use real infrastructure (TestContainers) rather than mocks
 * - Monitor actual JVM resources (memory, threads) during testing
 * - Test sustained high-load scenarios that reveal gradual leaks
 * - Validate proper cleanup after intensive operations
 * - Use threshold-based detection to distinguish real leaks from normal variance
 */
@Tag(TestCategories.SLOW)
@ExtendWith(VertxExtension.class)
@Testcontainers
class MemoryAndResourceLeakTest {
    private static final Logger logger = LoggerFactory.getLogger(MemoryAndResourceLeakTest.class);

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory factory;
    private MemoryMXBean memoryBean;
    private ThreadMXBean threadBean;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Configure test properties using TestContainer pattern
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .property("peegeeq.queue.visibility-timeout", "PT5S")
                .property("peegeeq.queue.max-retries", "2")
                .property("peegeeq.metrics.enabled", "true")
                .property("peegeeq.circuit-breaker.enabled", "true")
                .property("peegeeq.queue.consumer-group-retry.enabled", "false")
                .property("peegeeq.queue.dead-consumer-detection.enabled", "false")
                .build();

        // Initialize JVM monitoring beans
        memoryBean = ManagementFactory.getMemoryMXBean();
        threadBean = ManagementFactory.getThreadMXBean();

        // Ensure required schema exists before starting PeeGeeQ
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.NATIVE_QUEUE,
                SchemaComponent.OUTBOX,
                SchemaComponent.DEAD_LETTER_QUEUE);

        // Initialize PeeGeeQ
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .onSuccess(v -> {
                    // Create factory using the proper pattern
                    PgDatabaseService databaseService = new PgDatabaseService(manager);
                    PgQueueFactoryProvider provider = new PgQueueFactoryProvider();

                    // Register native factory implementation
                    PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

                    factory = provider.createFactory("native", databaseService);
                    logger.info("Test setup completed for memory and resource leak testing");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "Manager should start within 30 seconds");
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        logger.info("Tearing down: closing resources and manager");
        (factory != null ? factory.close() : Future.<Void>succeededFuture())
                .compose(v -> manager != null ? manager.closeReactive() : Future.<Void>succeededFuture())
                .onSuccess(v -> {
                    logger.info("Test teardown completed");
                    testContext.completeNow();
                })
                .onFailure(err -> {
                    logger.warn("Error during teardown: {}", err.getMessage());
                    testContext.completeNow();
                });
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "Teardown should complete within 30 seconds");
    }

    @Test
    void testSustainedHighLoadMemoryLeakDetection(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info(" Testing sustained high-load memory leak detection");

        String topicName = "test-memory-leak";
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class);

        // Record initial memory usage
        MemoryUsage initialHeap = memoryBean.getHeapMemoryUsage();
        MemoryUsage initialNonHeap = memoryBean.getNonHeapMemoryUsage();
        long initialUsedHeap = initialHeap.getUsed();
        long initialUsedNonHeap = initialNonHeap.getUsed();

        logger.info("Initial memory - Heap: {} MB, Non-Heap: {} MB",
            initialUsedHeap / (1024 * 1024), initialUsedNonHeap / (1024 * 1024));

        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicLong totalProcessingTime = new AtomicLong(0);

        // Set up consumer - synchronous processing on event loop (no blocking I/O)
        consumer.subscribe(message -> {
            long startTime = System.nanoTime();
            String payload = message.getPayload();
            StringBuilder sb = new StringBuilder(payload);
            for (int i = 0; i < 10; i++) {
                sb.append("-processed-").append(i);
            }
            processedCount.incrementAndGet();
            totalProcessingTime.addAndGet(System.nanoTime() - startTime);
            return Future.succeededFuture();
        });

        // Sustained high-load test - send and process many messages
        int messageCount = 200;
        int batchSize = 20;

        logger.info("Starting sustained high-load test with {} messages in batches of {}", messageCount, batchSize);

        // Chain sends sequentially to avoid connection pool exhaustion
        Future<Void> sendChain = Future.succeededFuture();
        for (int batch = 0; batch < messageCount / batchSize; batch++) {
            if (batch % 5 == 0) {
                final int batchCapture = batch;
                sendChain = sendChain.compose(v -> {
                    MemoryUsage currentHeap = memoryBean.getHeapMemoryUsage();
                    MemoryUsage currentNonHeap = memoryBean.getNonHeapMemoryUsage();
                    logger.info("Batch {}: Heap: {} MB, Non-Heap: {} MB, Processed: {}",
                        batchCapture,
                        currentHeap.getUsed() / (1024 * 1024),
                        currentNonHeap.getUsed() / (1024 * 1024),
                        processedCount.get());
                    return Future.succeededFuture();
                });
            }
            for (int i = 0; i < batchSize; i++) {
                final int messageNum = batch * batchSize + i;
                sendChain = sendChain.compose(v -> producer.send("High-load test message " + messageNum).mapEmpty());
            }
        }

        // After all sends complete, wait for processing (95% threshold matches assertion tolerance)
        Promise<Void> allProcessed = Promise.promise();
        sendChain
            .compose(v -> {
                long processingTimer1 = vertx.setPeriodic(200, id -> {
                    if (processedCount.get() >= (int)(messageCount * 0.95)) {
                        allProcessed.tryComplete();
                    }
                });
                return allProcessed.future()
                    .onSuccess(ignored -> vertx.cancelTimer(processingTimer1));
            })
            .compose(v -> {
                // GC settle via timer (avoids blocking the event loop)
                System.gc();
                return vertx.timer(2000);
            })
            .onSuccess(v -> testContext.verify(() -> {
                MemoryUsage finalHeap = memoryBean.getHeapMemoryUsage();
                MemoryUsage finalNonHeap = memoryBean.getNonHeapMemoryUsage();
                long finalUsedHeap = finalHeap.getUsed();
                long finalUsedNonHeap = finalNonHeap.getUsed();

                logger.info("Final memory - Heap: {} MB, Non-Heap: {} MB",
                    finalUsedHeap / (1024 * 1024), finalUsedNonHeap / (1024 * 1024));

                long heapGrowth = finalUsedHeap - initialUsedHeap;
                long nonHeapGrowth = finalUsedNonHeap - initialUsedNonHeap;

                logger.info("Memory growth - Heap: {} MB, Non-Heap: {} MB",
                    heapGrowth / (1024 * 1024), nonHeapGrowth / (1024 * 1024));

                consumer.close();
                producer.close();

                long maxAcceptableHeapGrowth = 100 * 1024 * 1024;
                long maxAcceptableNonHeapGrowth = 50 * 1024 * 1024;
                double avgProcessingTime = processedCount.get() > 0
                    ? totalProcessingTime.get() / (double) processedCount.get() / 1_000_000
                    : 0.0;

                logger.info("Sustained high-load memory leak test completed - processed {} messages, avg time: {} ms",
                    processedCount.get(), String.format("%.2f", avgProcessingTime));

                assertTrue(processedCount.get() >= messageCount * 0.95,
                    "Should have processed at least 95% of messages: " + processedCount.get() + "/" + messageCount);
                assertTrue(heapGrowth < maxAcceptableHeapGrowth,
                    "Heap memory growth should be reasonable: " + (heapGrowth / (1024 * 1024)) + " MB");
                assertTrue(nonHeapGrowth < maxAcceptableNonHeapGrowth,
                    "Non-heap memory growth should be reasonable: " + (nonHeapGrowth / (1024 * 1024)) + " MB");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(120, TimeUnit.SECONDS),
            "Test should complete within 120 seconds");
    }

    @Test
    void testThreadLeakDetectionForRapidConsumerCreationDestruction(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info(" Testing thread leak detection for rapid consumer creation/destruction");

        String topicName = "test-thread-leak";
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        // Send some messages for consumers to process (chained to ensure they're sent before cycles start)
        Future<Void> preSends = Future.succeededFuture();
        for (int i = 0; i < 20; i++) {
            final int msgNum = i;
            preSends = preSends.compose(v -> producer.send("Thread leak test message " + msgNum).mapEmpty());
        }

        int consumerCycles = 20;
        int messagesPerConsumer = 1;
        int[] initialThreadCountHolder = {0};
        int[] initialThreadNamesCountHolder = {0};
        AtomicInteger totalProcessedCount = new AtomicInteger(0);

        preSends
            .compose(v -> {
                // Record initial thread count after pre-sends complete
                initialThreadCountHolder[0] = threadBean.getThreadCount();
                initialThreadNamesCountHolder[0] = (int) Thread.getAllStackTraces().keySet().stream()
                    .map(Thread::getName)
                    .filter(name -> name.contains("native-queue") || name.contains("vert.x") || name.contains("pool"))
                    .count();
                logger.info("Initial thread count: {}, relevant threads: {}",
                    initialThreadCountHolder[0], initialThreadNamesCountHolder[0]);

                // Rapid consumer creation and destruction test
                logger.info("Starting rapid consumer creation/destruction test with {} cycles", consumerCycles);

                Future<Void> cycleChain = Future.succeededFuture();
                for (int cycle = 0; cycle < consumerCycles; cycle++) {
                    final int cycleNum = cycle;
                    cycleChain = cycleChain.compose(ignored -> {
                        MessageConsumer<String> cycleCons = factory.createConsumer(topicName, String.class);
                        String consumerId = "consumer-" + cycleNum;
                        AtomicInteger cycleProcessedCount = new AtomicInteger(0);
                        Promise<Void> cycleDone = Promise.promise();

                        // Subscribe consumer - synchronous processing (no blocking I/O)
                        cycleCons.subscribe(message -> {
                            cycleProcessedCount.incrementAndGet();
                            totalProcessedCount.incrementAndGet();
                            cycleDone.tryComplete();
                            logger.debug("Consumer {} processed message: {}", consumerId, message.getPayload());
                            return Future.succeededFuture();
                        });

                        long timeoutId = vertx.setTimer(10000, id -> cycleDone.tryFail("Timeout for cycle " + cycleNum));

                        return cycleDone.future()
                            .transform(ar -> {
                                vertx.cancelTimer(timeoutId);
                                if (ar.failed()) {
                                    logger.info("Consumer {} timed out waiting for messages", consumerId);
                                }
                                cycleCons.close();

                                // Monitor thread count every 10 cycles
                                if (cycleNum % 10 == 0) {
                                    int currentThreadCount = threadBean.getThreadCount();
                                    long currentRelevantThreads = Thread.getAllStackTraces().keySet().stream()
                                        .map(Thread::getName)
                                        .filter(name -> name.contains("native-queue") || name.contains("vert.x") || name.contains("pool"))
                                        .count();
                                    logger.info("Cycle {}: Thread count: {}, relevant threads: {}, processed: {}",
                                        cycleNum, currentThreadCount, currentRelevantThreads, totalProcessedCount.get());
                                    if (currentThreadCount > initialThreadCountHolder[0] + 50) {
                                        logger.warn("Potential thread leak detected at cycle {}: {} threads (started with {})",
                                            cycleNum, currentThreadCount, initialThreadCountHolder[0]);
                                    }
                                }

                                // Small delay between cycles to allow cleanup
                                if (cycleNum % 5 == 0) {
                                    return vertx.timer(100);
                                }
                                return Future.succeededFuture();
                            });
                    });
                }
                return cycleChain;
            })
            .compose(v -> vertx.timer(10000))  // Allow time for thread cleanup
            .compose(v -> {
                System.gc();
                return vertx.timer(2000);  // GC settle
            })
            .onSuccess(v -> testContext.verify(() -> {
                int finalThreadCount = threadBean.getThreadCount();
                Set<String> finalThreadNames = Thread.getAllStackTraces().keySet().stream()
                    .map(Thread::getName)
                    .filter(name -> name.contains("native-queue") || name.contains("vert.x") || name.contains("pool"))
                    .collect(Collectors.toSet());

                logger.info("Final thread count: {}, relevant threads: {}", finalThreadCount, finalThreadNames.size());

                int threadGrowth = finalThreadCount - initialThreadCountHolder[0];
                int relevantThreadGrowth = finalThreadNames.size() - initialThreadNamesCountHolder[0];

                logger.info("Thread growth - Total: {}, Relevant: {}", threadGrowth, relevantThreadGrowth);

                producer.close();

                int expectedMessages = consumerCycles * messagesPerConsumer;
                int maxAcceptableThreadGrowth = 30;
                int maxAcceptableRelevantThreadGrowth = 15;

                if (threadGrowth >= maxAcceptableThreadGrowth) {
                    logger.warn("Thread growth exceeded threshold: {} (max: {})", threadGrowth, maxAcceptableThreadGrowth);
                }
                if (relevantThreadGrowth >= maxAcceptableRelevantThreadGrowth) {
                    logger.warn("Relevant thread growth exceeded threshold: {} (max: {})", relevantThreadGrowth, maxAcceptableRelevantThreadGrowth);
                }

                logger.info("Thread leak detection test completed - {} consumers created/destroyed, {} messages processed",
                    consumerCycles, totalProcessedCount.get());

                assertTrue(totalProcessedCount.get() >= expectedMessages * 0.5,
                    "Should have processed at least 50% of expected messages: " + totalProcessedCount.get() + "/" + expectedMessages);
                assertTrue(threadGrowth < maxAcceptableThreadGrowth * 2,
                    "Total thread growth should not be excessive: " + threadGrowth);
                assertTrue(relevantThreadGrowth < maxAcceptableRelevantThreadGrowth * 2,
                    "Relevant thread growth should not be excessive: " + relevantThreadGrowth);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(300, TimeUnit.SECONDS),
            "Test should complete within 300 seconds");
    }

    @Test
    void testResourceCleanupUnderStressConditions(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info(" Testing resource cleanup under stress conditions");

        String topicName = "test-resource-cleanup";

        // Record initial resource state
        MemoryUsage initialHeap = memoryBean.getHeapMemoryUsage();
        int initialThreadCount = threadBean.getThreadCount();

        logger.info("Initial state - Heap: {} MB, Threads: {}",
            initialHeap.getUsed() / (1024 * 1024), initialThreadCount);

        AtomicInteger totalProcessedCount = new AtomicInteger(0);
        List<MessageProducer<String>> producers = new ArrayList<>();
        List<MessageConsumer<String>> consumers = new ArrayList<>();

        // Create multiple producers and consumers simultaneously - reduced scale
        int resourceCount = 5;
        int messagesPerResource = 10;

        logger.info("Creating {} producers and consumers under stress", resourceCount);

        for (int i = 0; i < resourceCount; i++) {
            MessageProducer<String> producer = factory.createProducer(topicName + "-" + i, String.class);
            MessageConsumer<String> consumer = factory.createConsumer(topicName + "-" + i, String.class);

            producers.add(producer);
            consumers.add(consumer);

            final int resourceId = i;

            // Subscribe consumer - synchronous processing on event loop (no blocking I/O)
            consumer.subscribe(message -> {
                byte[] data = new byte[1024];
                for (int j = 0; j < data.length; j++) {
                    data[j] = (byte) (j % 256);
                }
                totalProcessedCount.incrementAndGet();
                return Future.succeededFuture();
            });

            // Send messages rapidly (fire-and-forget)
            for (int j = 0; j < messagesPerResource; j++) {
                producer.send("Stress test message " + j + " from resource " + i);
            }
        }

        // Allow processing time, then clean up and verify
        vertx.timer(15000)
            .onSuccess(v -> {
                // Monitor resource usage during stress
                MemoryUsage stressHeap = memoryBean.getHeapMemoryUsage();
                int stressThreadCount = threadBean.getThreadCount();
                logger.info("Under stress - Heap: {} MB, Threads: {}, Processed: {}",
                    stressHeap.getUsed() / (1024 * 1024), stressThreadCount, totalProcessedCount.get());

                // Clean up all resources
                logger.info("Cleaning up {} producers and {} consumers", producers.size(), consumers.size());
                for (MessageConsumer<String> consumer : consumers) {
                    consumer.close();
                }
                for (MessageProducer<String> producer : producers) {
                    producer.close();
                }
            })
            .compose(v -> vertx.timer(10000))  // Allow cleanup time
            .compose(v -> {
                System.gc();
                return vertx.timer(2000);  // GC settle
            })
            .onSuccess(v -> {
                MemoryUsage finalHeap = memoryBean.getHeapMemoryUsage();
                int finalThreadCount = threadBean.getThreadCount();

                logger.info("Final state - Heap: {} MB, Threads: {}",
                    finalHeap.getUsed() / (1024 * 1024), finalThreadCount);

                long heapGrowth = finalHeap.getUsed() - initialHeap.getUsed();
                int threadGrowth = finalThreadCount - initialThreadCount;

                logger.info("Resource growth - Heap: {} MB, Threads: {}",
                    heapGrowth / (1024 * 1024), threadGrowth);

                long maxAcceptableHeapGrowth = 100 * 1024 * 1024;
                int maxAcceptableThreadGrowth = 25;

                if (heapGrowth >= maxAcceptableHeapGrowth) {
                    logger.warn("Heap growth exceeded threshold: {} MB (max: {} MB)",
                        heapGrowth / (1024 * 1024), maxAcceptableHeapGrowth / (1024 * 1024));
                }
                if (threadGrowth >= maxAcceptableThreadGrowth) {
                    logger.warn("Thread growth exceeded threshold: {} (max: {})", threadGrowth, maxAcceptableThreadGrowth);
                }

                logger.info("Resource cleanup under stress test completed - processed {} messages",
                    totalProcessedCount.get());

                testContext.verify(() -> {
                    assertTrue(totalProcessedCount.get() > 0, "Should have processed some messages");
                    assertTrue(heapGrowth < maxAcceptableHeapGrowth * 2,
                        "Heap growth should not be excessive: " + (heapGrowth / (1024 * 1024)) + " MB");
                    assertTrue(threadGrowth < maxAcceptableThreadGrowth * 2,
                        "Thread growth should not be excessive: " + threadGrowth + " threads");
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS),
            "Test should complete within 60 seconds");
    }

    @Test
    void testMemoryUsageMonitoringDuringIntensiveOperations(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info(" Testing memory usage monitoring during intensive operations");

        String topicName = "test-memory-monitoring";
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class);

        // Record baseline memory
        MemoryUsage baselineHeap = memoryBean.getHeapMemoryUsage();
        logger.info("Baseline memory - Heap: {} MB", baselineHeap.getUsed() / (1024 * 1024));

        AtomicInteger processedCount = new AtomicInteger(0);
        List<Long> memorySnapshots = new ArrayList<>();

        // Set up consumer - synchronous memory-intensive processing on event loop
        consumer.subscribe(message -> {
            List<String> tempData = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                tempData.add("Memory intensive data " + i + " for message " + message.getPayload());
            }
            // Process the data (memory-intensive operation)
            tempData.stream()
                .filter(s -> s.contains("data"))
                .reduce("", (a, b) -> a.length() > 1000 ? a : a + b.substring(0, Math.min(10, b.length())));

            processedCount.incrementAndGet();
            tempData.clear();
            return Future.succeededFuture();
        });

        // Send messages and monitor memory usage - chained sequentially to avoid pool exhaustion
        int messageCount = 200;
        int monitoringInterval = 20;

        logger.info("Sending {} messages with memory monitoring every {} messages", messageCount, monitoringInterval);

        // Chain sends sequentially to avoid connection pool exhaustion
        Future<Void> sendChain = Future.succeededFuture();
        for (int i = 0; i < messageCount; i++) {
            final int msgNum = i;
            if (i % monitoringInterval == 0) {
                final int iCapture = i;
                sendChain = sendChain.compose(v -> {
                    MemoryUsage currentHeap = memoryBean.getHeapMemoryUsage();
                    long currentUsed = currentHeap.getUsed();
                    memorySnapshots.add(currentUsed);
                    logger.info("Message {}: Heap: {} MB, Processed: {}",
                        iCapture, currentUsed / (1024 * 1024), processedCount.get());
                    if (iCapture % (monitoringInterval * 2) == 0) {
                        System.gc();
                    }
                    return Future.succeededFuture();
                });
            }
            sendChain = sendChain.compose(v -> producer.send("Memory monitoring test message " + msgNum).mapEmpty());
        }

        // Wait for processing to complete (95% threshold matches assertion tolerance)
        Promise<Void> allProcessed4 = Promise.promise();
        sendChain
            .compose(v -> {
                long processingTimer4 = vertx.setPeriodic(200, id -> {
                    if (processedCount.get() >= (int)(messageCount * 0.95)) {
                        allProcessed4.tryComplete();
                    }
                });
                return allProcessed4.future()
                    .onSuccess(ignored -> vertx.cancelTimer(processingTimer4));
            })
            .compose(v -> {
                System.gc();
                return vertx.timer(1000);  // GC settle
            })
            .onSuccess(v -> testContext.verify(() -> {
                MemoryUsage finalHeap = memoryBean.getHeapMemoryUsage();
                memorySnapshots.add(finalHeap.getUsed());

                logger.info("Final memory - Heap: {} MB", finalHeap.getUsed() / (1024 * 1024));

                long maxMemory = memorySnapshots.stream().mapToLong(Long::longValue).max().orElse(0);
                long minMemory = memorySnapshots.stream().mapToLong(Long::longValue).min().orElse(0);
                long avgMemory = (long) memorySnapshots.stream().mapToLong(Long::longValue).average().orElse(0);

                logger.info("Memory analysis - Min: {} MB, Max: {} MB, Avg: {} MB",
                    minMemory / (1024 * 1024), maxMemory / (1024 * 1024), avgMemory / (1024 * 1024));

                consumer.close();
                producer.close();

                long memoryRange = maxMemory - minMemory;
                long maxAcceptableRange = 200 * 1024 * 1024;
                long finalGrowth = finalHeap.getUsed() - baselineHeap.getUsed();
                long maxAcceptableFinalGrowth = 100 * 1024 * 1024;

                logger.info("Memory usage monitoring test completed - processed {} messages", processedCount.get());

                assertTrue(processedCount.get() >= messageCount * 0.95,
                    "Should have processed at least 95% of messages: " + processedCount.get() + "/" + messageCount);
                assertTrue(memoryRange < maxAcceptableRange,
                    "Memory usage range should be reasonable: " + (memoryRange / (1024 * 1024)) + " MB");
                assertTrue(finalGrowth < maxAcceptableFinalGrowth,
                    "Final memory growth should be reasonable: " + (finalGrowth / (1024 * 1024)) + " MB");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(120, TimeUnit.SECONDS),
            "Test should complete within 120 seconds");
    }
}


