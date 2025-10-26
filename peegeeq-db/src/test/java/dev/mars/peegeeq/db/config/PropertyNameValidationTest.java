package dev.mars.peegeeq.db.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.ResourceLock;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to validate that the correct property names are used for configuration.
 * This test specifically addresses the issue where users might use incorrect property names.
 */
@Tag(TestCategories.CORE)
@ResourceLock("system-properties")
public class PropertyNameValidationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(PropertyNameValidationTest.class);
    
    @BeforeEach
    void setUp() {
        // Clear ALL peegeeq.* system properties to start fresh
        System.getProperties().entrySet().removeIf(entry ->
            entry.getKey().toString().startsWith("peegeeq."));
    }

    @AfterEach
    void tearDown() {
        // Clean up ALL peegeeq.* system properties
        System.getProperties().entrySet().removeIf(entry ->
            entry.getKey().toString().startsWith("peegeeq."));
    }
    
    @Test
    void testIncorrectPropertyNameDoesNotWork() {
        logger.info("=== Testing Incorrect Property Name ===");

        // Set the incorrect property name that the user mentioned
        System.setProperty("peegeeq.Max-retries", "7");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");

        // Should use the value from test.properties (5) because the property name is incorrect
        // The test profile has peegeeq.queue.max-retries=5 in peegeeq-test.properties
        assertEquals(5, config.getQueueConfig().getMaxRetries(),
            "Incorrect property name 'peegeeq.Max-retries' should not be recognized, should use value from properties file");

        logger.info("✅ Confirmed that incorrect property name 'peegeeq.Max-retries' is ignored");
    }
    
    @Test
    void testCorrectPropertyNameWorks() {
        logger.info("=== Testing Correct Property Name ===");

        // Set the correct property name to override the test properties file
        System.setProperty("peegeeq.queue.max-retries", "8");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");

        // Should use the system property value (8) which overrides the properties file value (5)
        assertEquals(8, config.getQueueConfig().getMaxRetries(),
            "Correct property name 'peegeeq.queue.max-retries' should override properties file");

        logger.info("✅ Confirmed that correct property name 'peegeeq.queue.max-retries' works");
    }
    
    @Test
    void testBothPropertiesSetCorrectOneWins() {
        logger.info("=== Testing Both Properties Set ===");

        // Set both properties - the correct one should win
        System.setProperty("peegeeq.Max-retries", "7");
        System.setProperty("peegeeq.queue.max-retries", "9");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");

        // Should use the correct property value (9), not the incorrect one (7)
        assertEquals(9, config.getQueueConfig().getMaxRetries(),
            "When both properties are set, the correct property name should take precedence");

        logger.info("✅ Confirmed that correct property name takes precedence when both are set");
    }
    
    @Test
    void testDefaultValueWhenNoPropertySet() {
        logger.info("=== Testing Properties File Value ===");

        // Don't set any system properties - should use value from properties file
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");

        // Should use value from peegeeq-test.properties (5)
        assertEquals(5, config.getQueueConfig().getMaxRetries(),
            "Should use value from properties file when no system property is set");

        logger.info("✅ Confirmed properties file value is used when no system property is set");
    }
}
