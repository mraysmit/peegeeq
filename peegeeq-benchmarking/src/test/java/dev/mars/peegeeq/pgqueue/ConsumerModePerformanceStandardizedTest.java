package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.consumer.ConsumerModePerformanceTestBase;
import dev.mars.peegeeq.test.consumer.ConsumerModeTestScenario;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.vertx.core.Future;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Standardized consumer mode performance tests using the new ConsumerModePerformanceTestBase.
 * This class demonstrates migration from the old ConsumerModePerformanceTest to the new
 * standardized testing patterns with parameterized testing across performance profiles.
 *
 * <p>This migration showcases:
 * <ul>
 *   <li>Parameterized testing across multiple performance profiles and consumer modes</li>
 *   <li>Standardized PostgreSQL container setup with performance-specific configurations</li>
 *   <li>Consistent metrics collection and comparison</li>
 *   <li>Reduced boilerplate code through inheritance</li>
 *   <li>Better test isolation and reproducibility</li>
 * </ul>
 *
 * @see ConsumerModePerformanceTestBase
 * @see ConsumerModeTestScenario
 */
@Tag(TestCategories.PERFORMANCE)
@ExtendWith(VertxExtension.class)
public class ConsumerModePerformanceStandardizedTest extends ConsumerModePerformanceTestBase {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerModePerformanceStandardizedTest.class);

    private PeeGeeQManager manager;
    private QueueFactory factory;

    @BeforeAll
    static void logMigrationInfo() {
        logger.info("=== STANDARDIZED CONSUMER MODE PERFORMANCE TEST SUITE ===");
        logger.info("This test demonstrates migration to standardized testing patterns:");
        logger.info("- Parameterized testing across performance profiles and consumer modes");
        logger.info("- Standardized PostgreSQL container configurations");
        logger.info("- Consistent metrics collection and comparison");
        logger.info("- Reduced boilerplate through ConsumerModePerformanceTestBase");
        logger.info("=== Starting Standardized Performance Tests ===");
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        logger.info("Tearing down: closing resources and manager");
        closeResources()
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "tearDown timed out");
    }

    private Future<Void> initializeManagerAndFactory() {
        PeeGeeQTestSchemaInitializer.initializeSchema(
            container,
            PostgreSQLTestConstants.TEST_SCHEMA,
            SchemaComponent.NATIVE_QUEUE,
            SchemaComponent.OUTBOX,
            SchemaComponent.DEAD_LETTER_QUEUE);

        // Configure test properties using container from base class
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(container)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .build();

        // Initialize PeeGeeQ with test configuration
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        return manager.start()
            .map(v -> {
                PgDatabaseService databaseService = new PgDatabaseService(manager);
                PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
                PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                factory = provider.createFactory("native", databaseService);
                return null;
            });
    }

    private Future<Void> closeResources() {
        Future<Void> factoryClose = factory != null ? factory.close() : Future.succeededFuture();
        factory = null;
        return factoryClose.transform(factoryResult -> {
            Future<Void> managerClose = manager != null ? manager.closeReactive() : Future.succeededFuture();
            manager = null;
            return managerClose.transform(managerResult ->
                combinedCloseResult(factoryResult.cause(), managerResult.cause()));
        });
    }

    private Future<Void> combinedCloseResult(Throwable factoryFailure, Throwable managerFailure) {
        if (factoryFailure != null) {
            if (managerFailure != null) {
                factoryFailure.addSuppressed(managerFailure);
            }
            return Future.failedFuture(factoryFailure);
        }
        return managerFailure == null ? Future.succeededFuture() : Future.failedFuture(managerFailure);
    }

    /**
     * Convert TestConsumerMode to ConsumerMode for actual test execution.
     */
    private ConsumerMode convertConsumerMode(dev.mars.peegeeq.test.consumer.TestConsumerMode testMode) {
        switch (testMode) {
            case LISTEN_NOTIFY_ONLY:
                return ConsumerMode.LISTEN_NOTIFY_ONLY;
            case POLLING_ONLY:
                return ConsumerMode.POLLING_ONLY;
            case HYBRID:
                return ConsumerMode.HYBRID;
            default:
                throw new IllegalArgumentException("Unknown consumer mode: " + testMode);
        }
    }

    /**
     * Parameterized throughput test that runs across all consumer mode scenarios.
     * This replaces the old testThroughputComparison() method with standardized patterns.
     */
    @ParameterizedTest(name = "Throughput Test: {0}")
    @MethodSource("getConsumerModeTestMatrix")
    @Timeout(120)
    void testStandardizedThroughputComparison(ConsumerModeTestScenario scenario, Vertx vertx,
                                              VertxTestContext testContext) throws InterruptedException {
        logger.info("=== TEST METHOD STARTED: {} ===", scenario.getScenarioName());

        logger.info("Testing standardized throughput for scenario: {}", scenario.getDescription());

        String topicName = "standardized-throughput-" + scenario.getConsumerMode().name().toLowerCase();
        // Use smaller message counts for faster test execution following PGQ principles
        // Adjust message count based on performance profile - BASIC is slower
        int baseMessageCount = scenario.getPerformanceProfile() == PerformanceProfile.BASIC ? 5 : 10;
        int messageCount = Math.min(baseMessageCount, scenario.getMessageCount());
        int warmupMessages = Math.max(1, messageCount / 4); // 25% warmup

        setupContainerForProfile(scenario.getPerformanceProfile());
        initializeManagerAndFactory()
            .compose(v -> measureThroughputMetrics(topicName, scenario, messageCount, warmupMessages, vertx))
            .onSuccess(metrics -> testContext.verify(() -> {
                validateThroughputMetrics(metrics, scenario);
                logger.info("{} - Throughput: {:.2f} msg/sec, Avg Latency: {:.2f}ms",
                    scenario.getConsumerMode(), metrics.get("messages_per_second"),
                    metrics.get("average_processing_time"));
                logger.info("=== TEST METHOD COMPLETED: {} ===", scenario.getScenarioName());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(110, TimeUnit.SECONDS), "throughput test timed out");
    }

    /**
     * Parameterized latency test that runs across all consumer mode scenarios.
     * This replaces the old testLatencyComparison() method with standardized patterns.
     */
    @ParameterizedTest(name = "Latency Test: {0}")
    @MethodSource("getConsumerModeTestMatrix")
    void testStandardizedLatencyComparison(ConsumerModeTestScenario scenario, Vertx vertx,
                                           VertxTestContext testContext) throws InterruptedException {
        logger.info("Testing standardized latency for scenario: {}", scenario.getDescription());

        String topicName = "standardized-latency-" + scenario.getConsumerMode().name().toLowerCase();
        int messageCount = Math.min(50, scenario.getMessageCount()); // Smaller count for latency precision

        setupContainerForProfile(scenario.getPerformanceProfile());
        initializeManagerAndFactory()
            .compose(v -> measureLatencyMetrics(topicName, scenario, messageCount, vertx))
            .onSuccess(metrics -> testContext.verify(() -> {
                validateLatencyMetrics(metrics, scenario);
                logger.info("{} - Avg: {:.2f}ms, P95: {:.2f}ms",
                    scenario.getConsumerMode(), metrics.get("average_processing_time"),
                    metrics.get("p95_latency"));
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(110, TimeUnit.SECONDS), "latency test timed out");
    }

    /**
     * Measure throughput using the scenario configuration and return metrics.
     */
    private Future<Map<String, Object>> measureThroughputMetrics(String topicName,
                                                                ConsumerModeTestScenario scenario,
                                                                int messageCount,
                                                                int warmupMessages,
                                                                Vertx vertx) {
        logger.info("=== STARTING THROUGHPUT MEASUREMENT ===");

        logger.info("Starting throughput measurement: topic={}, messages={}, warmup={}, mode={}",
            topicName, messageCount, warmupMessages, scenario.getConsumerMode());

        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicLong totalLatency = new AtomicLong(0);
        Promise<Long> allProcessed = Promise.promise();
        int timeoutSeconds = scenario.getPerformanceProfile() == PerformanceProfile.BASIC ? 20 : 10;
        long timerId = vertx.setTimer(TimeUnit.SECONDS.toMillis(timeoutSeconds), id ->
            allProcessed.tryFail("Timed out after processing " + processedCount.get() + "/"
                + (messageCount + warmupMessages) + " messages"));
        long[] messageSentTimes = new long[messageCount + warmupMessages];

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(convertConsumerMode(scenario.getConsumerMode()))
                .pollingInterval(scenario.getPollingInterval())
                .build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
        long[] startTime = {0L};

        return consumer.subscribe(message -> {
            long receiveTime = System.currentTimeMillis();
            int index = processedCount.incrementAndGet();
            if (index > warmupMessages) {
                totalLatency.addAndGet(receiveTime - messageSentTimes[index - 1]);
                if (index == messageCount + warmupMessages) {
                    allProcessed.tryComplete(receiveTime);
                }
            }
            return Future.succeededFuture();
        }).compose(v -> {
            startTime[0] = System.currentTimeMillis();
            Future<Void> sends = Future.succeededFuture();
            for (int i = 0; i < messageCount + warmupMessages; i++) {
                int index = i;
                sends = sends.compose(ignored -> {
                    messageSentTimes[index] = System.currentTimeMillis();
                    return producer.send("Standardized performance test message " + index);
                });
            }
            return sends.compose(ignored -> allProcessed.future());
        }).eventually(() -> {
            vertx.cancelTimer(timerId);
            return Future.succeededFuture();
        }).map(endTime -> {
            double durationSeconds = Math.max(0.001, (endTime - startTime[0]) / 1000.0);
            double throughput = messageCount / durationSeconds;
            double averageLatency = totalLatency.get() / (double) messageCount;
            return createConsumerModeMetrics(throughput, averageLatency, 0.0, 0.8, 0.0);
        });
    }

    /**
     * Measure latency using the scenario configuration and return metrics.
     */
    private Future<Map<String, Object>> measureLatencyMetrics(String topicName,
                                                              ConsumerModeTestScenario scenario,
                                                              int messageCount,
                                                              Vertx vertx) {
        List<Long> latencies = new ArrayList<>();
        AtomicInteger processedCount = new AtomicInteger();
        long[] messageSentTimes = new long[messageCount];
        Promise<Void> allProcessed = Promise.promise();
        long timerId = vertx.setTimer(20_000, id ->
            allProcessed.tryFail("Timed out after processing " + processedCount.get() + "/" + messageCount + " messages"));

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(convertConsumerMode(scenario.getConsumerMode()))
                .pollingInterval(scenario.getPollingInterval())
                .build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        return consumer.subscribe(message -> {
            int index = processedCount.getAndIncrement();
            if (index < messageCount) {
                latencies.add(System.currentTimeMillis() - messageSentTimes[index]);
                if (index == messageCount - 1) {
                    allProcessed.tryComplete();
                }
            }
            return Future.succeededFuture();
        }).compose(v -> {
            Future<Void> sends = Future.succeededFuture();
            for (int i = 0; i < messageCount; i++) {
                int index = i;
                sends = sends.compose(ignored -> {
                    messageSentTimes[index] = System.currentTimeMillis();
                    return producer.send("Standardized latency test message " + index);
                });
            }
            return sends.compose(ignored -> allProcessed.future());
        }).eventually(() -> {
            vertx.cancelTimer(timerId);
            return Future.succeededFuture();
        }).map(v -> {
            latencies.sort(Long::compareTo);
            double averageLatency = latencies.stream().mapToLong(Long::longValue).average().orElseThrow();
            int p95Index = Math.min(latencies.size() - 1, (int) Math.ceil(latencies.size() * 0.95) - 1);
            Map<String, Object> metrics = createConsumerModeMetrics(
                0.0, averageLatency, 0.0, 0.8, 0.0);
            metrics.put("p95_latency", latencies.get(p95Index).doubleValue());
            return metrics;
        });
    }

    /**
     * Validate throughput results based on scenario expectations.
     * Follows PGQ coding principles: proper null checking and realistic expectations.
     */
    private void validateThroughputMetrics(Map<String, Object> metrics, ConsumerModeTestScenario scenario) {
        // Validate result and metrics are not null
        assertNotNull(metrics, "Metrics should not be null");

        // Safe extraction of metrics with null checking
        Object throughputObj = metrics.get("messages_per_second");
        Object latencyObj = metrics.get("average_processing_time");

        assertNotNull(throughputObj, "Throughput metric should not be null");
        assertNotNull(latencyObj, "Average latency metric should not be null");

        double throughput = ((Number) throughputObj).doubleValue();
        double averageLatency = ((Number) latencyObj).doubleValue();

        // Realistic performance expectations based on scenario profile (reduced for test environment)
        double minExpectedThroughput = getMinExpectedThroughput(scenario);
        double maxExpectedLatency = getMaxExpectedLatency(scenario);

        assertTrue(throughput > minExpectedThroughput,
            String.format("Scenario %s should have throughput > %.2f msg/sec, got %.2f",
                scenario.getDescription(), minExpectedThroughput, throughput));

        assertTrue(averageLatency < maxExpectedLatency,
            String.format("Scenario %s should have average latency < %.2f ms, got %.2f",
                scenario.getDescription(), maxExpectedLatency, averageLatency));

        logger.info("Validation passed for {}: throughput={:.2f} msg/sec, latency={:.2f}ms",
            scenario.getDescription(), throughput, averageLatency);
    }

    /**
     * Validate latency results based on scenario expectations.
     * Follows PGQ coding principles: proper null checking and error handling.
     */
    private void validateLatencyMetrics(Map<String, Object> metrics, ConsumerModeTestScenario scenario) {
        // Validate result and metrics are not null
        assertNotNull(metrics, "Metrics should not be null");

        // Safe extraction of latency metric with null checking
        Object latencyObj = metrics.get("average_processing_time");
        assertNotNull(latencyObj, "Average latency metric should not be null");

        double averageLatency = ((Number) latencyObj).doubleValue();

        // Latency should be reasonable for the scenario
        double maxExpectedLatency = getMaxExpectedLatency(scenario);

        assertTrue(averageLatency >= 0, "Average latency should be non-negative");
        assertTrue(averageLatency < maxExpectedLatency,
            String.format("Scenario %s average latency should be < %.2f ms, got %.2f",
                scenario.getDescription(), maxExpectedLatency, averageLatency));

        logger.info("Latency validation passed for {}: latency={:.2f}ms (max: {:.2f}ms)",
            scenario.getDescription(), averageLatency, maxExpectedLatency);
    }

    /**
     * Get minimum expected throughput based on scenario performance profile.
     * Adjusted for test environment with TestContainers - more realistic expectations.
     */
    private double getMinExpectedThroughput(ConsumerModeTestScenario scenario) {
        return switch (scenario.getPerformanceProfile()) {
            case BASIC -> 0.5;          // 0.5 msg/sec minimum for basic (realistic for polling-only)
            case STANDARD -> 3.0;       // 3 msg/sec minimum for standard
            case HIGH_PERFORMANCE -> 5.0;  // 5 msg/sec minimum for high performance
            case MAXIMUM_PERFORMANCE -> 8.0; // 8 msg/sec minimum for maximum performance
            case CUSTOM -> 0.5;         // 0.5 msg/sec minimum for custom
        };
    }

    /**
     * Get maximum expected latency based on scenario performance profile.
     * Adjusted for test environment with TestContainers - more realistic expectations.
     */
    private double getMaxExpectedLatency(ConsumerModeTestScenario scenario) {
        return switch (scenario.getPerformanceProfile()) {
            case BASIC -> 10000.0;      // 10 seconds for basic profile (test environment)
            case STANDARD -> 5000.0;    // 5 seconds for standard profile
            case HIGH_PERFORMANCE -> 3000.0;  // 3 seconds for high performance
            case MAXIMUM_PERFORMANCE -> 2000.0; // 2 seconds for maximum performance
            case CUSTOM -> 10000.0;     // 10 seconds for custom profile
        };
    }
}
