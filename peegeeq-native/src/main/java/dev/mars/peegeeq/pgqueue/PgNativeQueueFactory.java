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
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.poolAdapter = new VertxPoolAdapter(clientFactory);
        logger.info("Initialized PgNativeQueueFactory (legacy mode)");
    }

    // New constructor using DatabaseService interface
    public PgNativeQueueFactory(DatabaseService databaseService) {
        this(databaseService, new ObjectMapper());
    }

    public PgNativeQueueFactory(DatabaseService databaseService, ObjectMapper objectMapper) {
        this.databaseService = databaseService;
        this.clientFactory = null;
        this.legacyMetrics = null;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();

        // Extract PgClientFactory from DatabaseService if it's a PgDatabaseService
        PgClientFactory extractedClientFactory = extractClientFactory(databaseService);
        this.poolAdapter = new VertxPoolAdapter(extractedClientFactory);
        logger.info("Initialized PgNativeQueueFactory (new interface mode)");
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
        logger.info("Creating native queue consumer for topic: {}", topic);

        PeeGeeQMetrics metrics = getMetrics();
        return new PgNativeQueueConsumer<>(poolAdapter, objectMapper, topic, payloadType, metrics);
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
        return new PgNativeConsumerGroup<>(groupName, topic, payloadType, poolAdapter, objectMapper, metrics);
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
                poolAdapter.close();
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
}
