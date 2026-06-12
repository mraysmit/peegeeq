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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for new consumer modes functionality.
 * Tests LISTEN_NOTIFY_ONLY, POLLING_ONLY, and HYBRID modes.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
public class ConsumerModeIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerModeIntegrationTest.class);

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory factory;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Initialize database schema using centralized schema initializer - use QUEUE_ALL for PeeGeeQManager health checks
        logger.info("Initializing database schema for consumer mode integration tests");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer");

        // Configure test properties using TestContainer pattern
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT1S")
                .property("peegeeq.queue.visibility-timeout", "PT30S")
                .property("peegeeq.metrics.enabled", "true")
                .property("peegeeq.circuit-breaker.enabled", "true")
                .build();

        // Initialize PeeGeeQ
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().onSuccess(v -> {
            // Create factory using the proper pattern
            PgDatabaseService databaseService = new PgDatabaseService(manager);
            PgQueueFactoryProvider provider = new PgQueueFactoryProvider();

            // Register native factory implementation
            PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

            factory = provider.createFactory("native", databaseService);

            logger.info("Test setup completed");
            testContext.completeNow();
        })
        .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        if (factory != null) {
            factory.close();
        }
        if (manager != null) {
            manager.closeReactive()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
        logger.info("Test teardown completed");
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    void testListenNotifyOnlyMode(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("TEST METHOD CALLED: testListenNotifyOnlyMode");
        logger.info("STARTING LISTEN_NOTIFY_ONLY MODE TEST");

        // Create consumer with LISTEN_NOTIFY_ONLY mode - but don't subscribe yet
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
                .build();

        MessageConsumer<String> consumer = factory.createConsumer("test-listen-only-simple", String.class, config);
        MessageProducer<String> producer = factory.createProducer("test-listen-only-simple", String.class);

        AtomicReference<String> receivedMessage = new AtomicReference<>();

        // Subscribe and wait for LISTEN to be established, then send
        logger.info("About to call consumer.subscribe()");
        consumer.subscribe(message -> {
            logger.info("LISTEN_NOTIFY_ONLY: Message received: {}", message.getPayload());
            receivedMessage.set(message.getPayload());
            testContext.verify(() -> {
                assertEquals("Hello LISTEN_NOTIFY_ONLY!", receivedMessage.get());
            });
            testContext.completeNow();
            return Future.succeededFuture();
        })
        .onSuccess(v -> {
            logger.info("LISTEN_NOTIFY_ONLY: Sending test message...");
            producer.send("Hello LISTEN_NOTIFY_ONLY!");
            logger.info("LISTEN_NOTIFY_ONLY: Message sent");
        })
        .onFailure(testContext::failNow);

        // Wait for message
        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Message should be received via LISTEN/NOTIFY");

        consumer.close();
        producer.close();
        logger.info("LISTEN_NOTIFY_ONLY mode test completed");
    }

    @Test
    void testPollingOnlyMode() throws Exception {
        logger.info("Testing POLLING_ONLY mode");

        // Create consumer with POLLING_ONLY mode and fast polling
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofMillis(500)) // Fast polling for test
                .build();

        MessageConsumer<String> consumer = factory.createConsumer("test-polling-only", String.class, config);
        MessageProducer<String> producer = factory.createProducer("test-polling-only", String.class);

        CountDownLatch methodLatch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        // Subscribe to messages
        consumer.subscribe(message -> {
            receivedMessage.set(message.getPayload());
            methodLatch.countDown();
            return Future.succeededFuture();
        });

        // Send message
        producer.send("Hello POLLING_ONLY!");

        // Wait for message (should be received via polling)
        assertTrue(methodLatch.await(10, TimeUnit.SECONDS), "Message should be received via polling");
        assertEquals("Hello POLLING_ONLY!", receivedMessage.get());

        consumer.close();
        producer.close();
        logger.info("POLLING_ONLY mode test passed");
    }

    @Test
    void testHybridMode() throws Exception {
        logger.info("Testing HYBRID mode (default behavior)");

        // Create consumer with HYBRID mode (should work like before)
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofSeconds(2))
                .build();

        MessageConsumer<String> consumer = factory.createConsumer("test-hybrid", String.class, config);
        MessageProducer<String> producer = factory.createProducer("test-hybrid", String.class);

        CountDownLatch methodLatch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        // Subscribe to messages
        consumer.subscribe(message -> {
            receivedMessage.set(message.getPayload());
            methodLatch.countDown();
            return Future.succeededFuture();
        });

        // Send message
        producer.send("Hello HYBRID!");

        // Wait for message (should be received via LISTEN/NOTIFY or polling)
        assertTrue(methodLatch.await(10, TimeUnit.SECONDS), "Message should be received via HYBRID mode");
        assertEquals("Hello HYBRID!", receivedMessage.get());

        consumer.close();
        producer.close();
        logger.info("HYBRID mode test passed");
    }

    @Test
    void testBackwardCompatibility() throws Exception {
        logger.info("Testing backward compatibility (no ConsumerConfig)");

        // Create consumer without ConsumerConfig (should default to HYBRID)
        MessageConsumer<String> consumer = factory.createConsumer("test-backward-compat", String.class);
        MessageProducer<String> producer = factory.createProducer("test-backward-compat", String.class);

        CountDownLatch methodLatch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        // Subscribe to messages
        consumer.subscribe(message -> {
            receivedMessage.set(message.getPayload());
            methodLatch.countDown();
            return Future.succeededFuture();
        });

        // Send message
        producer.send("Hello Backward Compatibility!");

        // Wait for message
        assertTrue(methodLatch.await(10, TimeUnit.SECONDS), "Message should be received in backward compatibility mode");
        assertEquals("Hello Backward Compatibility!", receivedMessage.get());

        consumer.close();
        producer.close();
        logger.info("Backward compatibility test passed");
    }
}


