package dev.mars.peegeeq.pgqueue;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive validation tests for ConsumerConfig.
 * Tests all configuration parameters for edge cases, invalid values, and boundary conditions.
 * 
 * Following the established coding principles:
 * - Test both positive and negative scenarios
 * - Fail fast with clear error messages
 * - Validate all configuration parameters thoroughly
 */
class ConsumerConfigValidationTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerConfigValidationTest.class);

    @Test
    void testNegativePollingInterval() {
        logger.info("Testing negative polling interval validation");
        
        // Negative polling interval should be rejected for all modes
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofSeconds(-1))
                .build();
        });
        
        assertTrue(exception.getMessage().contains("negative") || exception.getMessage().contains("positive"),
            "Error message should indicate negative values are not allowed");
        logger.info(" Negative polling interval properly rejected");
    }

    @Test
    void testNullPollingInterval() {
        logger.info("Testing null polling interval validation");
        
        // Null polling interval should be rejected
        assertThrows(NullPointerException.class, () -> {
            ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(null)
                .build();
        });
        logger.info(" Null polling interval properly rejected");
    }

    @Test
    void testZeroBatchSize() {
        logger.info("Testing zero batch size validation");

        // Zero batch size should be rejected
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            ConsumerConfig.builder()
                .batchSize(0)
                .build();
        });

        logger.info("Actual error message: '{}'", exception.getMessage());
        assertTrue(exception.getMessage().contains("Batch size must be positive"),
            "Error message should indicate batch size must be positive. Actual: " + exception.getMessage());
        logger.info(" Zero batch size properly rejected");
    }

    @Test
    void testNegativeBatchSize() {
        logger.info("Testing negative batch size validation");

        // Negative batch size should be rejected
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            ConsumerConfig.builder()
                .batchSize(-5)
                .build();
        });

        logger.info("Actual error message: '{}'", exception.getMessage());
        assertTrue(exception.getMessage().contains("Batch size must be positive"),
            "Error message should indicate batch size must be positive. Actual: " + exception.getMessage());
        logger.info(" Negative batch size properly rejected");
    }

    @Test
    void testZeroConsumerThreads() {
        logger.info("Testing zero consumer threads validation");
        
        // Zero consumer threads should be rejected
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            ConsumerConfig.builder()
                .consumerThreads(0)
                .build();
        });
        
        assertTrue(exception.getMessage().contains("thread") && exception.getMessage().contains("positive"),
            "Error message should indicate consumer threads must be positive");
        logger.info(" Zero consumer threads properly rejected");
    }

    @Test
    void testNegativeConsumerThreads() {
        logger.info("Testing negative consumer threads validation");
        
        // Negative consumer threads should be rejected
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            ConsumerConfig.builder()
                .consumerThreads(-2)
                .build();
        });
        
        assertTrue(exception.getMessage().contains("thread") && exception.getMessage().contains("positive"),
            "Error message should indicate consumer threads must be positive");
        logger.info(" Negative consumer threads properly rejected");
    }

    @Test
    void testMaximumPollingInterval() {
        logger.info("Testing maximum polling interval validation");
        
        // Very large polling interval should be rejected (e.g., more than 1 hour)
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofHours(25)) // More than 24 hours
                .build();
        });
        
        assertTrue(exception.getMessage().contains("maximum") || exception.getMessage().contains("too large"),
            "Error message should indicate polling interval is too large");
        logger.info(" Maximum polling interval properly rejected");
    }

    @Test
    void testMaximumBatchSize() {
        logger.info("Testing maximum batch size validation");
        
        // Very large batch size should be rejected (e.g., more than 10000)
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            ConsumerConfig.builder()
                .batchSize(50000) // Unreasonably large
                .build();
        });
        
        assertTrue(exception.getMessage().contains("maximum") || exception.getMessage().contains("too large"),
            "Error message should indicate batch size is too large");
        logger.info(" Maximum batch size properly rejected");
    }

    @Test
    void testMaximumConsumerThreads() {
        logger.info("Testing maximum consumer threads validation");
        
        // Very large thread count should be rejected (e.g., more than 1000)
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            ConsumerConfig.builder()
                .consumerThreads(5000) // Unreasonably large
                .build();
        });
        
        assertTrue(exception.getMessage().contains("maximum") || exception.getMessage().contains("too large"),
            "Error message should indicate consumer threads count is too large");
        logger.info(" Maximum consumer threads properly rejected");
    }

    @Test
    void testValidBoundaryValues() {
        logger.info("Testing valid boundary values");
        
        // Test minimum valid values
        ConsumerConfig minConfig = ConsumerConfig.builder()
            .mode(ConsumerMode.HYBRID)
            .pollingInterval(Duration.ofMillis(100)) // Minimum reasonable polling
            .batchSize(1) // Minimum batch size
            .consumerThreads(1) // Minimum threads
            .build();
        
        assertEquals(ConsumerMode.HYBRID, minConfig.getMode());
        assertEquals(Duration.ofMillis(100), minConfig.getPollingInterval());
        assertEquals(1, minConfig.getBatchSize());
        assertEquals(1, minConfig.getConsumerThreads());
        
        // Test maximum valid values
        ConsumerConfig maxConfig = ConsumerConfig.builder()
            .mode(ConsumerMode.POLLING_ONLY)
            .pollingInterval(Duration.ofHours(1)) // Maximum reasonable polling
            .batchSize(1000) // Maximum reasonable batch size
            .consumerThreads(100) // Maximum reasonable threads
            .build();
        
        assertEquals(ConsumerMode.POLLING_ONLY, maxConfig.getMode());
        assertEquals(Duration.ofHours(1), maxConfig.getPollingInterval());
        assertEquals(1000, maxConfig.getBatchSize());
        assertEquals(100, maxConfig.getConsumerThreads());
        
        logger.info(" Valid boundary values accepted");
    }

    @Test
    void testPollingOnlyModeSpecificValidation() {
        logger.info("Testing POLLING_ONLY mode specific validation");
        
        // POLLING_ONLY mode should reject zero polling interval (existing test)
        assertThrows(IllegalArgumentException.class, () -> {
            ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ZERO)
                .build();
        });
        
        // POLLING_ONLY mode should accept positive polling interval
        ConsumerConfig validConfig = ConsumerConfig.builder()
            .mode(ConsumerMode.POLLING_ONLY)
            .pollingInterval(Duration.ofSeconds(5))
            .build();
        
        assertEquals(ConsumerMode.POLLING_ONLY, validConfig.getMode());
        assertEquals(Duration.ofSeconds(5), validConfig.getPollingInterval());
        
        logger.info(" POLLING_ONLY mode validation working correctly");
    }

    @Test
    void testListenNotifyOnlyModeValidation() {
        logger.info("Testing LISTEN_NOTIFY_ONLY mode validation");
        
        // LISTEN_NOTIFY_ONLY mode should accept any polling interval (it's ignored)
        ConsumerConfig config = ConsumerConfig.builder()
            .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
            .pollingInterval(Duration.ZERO) // Should be ignored for this mode
            .build();
        
        assertEquals(ConsumerMode.LISTEN_NOTIFY_ONLY, config.getMode());
        assertEquals(Duration.ZERO, config.getPollingInterval());
        
        logger.info(" LISTEN_NOTIFY_ONLY mode validation working correctly");
    }

    @Test
    void testHybridModeValidation() {
        logger.info("Testing HYBRID mode validation");
        
        // HYBRID mode should follow same rules as POLLING_ONLY for polling interval
        assertThrows(IllegalArgumentException.class, () -> {
            ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofSeconds(-1))
                .build();
        });
        
        // HYBRID mode should accept positive polling interval
        ConsumerConfig validConfig = ConsumerConfig.builder()
            .mode(ConsumerMode.HYBRID)
            .pollingInterval(Duration.ofSeconds(2))
            .build();
        
        assertEquals(ConsumerMode.HYBRID, validConfig.getMode());
        assertEquals(Duration.ofSeconds(2), validConfig.getPollingInterval());
        
        logger.info(" HYBRID mode validation working correctly");
    }

    @Test
    void testNullModeValidation() {
        logger.info("Testing null mode validation");
        
        // Null mode should be rejected
        assertThrows(NullPointerException.class, () -> {
            ConsumerConfig.builder()
                .mode(null)
                .build();
        });
        
        logger.info(" Null mode properly rejected");
    }

    @Test
    void testConfigurationToString() {
        logger.info("Testing configuration toString method");
        
        ConsumerConfig config = ConsumerConfig.builder()
            .mode(ConsumerMode.HYBRID)
            .pollingInterval(Duration.ofSeconds(5))
            .batchSize(25)
            .consumerThreads(3)
            .enableNotifications(false)
            .build();
        
        String configString = config.toString();
        
        // Verify all important fields are present in toString
        assertTrue(configString.contains("HYBRID"));
        assertTrue(configString.contains("PT5S") || configString.contains("5"));
        assertTrue(configString.contains("25"));
        assertTrue(configString.contains("3"));
        assertTrue(configString.contains("false"));
        
        logger.info(" Configuration toString contains all expected fields: {}", configString);
    }
}
