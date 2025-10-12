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
import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
public class OutboxFactory implements dev.mars.peegeeq.api.messaging.QueueFactory {
    private static final Logger logger = LoggerFactory.getLogger(OutboxFactory.class);

    // Legacy support
    private final PgClientFactory clientFactory;
    private final PeeGeeQMetrics legacyMetrics;

    // New interface support
    private final DatabaseService databaseService;

    // Configuration support
    private final PeeGeeQConfiguration configuration;

    // Common fields
    private final ObjectMapper objectMapper;
    private volatile boolean closed = false;

    // Track created consumers and producers for proper cleanup
    private final Set<AutoCloseable> createdResources = ConcurrentHashMap.newKeySet();

    // Legacy constructors for backward compatibility
    public OutboxFactory(PgClientFactory clientFactory) {
        this(clientFactory, createDefaultObjectMapper(), null);
    }

    public OutboxFactory(PgClientFactory clientFactory, ObjectMapper objectMapper) {
        this(clientFactory, objectMapper, null);
    }

    public OutboxFactory(PgClientFactory clientFactory, ObjectMapper objectMapper, PeeGeeQMetrics metrics) {
        this.clientFactory = clientFactory;
        this.legacyMetrics = metrics;
        this.databaseService = null;
        this.configuration = null;
        this.objectMapper = objectMapper != null ? objectMapper : createDefaultObjectMapper();
        logger.info("Initialized OutboxFactory (legacy mode)");
    }

    // New constructor using DatabaseService interface
    public OutboxFactory(DatabaseService databaseService) {
        this(databaseService, createDefaultObjectMapper());
    }

    public OutboxFactory(DatabaseService databaseService, ObjectMapper objectMapper) {
        this(databaseService, objectMapper, null);
    }

    public OutboxFactory(DatabaseService databaseService, PeeGeeQConfiguration configuration) {
        this(databaseService, createDefaultObjectMapper(), configuration);
    }

    public OutboxFactory(DatabaseService databaseService, ObjectMapper objectMapper, PeeGeeQConfiguration configuration) {
        this.databaseService = databaseService;
        this.clientFactory = null; // Do not reflect or create fallbacks; use DatabaseService directly
        this.legacyMetrics = null; // Metrics will be optional when using DatabaseService
        this.configuration = configuration;
        this.objectMapper = objectMapper != null ? objectMapper : createDefaultObjectMapper();
        logger.info("Initialized OutboxFactory (new interface mode) with configuration: {}",
            configuration != null ? "enabled" : "disabled");
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

        if (topic == null || topic.trim().isEmpty()) {
            throw new IllegalArgumentException("Topic cannot be null or empty");
        }
        if (payloadType == null) {
            throw new IllegalArgumentException("Payload type cannot be null");
        }

        PeeGeeQMetrics metrics = getMetrics();

        MessageProducer<T> producer;
        if (clientFactory != null) {
            producer = new OutboxProducer<>(clientFactory, objectMapper, topic, payloadType, metrics);
        } else if (databaseService != null) {
            producer = new OutboxProducer<>(databaseService, objectMapper, topic, payloadType, metrics);
        } else {
            throw new IllegalStateException("Both clientFactory and databaseService are null");
        }

        // Track the producer for cleanup
        createdResources.add(producer);
        return producer;
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
        logger.info("OutboxFactory state - clientFactory: {}, databaseService: {}, configuration: {}",
            clientFactory != null ? "present" : "null",
            databaseService != null ? "present" : "null",
            configuration != null ? "present" : "null");

        if (topic == null || topic.trim().isEmpty()) {
            throw new IllegalArgumentException("Topic cannot be null or empty");
        }
        if (payloadType == null) {
            throw new IllegalArgumentException("Payload type cannot be null");
        }

        PeeGeeQMetrics metrics = getMetrics();

        MessageConsumer<T> consumer;
        if (clientFactory != null) {
            logger.info("Using existing client factory for outbox consumer on topic: {}", topic);
            if (configuration != null) {
                consumer = new OutboxConsumer<>(clientFactory, objectMapper, topic, payloadType, metrics, configuration);
            } else {
                consumer = new OutboxConsumer<>(clientFactory, objectMapper, topic, payloadType, metrics);
            }
        } else if (databaseService != null) {
            logger.info("Using DatabaseService for outbox consumer on topic: {}", topic);
            if (configuration != null) {
                consumer = new OutboxConsumer<>(databaseService, objectMapper, topic, payloadType, metrics, configuration);
            } else {
                consumer = new OutboxConsumer<>(databaseService, objectMapper, topic, payloadType, metrics);
            }
        } else {
            throw new IllegalStateException("Both clientFactory and databaseService are null");
        }

        // Track the consumer for cleanup
        createdResources.add(consumer);
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

        PeeGeeQMetrics metrics = getMetrics();

        if (clientFactory == null) {
            throw new IllegalStateException("Cannot create consumer group without a PgClientFactory. Use legacy constructor or provide a clientFactory.");
        }

        ConsumerGroup<T> consumerGroup = new OutboxConsumerGroup<>(groupName, topic, payloadType,
            clientFactory, objectMapper, metrics, configuration);

        // Track the consumer group for cleanup
        createdResources.add(consumerGroup);
        return consumerGroup;
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
            } else if (clientFactory != null) {
                // Legacy health check - best effort
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

    private PeeGeeQMetrics getMetrics() {
        // No reflection; metrics are optional. When using DatabaseService path, return null.
        return legacyMetrics;
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
