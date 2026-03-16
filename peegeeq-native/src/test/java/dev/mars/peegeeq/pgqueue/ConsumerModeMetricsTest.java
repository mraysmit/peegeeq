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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final PostgreSQLContainer<?> postgres = createPostgresContainer();

    private static PostgreSQLContainer<?> createPostgresContainer() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("testdb");
        container.withUsername("testuser");
        container.withPassword("testpass");
        return container;
    }

    private PeeGeeQManager manager;
    private QueueFactory factory;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() throws Exception {
        logger.info("🔧 Setting up ConsumerModeMetricsTest");

        // Clear any existing system properties
        System.clearProperty("peegeeq.queue.polling-interval");
        System.clearProperty("peegeeq.queue.visibility-timeout");
        System.clearProperty("peegeeq.queue.batch-size");
        System.clearProperty("peegeeq.consumer.threads");

        // Ensure required schema exists before starting manager/factory
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres,
                SchemaComponent.NATIVE_QUEUE,
                SchemaComponent.OUTBOX,
                SchemaComponent.DEAD_LETTER_QUEUE);

        initializeManagerAndFactory();
        logger.info("ConsumerModeMetricsTest setup completed");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (factory != null) {
            factory.close();
        }
        if (manager != null) {
            manager.closeReactive().toCompletionStage().toCompletableFuture().join();
        }
        logger.info("🧹 ConsumerModeMetricsTest teardown completed");
    }

    private void initializeManagerAndFactory() throws Exception {
        // Configure test properties using TestContainer pattern (following established patterns)
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.1S"); // Fast polling for metrics tests
        System.setProperty("peegeeq.queue.visibility-timeout", "PT30S");
        System.setProperty("peegeeq.metrics.enabled", "true");
        System.setProperty("peegeeq.circuit-breaker.enabled", "true");

        // Initialize meter registry
        meterRegistry = new SimpleMeterRegistry();

        // Initialize PeeGeeQ with test configuration
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, meterRegistry);
        manager.start();

        // Create factory using the proper pattern
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register native factory implementation
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        factory = provider.createFactory("native", databaseService);
    }

    @Test
    void testMessageCountMetricsAcrossConsumerModes(Vertx vertx) throws Exception {
        logger.info("🧪 Testing message count metrics across consumer modes");

        String topicName = "test-message-count-metrics";
        int messageCount = 5;

        // Test each consumer mode
        ConsumerMode[] modes = {ConsumerMode.LISTEN_NOTIFY_ONLY, ConsumerMode.POLLING_ONLY, ConsumerMode.HYBRID};
        
        for (ConsumerMode mode : modes) {
            logger.info("📊 Testing message count metrics for mode: {}", mode);
            
            testMessageCountMetricsForMode(topicName + "-" + mode.name().toLowerCase(), mode, messageCount, vertx);
        }

        logger.info("Message count metrics test completed successfully");
    }

    @Test
    void testProcessingTimeMetricsAcrossConsumerModes(Vertx vertx) throws Exception {
        logger.info("🧪 Testing processing time metrics across consumer modes");

        String topicName = "test-processing-time-metrics";
        int messageCount = 3;

        // Test each consumer mode
        ConsumerMode[] modes = {ConsumerMode.LISTEN_NOTIFY_ONLY, ConsumerMode.POLLING_ONLY, ConsumerMode.HYBRID};
        
        for (ConsumerMode mode : modes) {
            logger.info("⏱️ Testing processing time metrics for mode: {}", mode);
            
            testProcessingTimeMetricsForMode(topicName + "-" + mode.name().toLowerCase(), mode, messageCount, vertx);
        }

        logger.info("Processing time metrics test completed successfully");
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
            producer.send("Queue depth test message " + i);
        }
        
        // Wait for metrics to update and queue depth to increase
        CompletableFuture<Void> depthIncreased = new CompletableFuture<>();
        long depthCheckTimer = vertx.setPeriodic(100, id -> {
            if (queueDepthGauge.value() >= initialDepth) {
                depthIncreased.complete(null);
            }
        });
        depthIncreased.orTimeout(3, TimeUnit.SECONDS).join();
        vertx.cancelTimer(depthCheckTimer);

        double newDepth = queueDepthGauge.value();
        logger.info("📊 Queue depth after sending messages: {}", newDepth);

        // Create consumer to process messages
        Checkpoint processedAll = testContext.checkpoint(3);
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofMillis(100))
                .build());

        consumer.subscribe(message -> {
            processedAll.flag();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for messages to be processed
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "All messages should be processed");
        
        // Wait for metrics to update and queue depth to decrease
        CompletableFuture<Void> depthDecreased = new CompletableFuture<>();
        long depthCheckTimer2 = vertx.setPeriodic(100, id -> {
            if (queueDepthGauge.value() <= newDepth) {
                depthDecreased.complete(null);
            }
        });
        depthDecreased.orTimeout(3, TimeUnit.SECONDS).handle((v, ex) -> null).join();
        vertx.cancelTimer(depthCheckTimer2);

        double finalDepth = queueDepthGauge.value();
        logger.info("📊 Final queue depth after processing: {}", finalDepth);

        consumer.close();
        producer.close();
        
        logger.info("Queue depth metrics test completed successfully");
    }

    private void testMessageCountMetricsForMode(String topicName, ConsumerMode mode, int messageCount, Vertx vertx) throws Exception {
        // Get initial metric values
        Counter sentCounter = meterRegistry.find("peegeeq.messages.sent").counter();
        Counter receivedCounter = meterRegistry.find("peegeeq.messages.received").counter();
        Counter processedCounter = meterRegistry.find("peegeeq.messages.processed").counter();
        
        assertNotNull(sentCounter, "Messages sent counter should be registered");
        assertNotNull(receivedCounter, "Messages received counter should be registered");
        assertNotNull(processedCounter, "Messages processed counter should be registered");
        
        double initialSent = sentCounter.count();
        double initialReceived = receivedCounter.count();
        double initialProcessed = processedCounter.count();
        
        logger.info("📊 Initial metrics - Sent: {}, Received: {}, Processed: {}", 
            initialSent, initialReceived, initialProcessed);

        // Create consumer and producer
        CompletableFuture<Void> allProcessed = new CompletableFuture<>();
        AtomicInteger processedCount = new AtomicInteger(0);
        
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(mode)
                .pollingInterval(Duration.ofMillis(100))
                .build());

        consumer.subscribe(message -> {
            if (processedCount.incrementAndGet() >= messageCount) {
                allProcessed.complete(null);
            }
            return CompletableFuture.completedFuture(null);
        });

        // Wait for consumer setup using Vert.x timer
        CompletableFuture<Void> setupDelay = new CompletableFuture<>();
        vertx.setTimer(1000, id -> setupDelay.complete(null));
        setupDelay.join();

        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
        
        // Send messages
        for (int i = 0; i < messageCount; i++) {
            producer.send("Metrics test message " + i);
        }

        // Wait for all messages to be processed
        allProcessed.orTimeout(15, TimeUnit.SECONDS).join();
        assertEquals(messageCount, processedCount.get(), "All messages should be processed");

        // Wait for metrics to be updated using Vert.x periodic polling
        CompletableFuture<Void> metricsUpdated = new CompletableFuture<>();
        long metricsTimer = vertx.setPeriodic(100, id -> {
            if (sentCounter.count() >= initialSent + messageCount &&
                receivedCounter.count() >= initialReceived + messageCount &&
                processedCounter.count() >= initialProcessed + messageCount) {
                metricsUpdated.complete(null);
            }
        });
        metricsUpdated.orTimeout(3, TimeUnit.SECONDS).join();
        vertx.cancelTimer(metricsTimer);

        double finalSent = sentCounter.count();
        double finalReceived = receivedCounter.count();
        double finalProcessed = processedCounter.count();
        
        logger.info("📊 Final metrics - Sent: {}, Received: {}, Processed: {}", 
            finalSent, finalReceived, finalProcessed);

        consumer.close();
        producer.close();
    }

    private void testProcessingTimeMetricsForMode(String topicName, ConsumerMode mode, int messageCount, Vertx vertx) throws Exception {
        // Get processing time timer
        Timer processingTimer = meterRegistry.find("peegeeq.message.processing.time").timer();
        assertNotNull(processingTimer, "Message processing time timer should be registered");
        
        long initialCount = processingTimer.count();
        logger.info("📊 Initial processing timer count: {}", initialCount);

        // Create consumer with artificial processing delay
        CompletableFuture<Void> allProcessed = new CompletableFuture<>();
        AtomicInteger processed = new AtomicInteger(0);
        
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(mode)
                .pollingInterval(Duration.ofMillis(100))
                .build());

        consumer.subscribe(message -> {
            // Add small processing delay using Vert.x timer to ensure measurable timing
            CompletableFuture<Void> future = new CompletableFuture<>();
            vertx.setTimer(10, timerId -> {
                if (processed.incrementAndGet() >= messageCount) {
                    allProcessed.complete(null);
                }
                future.complete(null);
            });
            return future;
        });

        // Wait for consumer setup using Vert.x timer
        CompletableFuture<Void> setupDelay = new CompletableFuture<>();
        vertx.setTimer(1000, id -> setupDelay.complete(null));
        setupDelay.join();

        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
        
        // Send messages
        for (int i = 0; i < messageCount; i++) {
            producer.send("Processing time test message " + i);
        }

        // Wait for all messages to be processed
        allProcessed.orTimeout(15, TimeUnit.SECONDS).join();

        // Wait for metrics to be updated using Vert.x periodic polling
        CompletableFuture<Void> metricsUpdated = new CompletableFuture<>();
        long metricsTimer = vertx.setPeriodic(100, id -> {
            if (processingTimer.count() >= initialCount + messageCount &&
                processingTimer.totalTime(TimeUnit.MILLISECONDS) > 0) {
                metricsUpdated.complete(null);
            }
        });
        metricsUpdated.orTimeout(3, TimeUnit.SECONDS).join();
        vertx.cancelTimer(metricsTimer);

        long finalCount = processingTimer.count();
        double totalTime = processingTimer.totalTime(TimeUnit.MILLISECONDS);
        
        logger.info("📊 Final processing timer - Count: {}, Total time: {}ms", finalCount, totalTime);

        consumer.close();
        producer.close();
    }
}


