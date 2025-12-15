package dev.mars.peegeeq.pgqueue;
/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating native PostgreSQL queue producers and consumers.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * Factory for creating native PostgreSQL queue producers and consumers.
 * Uses PostgreSQL's LISTEN/NOTIFY and advisory locks for real-time message processing.
 *
 * This implementation now follows the new QueueFactory interface pattern
 * and can work with either the legacy PgClientFactory or the new DatabaseService.
 */
public class PgNativeQueueFactory implements dev.mars.peegeeq.api.messaging.QueueFactory {
    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueueFactory.class);

    // Legacy support
    private final PgClientFactory clientFactory;
    private final PeeGeeQMetrics legacyMetrics;

    // New interface support
    private final DatabaseService databaseService;

    // Configuration support
    private final PeeGeeQConfiguration configuration;

    // Common fields
    private final ObjectMapper objectMapper;
    private final VertxPoolAdapter poolAdapter;
    private volatile boolean closed = false;

    // Legacy constructors for backward compatibility
    public PgNativeQueueFactory(PgClientFactory clientFactory) {
        this(clientFactory, new ObjectMapper(), null);
    }

    public PgNativeQueueFactory(PgClientFactory clientFactory, ObjectMapper objectMapper) {
        this(clientFactory, objectMapper, null);
    }

    public PgNativeQueueFactory(PgClientFactory clientFactory, ObjectMapper objectMapper, PeeGeeQMetrics metrics) {
        this.clientFactory = clientFactory;
        this.legacyMetrics = metrics;
        this.databaseService = null;
        this.configuration = null;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        // Use the default pool ID for LISTEN/NOTIFY dedicated connections
        Vertx vertx = extractVertx(clientFactory);
        this.poolAdapter = new VertxPoolAdapter(vertx, clientFactory, dev.mars.peegeeq.db.PeeGeeQDefaults.DEFAULT_POOL_ID);
        logger.info("Initialized PgNativeQueueFactory (legacy mode)");
    }

    private Vertx extractVertx(PgClientFactory clientFactory) {
        try {
            var connectionManager = clientFactory.getConnectionManager();
            var vertxField = connectionManager.getClass().getDeclaredField("vertx");
            vertxField.setAccessible(true);
            return (Vertx) vertxField.get(connectionManager);
        } catch (Exception e) {
            logger.warn("Could not extract Vert.x from PgClientFactory; LISTEN/polling timers may be disabled", e);
            return null;
        }
    }

    // New constructor using DatabaseService interface
    public PgNativeQueueFactory(DatabaseService databaseService) {
        this(databaseService, new ObjectMapper());
    }

    public PgNativeQueueFactory(DatabaseService databaseService, ObjectMapper objectMapper) {
        this(databaseService, objectMapper, null);
    }

    public PgNativeQueueFactory(DatabaseService databaseService, PeeGeeQConfiguration configuration) {
        this(databaseService, createDefaultObjectMapper(), configuration);
    }

    public PgNativeQueueFactory(DatabaseService databaseService, ObjectMapper objectMapper, PeeGeeQConfiguration configuration) {
        this.databaseService = databaseService;
        this.clientFactory = null;
        this.legacyMetrics = null;
        this.configuration = configuration;
        this.objectMapper = objectMapper != null ? objectMapper : createDefaultObjectMapper();

        // Extract PgClientFactory from DatabaseService if it's a PgDatabaseService
        PgClientFactory extractedClientFactory = extractClientFactory(databaseService);
        Vertx vertx = extractVertx(databaseService);
        // Use the default pool ID for LISTEN/NOTIFY dedicated connections
        this.poolAdapter = new VertxPoolAdapter(vertx, extractedClientFactory, dev.mars.peegeeq.db.PeeGeeQDefaults.DEFAULT_POOL_ID);
        logger.info("Initialized PgNativeQueueFactory (new interface mode) with configuration: {}",
            configuration != null ? "enabled" : "disabled");
        logger.info("PgNativeQueueFactory ready to create producers and consumers");

        // Register a no-op close hook with the manager if available (explicit lifecycle, no reflection)
        if (this.databaseService instanceof dev.mars.peegeeq.api.lifecycle.LifecycleHookRegistrar registrar) {
            registrar.registerCloseHook(new dev.mars.peegeeq.api.lifecycle.PeeGeeQCloseHook() {
                @Override public String name() { return "native-queue"; }
                @Override public io.vertx.core.Future<Void> closeReactive() { return io.vertx.core.Future.succeededFuture(); }
            });
            logger.debug("Registered native-queue close hook (no-op) with PeeGeeQManager");
        }
    }



    private PgClientFactory extractClientFactory(DatabaseService databaseService) {
        // This is a bridge method to extract the client factory from the database service
        // In a real implementation, we might use reflection or a specific interface method
        try {
            if (databaseService.getClass().getSimpleName().equals("PgDatabaseService")) {
                // Use reflection to get the underlying manager and client factory
                var managerField = databaseService.getClass().getDeclaredField("manager");
                managerField.setAccessible(true);
                var manager = managerField.get(databaseService);

                var clientFactoryMethod = manager.getClass().getMethod("getClientFactory");
                return (PgClientFactory) clientFactoryMethod.invoke(manager);
            }
        } catch (Exception e) {
            logger.warn("Could not extract PgClientFactory from DatabaseService, using default configuration", e);
        }
        return null;
    }

    private Vertx extractVertx(DatabaseService databaseService) {
        try {
            if (databaseService.getClass().getSimpleName().equals("PgDatabaseService")) {
                var managerField = databaseService.getClass().getDeclaredField("manager");
                managerField.setAccessible(true);
                var manager = managerField.get(databaseService);
                var vertxMethod = manager.getClass().getMethod("getVertx");
                return (Vertx) vertxMethod.invoke(manager);
            }
        } catch (Exception e) {
            logger.warn("Could not extract Vert.x from DatabaseService", e);
        }
        return null;
    }

    /**
     * Creates a message producer for the specified topic.
     *
     * @param topic The topic to produce messages to
     * @param payloadType The type of message payload
     * @return A message producer instance
     */
    @Override
    public <T> MessageProducer<T> createProducer(String topic, Class<T> payloadType) {
        checkNotClosed();
        logger.info("Creating native queue producer for topic: {}", topic);

        PeeGeeQMetrics metrics = getMetrics();
        return new PgNativeQueueProducer<>(poolAdapter, objectMapper, topic, payloadType, metrics);
    }

    /**
     * Creates a message consumer for the specified topic.
     *
     * @param topic The topic to consume messages from
     * @param payloadType The type of message payload
     * @return A message consumer instance
     */
    @Override
    public <T> MessageConsumer<T> createConsumer(String topic, Class<T> payloadType) {
        checkNotClosed();
        logger.info("Creating native queue consumer for topic: {} with configuration: {}", topic, configuration != null ? "enabled" : "disabled");

        PeeGeeQMetrics metrics = getMetrics();
        logger.debug("FACTORY-DEBUG: Creating consumer with metrics: {}, configuration: {} for topic: {}", (metrics != null), (configuration != null), topic);
        logger.info("Creating consumer with metrics: {}, configuration: {}", metrics != null, configuration != null);

        PgNativeQueueConsumer<T> consumer;
        if (configuration != null) {
            consumer = new PgNativeQueueConsumer<>(poolAdapter, objectMapper, topic, payloadType, metrics, configuration);
        } else {
            consumer = new PgNativeQueueConsumer<>(poolAdapter, objectMapper, topic, payloadType, metrics);
        }

        logger.debug("FACTORY-DEBUG: Successfully created native queue consumer for topic: {}, consumer class: {}", topic, consumer.getClass().getSimpleName());
        logger.info("Successfully created native queue consumer for topic: {}", topic);
        return consumer;
    }

    /**
     * Creates a message consumer for the specified topic with custom consumer configuration.
     * This method allows specifying consumer behavior such as LISTEN_NOTIFY_ONLY, POLLING_ONLY, or HYBRID modes.
     *
     * @param topic The topic to consume messages from
     * @param payloadType The type of message payload
     * @param consumerConfig The consumer configuration specifying operational mode and settings
     * @return A message consumer instance configured according to the provided settings
     */
    @Override
    public <T> MessageConsumer<T> createConsumer(String topic, Class<T> payloadType, Object consumerConfig) {
        checkNotClosed();

        // Validate that consumerConfig is the expected type
        if (consumerConfig != null && !(consumerConfig instanceof ConsumerConfig)) {
            throw new IllegalArgumentException("consumerConfig must be an instance of ConsumerConfig, got: " +
                consumerConfig.getClass().getSimpleName());
        }

        ConsumerConfig config = (ConsumerConfig) consumerConfig;
        logger.info("Creating native queue consumer for topic: {} with consumer mode: {}",
            topic, config != null ? config.getMode() : "default");

        PeeGeeQMetrics metrics = getMetrics();
        logger.debug("FACTORY-DEBUG: Creating consumer with metrics: {}, consumer config: {} for topic: {}",
            (metrics != null), (config != null), topic);

        // Create consumer with the new ConsumerConfig-aware constructor
        PgNativeQueueConsumer<T> consumer = new PgNativeQueueConsumer<>(
            poolAdapter, objectMapper, topic, payloadType, metrics, configuration, config);

        logger.debug("FACTORY-DEBUG: Successfully created native queue consumer for topic: {} with mode: {}, consumer class: {}",
            topic, config != null ? config.getMode() : "default", consumer.getClass().getSimpleName());
        logger.info("Successfully created native queue consumer for topic: {} with mode: {}",
            topic, config != null ? config.getMode() : "default");
        return consumer;
    }

    /**
     * Creates a consumer group for the specified topic.
     *
     * @param groupName The name of the consumer group
     * @param topic The topic to consume messages from
     * @param payloadType The type of message payload
     * @return A consumer group instance
     */
    @Override
    public <T> ConsumerGroup<T> createConsumerGroup(String groupName, String topic, Class<T> payloadType) {
        checkNotClosed();
        logger.info("Creating native queue consumer group '{}' for topic: {}", groupName, topic);

        PeeGeeQMetrics metrics = getMetrics();
        if (configuration != null) {
            return new PgNativeConsumerGroup<>(groupName, topic, payloadType, poolAdapter, objectMapper, metrics, configuration);
        } else {
            return new PgNativeConsumerGroup<>(groupName, topic, payloadType, poolAdapter, objectMapper, metrics);
        }
    }

    @Override
    public String getImplementationType() {
        return "native";
    }

    @Override
    public boolean isHealthy() {
        if (closed) {
            return false;
        }

        try {
            if (databaseService != null) {
                return databaseService.isHealthy();
            } else if (clientFactory != null) {
                // Legacy health check - check if we can get a connection
                return clientFactory.getConnectionManager().isHealthy();
            }
            return false;
        } catch (Exception e) {
            logger.warn("Health check failed for native queue factory", e);
            return false;
        }
    }

    @Override
    public dev.mars.peegeeq.api.messaging.QueueStats getStats(String topic) {
        checkNotClosed();
        logger.debug("Getting stats for topic: {}", topic);

        try {
            // Query the queue_messages table for statistics
            String sql = """
                SELECT
                    COUNT(*) as total,
                    COUNT(*) FILTER (WHERE status = 'AVAILABLE') as pending,
                    COUNT(*) FILTER (WHERE status = 'PROCESSED') as processed,
                    COUNT(*) FILTER (WHERE status = 'LOCKED') as in_flight,
                    COUNT(*) FILTER (WHERE status = 'DEAD_LETTER') as dead_lettered,
                    MIN(created_at) as first_message,
                    MAX(created_at) as last_message
                FROM peegeeq.queue_messages
                WHERE topic = $1
                """;

            io.vertx.sqlclient.Pool pool = poolAdapter.getPool();
            if (pool == null) {
                logger.warn("Pool not available for stats query");
                return dev.mars.peegeeq.api.messaging.QueueStats.basic(topic, 0, 0, 0);
            }

            var result = pool.preparedQuery(sql)
                .execute(io.vertx.sqlclient.Tuple.of(topic))
                .toCompletionStage()
                .toCompletableFuture()
                .get(5, java.util.concurrent.TimeUnit.SECONDS);

            if (result.rowCount() == 0) {
                return dev.mars.peegeeq.api.messaging.QueueStats.basic(topic, 0, 0, 0);
            }

            var row = result.iterator().next();
            long total = row.getLong("total");
            long pending = row.getLong("pending");
            long processed = row.getLong("processed");
            long inFlight = row.getLong("in_flight");
            long deadLettered = row.getLong("dead_lettered");
            java.time.Instant firstMessage = row.getLocalDateTime("first_message") != null
                ? row.getLocalDateTime("first_message").toInstant(java.time.ZoneOffset.UTC) : null;
            java.time.Instant lastMessage = row.getLocalDateTime("last_message") != null
                ? row.getLocalDateTime("last_message").toInstant(java.time.ZoneOffset.UTC) : null;

            // Calculate messages per second (rough estimate based on time range)
            double messagesPerSecond = 0.0;
            if (firstMessage != null && lastMessage != null && total > 1) {
                long durationSeconds = java.time.Duration.between(firstMessage, lastMessage).getSeconds();
                if (durationSeconds > 0) {
                    messagesPerSecond = (double) total / durationSeconds;
                }
            }

            return new dev.mars.peegeeq.api.messaging.QueueStats(
                topic, total, pending, processed, inFlight, deadLettered,
                messagesPerSecond, 0.0, firstMessage, lastMessage
            );
        } catch (Exception e) {
            logger.warn("Failed to get stats for topic {}: {}", topic, e.getMessage());
            return dev.mars.peegeeq.api.messaging.QueueStats.basic(topic, 0, 0, 0);
        }
    }

    private PeeGeeQMetrics getMetrics() {
        if (databaseService != null) {
            // Extract metrics from the new interface
            var metricsProvider = databaseService.getMetricsProvider();
            if (metricsProvider.getClass().getSimpleName().equals("PgMetricsProvider")) {
                try {
                    // Use reflection to get the underlying PeeGeeQMetrics
                    var metricsField = metricsProvider.getClass().getDeclaredField("metrics");
                    metricsField.setAccessible(true);
                    return (PeeGeeQMetrics) metricsField.get(metricsProvider);
                } catch (Exception e) {
                    logger.warn("Could not extract PeeGeeQMetrics from MetricsProvider", e);
                }
            }
        }
        return legacyMetrics; // May be null, which is fine
    }

    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("Queue factory is closed");
        }
    }

    /**
     * Closes the factory and releases resources.
     */
    @Override
    public void close() throws Exception {
        if (closed) {
            logger.debug("PgNativeQueueFactory already closed");
            return;
        }

        logger.info("Closing PgNativeQueueFactory");
        closed = true;

        try {
            if (poolAdapter != null) {
                poolAdapter.closeAsync().toCompletionStage().toCompletableFuture().join();
            }
        } catch (Exception e) {
            logger.error("Error closing pool adapter", e);
            throw e;
        }

        logger.info("PgNativeQueueFactory closed successfully");
    }

    /**
     * Legacy close method for backward compatibility.
     * Calls the new close() method but swallows exceptions.
     */
    public void closeLegacy() {
        try {
            close();
        } catch (Exception e) {
            logger.error("Error during legacy close", e);
        }
    }

    /**
     * Creates a default ObjectMapper with JSR310 support for Java 8 time types and CloudEvents support.
     */
    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        // Add CloudEvents Jackson module support if available on classpath
        try {
            Class<?> jsonFormatClass = Class.forName("io.cloudevents.jackson.JsonFormat");
            Object cloudEventModule = jsonFormatClass.getMethod("getCloudEventJacksonModule").invoke(null);
            if (cloudEventModule instanceof com.fasterxml.jackson.databind.Module) {
                mapper.registerModule((com.fasterxml.jackson.databind.Module) cloudEventModule);
                logger.debug("CloudEvents Jackson module registered successfully");
            }
        } catch (Exception e) {
            logger.debug("CloudEvents Jackson module not available on classpath, skipping registration: {}", e.getMessage());
        }

        return mapper;
    }
}
