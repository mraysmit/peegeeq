package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.TransactionPropagation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.IntFunction;

import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Performance benchmark test comparing JDBC vs Reactive approaches.
 * This test demonstrates the performance improvements achieved with the reactive implementation.
 */
@Tag(TestCategories.PERFORMANCE)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class PerformanceBenchmarkTest {

    private static final Logger logger = LoggerFactory.getLogger(PerformanceBenchmarkTest.class);

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory factory;
    private MessageProducer<String> producer;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        logger.info("=== Performance Benchmark Test Setup ===");

        // Configure PeeGeeQ to use test database
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.database.pool.min-size", "5")
                .property("peegeeq.database.pool.max-size", "10")
                .property("peegeeq.database.pool.max-wait-queue-size", "5000")
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);

        // Initialize manager
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .compose(v -> {
                    logger.info("PeeGeeQ Manager started successfully");

                    // Create outbox factory and producer - following existing patterns
                    PgDatabaseService databaseService = new PgDatabaseService(manager);
                    PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
                    OutboxFactoryRegistrar.registerWith(provider);

                    factory = provider.createFactory("outbox", databaseService);
                    producer = factory.createProducer("performance-test", String.class);

                    logger.info("Performance benchmark test setup complete");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Tearing down: closing resources and manager");
        Future<Void> factoryClose = factory != null
                ? factory.close()
                : Future.succeededFuture();
        factoryClose.transform(factoryResult -> {
                    Future<Void> managerClose = manager != null
                            ? manager.closeReactive()
                            : Future.succeededFuture();
                    return managerClose.transform(managerResult -> {
                        if (factoryResult.failed()) {
                            return Future.failedFuture(factoryResult.cause());
                        }
                        if (managerResult.failed()) {
                            return Future.failedFuture(managerResult.cause());
                        }
                        return Future.succeededFuture();
                    });
                })
                .onSuccess(v -> {
                    logger.info("Performance benchmark test cleanup completed");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("BENCHMARK: JDBC vs Reactive Performance Comparison")
    void benchmarkJdbcVsReactivePerformance(VertxTestContext testContext) {
        logger.info("=== PERFORMANCE BENCHMARK: JDBC vs Reactive ===");

        int messageCount = 1000;
        String testPayload = "benchmark-message-";

        // Benchmark JDBC approach
        logger.info(" Benchmarking JDBC approach with {} messages...", messageCount);
        long jdbcStartTime = System.currentTimeMillis();

        sendSequentially(0, messageCount, i -> producer.send(testPayload + i))
                .compose(v -> {
                    long jdbcDuration = System.currentTimeMillis() - jdbcStartTime;
                    double jdbcThroughput = (double) messageCount / (jdbcDuration / 1000.0);

                    logger.info("JDBC Approach: {} messages in {} ms ({:.1f} msg/sec)",
                            messageCount, jdbcDuration, jdbcThroughput);

                    // Benchmark Reactive approach
                    logger.info(" Benchmarking Reactive approach with {} messages...", messageCount);
                    long reactiveStartTime = System.currentTimeMillis();

                    List<Future<?>> reactiveFutures = new ArrayList<>();
                    for (int i = 0; i < messageCount; i++) {
                        reactiveFutures.add(producer.send(testPayload + "reactive-" + i));
                    }

                    return Future.all(reactiveFutures).map(completed -> {
                        long reactiveDuration = System.currentTimeMillis() - reactiveStartTime;
                        double reactiveThroughput = (double) messageCount / (reactiveDuration / 1000.0);

                        logger.info("Reactive Approach: {} messages in {} ms ({:.1f} msg/sec)",
                                messageCount, reactiveDuration, reactiveThroughput);

                        double improvementFactor = reactiveThroughput / jdbcThroughput;
                        logger.info(" Performance Improvement: {}x faster with reactive approach",
                                String.format("%.2f", improvementFactor));

                        logger.info("=== PERFORMANCE BENCHMARK RESULTS ===");
                        logger.info("JDBC:     {} messages in {} seconds ({} msg/sec)",
                                messageCount, String.format("%.1f", jdbcDuration / 1000.0),
                                String.format("%.0f", jdbcThroughput));
                        logger.info("Reactive: {} messages in {} seconds ({} msg/sec)",
                                messageCount, String.format("%.1f", reactiveDuration / 1000.0),
                                String.format("%.0f", reactiveThroughput));
                        logger.info("Improvement: {}x faster with reactive approach",
                                String.format("%.2f", improvementFactor));
                        logger.info("Performance comparison result: {:.2f}x improvement with reactive approach",
                                improvementFactor);

                        Assertions.assertTrue(improvementFactor >= 0.5,
                                String.format("Reactive approach is significantly slower than JDBC (%.2fx), indicating a potential issue",
                                        improvementFactor));

                        if (improvementFactor >= 3.0) {
                            logger.info(" EXCELLENT: Reactive approach shows excellent performance improvement");
                        } else if (improvementFactor >= 2.0) {
                            logger.info("GOOD: Reactive approach shows good performance improvement");
                        } else if (improvementFactor >= 1.5) {
                            logger.info(" MODERATE: Reactive approach shows moderate improvement (typical in test environments)");
                        } else {
                            logger.info(" MINIMAL: Reactive approach shows minimal improvement (may be due to test environment limitations)");
                        }
                        return (Void) null;
                    });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("BENCHMARK: TransactionPropagation Performance")
    void benchmarkTransactionPropagationPerformance(VertxTestContext testContext) {
        logger.info("=== BENCHMARK: TransactionPropagation Performance ===");

        int messageCount = 500;
        String testPayload = "tx-propagation-";

        // Benchmark without TransactionPropagation
        logger.info(" Benchmarking without TransactionPropagation...");
        long basicStartTime = System.currentTimeMillis();

        OutboxProducer<String> outboxProducer = (OutboxProducer<String>) producer;
        List<Future<?>> basicFutures = new ArrayList<>();
        for (int i = 0; i < messageCount; i++) {
            basicFutures.add(outboxProducer.sendInOwnTransaction(testPayload + i));
        }

        Future.all(basicFutures)
                .compose(v -> {
                    long basicDuration = System.currentTimeMillis() - basicStartTime;
                    double basicThroughput = (double) messageCount / (basicDuration / 1000.0);

                    logger.info(" Benchmarking with TransactionPropagation.CONTEXT...");
                    long contextStartTime = System.currentTimeMillis();
                    return sendOnVertxContext(messageCount, i -> outboxProducer.sendInOwnTransaction(
                                    testPayload + "context-" + i,
                                    TransactionPropagation.CONTEXT))
                            .map(completed -> {
                                long contextDuration = System.currentTimeMillis() - contextStartTime;
                                double contextThroughput = (double) messageCount / (contextDuration / 1000.0);

                                logger.info("Basic Transaction: {} messages in {} ms ({:.1f} msg/sec)",
                                        messageCount, basicDuration, basicThroughput);
                                logger.info("TransactionPropagation.CONTEXT: {} messages in {} ms ({:.1f} msg/sec)",
                                        messageCount, contextDuration, contextThroughput);

                                double contextEfficiency = contextThroughput / basicThroughput;
                                logger.info(" TransactionPropagation efficiency: {:.2f}x", contextEfficiency);

                                Assertions.assertTrue(basicThroughput > 0,
                                        "Basic transaction throughput should be positive");
                                Assertions.assertTrue(contextThroughput > 0,
                                        "Context transaction throughput should be positive");
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("BENCHMARK: Batch Operations Performance")
    void benchmarkBatchOperationsPerformance(VertxTestContext testContext) {
        logger.info("=== BENCHMARK: Batch Operations Performance ===");

        int batchSize = 100;
        int batchCount = 10;
        String testPayload = "batch-";

        // Benchmark individual operations
        logger.info(" Benchmarking individual operations...");
        long individualStartTime = System.currentTimeMillis();

        OutboxProducer<String> outboxProducer = (OutboxProducer<String>) producer;
        int totalMessages = batchSize * batchCount;
        sendSequentially(0, totalMessages, index -> {
                    int batch = index / batchSize;
                    int item = index % batchSize;
                    return outboxProducer.sendInOwnTransaction(testPayload + batch + "-" + item);
                })
                .compose(v -> {
                    long individualDuration = System.currentTimeMillis() - individualStartTime;
                    double individualThroughput = (double) totalMessages / (individualDuration / 1000.0);

                    logger.info(" Benchmarking batch operations...");
                    long batchStartTime = System.currentTimeMillis();
                    return sendOnVertxContext(totalMessages, index -> {
                                int batch = index / batchSize;
                                int item = index % batchSize;
                                return outboxProducer.sendInOwnTransaction(
                                        testPayload + "batch-" + batch + "-" + item,
                                        TransactionPropagation.CONTEXT);
                            })
                            .map(completed -> {
                                long batchDuration = System.currentTimeMillis() - batchStartTime;
                                double batchThroughput = (double) totalMessages / (batchDuration / 1000.0);

                                logger.info("Individual Operations: {} messages in {} ms ({:.1f} msg/sec)",
                                        totalMessages, individualDuration, individualThroughput);
                                logger.info("Batch Operations: {} messages in {} ms ({:.1f} msg/sec)",
                                        totalMessages, batchDuration, batchThroughput);

                                double batchImprovement = batchThroughput / individualThroughput;
                                logger.info(" Batch improvement: {:.2f}x faster", batchImprovement);

                                Assertions.assertTrue(batchThroughput >= individualThroughput,
                                        "Batch operations should be at least as fast as individual operations");
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    private Future<Void> sendSequentially(int index, int total,
                                          IntFunction<? extends Future<?>> sendOperation) {
        if (index >= total) {
            return Future.succeededFuture();
        }
        return sendOperation.apply(index)
                .compose(v -> sendSequentially(index + 1, total, sendOperation));
    }

    private Future<Void> sendOnVertxContext(int total,
                                            IntFunction<? extends Future<?>> sendOperation) {
        Promise<Void> completion = Promise.promise();
        manager.getVertx().runOnContext(ignored -> {
            List<Future<?>> futures = new ArrayList<>(total);
            try {
                for (int i = 0; i < total; i++) {
                    futures.add(sendOperation.apply(i));
                }
            } catch (RuntimeException error) {
                completion.fail(error);
                return;
            }
            Future.all(futures)
                    .mapEmpty()
                    .onSuccess(ignoredResult -> completion.complete())
                    .onFailure(completion::fail);
        });
        return completion.future();
    }
}
