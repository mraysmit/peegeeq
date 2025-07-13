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


import dev.mars.peegeeq.api.DatabaseService;
import dev.mars.peegeeq.api.MessageConsumer;
import dev.mars.peegeeq.api.MessageProducer;
import dev.mars.peegeeq.api.QueueFactory;
import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating outbox pattern message producers and consumers.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * Factory for creating outbox pattern message producers and consumers.
 * Uses the outbox pattern to ensure reliable message delivery through database transactions.
 *
 * This implementation now follows the new QueueFactory interface pattern
 * and can work with either the legacy PgClientFactory or the new DatabaseService.
 */
public class OutboxFactory implements QueueFactory {
    private static final Logger logger = LoggerFactory.getLogger(OutboxFactory.class);

    // Legacy support
    private final PgClientFactory clientFactory;
    private final PeeGeeQMetrics legacyMetrics;

    // New interface support
    private final DatabaseService databaseService;

    // Common fields
    private final ObjectMapper objectMapper;
    private volatile boolean closed = false;

    // Legacy constructors for backward compatibility
    public OutboxFactory(PgClientFactory clientFactory) {
        this(clientFactory, new ObjectMapper(), null);
    }

    public OutboxFactory(PgClientFactory clientFactory, ObjectMapper objectMapper) {
        this(clientFactory, objectMapper, null);
    }

    public OutboxFactory(PgClientFactory clientFactory, ObjectMapper objectMapper, PeeGeeQMetrics metrics) {
        this.clientFactory = clientFactory;
        this.legacyMetrics = metrics;
        this.databaseService = null;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        logger.info("Initialized OutboxFactory (legacy mode)");
    }

    // New constructor using DatabaseService interface
    public OutboxFactory(DatabaseService databaseService) {
        this(databaseService, new ObjectMapper());
    }

    public OutboxFactory(DatabaseService databaseService, ObjectMapper objectMapper) {
        this.databaseService = databaseService;
        this.clientFactory = extractClientFactory(databaseService);
        this.legacyMetrics = extractMetrics(databaseService);
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        logger.info("Initialized OutboxFactory (new interface mode)");
    }

    private PgClientFactory extractClientFactory(DatabaseService databaseService) {
        // This is a bridge method to extract the client factory from the database service
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

    private PeeGeeQMetrics extractMetrics(DatabaseService databaseService) {
        // Extract metrics from the new interface
        try {
            var metricsProvider = databaseService.getMetricsProvider();
            if (metricsProvider.getClass().getSimpleName().equals("PgMetricsProvider")) {
                // Use reflection to get the underlying PeeGeeQMetrics
                var metricsField = metricsProvider.getClass().getDeclaredField("metrics");
                metricsField.setAccessible(true);
                return (PeeGeeQMetrics) metricsField.get(metricsProvider);
            }
        } catch (Exception e) {
            logger.warn("Could not extract PeeGeeQMetrics from MetricsProvider", e);
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
        logger.info("Creating outbox producer for topic: {}", topic);

        PeeGeeQMetrics metrics = getMetrics();
        return new OutboxProducer<>(clientFactory, objectMapper, topic, payloadType, metrics);
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
        logger.info("Creating outbox consumer for topic: {}", topic);

        PeeGeeQMetrics metrics = getMetrics();
        return new OutboxConsumer<>(clientFactory, objectMapper, topic, payloadType, metrics);
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
                return databaseService.isHealthy();
            } else if (clientFactory != null) {
                // Legacy health check - check if we can get a connection
                return clientFactory.getConnectionManager().isHealthy();
            }
            return false;
        } catch (Exception e) {
            logger.warn("Health check failed for outbox queue factory", e);
            return false;
        }
    }

    @Override
    public void close() throws Exception {
        if (closed) {
            logger.debug("OutboxFactory already closed");
            return;
        }

        logger.info("Closing OutboxFactory");
        closed = true;

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
}
