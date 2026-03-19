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
import dev.mars.peegeeq.api.messaging.QueueStats;
import dev.mars.peegeeq.api.messaging.TopicNameValidator;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.database.MetricsProvider;
import dev.mars.peegeeq.api.database.NoOpMetricsProvider;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.cloudevents.jackson.JsonFormat;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Factory for creating native PostgreSQL queue producers and consumers.
 * Uses PostgreSQL's LISTEN/NOTIFY and advisory locks for real-time message
 * processing.
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
public class PgNativeQueueFactory implements dev.mars.peegeeq.api.messaging.QueueFactory {
    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueueFactory.class);

    // DatabaseService interface support
    private final DatabaseService databaseService;

    // Configuration support
    private final PeeGeeQConfiguration configuration;

    // Common fields
    private final ObjectMapper objectMapper;
    private final VertxPoolAdapter poolAdapter;
    private final List<AutoCloseable> managedResources = new CopyOnWriteArrayList<>();
    private volatile boolean closed = false;

    // Constructor using DatabaseService interface
    public PgNativeQueueFactory(DatabaseService databaseService) {
        this(databaseService, new ObjectMapper());
    }

    public PgNativeQueueFactory(DatabaseService databaseService, ObjectMapper objectMapper) {
        this(databaseService, objectMapper, null);
    }

    public PgNativeQueueFactory(DatabaseService databaseService, PeeGeeQConfiguration configuration) {
        this(databaseService, createDefaultObjectMapper(), configuration);
    }

    public PgNativeQueueFactory(DatabaseService databaseService, ObjectMapper objectMapper,
            PeeGeeQConfiguration configuration) {
        this.databaseService = databaseService;
        this.configuration = configuration;
        this.objectMapper = objectMapper != null ? objectMapper : createDefaultObjectMapper();

        // Use interfaces directly - no reflection needed
        this.poolAdapter = new VertxPoolAdapter(
                databaseService.getVertx(),
                databaseService.getPool(),
                databaseService // DatabaseService implements ConnectOptionsProvider
        );
        logger.info("Initialized PgNativeQueueFactory with configuration: {}",
                configuration != null ? "enabled" : "disabled");

        // Register a no-op close hook with the manager if available (explicit
        // lifecycle, no reflection)
        if (this.databaseService instanceof dev.mars.peegeeq.api.lifecycle.LifecycleHookRegistrar registrar) {
            registrar.registerCloseHook(new dev.mars.peegeeq.api.lifecycle.PeeGeeQCloseHook() {
                @Override
                public String name() {
                    return "native-queue";
                }

                @Override
                public io.vertx.core.Future<Void> closeReactive() {
                    return io.vertx.core.Future.succeededFuture();
                }
            });
            logger.debug("Registered native-queue close hook (no-op) with PeeGeeQManager");
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
        TopicNameValidator.validate(topic);
        logger.info("Creating native queue producer for topic: {}", topic);

        MetricsProvider metrics = getMetrics();
        return new PgNativeQueueProducer<>(poolAdapter, objectMapper, topic, payloadType, metrics, configuration);
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
        TopicNameValidator.validate(topic);
        logger.info("Creating native queue consumer for topic: {} with configuration: {}", topic,
                configuration != null ? "enabled" : "disabled");

        MetricsProvider metrics = getMetrics();
        logger.debug("Creating consumer with metrics: {}, configuration: {} for topic: {}", true,
                (configuration != null), topic);
        logger.info("Creating consumer with metrics: {}, configuration: {}", true, configuration != null);

        PgNativeQueueConsumer<T> consumer;
        if (configuration != null) {
            consumer = new PgNativeQueueConsumer<>(poolAdapter, objectMapper, topic, payloadType, metrics,
                    configuration);
        } else {
            consumer = new PgNativeQueueConsumer<>(poolAdapter, objectMapper, topic, payloadType, metrics);
        }

        logger.debug("Successfully created native queue consumer for topic: {}, consumer class: {}",
                topic, consumer.getClass().getSimpleName());
        logger.info("Successfully created native queue consumer for topic: {}", topic);
        return registerResource(consumer);
    }

    /**
     * Creates a message consumer for the specified topic with custom consumer
     * configuration.
     * This method allows specifying consumer behavior such as LISTEN_NOTIFY_ONLY,
     * POLLING_ONLY, or HYBRID modes.
     *
     * @param topic          The topic to consume messages from
     * @param payloadType    The type of message payload
     * @param consumerConfig The consumer configuration specifying operational mode
     *                       and settings
     * @return A message consumer instance configured according to the provided
     *         settings
     */
    @Override
    public <T> MessageConsumer<T> createConsumer(String topic, Class<T> payloadType, Object consumerConfig) {
        checkNotClosed();
        TopicNameValidator.validate(topic);

        // Validate that consumerConfig is the expected type
        if (consumerConfig != null && !(consumerConfig instanceof ConsumerConfig)) {
            throw new IllegalArgumentException("consumerConfig must be an instance of ConsumerConfig, got: " +
                    consumerConfig.getClass().getSimpleName());
        }

        ConsumerConfig config = (ConsumerConfig) consumerConfig;
        logger.info("Creating native queue consumer for topic: {} with consumer mode: {}",
                topic, config != null ? config.getMode() : "default");

        MetricsProvider metrics = getMetrics();
        logger.debug("Creating consumer with metrics: {}, consumer config: {} for topic: {}",
                true, (config != null), topic);

        // Create consumer with the new ConsumerConfig-aware constructor
        PgNativeQueueConsumer<T> consumer = new PgNativeQueueConsumer<>(
                poolAdapter, objectMapper, topic, payloadType, metrics, configuration, config);

        logger.debug(
                "Successfully created native queue consumer for topic: {} with mode: {}, consumer class: {}",
                topic, config != null ? config.getMode() : "default", consumer.getClass().getSimpleName());
        logger.info("Successfully created native queue consumer for topic: {} with mode: {}",
                topic, config != null ? config.getMode() : "default");
        return registerResource(consumer);
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
        TopicNameValidator.validate(topic);
        logger.info("Creating native queue consumer group '{}' for topic: {}", groupName, topic);

        MetricsProvider metrics = getMetrics();
        return registerResource(new PgNativeConsumerGroup<>(groupName, topic, payloadType, poolAdapter, objectMapper,
            metrics, configuration, databaseService));
    }

    @Override
    public <T> dev.mars.peegeeq.api.messaging.QueueBrowser<T> createBrowser(String topic, Class<T> payloadType) {
        checkNotClosed();
        TopicNameValidator.validate(topic);
        logger.debug("Creating browser for topic: {}", topic);

        io.vertx.sqlclient.Pool pool = poolAdapter.getPool();
        if (pool == null) {
            throw new IllegalStateException("Pool not available for browser creation");
        }

        String schema = configuration != null ? configuration.getDatabaseConfig().getSchema() : "public";
        return registerResource(new PgNativeQueueBrowser<>(topic, payloadType, pool, objectMapper, schema));
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
            }
            return false;
        } catch (Exception e) {
            logger.warn("Health check failed for native queue factory", e);
            return false;
        }
    }

    @Override
    public QueueStats getStats(String topic) {
        checkNotClosed();
        logger.debug("Getting stats for topic (blocking): {}", topic);

        try {
            // Delegate to async method and block for backward compatibility
            return getStatsAsync(topic)
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("Failed to get stats for topic {}: {}", topic, e.getMessage());
            return QueueStats.basic(topic, 0, 0, 0);
        }
    }

    @Override
    public Future<QueueStats> getStatsAsync(String topic) {
        checkNotClosed();
        logger.debug("Getting stats for topic (async): {}", topic);

        Pool pool = poolAdapter.getPool();
        if (pool == null) {
            logger.warn("Pool not available for stats query");
            return Future.succeededFuture(QueueStats.basic(topic, 0, 0, 0));
        }

        // : Remove manual schema qualification - rely on connection-level search_path
        // The connection pool is configured with search_path in PgConnectionManager.createReactivePool()
        // so all SQL statements will automatically use the correct schema
        String sql = """
                SELECT
                    COUNT(*) as total,
                    COUNT(*) FILTER (WHERE status = 'AVAILABLE') as pending,
                    COUNT(*) FILTER (WHERE status = 'PROCESSED') as processed,
                    COUNT(*) FILTER (WHERE status = 'LOCKED') as in_flight,
                    COUNT(*) FILTER (WHERE status = 'DEAD_LETTER') as dead_lettered,
                    MIN(created_at) as first_message,
                    MAX(created_at) as last_message
                FROM queue_messages
                WHERE topic = $1
                """;

        return pool.preparedQuery(sql)
                .execute(Tuple.of(topic))
                .map(result -> {
                    if (result.rowCount() == 0) {
                        return QueueStats.basic(topic, 0, 0, 0);
                    }

                    Row row = result.iterator().next();
                    long total = row.getLong("total");
                    long pending = row.getLong("pending");
                    long processed = row.getLong("processed");
                    long inFlight = row.getLong("in_flight");
                    long deadLettered = row.getLong("dead_lettered");
                    Instant firstMessage = row.getLocalDateTime("first_message") != null
                            ? row.getLocalDateTime("first_message").toInstant(ZoneOffset.UTC)
                            : null;
                    Instant lastMessage = row.getLocalDateTime("last_message") != null
                            ? row.getLocalDateTime("last_message").toInstant(ZoneOffset.UTC)
                            : null;

                    // Calculate messages per second (rough estimate based on time range)
                    double messagesPerSecond = 0.0;
                    if (firstMessage != null && lastMessage != null && total > 1) {
                        long durationSeconds = Duration.between(firstMessage, lastMessage).getSeconds();
                        if (durationSeconds > 0) {
                            messagesPerSecond = (double) total / durationSeconds;
                        }
                    }

                    return new QueueStats(
                            topic, total, pending, processed, inFlight, deadLettered,
                            messagesPerSecond, 0.0, firstMessage, lastMessage);
                })
                .otherwise(e -> {
                    logger.warn("Failed to get stats for topic {}: {}", topic, e.getMessage());
                    return QueueStats.basic(topic, 0, 0, 0);
                });
    }

    @Override
    public Future<Long> countMessagesAsync(String topic) {
        checkNotClosed();
        logger.debug("Counting messages for topic (async): {}", topic);

        Pool pool = poolAdapter.getPool();
        if (pool == null) {
            return Future.failedFuture(new IllegalStateException("Pool not available for message count"));
        }

        String sql = "SELECT COUNT(*) AS total FROM queue_messages WHERE topic = $1";
        return pool.preparedQuery(sql)
                .execute(Tuple.of(topic))
                .map(result -> result.iterator().hasNext() ? result.iterator().next().getLong("total") : 0L);
    }

    @Override
    public Future<Integer> purgeMessagesAsync(String topic) {
        checkNotClosed();
        logger.info("Purging native queue messages for topic: {}", topic);

        Pool pool = poolAdapter.getPool();
        if (pool == null) {
            return Future.failedFuture(new IllegalStateException("Pool not available for message purge"));
        }

        String sql = "DELETE FROM queue_messages WHERE topic = $1";
        return pool.preparedQuery(sql)
                .execute(Tuple.of(topic))
                .map(result -> result.rowCount());
    }

    private MetricsProvider getMetrics() {
        // Get metrics from DatabaseService
        if (databaseService != null) {
            return databaseService.getMetricsProvider();
        }

        // Fall back to no-op metrics
        return NoOpMetricsProvider.INSTANCE;
    }

    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("Queue factory is closed");
        }
    }

    private <T extends AutoCloseable> T registerResource(T resource) {
        managedResources.add(resource);
        return resource;
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

        if (io.vertx.core.Vertx.currentContext() != null && io.vertx.core.Vertx.currentContext().isEventLoopContext()) {
            throw new IllegalStateException("Do not call blocking close() on event-loop thread");
        }

        logger.info("Closing PgNativeQueueFactory");
        closed = true;

        try {
            closeManagedResources();
            if (poolAdapter != null) {
                poolAdapter.closeAsync().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
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
     * Creates a default ObjectMapper with JSR310 support for Java 8 time types and
     * CloudEvents support.
     */
    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(JsonFormat.getCloudEventJacksonModule());
        return mapper;
    }

    private void closeManagedResources() throws Exception {
        Exception firstFailure = null;

        for (AutoCloseable resource : managedResources) {
            try {
                resource.close();
            } catch (Exception e) {
                if (firstFailure == null) {
                    firstFailure = e;
                } else {
                    firstFailure.addSuppressed(e);
                }
                logger.error("Error closing managed native queue resource", e);
            }
        }

        managedResources.clear();

        if (firstFailure != null) {
            throw firstFailure;
        }
    }
}
