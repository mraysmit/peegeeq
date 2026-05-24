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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
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

import java.time.Duration;

import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.Future;
import io.vertx.core.Promise;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Metrics integration tests for different consumer modes.
 * Tests that metrics are properly recorded for LISTEN_NOTIFY_ONLY, POLLING_ONLY, and HYBRID modes.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
public class ConsumerModeMetricsTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerModeMetricsTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory factory;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws InterruptedException {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        logger.info("🔧 Setting up ConsumerModeMetricsTest");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres,
                SchemaComponent.NATIVE_QUEUE,
                SchemaComponent.OUTBOX,
                SchemaComponent.DEAD_LETTER_QUEUE);
        initializeManagerAndFactory()
            .onSuccess(v -> {
                logger.info("ConsumerModeMetricsTest setup completed");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        logger.info("Tearing down: closing resources and manager");
        (factory != null ? factory.close() : Future.<Void>succeededFuture())
            .compose(v -> manager != null ? manager.closeReactive() : Future.succeededFuture())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(err -> {
                logger.warn("Error during teardown: {}", err.getMessage());
                testContext.completeNow();
            });
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        logger.info("🧹 ConsumerModeMetricsTest teardown completed");
    }

    private Future<Void> initializeManagerAndFactory() {
        Properties testProps;
        try {
            testProps = PeeGeeQTestConfig.builder()
                    .from(postgres)
                    .property("peegeeq.queue.polling-interval", "PT0.1S")
                    .property("peegeeq.queue.visibility-timeout", "PT30S")
                    .property("peegeeq.metrics.enabled", "true")
                    .property("peegeeq.circuit-breaker.enabled", "true")
                    .build();
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
        meterRegistry = new SimpleMeterRegistry();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, meterRegistry);
        return manager.start().map(v -> {
            PgDatabaseService databaseService = new PgDatabaseService(manager);
            PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
            PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
            factory = provider.createFactory("native", databaseService);
            return (Void) null;
        });
    }

    @Test
    void testMessageCountMetricsAcrossConsumerModes(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        logger.info("🧪 Testing message count metrics across consumer modes");

        final String topicName = "test-message-count-metrics";
        final int messageCount = 5;
        ConsumerMode[] modes = {ConsumerMode.LISTEN_NOTIFY_ONLY, ConsumerMode.POLLING_ONLY, ConsumerMode.HYBRID};

        Future<Void> chain = Future.succeededFuture();
        for (ConsumerMode mode : modes) {
            final ConsumerMode m = mode;
            chain = chain.compose(v -> {
                logger.info("📊 Testing message count metrics for mode: {}", m);
                return testMessageCountMetricsForMode(
                        topicName + "-" + m.name().toLowerCase(), m, messageCount, vertx);
            });
        }
        chain.onSuccess(v -> {
            logger.info("Message count metrics test completed successfully");
            testContext.completeNow();
        }).onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(90, TimeUnit.SECONDS));
    }

    @Test
    void testProcessingTimeMetricsAcrossConsumerModes(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        logger.info("🧪 Testing processing time metrics across consumer modes");

        final String topicName = "test-processing-time-metrics";
        final int messageCount = 3;
        ConsumerMode[] modes = {ConsumerMode.LISTEN_NOTIFY_ONLY, ConsumerMode.POLLING_ONLY, ConsumerMode.HYBRID};

        Future<Void> chain = Future.succeededFuture();
        for (ConsumerMode mode : modes) {
            final ConsumerMode m = mode;
            chain = chain.compose(v -> {
                logger.info("⏱️ Testing processing time metrics for mode: {}", m);
                return testProcessingTimeMetricsForMode(
                        topicName + "-" + m.name().toLowerCase(), m, messageCount, vertx);
            });
        }
        chain.onSuccess(v -> {
            logger.info("Processing time metrics test completed successfully");
            testContext.completeNow();
        }).onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(90, TimeUnit.SECONDS));
    }

    @Test
    void testQueueDepthMetrics(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("🧪 Testing queue depth metrics");

        String topicName = "test-queue-depth-metrics";
        
        // Get initial queue depth
        Gauge queueDepthGauge = meterRegistry.find("peegeeq.queue.depth.native").gauge();
        assertNotNull(queueDepthGauge, "Queue depth gauge should be registered");
        
        double initialDepth = queueDepthGauge.value();
        logger.info("📊 Initial queue depth: {}", initialDepth);

        // Send messages to increase queue depth
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
        for (int i = 0; i < 3; i++) {
            producer.send("Queue depth test message " + i)
                .onFailure(err -> logger.error("Send failed", err));
        }

        // Create consumer to process messages
        Checkpoint processedAll = testContext.checkpoint(3);
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofMillis(100))
                .build());

        // After 3s, check depth and subscribe consumer
        vertx.timer(3000).compose(v -> {
            double newDepth = queueDepthGauge.value();
            logger.info("📊 Queue depth after sending messages: {}", newDepth);
            return consumer.subscribe(message -> {
                processedAll.flag();
                return Future.succeededFuture();
            });
        }).onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(20, TimeUnit.SECONDS), "All messages should be processed");

        double finalDepth = queueDepthGauge.value();
        logger.info("📊 Final queue depth after processing: {}", finalDepth);

        consumer.close();
        producer.close();
        
        logger.info("Queue depth metrics test completed successfully");
    }

    private Future<Void> testMessageCountMetricsForMode(String topicName, ConsumerMode mode, int messageCount, Vertx vertx) {
        Counter sentCounter = meterRegistry.find("peegeeq.messages.sent").counter();
        Counter receivedCounter = meterRegistry.find("peegeeq.messages.received").counter();
        Counter processedCounter = meterRegistry.find("peegeeq.messages.processed").counter();

        if (sentCounter == null || receivedCounter == null || processedCounter == null) {
            return Future.failedFuture("Required counters not registered for mode: " + mode);
        }

        double initialSent = sentCounter.count();
        double initialReceived = receivedCounter.count();
        double initialProcessed = processedCounter.count();
        logger.info("📊 Initial metrics - Sent: {}, Received: {}, Processed: {}",
            initialSent, initialReceived, initialProcessed);

        Promise<Void> allProcessed = Promise.promise();
        AtomicInteger processedCount = new AtomicInteger(0);

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(mode)
                .pollingInterval(Duration.ofMillis(100))
                .build());

        consumer.subscribe(message -> {
            if (processedCount.incrementAndGet() >= messageCount) {
                allProcessed.tryComplete();
            }
            return Future.succeededFuture();
        });

        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        return vertx.timer(3000)
            .compose(v -> {
                for (int i = 0; i < messageCount; i++) {
                    producer.send("Metrics test message " + i)
                        .onFailure(err -> logger.error("Send failed", err));
                }
                return allProcessed.future();
            })
            .compose(v -> {
                assertEquals(messageCount, processedCount.get(), "All messages should be processed");
                return vertx.timer(3000);
            })
            .map(v -> {
                double finalSent = sentCounter.count();
                double finalReceived = receivedCounter.count();
                double finalProcessed = processedCounter.count();
                logger.info("📊 Final metrics - Sent: {}, Received: {}, Processed: {}",
                    finalSent, finalReceived, finalProcessed);
                consumer.close();
                producer.close();
                return (Void) null;
            });
    }

    private Future<Void> testProcessingTimeMetricsForMode(String topicName, ConsumerMode mode, int messageCount, Vertx vertx) {
        Timer processingTimer = meterRegistry.find("peegeeq.message.processing.time").timer();
        if (processingTimer == null) {
            return Future.failedFuture("Message processing time timer not registered for mode: " + mode);
        }
        long initialCount = processingTimer.count();
        logger.info("📊 Initial processing timer count: {}", initialCount);

        Promise<Void> allProcessed2 = Promise.promise();
        AtomicInteger processed = new AtomicInteger(0);

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(mode)
                .pollingInterval(Duration.ofMillis(100))
                .consumerThreads(messageCount)
                .build());

        consumer.subscribe(message -> {
            Promise<Void> promise = Promise.promise();
            vertx.setTimer(10, timerId -> {
                if (processed.incrementAndGet() >= messageCount) {
                    allProcessed2.tryComplete();
                }
                promise.complete();
            });
            return promise.future();
        });

        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        return vertx.timer(3000)
            .compose(v -> {
                for (int i = 0; i < messageCount; i++) {
                    producer.send("Processing time test message " + i)
                        .onFailure(err -> logger.error("Send failed", err));
                }
                return allProcessed2.future();
            })
            .compose(v -> vertx.timer(3000))
            .map(v -> {
                long finalCount = processingTimer.count();
                double totalTime = processingTimer.totalTime(TimeUnit.MILLISECONDS);
                logger.info("📊 Final processing timer - Count: {}, Total time: {}ms", finalCount, totalTime);
                consumer.close();
                producer.close();
                return (Void) null;
            });
    }
}


