package dev.mars.peegeeq.db.config;

import org.junit.jupiter.api.Test;

/**
 * Debug test to check what configuration values are being loaded.
 */
public class ConfigurationDebugTest {
    
    @Test
    void debugConfigurationLoading() {
        System.out.println("=== Configuration Debug Test ===");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");

        System.out.println("Profile: " + config.getProfile());
        System.out.println("Max retries from config: " + config.getQueueConfig().getMaxRetries());
        System.out.println("Polling interval: " + config.getQueueConfig().getPollingInterval());
        System.out.println("Batch size: " + config.getQueueConfig().getBatchSize());
        System.out.println("Visibility timeout: " + config.getQueueConfig().getVisibilityTimeout());

        // Check system properties
        System.out.println("System property peegeeq.queue.max-retries: " + System.getProperty("peegeeq.queue.max-retries"));

        // Check if the properties file is being loaded
        System.out.println("Database host: " + config.getDatabaseConfig().getHost());
        System.out.println("Database database: " + config.getDatabaseConfig().getDatabase());
    }
}
