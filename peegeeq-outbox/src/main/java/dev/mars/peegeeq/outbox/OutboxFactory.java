package dev.mars.peegeeq.outbox;

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
import dev.mars.peegeeq.api.database.MetricsProvider;
import dev.mars.peegeeq.api.database.NoOpMetricsProvider;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.cloudevents.jackson.JsonFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating outbox pattern message producers and consumers.
 * Uses the outbox pattern to ensure reliable message delivery through database
 * transactions.
 *
 * <p>
 * This implementation follows the QueueFactory interface pattern
 * and works with the DatabaseService interface.
 *
 * <p>
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class OutboxFactory implements dev.mars.peegeeq.api.messaging.QueueFactory {
    private static final Logger logger = LoggerFactory.getLogger(OutboxFactory.class);

    // DatabaseService interface support
    private final DatabaseService databaseService;

    // Configuration support
    private final PeeGeeQConfiguration configuration;

    // Client ID for pool lookup - null means use default pool
    private final String clientId;

    // Common fields
    private final ObjectMapper objectMapper;
    private volatile boolean closed = false;

    // Track created consumers and producers for proper cleanup
    private final Set<AutoCloseable> createdResources = ConcurrentHashMap.newKeySet();

    // Constructor using DatabaseService interface
    public OutboxFactory(DatabaseService databaseService) {
        this(databaseService, createDefaultObjectMapper(), null, null);
    }

    public OutboxFactory(DatabaseService databaseService, ObjectMapper objectMapper) {
        this(databaseService, objectMapper, null, null);
    }

    public OutboxFactory(DatabaseService databaseService, PeeGeeQConfiguration configuration) {
        this(databaseService, createDefaultObjectMapper(), configuration, null);
    }

    public OutboxFactory(DatabaseService databaseService, ObjectMapper objectMapper,
            PeeGeeQConfiguration configuration) {
        this(databaseService, objectMapper, configuration, null);
    }

    public OutboxFactory(DatabaseService databaseService, ObjectMapper objectMapper, PeeGeeQConfiguration configuration,
            String clientId) {
        this.databaseService = databaseService;
        this.configuration = configuration;
        this.clientId = clientId; // null means use default pool
        this.objectMapper = objectMapper != null ? objectMapper : createDefaultObjectMapper();
        logger.info("Initialized OutboxFactory with configuration: {} (clientId: {})",
                configuration != null ? "enabled" : "disabled",
                clientId != null ? clientId : "default");

        // Register a no-op close hook with the manager if available (explicit
        // lifecycle, no reflection)
        if (this.databaseService instanceof dev.mars.peegeeq.api.lifecycle.LifecycleHookRegistrar registrar) {
            registrar.registerCloseHook(new dev.mars.peegeeq.api.lifecycle.PeeGeeQCloseHook() {
                @Override
                public String name() {
                    return "outbox";
                }

                @Override
                public io.vertx.core.Future<Void> closeReactive() {
                    return io.vertx.core.Future.succeededFuture();
                }
            });
            logger.debug("Registered outbox close hook (no-op) with PeeGeeQManager");
        }
    }

    /**
     * Creates a message producer for the specified topic.
     *
     * @param topic       The topic to produce messages to
     * @param payloadType The type of message payload
     * @return A message producer instance
     */
    @Override
    public <T> MessageProducer<T> createProducer(String topic, Class<T> payloadType) {
        checkNotClosed();
        logger.info("Creating outbox producer for topic: {}", topic);

        if (topic == null || topic.trim().isEmpty()) {
            throw new IllegalArgumentException("Topic cannot be null or empty");
        }
        if (payloadType == null) {
            throw new IllegalArgumentException("Payload type cannot be null");
        }

        MetricsProvider metrics = getMetrics();

        if (databaseService == null) {
            throw new IllegalStateException("DatabaseService is null");
        }

        MessageProducer<T> producer = new OutboxProducer<>(databaseService, objectMapper, topic, payloadType, metrics,
                configuration, clientId);

        // Track the producer for cleanup
        createdResources.add(producer);
        return producer;
    }

    /**
     * Creates a message consumer for the specified topic.
     *
     * @param topic       The topic to consume messages from
     * @param payloadType The type of message payload
     * @return A message consumer instance
     */
    @Override
    public <T> MessageConsumer<T> createConsumer(String topic, Class<T> payloadType) {
        checkNotClosed();
        logger.info("Creating outbox consumer for topic: {}", topic);
        logger.info("OutboxFactory state - databaseService: {}, configuration: {}",
                databaseService != null ? "present" : "null",
                configuration != null ? "present" : "null");

        if (topic == null || topic.trim().isEmpty()) {
            throw new IllegalArgumentException("Topic cannot be null or empty");
        }
        if (payloadType == null) {
            throw new IllegalArgumentException("Payload type cannot be null");
        }

        if (databaseService == null) {
            throw new IllegalStateException("DatabaseService is null");
        }

        MetricsProvider metrics = getMetrics();
        logger.info("Using DatabaseService for outbox consumer on topic: {}", topic);
        MessageConsumer<T> consumer = new OutboxConsumer<>(databaseService, objectMapper, topic, payloadType, metrics,
                configuration, clientId);

        // Track the consumer for cleanup
        createdResources.add(consumer);
        return consumer;
    }

    /**
     * Creates a message consumer for the specified topic with custom configuration.
     * This method allows specifying consumer behavior such as polling interval,
     * batch size, etc.
     *
     * @param topic          The topic to consume messages from
     * @param payloadType    The type of message payload
     * @param consumerConfig The consumer configuration specifying operational
     *                       settings
     * @return A message consumer instance configured according to the provided
     *         settings
     */
    @Override
    public <T> MessageConsumer<T> createConsumer(String topic, Class<T> payloadType, Object consumerConfig) {
        checkNotClosed();

        // Validate that consumerConfig is the expected type
        if (consumerConfig != null && !(consumerConfig instanceof OutboxConsumerConfig)) {
            throw new IllegalArgumentException("consumerConfig must be an instance of OutboxConsumerConfig, got: " +
                    consumerConfig.getClass().getSimpleName());
        }

        OutboxConsumerConfig config = (OutboxConsumerConfig) consumerConfig;
        logger.info("Creating outbox consumer for topic: {} with consumer config: {}",
                topic, config != null ? config : "default");

        if (topic == null || topic.trim().isEmpty()) {
            throw new IllegalArgumentException("Topic cannot be null or empty");
        }
        if (payloadType == null) {
            throw new IllegalArgumentException("Payload type cannot be null");
        }

        if (databaseService == null) {
            throw new IllegalStateException("DatabaseService is null");
        }

        MetricsProvider metrics = getMetrics();
        logger.info("Using DatabaseService for outbox consumer on topic: {}", topic);
        MessageConsumer<T> consumer = new OutboxConsumer<>(databaseService, objectMapper, topic, payloadType, metrics,
                configuration, clientId, config);

        // Track the consumer for cleanup
        createdResources.add(consumer);
        return consumer;
    }

    /**
     * Creates a consumer group for the specified topic.
     *
     * @param groupName   The name of the consumer group
     * @param topic       The topic to consume messages from
     * @param payloadType The type of message payload
     * @return A consumer group instance
     */
    @Override
    public <T> ConsumerGroup<T> createConsumerGroup(String groupName, String topic, Class<T> payloadType) {
        checkNotClosed();
        logger.info("Creating outbox consumer group '{}' for topic: {}", groupName, topic);

        if (groupName == null || groupName.trim().isEmpty()) {
            throw new IllegalArgumentException("Group name cannot be null or empty");
        }
        if (topic == null || topic.trim().isEmpty()) {
            throw new IllegalArgumentException("Topic cannot be null or empty");
        }
        if (payloadType == null) {
            throw new IllegalArgumentException("Payload type cannot be null");
        }

        if (databaseService == null) {
            throw new IllegalStateException("DatabaseService is null");
        }

        MetricsProvider metrics = getMetrics();
        ConsumerGroup<T> consumerGroup = new OutboxConsumerGroup<>(groupName, topic, payloadType,
                databaseService, objectMapper, metrics, configuration);

        // Track the consumer group for cleanup
        createdResources.add(consumerGroup);
        return consumerGroup;
    }

    @Override
    public <T> dev.mars.peegeeq.api.messaging.QueueBrowser<T> createBrowser(String topic, Class<T> payloadType) {
        checkNotClosed();
        logger.debug("Creating browser for topic: {}", topic);

        io.vertx.sqlclient.Pool pool = getPool();
        if (pool == null) {
            throw new IllegalStateException("Pool not available for browser creation");
        }

        String schema = configuration != null ? configuration.getDatabaseConfig().getSchema() : "public";
        OutboxQueueBrowser<T> browser = new OutboxQueueBrowser<>(topic, payloadType, pool, objectMapper, schema);
        createdResources.add(browser);
        return browser;
    }

    @Override
    public String getImplementationType() {
        return "outbox";
    }

    @Override
    public boolean isHealthy() {
        if (closed) {
            return false;
        }

        try {
            if (databaseService != null) {
                // Prefer a real reactive health probe via ConnectionProvider
                return databaseService.getConnectionProvider()
                        .isHealthy()
                        .toCompletionStage()
                        .toCompletableFuture()
                        .get(2, java.util.concurrent.TimeUnit.SECONDS);
            }
            return false;
        } catch (Exception e) {
            logger.warn("Health check failed for outbox queue factory", e);
            return false;
        }
    }

    @Override
    public io.vertx.core.Future<Boolean> isHealthyAsync() {
        if (closed) {
            return io.vertx.core.Future.succeededFuture(false);
        }

        if (databaseService != null) {
            // Truly async health check via ConnectionProvider
            return databaseService.getConnectionProvider()
                    .isHealthy()
                    .recover(err -> {
                        logger.warn("Async health check failed for outbox queue factory", err);
                        return io.vertx.core.Future.succeededFuture(false);
                    });
        }
        return io.vertx.core.Future.succeededFuture(false);
    }

    @Override
    public dev.mars.peegeeq.api.messaging.QueueStats getStats(String topic) {
        checkNotClosed();
        logger.debug("Getting stats for topic: {}", topic);

        try {
            // Query the outbox table for statistics
            String sql = """
                    SELECT
                        COUNT(*) as total,
                        COUNT(*) FILTER (WHERE status = 'PENDING') as pending,
                        COUNT(*) FILTER (WHERE status = 'COMPLETED') as processed,
                        COUNT(*) FILTER (WHERE status = 'PROCESSING') as in_flight,
                        COUNT(*) FILTER (WHERE status = 'DEAD_LETTER') as dead_lettered,
                        MIN(created_at) as first_message,
                        MAX(created_at) as last_message
                    FROM %s.outbox
                    WHERE topic = $1
                    """.formatted(configuration != null ? configuration.getDatabaseConfig().getSchema() : "peegeeq");

            io.vertx.sqlclient.Pool pool = getPool();
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
                    ? row.getLocalDateTime("first_message").toInstant(java.time.ZoneOffset.UTC)
                    : null;
            java.time.Instant lastMessage = row.getLocalDateTime("last_message") != null
                    ? row.getLocalDateTime("last_message").toInstant(java.time.ZoneOffset.UTC)
                    : null;

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
                    messagesPerSecond, 0.0, firstMessage, lastMessage);
        } catch (Exception e) {
            logger.warn("Failed to get stats for topic {}: {}", topic, e.getMessage());
            return dev.mars.peegeeq.api.messaging.QueueStats.basic(topic, 0, 0, 0);
        }
    }

    @Override
    public io.vertx.core.Future<dev.mars.peegeeq.api.messaging.QueueStats> getStatsAsync(String topic) {
        if (closed) {
            return io.vertx.core.Future.failedFuture(new IllegalStateException("Factory is closed"));
        }
        logger.debug("Getting stats async for topic: {}", topic);

        String sql = """
                SELECT
                    COUNT(*) as total,
                    COUNT(*) FILTER (WHERE status = 'PENDING') as pending,
                    COUNT(*) FILTER (WHERE status = 'COMPLETED') as processed,
                    COUNT(*) FILTER (WHERE status = 'PROCESSING') as in_flight,
                    COUNT(*) FILTER (WHERE status = 'DEAD_LETTER') as dead_lettered,
                    MIN(created_at) as first_message,
                    MAX(created_at) as last_message
                FROM %s.outbox
                WHERE topic = $1
                """.formatted(configuration != null ? configuration.getDatabaseConfig().getSchema() : "peegeeq");

        return getPoolAsync()
                .compose(pool -> {
                    if (pool == null) {
                        logger.warn("Pool not available for async stats query");
                        return io.vertx.core.Future.succeededFuture(
                                dev.mars.peegeeq.api.messaging.QueueStats.basic(topic, 0, 0, 0));
                    }
                    return pool.preparedQuery(sql)
                            .execute(io.vertx.sqlclient.Tuple.of(topic))
                            .map(result -> {
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
                                        ? row.getLocalDateTime("first_message").toInstant(java.time.ZoneOffset.UTC)
                                        : null;
                                java.time.Instant lastMessage = row.getLocalDateTime("last_message") != null
                                        ? row.getLocalDateTime("last_message").toInstant(java.time.ZoneOffset.UTC)
                                        : null;

                                double messagesPerSecond = 0.0;
                                if (firstMessage != null && lastMessage != null && total > 1) {
                                    long durationSeconds = java.time.Duration.between(firstMessage, lastMessage)
                                            .getSeconds();
                                    if (durationSeconds > 0) {
                                        messagesPerSecond = (double) total / durationSeconds;
                                    }
                                }

                                return new dev.mars.peegeeq.api.messaging.QueueStats(
                                        topic, total, pending, processed, inFlight, deadLettered,
                                        messagesPerSecond, 0.0, firstMessage, lastMessage);
                            });
                })
                .recover(err -> {
                    logger.warn("Failed to get async stats for topic {}: {}", topic, err.getMessage());
                    return io.vertx.core.Future.succeededFuture(
                            dev.mars.peegeeq.api.messaging.QueueStats.basic(topic, 0, 0, 0));
                });
    }

    private io.vertx.core.Future<io.vertx.sqlclient.Pool> getPoolAsync() {
        if (databaseService != null) {
            return databaseService.getConnectionProvider().getReactivePool(clientId);
        }
        return io.vertx.core.Future.succeededFuture(null);
    }

    private io.vertx.sqlclient.Pool getPool() {
        // clientId can be null - ConnectionProvider resolves null to the default pool
        try {
            if (databaseService != null) {
                // Use the same pattern as OutboxProducer.getReactivePoolFuture()
                return databaseService.getConnectionProvider()
                        .getReactivePool(clientId)
                        .toCompletionStage()
                        .toCompletableFuture()
                        .get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            logger.warn("Could not get pool for stats query: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public void close() throws Exception {
        if (closed) {
            logger.debug("OutboxFactory already closed");
            return;
        }

        logger.info("Closing OutboxFactory");
        closed = true;

        // Close all tracked resources (consumers, producers, consumer groups)
        logger.info("Closing {} tracked resources", createdResources.size());
        for (AutoCloseable resource : createdResources) {
            try {
                resource.close();
                logger.debug("Closed resource: {}", resource.getClass().getSimpleName());
            } catch (Exception e) {
                logger.warn("Error closing resource {}: {}", resource.getClass().getSimpleName(), e.getMessage());
            }
        }
        createdResources.clear();

        logger.info("OutboxFactory closed successfully");
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

    private MetricsProvider getMetrics() {
        // Get metrics from DatabaseService
        if (databaseService != null) {
            return databaseService.getMetricsProvider();
        }
        return NoOpMetricsProvider.INSTANCE;
    }

    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("Queue factory is closed");
        }
    }

    /**
     * Gets the ObjectMapper used by this factory.
     *
     * @return The ObjectMapper instance
     */
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    /**
     * Creates a default ObjectMapper with JSR310 support for Java 8 time types and
     * CloudEvents support.
     */
    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(JsonFormat.getCloudEventJacksonModule());
        return mapper;
    }
}
