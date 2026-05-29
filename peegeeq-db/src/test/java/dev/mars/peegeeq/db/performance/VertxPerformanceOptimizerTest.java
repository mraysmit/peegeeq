package dev.mars.peegeeq.db.performance;

import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Vert.x performance optimization utilities.
 */
@Tag(TestCategories.CORE)
class VertxPerformanceOptimizerTest {
    
    private Vertx vertx;
    
    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }
    
    @AfterEach
    void tearDown() {
        if (vertx != null) {
            vertx.close();
        }
    }
    
    @Test
    @DisplayName("Should create optimized Vertx instance")
    void shouldCreateOptimizedVertx() {
        // Given & When
        Vertx optimizedVertx = VertxPerformanceOptimizer.createOptimizedVertx(null);
        
        // Then
        assertNotNull(optimizedVertx);
        
        // Cleanup
        optimizedVertx.close();
    }
    
    @Test
    @DisplayName("Should create optimized deployment options with multiple instances")
    void shouldCreateOptimizedDeploymentOptions() {
        // Given & When
        DeploymentOptions options = VertxPerformanceOptimizer.createOptimizedDeploymentOptions(null);
        
        // Then
        assertNotNull(options);
        assertTrue(options.getInstances() >= 1, "Should have at least 1 instance");
        assertTrue(options.getInstances() <= 16, "Should not exceed 16 instances");
    }
    
    @Test
    @DisplayName("Should create optimized pool with correct configuration")
    void shouldCreateOptimizedPool() {
        // Given
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host("localhost")
            .port(5432)
            .database("test")
            .username("test")
            .password("test")
            .build();
            
        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(32)
            .shared(true)
            .build();
        
        // When
        Pool pool = VertxPerformanceOptimizer.createOptimizedPool(vertx, connectionConfig, poolConfig, null);
        
        // Then
        assertNotNull(pool);
        
        // Cleanup
        pool.close();
    }
    
    @Test
    @DisplayName("Should validate pool configuration and provide recommendations")
    void shouldValidatePoolConfiguration() {
        // Given - Good configuration
        PgPoolConfig goodConfig = new PgPoolConfig.Builder()
            .maxSize(32)
            .shared(true)
            .build();
        
        // When
        String validation = VertxPerformanceOptimizer.validatePoolConfiguration(goodConfig, null);
        
        // Then
        assertFalse(validation.contains("\u26a0\ufe0f"), "Good configuration should not contain warnings");
        
        // Given - Poor configuration
        PgPoolConfig poorConfig = new PgPoolConfig.Builder()
            .maxSize(4)
            .shared(false)
            .build();
        
        // When
        String poorValidation = VertxPerformanceOptimizer.validatePoolConfiguration(poorConfig, null);
        
        // Then
        assertTrue(poorValidation.contains(""), "Should contain warnings");
        assertTrue(poorValidation.contains("Pool size"), "Should warn about pool size");
        assertTrue(poorValidation.contains("sharing"), "Should warn about sharing");
    }
    
    @Test
    @DisplayName("Should respect PeeGeeQConfiguration for deployment options")
    void shouldRespectConfigurationProperties() {
        // Given
        java.util.Properties props = new java.util.Properties();
        props.setProperty("peegeeq.verticle.instances", "4");
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", props);

        // When
        DeploymentOptions options = VertxPerformanceOptimizer.createOptimizedDeploymentOptions(config);

        // Then
        assertEquals(4, options.getInstances(), "Should use configuration value for instances");
    }
    
    @Test
    @DisplayName("Should clamp extreme configuration values to reasonable bounds")
    void shouldHandleEdgeCases() {
        // Given - extreme values that should be clamped
        java.util.Properties props = new java.util.Properties();
        props.setProperty("peegeeq.verticle.instances", "100"); // Too high  max is 16
        props.setProperty("peegeeq.database.pipelining.limit", "1000"); // Too high  max is 256
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", props);

        // When
        DeploymentOptions options = VertxPerformanceOptimizer.createOptimizedDeploymentOptions(config);

        // Then
        assertTrue(options.getInstances() <= 16, "Should clamp instances to max 16");
    }
}
