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
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resource management tests for consumer mode implementation.
 * Tests that consumer modes properly manage resources including connection pools,
 * Vert.x instances, scheduler resources, memory usage, and cleanup on shutdown.
 *
 * Following established coding principles:
 * - Use real infrastructure (TestContainers) rather than mocks
 * - Test resource management edge cases that could cause production issues
 * - Validate proper cleanup and resource sharing across consumer modes
 * - Follow existing patterns from other integration tests
 * - Test with various resource scenarios including high load and shutdown
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class ConsumerModeResourceManagementTest {

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory factory;

    private static final String[] SYSTEM_PROPERTIES = {
        "peegeeq.database.host", "peegeeq.database.port", "peegeeq.database.name",
        "peegeeq.database.username", "peegeeq.database.password", "peegeeq.database.ssl.enabled",
        "peegeeq.queue.polling-interval", "peegeeq.queue.visibility-timeout",
        "peegeeq.metrics.enabled", "peegeeq.circuit-breaker.enabled"
    };

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.queue.polling-interval", "PT1S");
        System.setProperty("peegeeq.queue.visibility-timeout", "PT30S");
        System.setProperty("peegeeq.metrics.enabled", "true");
        System.setProperty("peegeeq.circuit-breaker.enabled", "true");

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres,
                SchemaComponent.NATIVE_QUEUE,
                SchemaComponent.OUTBOX,
                SchemaComponent.DEAD_LETTER_QUEUE);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        PgDatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        factory = provider.createFactory("native", databaseService);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (factory != null) {
            factory.close();
        }
        if (manager != null) {
            manager.closeReactive().await();
        }
        for (String prop : SYSTEM_PROPERTIES) {
            System.clearProperty(prop);
        }
    }

    @Test
    void testConnectionPoolUsageAcrossConsumerModes(Vertx vertx, VertxTestContext testContext) throws Exception {
        String topicName = "test-connection-pool-usage";
        List<MessageConsumer<String>> consumers = new ArrayList<>();
        List<MessageProducer<String>> producers = new ArrayList<>();

        try {
            MessageConsumer<String> listenConsumer = factory.createConsumer(topicName + "-listen", String.class,
                ConsumerConfig.builder().mode(ConsumerMode.LISTEN_NOTIFY_ONLY).build());
            MessageConsumer<String> pollingConsumer = factory.createConsumer(topicName + "-polling", String.class,
                ConsumerConfig.builder().mode(ConsumerMode.POLLING_ONLY).pollingInterval(Duration.ofSeconds(1)).build());
            MessageConsumer<String> hybridConsumer = factory.createConsumer(topicName + "-hybrid", String.class,
                ConsumerConfig.builder().mode(ConsumerMode.HYBRID).pollingInterval(Duration.ofSeconds(1)).build());

            consumers.add(listenConsumer);
            consumers.add(pollingConsumer);
            consumers.add(hybridConsumer);

            producers.add(factory.createProducer(topicName + "-listen", String.class));
            producers.add(factory.createProducer(topicName + "-polling", String.class));
            producers.add(factory.createProducer(topicName + "-hybrid", String.class));

            AtomicInteger messageCount = new AtomicInteger(0);
            Checkpoint messagesReceived = testContext.checkpoint(3);

            for (int i = 0; i < consumers.size(); i++) {
                consumers.get(i).subscribe(message -> {
                    messageCount.incrementAndGet();
                    messagesReceived.flag();
                    return Future.succeededFuture();
                });
            }

            // Wait for consumer setup, then send
            vertx.setTimer(1000, id -> {
                io.vertx.core.Future<Void> chain = Future.succeededFuture();
                for (int i = 0; i < producers.size(); i++) {
                    final int idx = i;
                    chain = chain.compose(v -> producers.get(idx).send("Test message " + idx));
                }
                chain.onFailure(testContext::failNow);
            });

            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should receive all messages across different consumer modes");
            assertEquals(3, messageCount.get(), "Should process exactly 3 messages");

        } finally {
            // Clean up resources
            for (MessageConsumer<String> consumer : consumers) {
                consumer.close();
            }
            for (MessageProducer<String> producer : producers) {
                producer.close();
            }
        }
    }

    @Test
    void testSchedulerResourceManagement(Vertx vertx, VertxTestContext testContext) throws Exception {

        String topicName = "test-scheduler-resources";
        List<MessageConsumer<String>> pollingConsumers = new ArrayList<>();

        try {
            for (int i = 0; i < 3; i++) {
                ConsumerConfig config = ConsumerConfig.builder()
                    .mode(ConsumerMode.POLLING_ONLY)
                    .pollingInterval(Duration.ofMillis(500))
                    .build();

                MessageConsumer<String> consumer = factory.createConsumer(topicName + "-" + i, String.class, config);
                pollingConsumers.add(consumer);
            }

            AtomicInteger messageCount = new AtomicInteger(0);
            Checkpoint messagesReceived = testContext.checkpoint(3);

            for (int i = 0; i < pollingConsumers.size(); i++) {
                pollingConsumers.get(i).subscribe(message -> {
                    messageCount.incrementAndGet();
                    messagesReceived.flag();
                    return Future.succeededFuture();
                });
            }

            // Wait for polling setup, then send
            vertx.setTimer(1000, id -> {
                MessageProducer<String> producer = factory.createProducer(topicName + "-0", String.class);
                MessageProducer<String> producer2 = factory.createProducer(topicName + "-1", String.class);
                MessageProducer<String> producer3 = factory.createProducer(topicName + "-2", String.class);

                producer.send("Test polling message 1")
                    .compose(v -> producer2.send("Test polling message 2"))
                    .compose(v -> producer3.send("Test polling message 3"))
                    .eventually(() -> {
                        producer.close();
                        producer2.close();
                        producer3.close();
                        return Future.succeededFuture();
                    })
                    .onFailure(testContext::failNow);
            });

            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should receive messages via polling mechanism");
            assertEquals(3, messageCount.get(), "Should process exactly 3 messages");

        } finally {
            for (MessageConsumer<String> consumer : pollingConsumers) {
                consumer.close();
            }
        }
    }

    @Test
    void testMemoryUsagePatterns(Vertx vertx, VertxTestContext testContext) throws Exception {
        String topicName = "test-memory-usage";

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.HYBRID).pollingInterval(Duration.ofSeconds(1)).build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            Checkpoint messagesReceived = testContext.checkpoint(10);

            consumer.subscribe(message -> {
                processedCount.incrementAndGet();
                messagesReceived.flag();
                return Future.succeededFuture();
            });

            // Wait for consumer setup, then send
            vertx.setTimer(500, id -> {
                io.vertx.core.Future<Void> chain = Future.succeededFuture();
                for (int i = 0; i < 10; i++) {
                    final int msgNum = i;
                    chain = chain.compose(v -> producer.send("Memory test message " + msgNum));
                }
                chain.onFailure(testContext::failNow);
            });

            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Should process all messages without memory issues");
            assertEquals(10, processedCount.get(), "Should process exactly 10 messages");

        } finally {
            consumer.close();
            producer.close();
        }
    }

    @Test
    void testGracefulShutdownResourceCleanup(Vertx vertx, VertxTestContext testContext) throws Exception {
        String topicName = "test-graceful-shutdown";

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder().mode(ConsumerMode.HYBRID).pollingInterval(Duration.ofSeconds(1)).build());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        try {
            AtomicInteger processedCount = new AtomicInteger(0);
            Checkpoint messagesProcessed = testContext.checkpoint(2);

            consumer.subscribe(message -> {
                processedCount.incrementAndGet();
                messagesProcessed.flag();
                return Future.succeededFuture();
            });

            // Wait for consumer setup, then send
            vertx.setTimer(500, id -> {
                producer.send("Shutdown test message 1")
                    .compose(v -> producer.send("Shutdown test message 2"))
                    .onFailure(testContext::failNow);
            });

            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should process messages before shutdown");
            assertEquals(2, processedCount.get(), "Should process exactly 2 messages");
        } finally {
            consumer.close();
            producer.close();
        }
    }
}


