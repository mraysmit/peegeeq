package dev.mars.peegeeq.db.performance;

import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Vert.x performance optimization utilities.
 */
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
        Vertx optimizedVertx = VertxPerformanceOptimizer.createOptimizedVertx();
        
        // Then
        assertNotNull(optimizedVertx);
        
        // Cleanup
        optimizedVertx.close();
    }
    
    @Test
    @DisplayName("Should create optimized deployment options with multiple instances")
    void shouldCreateOptimizedDeploymentOptions() {
        // Given & When
        DeploymentOptions options = VertxPerformanceOptimizer.createOptimizedDeploymentOptions();
        
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
        Pool pool = VertxPerformanceOptimizer.createOptimizedPool(vertx, connectionConfig, poolConfig);
        
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
        String validation = VertxPerformanceOptimizer.validatePoolConfiguration(goodConfig);
        
        // Then
        assertTrue(validation.contains("✅"), "Should indicate good configuration");
        
        // Given - Poor configuration
        PgPoolConfig poorConfig = new PgPoolConfig.Builder()
            .maxSize(4)
            .shared(false)
            .build();
        
        // When
        String poorValidation = VertxPerformanceOptimizer.validatePoolConfiguration(poorConfig);
        
        // Then
        assertTrue(poorValidation.contains("⚠️"), "Should contain warnings");
        assertTrue(poorValidation.contains("Pool size"), "Should warn about pool size");
        assertTrue(poorValidation.contains("sharing"), "Should warn about sharing");
    }
    
    @Test
    @DisplayName("Should respect system properties for configuration")
    void shouldRespectSystemProperties() {
        try {
            // Given
            System.setProperty("peegeeq.verticle.instances", "4");
            System.setProperty("peegeeq.database.pipelining.limit", "16");
            
            // When
            DeploymentOptions options = VertxPerformanceOptimizer.createOptimizedDeploymentOptions();
            
            // Then
            assertEquals(4, options.getInstances(), "Should use system property for instances");
            
        } finally {
            // Cleanup
            System.clearProperty("peegeeq.verticle.instances");
            System.clearProperty("peegeeq.database.pipelining.limit");
        }
    }
    
    @Test
    @DisplayName("Should handle edge cases gracefully")
    void shouldHandleEdgeCases() {
        // Test with extreme values
        try {
            System.setProperty("peegeeq.verticle.instances", "100"); // Too high
            System.setProperty("peegeeq.database.pipelining.limit", "1000"); // Too high
            
            DeploymentOptions options = VertxPerformanceOptimizer.createOptimizedDeploymentOptions();
            
            // Should be clamped to reasonable bounds
            assertTrue(options.getInstances() <= 16, "Should clamp instances to max 16");
            
        } finally {
            System.clearProperty("peegeeq.verticle.instances");
            System.clearProperty("peegeeq.database.pipelining.limit");
        }
    }
}
