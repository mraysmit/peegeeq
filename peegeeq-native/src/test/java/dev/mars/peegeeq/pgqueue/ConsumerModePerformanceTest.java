package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.performance.SystemInfoCollector;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.BASIC;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance comparison tests for different consumer modes.
 * Tests throughput and latency across LISTEN_NOTIFY_ONLY, POLLING_ONLY, and HYBRID modes.
 */
@Tag(TestCategories.PERFORMANCE)
@ExtendWith(VertxExtension.class)
@Testcontainers
class ConsumerModePerformanceTest {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerModePerformanceTest.class);

    @Container
    static final PostgreSQLContainer postgres = PeeGeeQTestContainerFactory.createContainer(BASIC);

    private PeeGeeQManager manager;
    private QueueFactory factory;

    @BeforeAll
    static void beforeAll() {
        logger.info("=== CONSUMER MODE PERFORMANCE TEST SUITE ===");
        logger.info(SystemInfoCollector.formatAsSummary());
        PeeGeeQTestSchemaInitializer.initializeSchema(
                postgres,
                SchemaComponent.NATIVE_QUEUE,
                SchemaComponent.OUTBOX,
                SchemaComponent.DEAD_LETTER_QUEUE
        );
    }

    @BeforeEach
    void setUp(VertxTestContext testContext) throws InterruptedException {
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .property("peegeeq.queue.visibility-timeout", "PT30S")
                .property("peegeeq.metrics.enabled", "true")
                .property("peegeeq.circuit-breaker.enabled", "true")
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        manager.start()
                .onSuccess(v -> testContext.verify(() -> {
                    PgDatabaseService databaseService = new PgDatabaseService(manager);
                    PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
                    PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                    factory = provider.createFactory("native", databaseService);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "setUp timed out");
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        if (factory != null) {
            try {
                factory.close();
            } catch (Exception e) {
                logger.warn("factory.close() failed in teardown", e);
            }
            factory = null;
        }
        if (manager == null) {
            testContext.completeNow();
        } else {
            manager.closeReactive()
                    .onSuccess(v -> { manager = null; testContext.completeNow(); })
                    .onFailure(err -> {
                        logger.error("manager.closeReactive() failed in teardown", err);
                        manager = null;
                        testContext.failNow(err);
                    });
        }
        testContext.awaitCompletion(30, TimeUnit.SECONDS);
    }

    @Test
    void testThroughputComparison(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        String topicBase = "test-throughput-comparison";
        int messageCount = 100;
        int warmupMessages = 10;
        List<PerformanceResult> results = new ArrayList<>();

        measureThroughput(topicBase + "-listen", ConsumerMode.LISTEN_NOTIFY_ONLY, messageCount, warmupMessages, vertx)
                .compose(r -> { results.add(r); return measureThroughput(topicBase + "-polling", ConsumerMode.POLLING_ONLY, messageCount, warmupMessages, vertx); })
                .compose(r -> { results.add(r); return measureThroughput(topicBase + "-hybrid", ConsumerMode.HYBRID, messageCount, warmupMessages, vertx); })
                .onSuccess(r -> testContext.verify(() -> {
                    results.add(r);
                    assertEquals(3, results.size(), "Should have results for all 3 consumer modes");
                    for (PerformanceResult result : results) {
                        assertTrue(result.throughput > 5.0,
                                String.format("Mode %s should have throughput > 5 msg/sec, got %.2f",
                                        result.mode, result.throughput));
                        assertTrue(result.averageLatency < 10000,
                                String.format("Mode %s should have average latency < 10000ms, got %.2f",
                                        result.mode, result.averageLatency));
                    }
                    PerformanceResult hybridResult = results.stream()
                            .filter(res -> res.mode == ConsumerMode.HYBRID)
                            .findFirst()
                            .orElseThrow();
                    assertTrue(hybridResult.throughput > 7.0,
                            String.format("HYBRID mode should have good throughput, got %.2f", hybridResult.throughput));
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(180, TimeUnit.SECONDS), "Throughput comparison test timed out");
    }

    @Test
    void testLatencyComparison(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        String topicBase = "test-latency-comparison";
        int messageCount = 50;
        List<LatencyResult> results = new ArrayList<>();

        measureLatency(topicBase + "-listen", ConsumerMode.LISTEN_NOTIFY_ONLY, messageCount, vertx)
                .compose(r -> { results.add(r); return measureLatency(topicBase + "-polling", ConsumerMode.POLLING_ONLY, messageCount, vertx); })
                .compose(r -> { results.add(r); return measureLatency(topicBase + "-hybrid", ConsumerMode.HYBRID, messageCount, vertx); })
                .onSuccess(r -> testContext.verify(() -> {
                    results.add(r);
                    for (LatencyResult result : results) {
                        assertTrue(result.minLatency >= 0, "Min latency should be non-negative");
                        assertTrue(result.maxLatency >= result.minLatency, "Max latency should be >= min latency");
                        assertTrue(result.averageLatency >= result.minLatency, "Average latency should be >= min latency");
                        assertTrue(result.p95Latency >= result.averageLatency, "P95 latency should be >= average latency");
                        assertTrue(result.averageLatency < 5000,
                                String.format("Mode %s average latency should be reasonable, got %.2f ms",
                                        result.mode, result.averageLatency));
                    }
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(120, TimeUnit.SECONDS), "Latency comparison test timed out");
    }

    private Future<PerformanceResult> measureThroughput(String topicName, ConsumerMode mode,
                                                         int messageCount, int warmupMessages,
                                                         Vertx vertx) {
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicLong totalLatency = new AtomicLong(0);
        long[] messageSentTimes = new long[messageCount + warmupMessages];
        long[] startTimeRef = {0L};
        Promise<Long> allProcessedSignal = Promise.promise();

        long timerId = vertx.setTimer(90_000, id ->
                allProcessedSignal.tryFail(new Exception(
                        "Timed out waiting for " + messageCount + " messages in throughput mode " + mode)));

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
                ConsumerConfig.builder()
                        .mode(mode)
                        .pollingInterval(Duration.ofMillis(100))
                        .build());

        return consumer.subscribe(message -> {
            long receiveTime = System.currentTimeMillis();
            int index = processedCount.incrementAndGet();
            if (index > warmupMessages) {
                long sendTime = messageSentTimes[index - 1];
                totalLatency.addAndGet(receiveTime - sendTime);
                if (index == messageCount + warmupMessages) {
                    allProcessedSignal.tryComplete(receiveTime);
                }
            }
            return Future.succeededFuture();
        })
        .compose(v -> {
            MessageProducer<String> producer = factory.createProducer(topicName, String.class);
            startTimeRef[0] = System.currentTimeMillis();

            Future<Void> sendChain = Future.succeededFuture();
            for (int i = 0; i < messageCount + warmupMessages; i++) {
                final int idx = i;
                sendChain = sendChain.compose(ignored -> {
                    messageSentTimes[idx] = System.currentTimeMillis();
                    return producer.send("Performance test message " + idx);
                });
            }

            return sendChain
                    .compose(ignored -> allProcessedSignal.future())
                    .eventually(() -> {
                        vertx.cancelTimer(timerId);
                        try { producer.close(); } catch (Exception e) { logger.warn("producer.close failed in throughput measurement", e); }
                        try { consumer.close(); } catch (Exception e) { logger.warn("consumer.close failed in throughput measurement", e); }
                        return Future.succeededFuture();
                    })
                    .map(endTime -> {
                        double durationSeconds = (endTime - startTimeRef[0]) / 1000.0;
                        double throughput = messageCount / durationSeconds;
                        double averageLatency = totalLatency.get() / (double) messageCount;
                        return new PerformanceResult(mode, throughput, averageLatency, messageCount);
                    });
        });
    }

    private Future<LatencyResult> measureLatency(String topicName, ConsumerMode mode,
                                                   int messageCount, Vertx vertx) {
        List<Long> latencies = new ArrayList<>();
        long[] messageSentTimes = new long[messageCount];
        AtomicInteger processedCount = new AtomicInteger(0);
        Promise<Void> allProcessedSignal = Promise.promise();

        long timerId = vertx.setTimer(60_000, id ->
                allProcessedSignal.tryFail(new Exception(
                        "Timed out waiting for " + messageCount + " messages in latency mode " + mode)));

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
                ConsumerConfig.builder()
                        .mode(mode)
                        .pollingInterval(Duration.ofMillis(100))
                        .build());

        return consumer.subscribe(message -> {
            long receiveTime = System.currentTimeMillis();
            int index = processedCount.getAndIncrement();
            if (index < messageCount) {
                long sendTime = messageSentTimes[index];
                long latency = receiveTime - sendTime;
                synchronized (latencies) {
                    latencies.add(latency);
                }
                if (index == messageCount - 1) {
                    allProcessedSignal.tryComplete();
                }
            }
            return Future.succeededFuture();
        })
        .compose(v -> {
            MessageProducer<String> producer = factory.createProducer(topicName, String.class);

            Future<Void> sendChain = Future.succeededFuture();
            for (int i = 0; i < messageCount; i++) {
                final int idx = i;
                sendChain = sendChain.compose(ignored -> {
                    messageSentTimes[idx] = System.currentTimeMillis();
                    return producer.send("Latency test message " + idx);
                });
            }

            return sendChain
                    .compose(ignored -> allProcessedSignal.future())
                    .eventually(() -> {
                        vertx.cancelTimer(timerId);
                        try { producer.close(); } catch (Exception e) { logger.warn("producer.close failed in latency measurement", e); }
                        try { consumer.close(); } catch (Exception e) { logger.warn("consumer.close failed in latency measurement", e); }
                        return Future.succeededFuture();
                    })
                    .map(ignored -> {
                        latencies.sort(Long::compareTo);
                        double minLatency = latencies.get(0);
                        double maxLatency = latencies.get(latencies.size() - 1);
                        double averageLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0.0);
                        double p95Latency = latencies.get((int) (latencies.size() * 0.95));
                        return new LatencyResult(mode, minLatency, maxLatency, averageLatency, p95Latency);
                    });
        });
    }

    private static class PerformanceResult {
        final ConsumerMode mode;
        final double throughput;
        final double averageLatency;

        PerformanceResult(ConsumerMode mode, double throughput, double averageLatency, int messageCount) {
            this.mode = mode;
            this.throughput = throughput;
            this.averageLatency = averageLatency;
        }
    }

    private static class LatencyResult {
        final ConsumerMode mode;
        final double minLatency;
        final double maxLatency;
        final double averageLatency;
        final double p95Latency;

        LatencyResult(ConsumerMode mode, double minLatency, double maxLatency,
                      double averageLatency, double p95Latency) {
            this.mode = mode;
            this.minLatency = minLatency;
            this.maxLatency = maxLatency;
            this.averageLatency = averageLatency;
            this.p95Latency = p95Latency;
        }
    }
}

