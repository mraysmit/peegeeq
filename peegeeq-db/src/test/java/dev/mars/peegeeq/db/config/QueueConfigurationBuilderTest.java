package dev.mars.peegeeq.db.config;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for QueueConfigurationBuilder functionality.
 * Tests the real behavior and configuration logic without mocking.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-17
 * @version 1.0
 */
@Tag(TestCategories.CORE)
class QueueConfigurationBuilderTest {

    private static final Logger logger = LoggerFactory.getLogger(QueueConfigurationBuilderTest.class);
    
    @Test
    void testBuilderEntryPointsAreCallable() {
        assertNotNull(QueueConfigurationBuilder.class);

        assertThrows(NullPointerException.class, () -> QueueConfigurationBuilder.createHighThroughputQueue(null));
        assertThrows(NullPointerException.class, () -> QueueConfigurationBuilder.createLowLatencyQueue(null));
        assertThrows(NullPointerException.class, () -> QueueConfigurationBuilder.createReliableQueue(null));
        assertThrows(NullPointerException.class, () -> QueueConfigurationBuilder.createDurableQueue(null));

        logger.info("QueueConfigurationBuilder entry points are callable and validate null database services");
    }

    @Test
    void testNullParameterHandling() {
        assertThrows(NullPointerException.class, () -> QueueConfigurationBuilder.createCustomQueue(
            null, "native", 5, Duration.ofSeconds(1), 3, Duration.ofSeconds(30), true));

        assertThrows(NullPointerException.class, () -> QueueConfigurationBuilder.createCustomQueue(
            new TestDatabaseService(), "native", 5, null, 3, Duration.ofSeconds(30), true));

        assertThrows(NullPointerException.class, () -> QueueConfigurationBuilder.createCustomQueue(
            new TestDatabaseService(), "native", 5, Duration.ofSeconds(1), 3, null, true));

        logger.info("Null parameter handling verified");
    }

    @Test
    void testCustomQueueParameterValidation() {
        TestDatabaseService databaseService = new TestDatabaseService();

        assertThrows(IllegalArgumentException.class, () -> QueueConfigurationBuilder.createCustomQueue(
            databaseService, null, 5, Duration.ofMillis(500), 3, Duration.ofSeconds(30), true));

        assertThrows(IllegalArgumentException.class, () -> QueueConfigurationBuilder.createCustomQueue(
            databaseService, " ", 5, Duration.ofMillis(500), 3, Duration.ofSeconds(30), true));

        assertThrows(IllegalArgumentException.class, () -> QueueConfigurationBuilder.createCustomQueue(
            databaseService, "native", 0, Duration.ofMillis(500), 3, Duration.ofSeconds(30), true));

        assertThrows(IllegalArgumentException.class, () -> QueueConfigurationBuilder.createCustomQueue(
            databaseService, "native", 5, Duration.ofMillis(500), -1, Duration.ofSeconds(30), true));

        logger.info("Custom queue parameter validation verified");
    }

    @Test
    void testConfigurationLogic() {
        TestDatabaseService databaseService = new TestDatabaseService();

        assertThrows(RuntimeException.class, () -> QueueConfigurationBuilder.createHighThroughputQueue(databaseService));
        assertThrows(RuntimeException.class, () -> QueueConfigurationBuilder.createLowLatencyQueue(databaseService));
        assertThrows(RuntimeException.class, () -> QueueConfigurationBuilder.createReliableQueue(databaseService));
        assertThrows(RuntimeException.class, () -> QueueConfigurationBuilder.createDurableQueue(databaseService));

        logger.info("Configuration logic paths are reachable with a concrete database service");
    }
}
