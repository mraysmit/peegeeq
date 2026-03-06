package dev.mars.peegeeq.db.config;

import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
    private static final PgQueueFactoryProvider PROVIDER = new PgQueueFactoryProvider();

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
        Objects.requireNonNull(databaseService, "databaseService");
        Map<String, Object> config = new HashMap<>();
        config.put("batch-size", 100);
        config.put("polling-interval", Duration.ofMillis(100));
        config.put("max-retries", 5);
        config.put("visibility-timeout", Duration.ofSeconds(60));
        config.put("prefetch-count", 50);
        config.put("concurrent-consumers", 10);

        logger.info("Creating high-throughput queue configuration");
        return PROVIDER.createFactory("native", databaseService, config);
    }

    /**
     * Creates a low-latency queue factory optimized for real-time processing.
     *
     * @param databaseService The database service to use
     * @return A queue factory with low-latency settings
     */
    public static QueueFactory createLowLatencyQueue(DatabaseService databaseService) {
        Objects.requireNonNull(databaseService, "databaseService");
        Map<String, Object> config = new HashMap<>();
        config.put("batch-size", 1);
        config.put("polling-interval", Duration.ofMillis(10));
        config.put("max-retries", 1);
        config.put("visibility-timeout", Duration.ofSeconds(10));
        config.put("prefetch-count", 1);
        config.put("concurrent-consumers", 1);

        logger.info("Creating low-latency queue configuration");
        return PROVIDER.createFactory("native", databaseService, config);
    }

    /**
     * Creates a reliable queue factory optimized for guaranteed delivery.
     *
     * @param databaseService The database service to use
     * @return A queue factory with reliability settings
     */
    public static QueueFactory createReliableQueue(DatabaseService databaseService) {
        Objects.requireNonNull(databaseService, "databaseService");
        Map<String, Object> config = new HashMap<>();
        config.put("max-retries", 10);
        config.put("dead-letter-enabled", true);
        config.put("circuit-breaker-enabled", true);
        config.put("visibility-timeout", Duration.ofMinutes(5));
        config.put("batch-size", 10);
        config.put("polling-interval", Duration.ofSeconds(1));

        logger.info("Creating reliable queue configuration");
        return PROVIDER.createFactory("outbox", databaseService, config);
    }

    /**
     * Creates a durable queue factory optimized for long-term storage.
     *
     * @param databaseService The database service to use
     * @return A queue factory with durability settings
     */
    public static QueueFactory createDurableQueue(DatabaseService databaseService) {
        Objects.requireNonNull(databaseService, "databaseService");
        Map<String, Object> config = new HashMap<>();
        config.put("retention-period", Duration.ofDays(30));
        config.put("max-retries", 5);
        config.put("dead-letter-enabled", true);
        config.put("batch-size", 20);
        config.put("polling-interval", Duration.ofSeconds(5));

        logger.info("Creating durable queue configuration");
        return PROVIDER.createFactory("outbox", databaseService, config);
    }

    /**
     * Creates a custom queue factory with the specified settings.
     *
     * @param databaseService    The database service to use
     * @param implementationType The queue implementation type
     * @param batchSize          The batch size for message processing; must be >= 1
     * @param pollingInterval    The polling interval for message retrieval
     * @param maxRetries         The maximum number of retry attempts; must be >= 0
     * @param visibilityTimeout  The visibility timeout for in-process messages
     * @param deadLetterEnabled  Whether to enable dead letter queue
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

        Objects.requireNonNull(databaseService, "databaseService");
        if (implementationType == null || implementationType.isBlank()) {
            throw new IllegalArgumentException("implementationType must not be null or blank");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1, got: " + batchSize);
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0, got: " + maxRetries);
        }
        Objects.requireNonNull(pollingInterval, "pollingInterval");
        Objects.requireNonNull(visibilityTimeout, "visibilityTimeout");

        Map<String, Object> config = new HashMap<>();
        config.put("batch-size", batchSize);
        config.put("polling-interval", pollingInterval);
        config.put("max-retries", maxRetries);
        config.put("visibility-timeout", visibilityTimeout);
        config.put("dead-letter-enabled", deadLetterEnabled);

        logger.info("Creating custom queue configuration of type: {}", implementationType);
        return PROVIDER.createFactory(implementationType, databaseService, config);
    }
}
