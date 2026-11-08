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
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.Timeout;
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
import java.util.ArrayList;
import java.util.List;

import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import dev.mars.peegeeq.test.vertx.TestCleanup;

/**
 * Multi-consumer mode integration tests for PeeGeeQ Native Queue.
 * Tests multiple consumers with same/different modes, consumer mode isolation,
 * and thread safety across modes.
 *
 * Following established coding principles:
 * - Use real infrastructure (TestContainers) rather than mocks
 * - Test multi-consumer scenarios that could cause production issues
 * - Validate proper isolation and thread safety across consumer modes
 * - Follow existing patterns from other integration tests
 * - Test with various consumer combinations and load scenarios
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class MultiConsumerModeTest {

    private static final Logger logger = LoggerFactory.getLogger(MultiConsumerModeTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory factory;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws InterruptedException {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        logger.info(" Setting up MultiConsumerModeTest");
        initializeManagerAndFactory()
            .onSuccess(v -> {
                logger.info("MultiConsumerModeTest setup completed");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        logger.info("Tearing down: closing resources and manager");
        logger.info(" Cleaning up MultiConsumerModeTest");
        (factory != null ? factory.close() : Future.<Void>succeededFuture())
            .compose(v -> manager != null ? manager.closeReactive() : Future.succeededFuture())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(err -> {
                logger.error("Error during teardown", err);
                testContext.failNow(err);
            });
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        logger.info("MultiConsumerModeTest cleanup completed");
    }

    private Future<Void> initializeManagerAndFactory() {
        try {
            Properties testProps = PeeGeeQTestConfig.builder()
                    .from(postgres)
                    .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                    .property("peegeeq.queue.polling-interval", "PT1S")
                    .property("peegeeq.queue.visibility-timeout", "PT30S")
                    .property("peegeeq.metrics.enabled", "true")
                    .property("peegeeq.circuit-breaker.enabled", "true")
                    .build();
            // Ensure required schema exists for native queue tests
            PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.NATIVE_QUEUE, SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);
            // Initialize PeeGeeQ with test configuration
            PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
            manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
        return manager.start().map(v -> {
            PgDatabaseService databaseService = new PgDatabaseService(manager);
            PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
            PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
            factory = provider.createFactory("native", databaseService);
            return (Void) null;
        });
    }

    @Test
    @Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
    void testMultipleConsumersSameMode(Vertx vertx, VertxTestContext testContext) {
        String topicName = "test-multi-same-mode";
        int consumerCount = 3;
        int messagesPerConsumer = 2;
        int totalMessages = consumerCount * messagesPerConsumer;

        // A strict checkpoint across the competing consumers: every message is processed once.
        // The teardown closes the consumers through the factory.
        List<Future<Void>> subscriptions = new ArrayList<>();
        Checkpoint messagesReceived = testContext.checkpoint(totalMessages);

        for (int i = 0; i < consumerCount; i++) {
            MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
                ConsumerConfig.builder()
                    .mode(ConsumerMode.HYBRID)
                    .pollingInterval(Duration.ofSeconds(1))
                    .build());
            subscriptions.add(consumer.subscribe(message -> {
                messagesReceived.flag();
                return Future.succeededFuture();
            }));
        }

        List<MessageProducer<String>> producers = new ArrayList<>();
        Future.all(subscriptions)
            .compose(v -> {
                MessageProducer<String> producer = factory.createProducer(topicName, String.class);
                producers.add(producer);
                List<Future<Void>> sends = new ArrayList<>();
                for (int i = 0; i < totalMessages; i++) {
                    sends.add(producer.send("Multi-same-mode message " + (i + 1)));
                }
                return Future.all(sends);
            })
            .transform(TestCleanup.cleanup(() -> {
                // MessageProducer.close() is synchronous and void.
                for (MessageProducer<String> p : producers) {
                    try { p.close(); } catch (Exception e) { logger.warn("Producer close failed: {}", e.getMessage()); }
                }
                return Future.succeededFuture();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 20, timeUnit = TimeUnit.SECONDS)
    void testMultipleConsumersDifferentModes(Vertx vertx, VertxTestContext testContext) {
        String topicName = "test-multi-different-modes";

        // One strict two-pass checkpoint per consumer: each processes exactly its two messages.
        Checkpoint listenReceived = testContext.checkpoint(2);
        Checkpoint pollingReceived = testContext.checkpoint(2);
        Checkpoint hybridReceived = testContext.checkpoint(2);

        // Create consumers with different modes
        MessageConsumer<String> listenConsumer = factory.createConsumer(topicName + "-listen", String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
                .build());

        MessageConsumer<String> pollingConsumer = factory.createConsumer(topicName + "-polling", String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofMillis(500))
                .build());

        MessageConsumer<String> hybridConsumer = factory.createConsumer(topicName + "-hybrid", String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofSeconds(1))
                .build());

        List<MessageProducer<String>> producers = new ArrayList<>();
        Future.all(List.of(
                listenConsumer.subscribe(message -> {
                    listenReceived.flag();
                    return Future.succeededFuture();
                }),
                pollingConsumer.subscribe(message -> {
                    pollingReceived.flag();
                    return Future.succeededFuture();
                }),
                hybridConsumer.subscribe(message -> {
                    hybridReceived.flag();
                    return Future.succeededFuture();
                })))
            .compose(v -> {
                MessageProducer<String> listenProducer = factory.createProducer(topicName + "-listen", String.class);
                MessageProducer<String> pollingProducer = factory.createProducer(topicName + "-polling", String.class);
                MessageProducer<String> hybridProducer = factory.createProducer(topicName + "-hybrid", String.class);
                producers.addAll(List.of(listenProducer, pollingProducer, hybridProducer));
                List<Future<Void>> sends = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    sends.add(listenProducer.send("Listen message " + (i + 1)));
                    sends.add(pollingProducer.send("Polling message " + (i + 1)));
                    sends.add(hybridProducer.send("Hybrid message " + (i + 1)));
                }
                return Future.all(sends);
            })
            .transform(TestCleanup.cleanup(() -> {
                // MessageProducer.close() is synchronous and void.
                for (MessageProducer<String> p : producers) {
                    try { p.close(); } catch (Exception e) { logger.warn("Producer close failed: {}", e.getMessage()); }
                }
                return Future.succeededFuture();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
    void testConsumerModeIsolation(Vertx vertx, VertxTestContext testContext) {
        String topicName = "test-mode-isolation";
        int messagesPerMode = 3;

        // Two competing consumers on one topic: a strict checkpoint across both, so every message
        // is processed once and none twice.
        Checkpoint messagesReceived = testContext.checkpoint(messagesPerMode);

        MessageConsumer<String> pollingConsumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofMillis(500))
                .build());

        MessageConsumer<String> hybridConsumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofSeconds(1))
                .build());

        List<MessageProducer<String>> producers = new ArrayList<>();
        Future.all(List.of(
                pollingConsumer.subscribe(message -> {
                    messagesReceived.flag();
                    return Future.succeededFuture();
                }),
                hybridConsumer.subscribe(message -> {
                    messagesReceived.flag();
                    return Future.succeededFuture();
                })))
            .compose(v -> {
                MessageProducer<String> producer = factory.createProducer(topicName, String.class);
                producers.add(producer);
                List<Future<Void>> sends = new ArrayList<>();
                for (int i = 0; i < messagesPerMode; i++) {
                    sends.add(producer.send("Isolation test message " + (i + 1)));
                }
                return Future.all(sends);
            })
            .transform(TestCleanup.cleanup(() -> {
                // MessageProducer.close() is synchronous and void.
                for (MessageProducer<String> p : producers) {
                    try { p.close(); } catch (Exception e) { logger.warn("Producer close failed: {}", e.getMessage()); }
                }
                return Future.succeededFuture();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
    void testThreadSafetyAcrossModes(Vertx vertx, VertxTestContext testContext) {
        String topicName = "test-thread-safety";
        int consumerCount = 4;
        int messagesPerConsumer = 5;
        int totalMessages = consumerCount * messagesPerConsumer;

        // A strict checkpoint: exactly twenty deliveries across the four topics.
        List<Future<Void>> subscriptions = new ArrayList<>();
        Checkpoint messagesReceived = testContext.checkpoint(totalMessages);
        ConsumerMode[] modes = {ConsumerMode.POLLING_ONLY, ConsumerMode.HYBRID, ConsumerMode.POLLING_ONLY, ConsumerMode.HYBRID};

        for (int i = 0; i < consumerCount; i++) {
            MessageConsumer<String> consumer = factory.createConsumer(topicName + "-" + i, String.class,
                ConsumerConfig.builder()
                    .mode(modes[i])
                    .pollingInterval(Duration.ofMillis(200 + (i * 100)))
                    .consumerThreads(1 + (i % 2))
                    .build());

            subscriptions.add(consumer.subscribe(message ->
                // Simulated processing time, so handlers of different modes overlap.
                vertx.timer(50 + (int) (Math.random() * 100)).map(tid -> {
                    messagesReceived.flag();
                    return (Void) null;
                })));
        }

        List<MessageProducer<String>> producers = new ArrayList<>();
        Future.all(subscriptions)
            .compose(v -> {
                List<Future<Void>> sends = new ArrayList<>();
                for (int i = 0; i < consumerCount; i++) {
                    MessageProducer<String> producer = factory.createProducer(topicName + "-" + i, String.class);
                    producers.add(producer);
                    for (int j = 0; j < messagesPerConsumer; j++) {
                        sends.add(producer.send("Thread-safety message " + i + "-" + (j + 1)));
                    }
                }
                return Future.all(sends);
            })
            .transform(TestCleanup.cleanup(() -> {
                // MessageProducer.close() is synchronous and void.
                for (MessageProducer<String> p : producers) {
                    try { p.close(); } catch (Exception e) { logger.warn("Producer close failed: {}", e.getMessage()); }
                }
                return Future.succeededFuture();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 25, timeUnit = TimeUnit.SECONDS)
    void testConsumerModePerformanceIsolation(Vertx vertx, VertxTestContext testContext) {
        String topicName = "test-performance-isolation";
        int fastMessages = 10;
        int slowMessages = 5;

        // One strict checkpoint per consumer: each processes exactly its own messages.
        Checkpoint fastReceived = testContext.checkpoint(fastMessages);
        Checkpoint slowReceived = testContext.checkpoint(slowMessages);

        // Create fast consumer (LISTEN_NOTIFY_ONLY for immediate processing)
        MessageConsumer<String> fastConsumer = factory.createConsumer(topicName + "-fast", String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
                .build());

        // Create slow consumer (POLLING_ONLY with slower polling)
        MessageConsumer<String> slowConsumer = factory.createConsumer(topicName + "-slow", String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofSeconds(2))
                .build());

        List<MessageProducer<String>> producers = new ArrayList<>();
        Future.all(List.of(
                fastConsumer.subscribe(message -> {
                    fastReceived.flag();
                    return Future.succeededFuture();
                }),
                slowConsumer.subscribe(message -> {
                    slowReceived.flag();
                    return Future.succeededFuture();
                })))
            .compose(v -> {
                MessageProducer<String> fastProducer = factory.createProducer(topicName + "-fast", String.class);
                MessageProducer<String> slowProducer = factory.createProducer(topicName + "-slow", String.class);
                producers.addAll(List.of(fastProducer, slowProducer));
                List<Future<Void>> sends = new ArrayList<>();
                for (int i = 0; i < fastMessages; i++) {
                    sends.add(fastProducer.send("Fast message " + (i + 1)));
                }
                for (int i = 0; i < slowMessages; i++) {
                    sends.add(slowProducer.send("Slow message " + (i + 1)));
                }
                return Future.all(sends);
            })
            .transform(TestCleanup.cleanup(() -> {
                // MessageProducer.close() is synchronous and void.
                for (MessageProducer<String> p : producers) {
                    try { p.close(); } catch (Exception e) { logger.warn("Producer close failed: {}", e.getMessage()); }
                }
                return Future.succeededFuture();
            }))
            .onFailure(testContext::failNow);
    }
}

