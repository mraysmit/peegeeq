package dev.mars.peegeeq.examples.nativequeue;

import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demo test showcasing System Properties Configuration Patterns for PeeGeeQ.
 * 
 * This test demonstrates:
 * 1. Dynamic Configuration Management - Runtime property updates
 * 2. Environment-Specific Settings - DEV/STAGING/PROD configurations  
 * 3. Configuration Validation - Property validation and error handling
 * 4. Hot Configuration Reload - Live configuration updates without restart
 * 5. Configuration Inheritance - Hierarchical configuration patterns
 * 
 * Based on Advanced Messaging Patterns from PeeGeeQ Complete Guide.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SystemPropertiesConfigurationDemoTest {

    static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.configureSharedProperties(registry);
    }

    private PeeGeeQManager manager;
    private QueueFactory queueFactory;

    // Track all consumers and producers for proper cleanup
    private final List<MessageConsumer<?>> activeConsumers = new ArrayList<>();
    private final List<MessageProducer<?>> activeProducers = new ArrayList<>();

    // Test configuration environments
    enum Environment {
        DEVELOPMENT("dev", 100, 5000, true),
        STAGING("staging", 500, 10000, true), 
        PRODUCTION("prod", 1000, 30000, false);

        final String name;
        final int batchSize;
        final int timeoutMs;
        final boolean debugEnabled;

        Environment(String name, int batchSize, int timeoutMs, boolean debugEnabled) {
            this.name = name;
            this.batchSize = batchSize;
            this.timeoutMs = timeoutMs;
            this.debugEnabled = debugEnabled;
        }
    }

    // Configuration event for testing - following established POJO pattern
    static class ConfigurationEvent {
        private String eventId;
        private String environment;
        private Integer batchSize;
        private Integer timeoutMs;
        private Boolean debugEnabled;
        private String change;
        private String timestamp;

        // Default constructor for Jackson
        public ConfigurationEvent() {}

        public ConfigurationEvent(String eventId, String environment, Integer batchSize, Integer timeoutMs, Boolean debugEnabled, String change) {
            this.eventId = eventId;
            this.environment = environment;
            this.batchSize = batchSize;
            this.timeoutMs = timeoutMs;
            this.debugEnabled = debugEnabled;
            this.change = change;
            this.timestamp = Instant.now().toString();
        }

        // Getters and setters
        public String getEventId() { return eventId; }
        public void setEventId(String eventId) { this.eventId = eventId; }

        public String getEnvironment() { return environment; }
        public void setEnvironment(String environment) { this.environment = environment; }

        public Integer getBatchSize() { return batchSize; }
        public void setBatchSize(Integer batchSize) { this.batchSize = batchSize; }

        public Integer getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(Integer timeoutMs) { this.timeoutMs = timeoutMs; }

        public Boolean getDebugEnabled() { return debugEnabled; }
        public void setDebugEnabled(Boolean debugEnabled) { this.debugEnabled = debugEnabled; }

        public String getChange() { return change; }
        public void setChange(String change) { this.change = change; }

        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

        @Override
        public String toString() {
            return String.format("ConfigurationEvent{eventId='%s', environment='%s', batchSize=%s, timeoutMs=%s, debugEnabled=%s, change='%s', timestamp='%s'}",
                eventId, environment, batchSize, timeoutMs, debugEnabled, change, timestamp);
        }
    }

    /**
     * Configure system properties for TestContainers PostgreSQL connection
     */
    private void configureSystemPropertiesForContainer() {
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
    }

    @BeforeEach
    void setUp() {
        System.out.println("\n🔧 Setting up System Properties Configuration Demo Test");

        // Configure system properties for TestContainers
        configureSystemPropertiesForContainer();

        // Initialize database schema for system properties configuration test
        System.out.println("🔧 Initializing database schema for system properties configuration test");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
        System.out.println("✅ Database schema initialized successfully using centralized schema initializer (ALL components)");

        // Initialize PeeGeeQ with development configuration
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("development");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create native factory
        var databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register native factory implementation
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        queueFactory = provider.createFactory("native", databaseService);

        System.out.println("✅ Setup complete - Ready for configuration pattern testing");
    }

    @AfterEach
    void tearDown() {
        System.out.println("🧹 Cleaning up System Properties Configuration Demo Test");

        // CRITICAL: Close all consumers first to stop background polling
        System.out.println("🔄 Closing " + activeConsumers.size() + " active consumers...");
        for (MessageConsumer<?> consumer : activeConsumers) {
            try {
                consumer.close();
                System.out.println("✅ Closed consumer");
            } catch (Exception e) {
                System.err.println("⚠️ Error closing consumer: " + e.getMessage());
            }
        }
        activeConsumers.clear();

        // Close all producers
        System.out.println("🔄 Closing " + activeProducers.size() + " active producers...");
        for (MessageProducer<?> producer : activeProducers) {
            try {
                producer.close();
                System.out.println("✅ Closed producer");
            } catch (Exception e) {
                System.err.println("⚠️ Error closing producer: " + e.getMessage());
            }
        }
        activeProducers.clear();

        if (manager != null) {
            try {
                System.out.println("🔄 Closing PeeGeeQ manager...");
                manager.close();
                System.out.println("✅ PeeGeeQ manager closed successfully");

                // CRITICAL: Wait for all resources to be fully released
                // This prevents connection pool exhaustion in subsequent tests
                Thread.sleep(2000);
                System.out.println("⏱️ Resource cleanup wait completed");
            } catch (Exception e) {
                System.err.println("⚠️ Error during manager cleanup: " + e.getMessage());
            }
        }

        // Clean up system properties
        System.clearProperty("peegeeq.database.url");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        System.clearProperty("peegeeq.batch.size");
        System.clearProperty("peegeeq.timeout.ms");
        System.clearProperty("peegeeq.debug.enabled");

        System.out.println("✅ Cleanup complete");
    }

    @Test
    @Order(1)
    @DisplayName("Dynamic Configuration Management - Runtime Property Updates")
    void testDynamicConfigurationManagement() throws Exception {
        System.out.println("\n🔄 Testing Dynamic Configuration Management");

        String queueName = "config-dynamic-queue";
        List<ConfigurationEvent> receivedEvents = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(3); // Expect 3 configuration updates

        // Create producer and consumer
        MessageProducer<ConfigurationEvent> producer = queueFactory.createProducer(queueName, ConfigurationEvent.class);
        MessageConsumer<ConfigurationEvent> consumer = queueFactory.createConsumer(queueName, ConfigurationEvent.class);

        // Track for cleanup
        activeProducers.add(producer);
        activeConsumers.add(consumer);

        // Subscribe to configuration events
        consumer.subscribe(message -> {
            ConfigurationEvent event = message.getPayload();
            System.out.println("📨 Received configuration update: " + event.getEnvironment() +
                             " - Batch Size: " + event.getBatchSize() +
                             " - Timeout: " + event.getTimeoutMs() +
                             " - Debug: " + event.getDebugEnabled());
            System.out.println("📨 Full event: " + event.toString());
            receivedEvents.add(event);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Test dynamic configuration updates
        System.out.println("🔧 Applying dynamic configuration changes...");

        // Update 1: Change batch size
        System.setProperty("peegeeq.batch.size", "200");
        producer.send(new ConfigurationEvent("config-1", "development", 200, null, null, "increased_batch_size"));

        // Update 2: Change timeout
        System.setProperty("peegeeq.timeout.ms", "8000");
        producer.send(new ConfigurationEvent("config-2", "development", null, 8000, null, "increased_timeout"));

        // Update 3: Enable debug mode
        System.setProperty("peegeeq.debug.enabled", "true");
        producer.send(new ConfigurationEvent("config-3", "development", null, null, true, "enabled_debug"));

        // Wait for all configuration updates
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Should receive all configuration updates");

        // Verify configuration updates
        assertEquals(3, receivedEvents.size(), "Should receive exactly 3 configuration updates");
        
        // Verify each configuration change (order-independent verification)
        // PostgreSQL NOTIFY doesn't guarantee delivery order, especially under load
        ConfigurationEvent batchSizeEvent = receivedEvents.stream()
            .filter(e -> e.getBatchSize() != null)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No batch size event found"));

        ConfigurationEvent timeoutEvent = receivedEvents.stream()
            .filter(e -> e.getTimeoutMs() != null)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No timeout event found"));

        ConfigurationEvent debugEvent = receivedEvents.stream()
            .filter(e -> e.getDebugEnabled() != null)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No debug event found"));

        // Verify batch size event
        assertEquals("development", batchSizeEvent.getEnvironment());
        assertEquals(200, batchSizeEvent.getBatchSize());
        assertEquals("config-1", batchSizeEvent.getEventId());

        // Verify timeout event
        assertEquals("development", timeoutEvent.getEnvironment());
        assertEquals(8000, timeoutEvent.getTimeoutMs());
        assertEquals("config-2", timeoutEvent.getEventId());

        // Verify debug event
        assertEquals("development", debugEvent.getEnvironment());
        assertTrue(debugEvent.getDebugEnabled());
        assertEquals("config-3", debugEvent.getEventId());

        System.out.println("✅ Dynamic Configuration Management test completed successfully");
        System.out.println("📊 Configuration updates processed: " + receivedEvents.size());
    }

    @Test
    @Order(2)
    @DisplayName("Environment-Specific Settings - DEV/STAGING/PROD Configurations")
    void testEnvironmentSpecificSettings() throws Exception {
        System.out.println("\n🌍 Testing Environment-Specific Settings");

        String queueName = "config-environment-queue";
        List<ConfigurationEvent> receivedEvents = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(Environment.values().length);

        // Create producer and consumer
        MessageProducer<ConfigurationEvent> producer = queueFactory.createProducer(queueName, ConfigurationEvent.class);
        MessageConsumer<ConfigurationEvent> consumer = queueFactory.createConsumer(queueName, ConfigurationEvent.class);

        // Track for cleanup
        activeProducers.add(producer);
        activeConsumers.add(consumer);

        // Subscribe to environment configuration events
        consumer.subscribe(message -> {
            ConfigurationEvent event = message.getPayload();
            System.out.println("🌍 Environment configuration: " + event.getEnvironment() +
                             " - Batch: " + event.getBatchSize() +
                             ", Timeout: " + event.getTimeoutMs() +
                             ", Debug: " + event.getDebugEnabled());
            receivedEvents.add(event);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Test each environment configuration
        System.out.println("🔧 Testing environment-specific configurations...");

        for (Environment env : Environment.values()) {
            // Apply environment-specific system properties
            System.setProperty("peegeeq.environment", env.name);
            System.setProperty("peegeeq.batch.size", String.valueOf(env.batchSize));
            System.setProperty("peegeeq.timeout.ms", String.valueOf(env.timeoutMs));
            System.setProperty("peegeeq.debug.enabled", String.valueOf(env.debugEnabled));

            // Create configuration event for this environment
            producer.send(new ConfigurationEvent("env-" + env.name, env.name, env.batchSize, env.timeoutMs, env.debugEnabled, "environment_config"));
            
            System.out.println("📤 Sent " + env.name.toUpperCase() + " configuration");
        }

        // Wait for all environment configurations
        assertTrue(latch.await(15, TimeUnit.SECONDS), "Should receive all environment configurations");

        // Verify environment configurations
        assertEquals(Environment.values().length, receivedEvents.size(),
                    "Should receive configuration for each environment");

        // Verify each environment's settings (order-independent)
        for (Environment expectedEnv : Environment.values()) {
            ConfigurationEvent event = receivedEvents.stream()
                .filter(e -> expectedEnv.name.equals(e.getEnvironment()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing configuration for environment: " + expectedEnv.name));

            assertEquals(expectedEnv.name, event.getEnvironment());
            assertEquals(expectedEnv.batchSize, event.getBatchSize());
            assertEquals(expectedEnv.timeoutMs, event.getTimeoutMs());
            assertEquals(expectedEnv.debugEnabled, event.getDebugEnabled());
        }

        System.out.println("✅ Environment-Specific Settings test completed successfully");
        System.out.println("📊 Environment configurations tested: " + receivedEvents.size());
    }

    @Test
    @Order(3)
    @DisplayName("Configuration Validation - Property Validation and Error Handling")
    void testConfigurationValidation() throws Exception {
        System.out.println("\n✅ Testing Configuration Validation");

        String queueName = "config-validation-queue";
        List<ConfigurationEvent> validEvents = new ArrayList<>();
        List<String> validationErrors = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(5); // 3 valid + 2 invalid configurations

        // Create producer and consumer
        MessageProducer<ConfigurationEvent> producer = queueFactory.createProducer(queueName, ConfigurationEvent.class);
        MessageConsumer<ConfigurationEvent> consumer = queueFactory.createConsumer(queueName, ConfigurationEvent.class);

        // Track for cleanup
        activeProducers.add(producer);
        activeConsumers.add(consumer);

        // Subscribe with validation logic
        consumer.subscribe(message -> {
            ConfigurationEvent event = message.getPayload();
            try {
                // Validate configuration
                if (validateConfiguration(event)) {
                    System.out.println("✅ Valid configuration: " + event.getEventId());
                    validEvents.add(event);
                } else {
                    String error = "Invalid configuration: " + event.getEventId();
                    System.out.println("❌ " + error);
                    validationErrors.add(error);
                }
            } catch (Exception e) {
                String error = "Validation error for " + event.getEventId() + ": " + e.getMessage();
                System.out.println("⚠️ " + error);
                validationErrors.add(error);
            }
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Test valid configurations
        System.out.println("🔧 Testing configuration validation...");

        // Valid config 1: Normal values
        producer.send(new ConfigurationEvent("valid-1", "development", 100, 5000, true, "normal_values"));

        // Valid config 2: Production values
        producer.send(new ConfigurationEvent("valid-2", "production", 1000, 30000, false, "production_values"));

        // Valid config 3: Edge case values
        producer.send(new ConfigurationEvent("valid-3", "test", 1, 1000, true, "edge_case_values"));

        // Invalid config 1: Negative batch size
        producer.send(new ConfigurationEvent("invalid-1", "development", -10, 5000, true, "negative_batch_size"));

        // Invalid config 2: Zero timeout
        producer.send(new ConfigurationEvent("invalid-2", "development", 100, 0, true, "zero_timeout"));

        // Wait for all validation attempts
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Should process all configuration validations");

        // Verify validation results
        assertEquals(3, validEvents.size(), "Should have 3 valid configurations");
        assertEquals(2, validationErrors.size(), "Should have 2 validation errors");

        // Verify valid configurations
        assertTrue(validEvents.stream().anyMatch(e -> e.getEventId().equals("valid-1")));
        assertTrue(validEvents.stream().anyMatch(e -> e.getEventId().equals("valid-2")));
        assertTrue(validEvents.stream().anyMatch(e -> e.getEventId().equals("valid-3")));

        // Verify validation errors
        assertTrue(validationErrors.stream().anyMatch(e -> e.contains("invalid-1")));
        assertTrue(validationErrors.stream().anyMatch(e -> e.contains("invalid-2")));

        System.out.println("✅ Configuration Validation test completed successfully");
        System.out.println("📊 Valid configurations: " + validEvents.size() + ", Validation errors: " + validationErrors.size());
    }

    @Test
    @Order(4)
    @DisplayName("Hot Configuration Reload - Live Configuration Updates")
    void testHotConfigurationReload() throws Exception {
        System.out.println("\n🔥 Testing Hot Configuration Reload");

        String queueName = "config-hot-reload-queue";
        AtomicInteger reloadCount = new AtomicInteger(0);
        List<ConfigurationEvent> reloadEvents = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(4); // 4 hot reloads

        // Create producer and consumer
        MessageProducer<ConfigurationEvent> producer = queueFactory.createProducer(queueName, ConfigurationEvent.class);
        MessageConsumer<ConfigurationEvent> consumer = queueFactory.createConsumer(queueName, ConfigurationEvent.class);

        // Track for cleanup
        activeProducers.add(producer);
        activeConsumers.add(consumer);

        // Subscribe to hot reload events
        consumer.subscribe(message -> {
            ConfigurationEvent event = message.getPayload();
            int currentReload = reloadCount.incrementAndGet();
            System.out.println("🔥 Hot reload #" + currentReload + ": " + event.getEventId() +
                             " - Config: " + event.toString());
            reloadEvents.add(event);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Simulate hot configuration reloads
        System.out.println("🔧 Performing hot configuration reloads...");

        // Reload 1: Increase batch size during runtime
        producer.send(new ConfigurationEvent("hot-reload-1", "production", 500, null, null, "performance_optimization"));

        // Use CompletableFuture for async delay instead of Thread.sleep
        CompletableFuture<Void> delay1 = CompletableFuture.runAsync(() -> {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        delay1.join();

        // Reload 2: Adjust timeout for better responsiveness
        producer.send(new ConfigurationEvent("hot-reload-2", "production", null, 15000, null, "responsiveness_improvement"));

        CompletableFuture<Void> delay2 = CompletableFuture.runAsync(() -> {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        delay2.join();

        // Reload 3: Enable debug for troubleshooting
        producer.send(new ConfigurationEvent("hot-reload-3", "production", null, null, true, "troubleshooting_enabled"));

        CompletableFuture<Void> delay3 = CompletableFuture.runAsync(() -> {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        delay3.join();

        // Reload 4: Complete configuration update
        producer.send(new ConfigurationEvent("hot-reload-4", "production", 750, 20000, false, "complete_optimization"));

        // Wait for all hot reloads
        assertTrue(latch.await(15, TimeUnit.SECONDS), "Should complete all hot reloads");

        // Verify hot reload results
        assertEquals(4, reloadEvents.size(), "Should have 4 hot reload events");
        assertEquals(4, reloadCount.get(), "Should have processed 4 hot reloads");

        // Verify reload sequence
        assertEquals("hot-reload-1", reloadEvents.get(0).eventId);
        assertEquals("hot-reload-2", reloadEvents.get(1).eventId);
        assertEquals("hot-reload-3", reloadEvents.get(2).eventId);
        assertEquals("hot-reload-4", reloadEvents.get(3).eventId);

        // Verify final configuration state
        ConfigurationEvent finalReload = reloadEvents.get(3);
        assertEquals(750, finalReload.getBatchSize());
        assertEquals(20000, finalReload.getTimeoutMs());
        assertFalse(finalReload.getDebugEnabled());

        System.out.println("✅ Hot Configuration Reload test completed successfully");
        System.out.println("📊 Hot reloads processed: " + reloadCount.get());
    }

    /**
     * Validates configuration parameters according to business rules.
     */
    private boolean validateConfiguration(ConfigurationEvent event) {
        // Validate batch size
        if (event.getBatchSize() != null) {
            Integer batchSize = event.getBatchSize();
            if (batchSize <= 0 || batchSize > 10000) {
                return false;
            }
        }

        // Validate timeout
        if (event.getTimeoutMs() != null) {
            Integer timeoutMs = event.getTimeoutMs();
            if (timeoutMs <= 0 || timeoutMs > 300000) { // Max 5 minutes
                return false;
            }
        }

        // Debug enabled is always valid (boolean)
        return true;
    }


}
