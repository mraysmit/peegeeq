package dev.mars.peegeeq.outbox.examples;

import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test for SystemPropertiesConfigurationExample functionality.
 * 
 * This test validates all system properties configuration patterns from the original 235-line example:
 * 1. High-Throughput Configuration - Optimized for maximum throughput with large batches
 * 2. Low-Latency Configuration - Optimized for minimal latency with frequent polling
 * 3. Reliable Configuration - Optimized for reliability with extensive retry logic
 * 4. Custom Configuration - Custom configuration for specific business requirements
 * 5. System Properties Validation - Validates that properties actually control runtime behavior
 * 
 * All original functionality is preserved with enhanced test assertions and documentation.
 * Tests demonstrate how system properties control PeeGeeQ runtime behavior.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
public class SystemPropertiesConfigurationExampleTest {

    private static final Logger logger = LoggerFactory.getLogger(SystemPropertiesConfigurationExampleTest.class);
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_sysprops_test")
            .withUsername("postgres")
            .withPassword("password");

    private final Map<String, String> originalProperties = new HashMap<>();
    
    @BeforeEach
    void setUp() throws Exception {
        // Initialize schema first
        TestSchemaInitializer.initializeSchema(postgres);

        logger.info("Setting up System Properties Configuration Example Test");
        
        // Save original system properties
        saveOriginalProperties();
        
        // Set database properties from TestContainer
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.database.schema", "public");
        
        logger.info("✓ System Properties Configuration Example Test setup completed");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        logger.info("Tearing down System Properties Configuration Example Test");
        
        // Restore original system properties
        restoreOriginalProperties();
        
        logger.info("✓ System Properties Configuration Example Test teardown completed");
    }

    /**
     * Test Pattern 1: High-Throughput Configuration
     * Validates configuration optimized for maximum throughput with large batches
     */
    @Test
    void testHighThroughputConfiguration() throws Exception {
        logger.info("=== Testing High-Throughput Configuration ===");
        
        // Configure for high throughput
        System.setProperty("peegeeq.queue.max-retries", "5");
        System.setProperty("peegeeq.queue.polling-interval", "PT1S");  // 1 second polling
        System.setProperty("peegeeq.consumer.threads", "8");           // 8 concurrent threads
        System.setProperty("peegeeq.queue.batch-size", "100");         // Large batches
        
        runScenario("high-throughput", "Optimized for maximum throughput with large batches");
        
        logger.info("✅ High-throughput configuration validated successfully");
    }

    /**
     * Test Pattern 2: Low-Latency Configuration
     * Validates configuration optimized for minimal latency with frequent polling
     */
    @Test
    void testLowLatencyConfiguration() throws Exception {
        logger.info("=== Testing Low-Latency Configuration ===");
        
        // Configure for low latency
        System.setProperty("peegeeq.queue.max-retries", "3");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.1S"); // 100ms polling
        System.setProperty("peegeeq.consumer.threads", "2");            // Moderate concurrency
        System.setProperty("peegeeq.queue.batch-size", "1");            // Single message processing
        
        runScenario("low-latency", "Optimized for minimal latency with frequent polling");
        
        logger.info("✅ Low-latency configuration validated successfully");
    }

    /**
     * Test Pattern 3: Reliable Configuration
     * Validates configuration optimized for reliability with extensive retry logic
     */
    @Test
    void testReliableConfiguration() throws Exception {
        logger.info("=== Testing Reliable Configuration ===");
        
        // Configure for reliability
        System.setProperty("peegeeq.queue.max-retries", "10");
        System.setProperty("peegeeq.queue.polling-interval", "PT2S");   // 2 second polling
        System.setProperty("peegeeq.consumer.threads", "4");            // Balanced concurrency
        System.setProperty("peegeeq.queue.batch-size", "25");           // Medium batches
        
        runScenario("reliable", "Optimized for reliability with extensive retry logic");
        
        logger.info("✅ Reliable configuration validated successfully");
    }

    /**
     * Test Pattern 4: Custom Configuration
     * Validates custom configuration for specific business requirements
     */
    @Test
    void testCustomConfiguration() throws Exception {
        logger.info("=== Testing Custom Business Configuration ===");
        
        // Configure for specific business needs
        System.setProperty("peegeeq.queue.max-retries", "7");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.5S"); // 500ms polling
        System.setProperty("peegeeq.consumer.threads", "6");            // Custom thread count
        System.setProperty("peegeeq.queue.batch-size", "50");           // Custom batch size
        
        runScenario("custom", "Custom configuration for specific business requirements");
        
        logger.info("✅ Custom configuration validated successfully");
    }

