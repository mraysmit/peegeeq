package dev.mars.peegeeq.test.containers;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.*;

/**
 * Test class for PeeGeeQTestContainerFactory.
 * 
 * This test validates that the factory creates containers with correct configurations
 * for different performance profiles. It follows PGQ coding principles by testing
 * incrementally and validating each configuration.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-18
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
class PeeGeeQTestContainerFactoryTest {
    
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQTestContainerFactoryTest.class);
    
    @Test
    void testBasicContainerCreation() {
        logger.info("=== TEST METHOD STARTED: testBasicContainerCreation ===");
        
        logger.info("Testing basic container creation");
        
        PostgreSQLContainer container = PeeGeeQTestContainerFactory.createContainer(BASIC);
        
        assertNotNull(container, "Container should not be null");
        assertEquals("peegeeq_test", container.getDatabaseName());
        assertEquals("peegeeq_test", container.getUsername());
        assertEquals("peegeeq_test", container.getPassword());
        assertFalse(container.isShouldBeReused(), "Container should not be reused by default");
        
        logger.info("Basic container creation test passed");
        
        logger.info("=== TEST METHOD COMPLETED: testBasicContainerCreation ===");
    }
    
    @ParameterizedTest
    @EnumSource(PeeGeeQTestContainerFactory.PerformanceProfile.class)
    void testAllPerformanceProfiles(PeeGeeQTestContainerFactory.PerformanceProfile profile) {
        logger.info("=== TEST METHOD STARTED: testAllPerformanceProfiles({}) ===", profile);
        
        logger.info("Testing performance profile: {}", profile.getDisplayName());
        
        PostgreSQLContainer container;
        
        if (profile == CUSTOM) {
            // For CUSTOM profile, provide some custom settings
            Map<String, String> customSettings = Map.of(
                "max_connections", "400",
                "shared_buffers", "64MB"
            );
            container = PeeGeeQTestContainerFactory.createContainer(profile, null, null, null, customSettings);
        } else {
            container = PeeGeeQTestContainerFactory.createContainer(profile);
        }
        
        assertNotNull(container, "Container should not be null for profile: " + profile);
        assertEquals("peegeeq_test", container.getDatabaseName());
        assertEquals("peegeeq_test", container.getUsername());
        assertEquals("peegeeq_test", container.getPassword());
        
        // Verify profile information
        Map<String, Object> profileInfo = PeeGeeQTestContainerFactory.getProfileInfo(profile);
        assertNotNull(profileInfo, "Profile info should not be null");
        assertEquals(profile.name(), profileInfo.get("name"));
        assertEquals(profile.getDisplayName(), profileInfo.get("displayName"));
        assertEquals(profile.getDescription(), profileInfo.get("description"));
        
        logger.info("Performance profile test passed for: {}", profile.getDisplayName());
        
        logger.info("=== TEST METHOD COMPLETED: testAllPerformanceProfiles({}) ===", profile);
    }
    
    @Test
    void testCustomDatabaseSettings() {
        logger.info("=== TEST METHOD STARTED: testCustomDatabaseSettings ===");
        
        logger.info("Testing custom database settings");
        
        String customDbName = "custom_test_db";
        String customUsername = "custom_user";
        String customPassword = "custom_password";
        
        PostgreSQLContainer container = PeeGeeQTestContainerFactory.createContainer(
            STANDARD, customDbName, customUsername, customPassword, null);
        
        assertNotNull(container, "Container should not be null");
        assertEquals(customDbName, container.getDatabaseName());
        assertEquals(customUsername, container.getUsername());
        assertEquals(customPassword, container.getPassword());
        
        logger.info("Custom database settings test passed");
        
        logger.info("=== TEST METHOD COMPLETED: testCustomDatabaseSettings ===");
    }
    
    @Test
    void testCustomProfileWithSettings() {
        logger.info("=== TEST METHOD STARTED: testCustomProfileWithSettings ===");
        
        logger.info("Testing CUSTOM profile with custom settings");
        
        Map<String, String> customSettings = Map.of(
            "max_connections", "600",
            "shared_buffers", "512MB",
            "work_mem", "32MB"
        );
        
        PostgreSQLContainer container = PeeGeeQTestContainerFactory.createContainer(
            CUSTOM, null, null, null, customSettings);
        
        assertNotNull(container, "Container should not be null");
        assertEquals("peegeeq_test", container.getDatabaseName()); // Should use defaults
        
        logger.info("Custom profile with settings test passed");
        
        logger.info("=== TEST METHOD COMPLETED: testCustomProfileWithSettings ===");
    }
    
    @Test
    void testCustomProfileWithoutSettings() {
        logger.info("=== TEST METHOD STARTED: testCustomProfileWithoutSettings ===");
        
        logger.info("Testing CUSTOM profile without custom settings (should fallback to STANDARD)");
        
        PostgreSQLContainer container = PeeGeeQTestContainerFactory.createContainer(
            CUSTOM, null, null, null, null);
        
        assertNotNull(container, "Container should not be null");
        assertEquals("peegeeq_test", container.getDatabaseName());
        
        logger.info("Custom profile without settings test passed (fallback to STANDARD)");
        
        logger.info("=== TEST METHOD COMPLETED: testCustomProfileWithoutSettings ===");
    }
    
    @Test
    void testBaseIntegrationTestCompatibility() {
        logger.info("=== TEST METHOD STARTED: testBaseIntegrationTestCompatibility ===");
        
        logger.info("Testing BaseIntegrationTest compatibility");
        
        PostgreSQLContainer container = PeeGeeQTestContainerFactory.createBaseIntegrationTestContainer();
        
        assertNotNull(container, "Container should not be null");
        assertEquals("peegeeq_test", container.getDatabaseName());
        assertEquals("peegeeq_test", container.getUsername());
        assertEquals("peegeeq_test", container.getPassword());
        assertTrue(container.isShouldBeReused(), "BaseIntegrationTest container should be reused");
        
        logger.info("BaseIntegrationTest compatibility test passed");
        
        logger.info("=== TEST METHOD COMPLETED: testBaseIntegrationTestCompatibility ===");
    }
    
    @Test
    void testProfileInfoRetrieval() {
        logger.info("=== TEST METHOD STARTED: testProfileInfoRetrieval ===");
        
        logger.info("Testing profile information retrieval");
        
        for (PeeGeeQTestContainerFactory.PerformanceProfile profile : PeeGeeQTestContainerFactory.PerformanceProfile.values()) {
            Map<String, Object> info = PeeGeeQTestContainerFactory.getProfileInfo(profile);
            
            assertNotNull(info, "Profile info should not be null for: " + profile);
            assertEquals(profile.name(), info.get("name"));
            assertEquals(profile.getDisplayName(), info.get("displayName"));
            assertEquals(profile.getDescription(), info.get("description"));
            assertNotNull(info.get("postgresImage"));
            assertNotNull(info.get("sharedMemorySize"));
            
            logger.debug("Profile info for {}: {}", profile, info);
        }
        
        logger.info("Profile information retrieval test passed");
        
        logger.info("=== TEST METHOD COMPLETED: testProfileInfoRetrieval ===");
    }
}
