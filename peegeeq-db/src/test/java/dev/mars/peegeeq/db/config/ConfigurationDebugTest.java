package dev.mars.peegeeq.db.config;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Debug test to check what configuration values are being loaded.
 */
@Tag(TestCategories.CORE)
public class ConfigurationDebugTest {

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationDebugTest.class);
    
    @Test
    void debugConfigurationLoading() {
        logger.info("=== Configuration Debug Test ===");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test", new Properties());

        logger.info("Profile: {}", config.getProfile());
        logger.info("Max retries from config: {}", config.getQueueConfig().getMaxRetries());
        logger.info("Polling interval: {}", config.getQueueConfig().getPollingInterval());
        logger.info("Batch size: {}", config.getQueueConfig().getBatchSize());
        logger.info("Visibility timeout: {}", config.getQueueConfig().getVisibilityTimeout());

        // Phase 11: System property sweep removed  values come from config instance only
        logger.info("Max retries (from config, not System): {}", config.getQueueConfig().getMaxRetries());

        // Check if the properties file is being loaded
        logger.info("Database host: {}", config.getDatabaseConfig().getHost());
        logger.info("Database database: {}", config.getDatabaseConfig().getDatabase());
    }
}