    /**
     * Test Pattern 5: System Properties Validation
     * Validates that system properties actually control runtime behavior
     */
    @Test
    void testSystemPropertiesValidation() throws Exception {
        logger.info("=== Testing System Properties Validation ===");
        
        // Test different property values and verify they're applied
        String[] maxRetries = {"3", "5", "10"};
        String[] batchSizes = {"1", "25", "100"};
        
        for (String retries : maxRetries) {
            for (String batchSize : batchSizes) {
                System.setProperty("peegeeq.queue.max-retries", retries);
                System.setProperty("peegeeq.queue.batch-size", batchSize);
                
                // Create new configuration to pick up properties
                PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
                
                // Validate properties are applied
                assertEquals(Integer.parseInt(retries), config.getQueueConfig().getMaxRetries(),
                    "Max retries should match system property");
                assertEquals(Integer.parseInt(batchSize), config.getQueueConfig().getBatchSize(),
                    "Batch size should match system property");
                
                logger.info("✓ Validated max-retries={}, batch-size={}", retries, batchSize);
            }
        }
        
        logger.info("✅ System properties validation completed successfully");
    }

    /**
     * Runs a complete scenario with the current system properties configuration.
     */
    private void runScenario(String scenarioName, String description) throws Exception {
        logger.info("📋 Scenario: {} - {}", scenarioName, description);
        
        // Initialize PeeGeeQ with current system properties
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        try (PeeGeeQManager manager = new PeeGeeQManager(config, new SimpleMeterRegistry())) {
            manager.start();
            
            // Log the current configuration
            logCurrentConfiguration(config);
            
            // Register outbox factory
            PgDatabaseService databaseService = new PgDatabaseService(manager);
            PgQueueFactoryProvider factoryProvider = new PgQueueFactoryProvider();
            OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) factoryProvider);
            
            // Create queue factory
            QueueFactory queueFactory = factoryProvider.createFactory("outbox", databaseService, new HashMap<>());
            
            String topic = "system-properties-demo-" + scenarioName;
            
            // Create producer and consumer
            MessageProducer<TestMessage> producer = queueFactory.createProducer(topic, TestMessage.class);
            MessageConsumer<TestMessage> consumer = queueFactory.createConsumer(topic, TestMessage.class);
            
            // Set up message processing
            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(5); // Wait for 5 messages
            
            consumer.subscribe(message -> {
                int count = processedCount.incrementAndGet();
                logger.info("📨 [{}] Processed message {} in thread: {} - Content: {}", 
                    scenarioName, count, Thread.currentThread().getName(), message.getPayload().content);
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });
            
            // Send test messages
            logger.info("📤 Sending 5 test messages for scenario: {}", scenarioName);
            for (int i = 1; i <= 5; i++) {
                TestMessage message = new TestMessage(
                    "Message " + i + " for " + scenarioName,
                    Instant.now().toString(),
                    "scenario-" + scenarioName
                );
                
                Map<String, String> headers = new HashMap<>();
                headers.put("scenario", scenarioName);
                headers.put("messageNumber", String.valueOf(i));
                
                producer.send(message, headers);
                logger.debug("📤 Sent message {}", i);
            }
            
            // Wait for processing
            boolean completed = latch.await(30, TimeUnit.SECONDS);
            assertTrue(completed, "All messages should be processed within timeout");
            assertEquals(5, processedCount.get(), "Should process exactly 5 messages");
            
            logger.info("📊 Scenario Results: {} - Processed {} messages", scenarioName, processedCount.get());
            
            // Cleanup
            consumer.close();
            producer.close();
        }
    }

    /**
     * Logs the current configuration values to show how system properties affect runtime behavior.
     */
    private void logCurrentConfiguration(PeeGeeQConfiguration config) {
        var queueConfig = config.getQueueConfig();
        
        logger.info("🔧 Current Configuration:");
        logger.info("  📊 Max Retries: {} (peegeeq.queue.max-retries)", queueConfig.getMaxRetries());
        logger.info("  ⏱️ Polling Interval: {} (peegeeq.queue.polling-interval)", queueConfig.getPollingInterval());
        logger.info("  🧵 Consumer Threads: {} (peegeeq.consumer.threads)", queueConfig.getConsumerThreads());
        logger.info("  📦 Batch Size: {} (peegeeq.queue.batch-size)", queueConfig.getBatchSize());
    }

    private void saveOriginalProperties() {
        String[] properties = {
            "peegeeq.queue.max-retries",
            "peegeeq.queue.polling-interval", 
            "peegeeq.consumer.threads",
            "peegeeq.queue.batch-size"
        };
        
        for (String prop : properties) {
            String value = System.getProperty(prop);
            if (value != null) {
                originalProperties.put(prop, value);
            }
        }
    }

    private void restoreOriginalProperties() {
        String[] properties = {
            "peegeeq.queue.max-retries",
            "peegeeq.queue.polling-interval", 
            "peegeeq.consumer.threads",
            "peegeeq.queue.batch-size"
        };
        
        for (String prop : properties) {
            if (originalProperties.containsKey(prop)) {
                System.setProperty(prop, originalProperties.get(prop));
            } else {
                System.clearProperty(prop);
            }
        }
    }

    /**
     * Test message class for system properties testing
     */
    public static class TestMessage {
        public String content;
        public String timestamp; // Use String instead of Instant to avoid serialization issues
        public String category;

        // Default constructor for Jackson
        public TestMessage() {
        }

        public TestMessage(String content, String timestamp, String category) {
            this.content = content;
            this.timestamp = timestamp;
            this.category = category;
        }
    }
}
