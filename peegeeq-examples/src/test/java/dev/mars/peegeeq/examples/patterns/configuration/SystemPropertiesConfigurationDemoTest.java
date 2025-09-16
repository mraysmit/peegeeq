package dev.mars.peegeeq.examples.patterns.configuration;

import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory queueFactory;

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

    // Configuration event for testing
    static class ConfigurationEvent {
        public final String eventId;
        public final String environment;
        public final JsonObject configuration;
        public final Instant timestamp;

        public ConfigurationEvent(String eventId, String environment, JsonObject configuration) {
            this.eventId = eventId;
            this.environment = environment;
            this.configuration = configuration;
            this.timestamp = Instant.now();
        }

        public JsonObject toJson() {
            return new JsonObject()
                    .put("eventId", eventId)
                    .put("environment", environment)
                    .put("configuration", configuration)
                    .put("timestamp", timestamp);
        }
    }

    @BeforeEach
    void setUp() {
        System.out.println("\n🔧 Setting up System Properties Configuration Demo Test");

        System.setProperty("peegeeq.database.url", jdbcUrl);
        System.setProperty("peegeeq.database.username", username);
        System.setProperty("peegeeq.database.password", password);

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
        
        if (manager != null) {
            try {
                manager.close();
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

        // Subscribe to configuration events
        consumer.subscribe(message -> {
            ConfigurationEvent event = message.getPayload();
            System.out.println("📨 Received configuration update: " + event.environment +
                             " - Batch Size: " + event.configuration.get("batchSize"));
            receivedEvents.add(event);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Test dynamic configuration updates
        System.out.println("🔧 Applying dynamic configuration changes...");

        // Update 1: Change batch size
        System.setProperty("peegeeq.batch.size", "200");
        JsonObject config1 = new JsonObject().put("batchSize", 200).put("change", "increased_batch_size");
        producer.send(new ConfigurationEvent("config-1", "development", config1));

        // Update 2: Change timeout
        System.setProperty("peegeeq.timeout.ms", "8000");
        JsonObject config2 = new JsonObject().put("timeoutMs", 8000).put("change", "increased_timeout");
        producer.send(new ConfigurationEvent("config-2", "development", config2));

        // Update 3: Enable debug mode
        System.setProperty("peegeeq.debug.enabled", "true");
        JsonObject config3 = new JsonObject().put("debugEnabled", true).put("change", "enabled_debug");
        producer.send(new ConfigurationEvent("config-3", "development", config3));

        // Wait for all configuration updates
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Should receive all configuration updates");

        // Verify configuration updates
        assertEquals(3, receivedEvents.size(), "Should receive exactly 3 configuration updates");
        
        // Verify each configuration change
        ConfigurationEvent event1 = receivedEvents.get(0);
        assertEquals("development", event1.environment);
        assertEquals(200, event1.configuration.getInteger("batchSize"));

        ConfigurationEvent event2 = receivedEvents.get(1);
        assertEquals(8000, event2.configuration.getInteger("timeoutMs"));

        ConfigurationEvent event3 = receivedEvents.get(2);
        assertTrue(event3.configuration.getBoolean("debugEnabled"));

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

        // Subscribe to environment configuration events
        consumer.subscribe(message -> {
            ConfigurationEvent event = message.getPayload();
            System.out.println("🌍 Environment configuration: " + event.environment +
                             " - Batch: " + event.configuration.get("batchSize") +
                             ", Timeout: " + event.configuration.get("timeoutMs") +
                             ", Debug: " + event.configuration.get("debugEnabled"));
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
            JsonObject config = new JsonObject()
                    .put("environment", env.name)
                    .put("batchSize", env.batchSize)
                    .put("timeoutMs", env.timeoutMs)
                    .put("debugEnabled", env.debugEnabled);

            producer.send(new ConfigurationEvent("env-" + env.name, env.name, config));
            
            System.out.println("📤 Sent " + env.name.toUpperCase() + " configuration");
        }

        // Wait for all environment configurations
        assertTrue(latch.await(15, TimeUnit.SECONDS), "Should receive all environment configurations");

        // Verify environment configurations
        assertEquals(Environment.values().length, receivedEvents.size(), 
                    "Should receive configuration for each environment");

        // Verify each environment's settings
        for (int i = 0; i < Environment.values().length; i++) {
            Environment expectedEnv = Environment.values()[i];
            ConfigurationEvent event = receivedEvents.get(i);
            
            assertEquals(expectedEnv.name, event.environment);
            assertEquals(expectedEnv.batchSize, event.configuration.getInteger("batchSize"));
            assertEquals(expectedEnv.timeoutMs, event.configuration.getInteger("timeoutMs"));
            assertEquals(expectedEnv.debugEnabled, event.configuration.getBoolean("debugEnabled"));
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

        // Subscribe with validation logic
        consumer.subscribe(message -> {
            ConfigurationEvent event = message.getPayload();
            try {
                // Validate configuration
                if (validateConfiguration(event.configuration)) {
                    System.out.println("✅ Valid configuration: " + event.eventId);
                    validEvents.add(event);
                } else {
                    String error = "Invalid configuration: " + event.eventId;
                    System.out.println("❌ " + error);
                    validationErrors.add(error);
                }
            } catch (Exception e) {
                String error = "Validation error for " + event.eventId + ": " + e.getMessage();
                System.out.println("⚠️ " + error);
                validationErrors.add(error);
            }
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Test valid configurations
        System.out.println("🔧 Testing configuration validation...");

        // Valid config 1: Normal values
        JsonObject validConfig1 = new JsonObject()
                .put("batchSize", 100)
                .put("timeoutMs", 5000)
                .put("debugEnabled", true);
        producer.send(new ConfigurationEvent("valid-1", "development", validConfig1));

        // Valid config 2: Production values
        JsonObject validConfig2 = new JsonObject()
                .put("batchSize", 1000)
                .put("timeoutMs", 30000)
                .put("debugEnabled", false);
        producer.send(new ConfigurationEvent("valid-2", "production", validConfig2));

        // Valid config 3: Edge case values
        JsonObject validConfig3 = new JsonObject()
                .put("batchSize", 1)
                .put("timeoutMs", 1000)
                .put("debugEnabled", true);
        producer.send(new ConfigurationEvent("valid-3", "test", validConfig3));

        // Invalid config 1: Negative batch size
        JsonObject invalidConfig1 = new JsonObject()
                .put("batchSize", -10)
                .put("timeoutMs", 5000)
                .put("debugEnabled", true);
        producer.send(new ConfigurationEvent("invalid-1", "development", invalidConfig1));

        // Invalid config 2: Zero timeout
        JsonObject invalidConfig2 = new JsonObject()
                .put("batchSize", 100)
                .put("timeoutMs", 0)
                .put("debugEnabled", true);
        producer.send(new ConfigurationEvent("invalid-2", "development", invalidConfig2));

        // Wait for all validation attempts
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Should process all configuration validations");

        // Verify validation results
        assertEquals(3, validEvents.size(), "Should have 3 valid configurations");
        assertEquals(2, validationErrors.size(), "Should have 2 validation errors");

        // Verify valid configurations
        assertTrue(validEvents.stream().anyMatch(e -> e.eventId.equals("valid-1")));
        assertTrue(validEvents.stream().anyMatch(e -> e.eventId.equals("valid-2")));
        assertTrue(validEvents.stream().anyMatch(e -> e.eventId.equals("valid-3")));

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

        // Subscribe to hot reload events
        consumer.subscribe(message -> {
            ConfigurationEvent event = message.getPayload();
            int currentReload = reloadCount.incrementAndGet();
            System.out.println("🔥 Hot reload #" + currentReload + ": " + event.eventId +
                             " - Config: " + event.configuration.encode());
            reloadEvents.add(event);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Simulate hot configuration reloads
        System.out.println("🔧 Performing hot configuration reloads...");

        // Reload 1: Increase batch size during runtime
        JsonObject reload1 = new JsonObject()
                .put("batchSize", 500)
                .put("reloadReason", "performance_optimization")
                .put("reloadTimestamp", Instant.now().toString());
        producer.send(new ConfigurationEvent("hot-reload-1", "production", reload1));

        // Use CompletableFuture for async delay instead of Thread.sleep
        CompletableFuture<Void> delay1 = CompletableFuture.runAsync(() -> {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        delay1.join();

        // Reload 2: Adjust timeout for better responsiveness
        JsonObject reload2 = new JsonObject()
                .put("timeoutMs", 15000)
                .put("reloadReason", "responsiveness_improvement")
                .put("reloadTimestamp", Instant.now().toString());
        producer.send(new ConfigurationEvent("hot-reload-2", "production", reload2));

        CompletableFuture<Void> delay2 = CompletableFuture.runAsync(() -> {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        delay2.join();

        // Reload 3: Enable debug for troubleshooting
        JsonObject reload3 = new JsonObject()
                .put("debugEnabled", true)
                .put("reloadReason", "troubleshooting_enabled")
                .put("reloadTimestamp", Instant.now().toString());
        producer.send(new ConfigurationEvent("hot-reload-3", "production", reload3));

        CompletableFuture<Void> delay3 = CompletableFuture.runAsync(() -> {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        delay3.join();

        // Reload 4: Complete configuration update
        JsonObject reload4 = new JsonObject()
                .put("batchSize", 750)
                .put("timeoutMs", 20000)
                .put("debugEnabled", false)
                .put("reloadReason", "complete_optimization")
                .put("reloadTimestamp", Instant.now().toString());
        producer.send(new ConfigurationEvent("hot-reload-4", "production", reload4));

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
        assertEquals(750, (Integer) finalReload.configuration.get("batchSize"));
        assertEquals(20000, (Integer) finalReload.configuration.get("timeoutMs"));
        assertFalse((Boolean) finalReload.configuration.get("debugEnabled"));

        System.out.println("✅ Hot Configuration Reload test completed successfully");
        System.out.println("📊 Hot reloads processed: " + reloadCount.get());
    }

    /**
     * Validates configuration parameters according to business rules.
     */
    private boolean validateConfiguration(JsonObject config) {
        // Validate batch size
        if (config.containsKey("batchSize")) {
            Integer batchSize = config.getInteger("batchSize");
            if (batchSize == null || batchSize <= 0 || batchSize > 10000) {
                return false;
            }
        }

        // Validate timeout
        if (config.containsKey("timeoutMs")) {
            Integer timeoutMs = config.getInteger("timeoutMs");
            if (timeoutMs == null || timeoutMs <= 0 || timeoutMs > 300000) { // Max 5 minutes
                return false;
            }
        }

        // Debug enabled is always valid (boolean)
        return true;
    }

    /**
     * Configures system properties to use the TestContainer database.
     */
    private void configureSystemPropertiesForContainer(PostgreSQLContainer<?> postgres) {
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.schema", "public");
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.migration.enabled", "true");
        System.setProperty("peegeeq.migration.auto-migrate", "true");
    }
}
