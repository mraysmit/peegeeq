package dev.mars.peegeeq.test.base;

import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.Map;
import java.util.HashMap;

/**
 * Standardized base class for PeeGeeQ tests with TestContainers and metrics integration.
 * 
 * This class provides:
 * - Standardized TestContainers setup with different performance profiles
 * - Integrated metrics collection using PeeGeeQMetrics
 * - Consistent test configuration and cleanup
 * - Support for parameterized testing with different PostgreSQL configurations
 * 
 * Usage:
 * ```java
 * @Testcontainers
 * class MyTest extends PeeGeeQTestBase {
 *     
 *     @Test
 *     void testSomething() {
 *         // Container and metrics are already set up
 *         String jdbcUrl = getJdbcUrl();
 *         PeeGeeQMetrics metrics = getMetrics();
 *         
 *         // Your test logic here
 *     }
 * }
 * ```
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-18
 * @version 1.0
 */
@Testcontainers
public abstract class PeeGeeQTestBase {
    
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQTestBase.class);
    
    protected PostgreSQLContainer<?> container;
    protected PeeGeeQMetrics metrics;
    protected MeterRegistry meterRegistry;
    protected String testInstanceId;
    protected PerformanceProfile currentProfile;
    
    /**
     * Override this method to specify which performance profile to use.
     * Default is STANDARD profile.
     * 
     * @return the performance profile for this test
     */
    protected PerformanceProfile getPerformanceProfile() {
        return PerformanceProfile.STANDARD;
    }
    
    /**
     * Override this method to provide custom database settings.
     * Only used when getPerformanceProfile() returns CUSTOM.
     * 
     * @return map of custom PostgreSQL settings, or null for default
     */
    protected Map<String, String> getCustomSettings() {
        return null;
    }
    
    /**
     * Override this method to provide custom database name.
     * 
     * @return custom database name, or null to use default
     */
    protected String getDatabaseName() {
        return null;
    }
    
    /**
     * Override this method to provide custom username.
     * 
     * @return custom username, or null to use default
     */
    protected String getUsername() {
        return null;
    }
    
    /**
     * Override this method to provide custom password.
     * 
     * @return custom password, or null to use default
     */
    protected String getPassword() {
        return null;
    }
    
    @BeforeEach
    protected void setUpPeeGeeQTestBase() {
        System.err.println("=== PeeGeeQTestBase setUp() started ===");
        System.err.flush();
        
        // Generate unique test instance ID
        testInstanceId = "test-" + UUID.randomUUID().toString().substring(0, 8);
        currentProfile = getPerformanceProfile();
        
        logger.info("Setting up PeeGeeQ test base with profile: {} (instance: {})", 
                   currentProfile.getDisplayName(), testInstanceId);
        
        // Create and start container
        setupContainer();
        
        // Setup metrics
        setupMetrics();
        
        // Setup database connection properties for tests that need them
        setupDatabaseProperties();
        
        logger.info("PeeGeeQ test base setup complete for instance: {}", testInstanceId);
        
        System.err.println("=== PeeGeeQTestBase setUp() completed ===");
        System.err.flush();
    }
    
    @AfterEach
    protected void tearDownPeeGeeQTestBase() {
        System.err.println("=== PeeGeeQTestBase tearDown() started ===");
        System.err.flush();
        
        logger.info("Tearing down PeeGeeQ test base for instance: {}", testInstanceId);
        
        try {
            // Record final metrics if available
            if (metrics != null && meterRegistry != null) {
                recordTestCompletionMetrics();
            }
            
            // Stop container if it was started by this test
            if (container != null && container.isRunning()) {
                logger.debug("Stopping PostgreSQL container for instance: {}", testInstanceId);
                container.stop();
            }
            
        } catch (Exception e) {
            logger.warn("Error during test teardown for instance: {}", testInstanceId, e);
        }
        
        logger.info("PeeGeeQ test base teardown complete for instance: {}", testInstanceId);
        
        System.err.println("=== PeeGeeQTestBase tearDown() completed ===");
        System.err.flush();
    }
    
    /**
     * Setup the PostgreSQL container with the specified performance profile.
     */
    private void setupContainer() {
        logger.debug("Creating PostgreSQL container with profile: {}", currentProfile.getDisplayName());
        
        container = PeeGeeQTestContainerFactory.createContainer(
            currentProfile,
            getDatabaseName(),
            getUsername(),
            getPassword(),
            getCustomSettings()
        );
        
        logger.debug("Starting PostgreSQL container...");
        container.start();
        
        logger.info("PostgreSQL container started: {}:{} (database: {})", 
                   container.getHost(), container.getFirstMappedPort(), container.getDatabaseName());
    }
    
    /**
     * Setup metrics collection for the test.
     */
    private void setupMetrics() {
        logger.debug("Setting up metrics for test instance: {}", testInstanceId);
        
        meterRegistry = new SimpleMeterRegistry();
        
        // Create metrics instance without database connection for now
        // Tests that need database-connected metrics should override this
        metrics = new PeeGeeQMetrics((io.vertx.sqlclient.Pool) null, testInstanceId);
        metrics.bindTo(meterRegistry);
        
        logger.debug("Metrics setup complete for instance: {}", testInstanceId);
    }
    
    /**
     * Setup system properties for database connection.
     * This allows tests to use the container database easily.
     */
    private void setupDatabaseProperties() {
        System.setProperty("test.database.host", container.getHost());
        System.setProperty("test.database.port", String.valueOf(container.getFirstMappedPort()));
        System.setProperty("test.database.name", container.getDatabaseName());
        System.setProperty("test.database.username", container.getUsername());
        System.setProperty("test.database.password", container.getPassword());
        System.setProperty("test.database.url", container.getJdbcUrl());
        
        logger.debug("Database properties set for test instance: {}", testInstanceId);
    }
    
    /**
     * Record test completion metrics.
     */
    private void recordTestCompletionMetrics() {
        Map<String, String> tags = Map.of(
            "profile", currentProfile.name(),
            "test_class", this.getClass().getSimpleName(),
            "instance", testInstanceId
        );
        
        metrics.incrementCounter("peegeeq.test.completed", tags);
        
        logger.debug("Test completion metrics recorded for instance: {}", testInstanceId);
    }
    
    // Getter methods for subclasses
    
    /**
     * Get the PostgreSQL container for this test.
     * 
     * @return the PostgreSQL container
     */
    protected PostgreSQLContainer<?> getContainer() {
        return container;
    }
    
    /**
     * Get the JDBC URL for the test database.
     * 
     * @return JDBC URL
     */
    protected String getJdbcUrl() {
        return container != null ? container.getJdbcUrl() : null;
    }
    
    /**
     * Get the database host.
     * 
     * @return database host
     */
    protected String getHost() {
        return container != null ? container.getHost() : null;
    }
    
    /**
     * Get the database port.
     * 
     * @return database port
     */
    protected Integer getPort() {
        return container != null ? container.getFirstMappedPort() : null;
    }
    
    /**
     * Get the metrics instance for this test.
     * 
     * @return PeeGeeQMetrics instance
     */
    protected PeeGeeQMetrics getMetrics() {
        return metrics;
    }
    
    /**
     * Get the meter registry for this test.
     * 
     * @return MeterRegistry instance
     */
    protected MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }
    
    /**
     * Get the test instance ID.
     * 
     * @return test instance ID
     */
    protected String getTestInstanceId() {
        return testInstanceId;
    }
    
    /**
     * Get the current performance profile.
     * 
     * @return current performance profile
     */
    protected PerformanceProfile getCurrentProfile() {
        return currentProfile;
    }
    
    /**
     * Record a custom metric for this test.
     * 
     * @param name metric name
     * @param value metric value
     * @param additionalTags additional tags (will be merged with standard tags)
     */
    protected void recordTestMetric(String name, double value, Map<String, String> additionalTags) {
        Map<String, String> tags = new HashMap<>();
        tags.put("profile", currentProfile.name());
        tags.put("test_class", this.getClass().getSimpleName());
        tags.put("instance", testInstanceId);
        
        if (additionalTags != null) {
            tags.putAll(additionalTags);
        }
        
        metrics.recordGauge(name, value, tags);
    }
    
    /**
     * Record a test timer metric.
     * 
     * @param name metric name
     * @param durationMs duration in milliseconds
     * @param additionalTags additional tags (will be merged with standard tags)
     */
    protected void recordTestTimer(String name, long durationMs, Map<String, String> additionalTags) {
        Map<String, String> tags = new HashMap<>();
        tags.put("profile", currentProfile.name());
        tags.put("test_class", this.getClass().getSimpleName());
        tags.put("instance", testInstanceId);
        
        if (additionalTags != null) {
            tags.putAll(additionalTags);
        }
        
        metrics.recordTimer(name, durationMs, tags);
    }
}
