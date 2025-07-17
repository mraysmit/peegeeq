package dev.mars.peegeeq.db.config;

import dev.mars.peegeeq.api.DatabaseService;
import dev.mars.peegeeq.api.QueueFactory;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Builder for creating specialized queue configurations with predefined settings
 * optimized for different use cases.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-17
 * @version 1.0
 */
public class QueueConfigurationBuilder {
    
    private static final Logger logger = LoggerFactory.getLogger(QueueConfigurationBuilder.class);
    
    private QueueConfigurationBuilder() {
        // Utility class, no instantiation
    }
    
    /**
     * Creates a high-throughput queue factory optimized for batch processing.
     * 
     * @param databaseService The database service to use
     * @return A queue factory with high-throughput settings
     */
    public static QueueFactory createHighThroughputQueue(DatabaseService databaseService) {
        Map<String, Object> config = new HashMap<>();
        config.put("batch-size", 100);
        config.put("polling-interval", Duration.ofMillis(100).toString());
        config.put("max-retries", 5);
        config.put("visibility-timeout", Duration.ofSeconds(60).toString());
        config.put("prefetch-count", 50);
        config.put("concurrent-consumers", 10);
        
        logger.info("Creating high-throughput queue configuration");
        return new PgQueueFactoryProvider().createFactory("native", databaseService, config);
    }
    
    /**
     * Creates a low-latency queue factory optimized for real-time processing.
     * 
     * @param databaseService The database service to use
     * @return A queue factory with low-latency settings
     */
    public static QueueFactory createLowLatencyQueue(DatabaseService databaseService) {
        Map<String, Object> config = new HashMap<>();
        config.put("batch-size", 1);
        config.put("polling-interval", Duration.ofMillis(10).toString());
        config.put("max-retries", 1);
        config.put("visibility-timeout", Duration.ofSeconds(10).toString());
        config.put("prefetch-count", 1);
        config.put("concurrent-consumers", 1);
        
        logger.info("Creating low-latency queue configuration");
        return new PgQueueFactoryProvider().createFactory("native", databaseService, config);
    }
    
    /**
     * Creates a reliable queue factory optimized for guaranteed delivery.
     * 
     * @param databaseService The database service to use
     * @return A queue factory with reliability settings
     */
    public static QueueFactory createReliableQueue(DatabaseService databaseService) {
        Map<String, Object> config = new HashMap<>();
        config.put("max-retries", 10);
        config.put("dead-letter-enabled", true);
        config.put("circuit-breaker-enabled", true);
        config.put("visibility-timeout", Duration.ofMinutes(5).toString());
        config.put("batch-size", 10);
        config.put("polling-interval", Duration.ofSeconds(1).toString());
        
        logger.info("Creating reliable queue configuration");
        return new PgQueueFactoryProvider().createFactory("outbox", databaseService, config);
    }
    
    /**
     * Creates a durable queue factory optimized for long-term storage.
     * 
     * @param databaseService The database service to use
     * @return A queue factory with durability settings
     */
    public static QueueFactory createDurableQueue(DatabaseService databaseService) {
        Map<String, Object> config = new HashMap<>();
        config.put("retention-period", Duration.ofDays(30).toString());
        config.put("max-retries", 5);
        config.put("dead-letter-enabled", true);
        config.put("batch-size", 20);
        config.put("polling-interval", Duration.ofSeconds(5).toString());
        
        logger.info("Creating durable queue configuration");
        return new PgQueueFactoryProvider().createFactory("outbox", databaseService, config);
    }
    
    /**
     * Creates a custom queue factory with the specified settings.
     * 
     * @param databaseService The database service to use
     * @param implementationType The queue implementation type
     * @param batchSize The batch size for message processing
     * @param pollingInterval The polling interval for message retrieval
     * @param maxRetries The maximum number of retry attempts
     * @param visibilityTimeout The visibility timeout for in-process messages
     * @param deadLetterEnabled Whether to enable dead letter queue
     * @return A queue factory with custom settings
     */
    public static QueueFactory createCustomQueue(
            DatabaseService databaseService,
            String implementationType,
            int batchSize,
            Duration pollingInterval,
            int maxRetries,
            Duration visibilityTimeout,
            boolean deadLetterEnabled) {
        
        Map<String, Object> config = new HashMap<>();
        config.put("batch-size", batchSize);
        config.put("polling-interval", pollingInterval.toString());
        config.put("max-retries", maxRetries);
        config.put("visibility-timeout", visibilityTimeout.toString());
        config.put("dead-letter-enabled", deadLetterEnabled);
        
        logger.info("Creating custom queue configuration of type: {}", implementationType);
        return new PgQueueFactoryProvider().createFactory(implementationType, databaseService, config);
    }
}
