package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.outbox.OutboxFactory;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test to verify that the outbox module correctly uses the max-retries configuration.
 */
@Testcontainers
public class OutboxConfigurationIntegrationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(OutboxConfigurationIntegrationTest.class);
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_test")
            .withUsername("peegeeq_user")
            .withPassword("peegeeq_password");

    private PeeGeeQManager manager;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize schema first
        TestSchemaInitializer.initializeSchema(postgres);

        // Clear any existing system properties
        System.clearProperty("peegeeq.queue.max-retries");
        
        // Set database connection properties
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.queue.polling-interval", "PT0.1S");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (manager != null) {
            manager.stop();
        }
        
        // Clean up system properties
        System.clearProperty("peegeeq.queue.max-retries");
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        System.clearProperty("peegeeq.queue.polling-interval");
    }
    
    @Test
    void testOutboxRespectsMaxRetriesConfiguration() throws Exception {
        logger.info("=== Testing Outbox Respects Max Retries Configuration ===");
        
        // Set up database connection properties (like working tests)
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Set max retries to 2 (so we expect 3 total attempts: initial + 2 retries)
        System.setProperty("peegeeq.queue.max-retries", "2");

        // Initialize manager and components (using working profile)
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("basic-test"), new SimpleMeterRegistry());
        manager.start();

        // Create factory and components (following the pattern of working tests)
        DatabaseService databaseService = new PgDatabaseService(manager);
        OutboxFactory outboxFactory = new OutboxFactory(databaseService, manager.getConfiguration());

        // Use unique topic name like other tests
        String testTopic = "test-config-integration-" + UUID.randomUUID().toString().substring(0, 8);

        producer = outboxFactory.createProducer(testTopic, String.class);
        consumer = outboxFactory.createConsumer(testTopic, String.class);
        
        // Test message and attempt tracking
        String testMessage = "Message for config integration test";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch retryLatch = new CountDownLatch(3); // Expect 3 attempts (initial + 2 retries)

        // Send message
        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Set up consumer that always fails
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.error("🔥 INTENTIONAL FAILURE: Config integration attempt {} for message: {} (Thread: {})",
                attempt, message.getPayload(), Thread.currentThread().getName());
            retryLatch.countDown();

            // Return a failed CompletableFuture instead of throwing an exception
            return CompletableFuture.failedFuture(
                new RuntimeException("INTENTIONAL FAILURE: Testing config integration, attempt " + attempt));
        });

        // Wait for all expected attempts
        boolean completed = retryLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing exactly 3 times (initial + 2 retries)");
        assertEquals(3, attemptCount.get(), "Should respect max retries configuration of 2");
        
        // Wait a bit more to ensure no additional attempts
        Thread.sleep(2000);
        assertEquals(3, attemptCount.get(), "Should not exceed configured max retries");
        
        logger.info("✅ Outbox configuration integration test completed successfully");
        logger.info("   Expected attempts: 3 (initial + 2 retries)");
        logger.info("   Actual attempts: {}", attemptCount.get());
    }
    
    @Test
    void testOutboxUsesDefaultWhenNoConfigurationSet() throws Exception {
        logger.info("=== Testing Outbox Uses Default Configuration ===");
        
        // Set up database connection properties (like working tests)
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Don't set max retries - should use default from properties file (3 for basic-test/default profile)

        // Initialize manager and components (using working profile)
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("basic-test"), new SimpleMeterRegistry());
        manager.start();

        // Create factory and components (following the pattern of working tests)
        DatabaseService databaseService = new PgDatabaseService(manager);
        OutboxFactory outboxFactory = new OutboxFactory(databaseService, manager.getConfiguration());

        // Use unique topic name like other tests
        String testTopic = "test-default-config-" + UUID.randomUUID().toString().substring(0, 8);

        producer = outboxFactory.createProducer(testTopic, String.class);
        consumer = outboxFactory.createConsumer(testTopic, String.class);
        
        // Test message and attempt tracking
        String testMessage = "Message for default config test";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch retryLatch = new CountDownLatch(4); // Expect 4 attempts (initial + 3 retries from default profile)

        // Set up consumer that always fails BEFORE sending the message
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.error("🔥 INTENTIONAL FAILURE: Default config attempt {} for message: {} (Thread: {})",
                attempt, message.getPayload(), Thread.currentThread().getName());
            retryLatch.countDown();

            // Return a failed CompletableFuture instead of throwing an exception
            return CompletableFuture.failedFuture(
                new RuntimeException("INTENTIONAL FAILURE: Testing default config, attempt " + attempt));
        });

        // Send message
        logger.info("📤 Sending message: {}", testMessage);
        try {
            producer.send(testMessage).get(5, TimeUnit.SECONDS);
            logger.info("✅ Message sent successfully");
        } catch (Exception e) {
            logger.error("❌ Failed to send message", e);
            throw e;
        }

        // Wait for all expected attempts
        boolean completed = retryLatch.await(20, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing exactly 4 times (initial + 3 retries from default profile)");
        assertEquals(4, attemptCount.get(), "Should use default max retries from default profile (3)");

        // Wait a bit more to ensure no additional attempts
        Thread.sleep(2000);
        assertEquals(4, attemptCount.get(), "Should not exceed default max retries");

        logger.info("✅ Outbox default configuration test completed successfully");
        logger.info("   Expected attempts: 4 (initial + 3 retries from default profile)");
        logger.info("   Actual attempts: {}", attemptCount.get());
    }

    @Test
    void testBasicOutboxMessageProcessing() throws Exception {
        System.out.println("=== Testing Basic Outbox Message Processing ===");

        // Set up database connection properties (like OutboxBasicTest does)
        System.out.println("🔧 Setting up database connection properties");
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.out.println("✅ Database connection properties set");

        // Initialize manager and components
        System.out.println("🔧 Creating PeeGeeQManager with basic-test configuration");
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("basic-test"), new SimpleMeterRegistry());
        System.out.println("🚀 Starting PeeGeeQManager");
        manager.start();
        System.out.println("✅ PeeGeeQManager started successfully");

        // Create factory and components (following the pattern of working tests)
        System.out.println("🔧 Creating database service and outbox factory");
        DatabaseService databaseService = new PgDatabaseService(manager);
        OutboxFactory outboxFactory = new OutboxFactory(databaseService, manager.getConfiguration());
        System.out.println("✅ Outbox factory created successfully");

        // Use unique topic name like other tests
        String testTopic = "test-basic-processing-" + UUID.randomUUID().toString().substring(0, 8);

        System.out.println("📤 Creating producer for topic: " + testTopic);
        producer = outboxFactory.createProducer(testTopic, String.class);
        System.out.println("✅ Producer created successfully");

        System.out.println("📥 Creating consumer for topic: " + testTopic);
        consumer = outboxFactory.createConsumer(testTopic, String.class);
        System.out.println("✅ Consumer created successfully");

        // Test message and simple processing
        String testMessage = "Basic processing test message";
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger processedCount = new AtomicInteger(0);

        System.out.println("🔧 Setting up consumer subscription");
        // Set up consumer that succeeds
        consumer.subscribe(message -> {
            int count = processedCount.incrementAndGet();
            System.out.println("✅ Successfully processed message " + count + " (attempt " + count + "): " + message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        System.out.println("✅ Consumer subscribed successfully");

        // Send message
        System.out.println("📤 Sending basic test message: " + testMessage);
        try {
            producer.send(testMessage).get(5, TimeUnit.SECONDS);
            System.out.println("✅ Message sent successfully");
        } catch (Exception e) {
            System.out.println("❌ Failed to send message: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }

        // Wait for processing
        System.out.println("⏳ Waiting for message processing (10 seconds timeout)...");
        boolean completed = latch.await(10, TimeUnit.SECONDS);
        System.out.println("⏰ Wait completed. Result: " + completed + ", Processed count: " + processedCount.get());
        assertTrue(completed, "Should have processed the message within timeout");
        assertEquals(1, processedCount.get(), "Should process exactly one message");

        logger.info("✅ Basic outbox message processing test completed successfully");
        logger.info("   Messages processed: {}", processedCount.get());
    }
}
