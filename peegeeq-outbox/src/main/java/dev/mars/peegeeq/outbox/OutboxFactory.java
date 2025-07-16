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


import dev.mars.peegeeq.api.ConsumerGroup;
import dev.mars.peegeeq.api.DatabaseService;
import dev.mars.peegeeq.api.MessageConsumer;
import dev.mars.peegeeq.api.MessageProducer;
import dev.mars.peegeeq.api.QueueFactory;
import dev.mars.peegeeq.db.client.PgClientFactory;
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
        this.objectMapper = objectMapper != null ? objectMapper : createDefaultObjectMapper();
        logger.info("Initialized OutboxFactory (legacy mode)");
    }

    // New constructor using DatabaseService interface
    public OutboxFactory(DatabaseService databaseService) {
        this(databaseService, createDefaultObjectMapper());
    }

    public OutboxFactory(DatabaseService databaseService, ObjectMapper objectMapper) {
        this.databaseService = databaseService;
        this.clientFactory = extractClientFactory(databaseService);
        this.legacyMetrics = extractMetrics(databaseService);
        this.objectMapper = objectMapper != null ? objectMapper : createDefaultObjectMapper();
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
                PgClientFactory factory = (PgClientFactory) clientFactoryMethod.invoke(manager);

                if (factory != null) {
                    logger.debug("Successfully extracted PgClientFactory from DatabaseService");
                    return factory;
                } else {
                    logger.warn("PgClientFactory extracted from DatabaseService is null");
                }
            } else {
                logger.warn("DatabaseService is not a PgDatabaseService: {}", databaseService.getClass().getSimpleName());
            }
        } catch (Exception e) {
            logger.warn("Could not extract PgClientFactory from DatabaseService: {}", e.getMessage(), e);
        }

        // Create a fallback client factory if extraction failed
        logger.info("Creating fallback PgClientFactory for outbox operations");
        return createFallbackClientFactory(databaseService);
    }

    private PgClientFactory createFallbackClientFactory(DatabaseService databaseService) {
        logger.info("Creating fallback client factory for DatabaseService: {}", databaseService.getClass().getSimpleName());

        try {
            // Try to get connection information from the database service
            var connectionProvider = databaseService.getConnectionProvider();
            logger.info("Got connection provider: {}", connectionProvider.getClass().getSimpleName());

            // Check if the connection provider has the "peegeeq-main" client
            if (connectionProvider.hasClient("peegeeq-main")) {
                logger.info("Found existing 'peegeeq-main' client in connection provider, using existing data source");

                // Create a fallback client factory that uses the existing data source
                PgClientFactory fallbackFactory = new PgClientFactory();

                // Try to get the data source and extract connection info from it
                try {
                    var dataSource = connectionProvider.getDataSource("peegeeq-main");

                    // Get the actual configuration from the database service if it's a PgDatabaseService
                    dev.mars.peegeeq.db.config.PgConnectionConfig connectionConfig;
                    dev.mars.peegeeq.db.config.PgPoolConfig poolConfig;

                    // Since we can't access the manager directly, use fallback configuration
                    // The connection provider should already have the correct configuration
                    connectionConfig = createFallbackConnectionConfig();
                    poolConfig = new dev.mars.peegeeq.db.config.PgPoolConfig.Builder().build();
                    logger.info("Using fallback configuration for client factory creation");

                    // Register the configuration in the fallback factory
                    fallbackFactory.createClient("peegeeq-main", connectionConfig, poolConfig);

                    logger.info("Successfully created fallback client factory with 'peegeeq-main' configuration");
                    return fallbackFactory;

                } catch (Exception e) {
                    logger.warn("Failed to extract configuration from existing data source: {}", e.getMessage());
                }
            }

            // If we can't use the existing client, try to create one with the actual configuration
            logger.info("No 'peegeeq-main' client found in connection provider, creating new one with system properties");

            // Create fallback factory with configuration from system properties
            var connectionConfig = createFallbackConnectionConfig();
            var poolConfig = new dev.mars.peegeeq.db.config.PgPoolConfig.Builder()
                .maximumPoolSize(10)
                .minimumIdle(2)
                .build();

            PgClientFactory fallbackFactory = new PgClientFactory();
            fallbackFactory.createClient("peegeeq-main", connectionConfig, poolConfig);

            logger.info("Created new fallback client factory with system property configuration for 'peegeeq-main'");
            return fallbackFactory;

        } catch (Exception e) {
            logger.error("Failed to create fallback PgClientFactory: {}", e.getMessage(), e);
            // Create minimal fallback
            logger.warn("Using minimal fallback client factory - outbox operations may fail");
            return new PgClientFactory();
        }
    }

    private dev.mars.peegeeq.db.config.PgConnectionConfig createFallbackConnectionConfig() {
        // Try to get connection details from system properties (used in tests)
        String host = System.getProperty("peegeeq.database.host", "localhost");
        int port = Integer.parseInt(System.getProperty("peegeeq.database.port", "5432"));
        String database = System.getProperty("peegeeq.database.name", "peegeeq");
        String username = System.getProperty("peegeeq.database.username", "peegeeq");
        String password = System.getProperty("peegeeq.database.password", "");

        logger.info("Creating fallback connection config: host={}, port={}, database={}, username={}",
            host, port, database, username);

        return new dev.mars.peegeeq.db.config.PgConnectionConfig.Builder()
            .host(host)
            .port(port)
            .database(database)
            .username(username)
            .password(password)
            .build();
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

        PeeGeeQMetrics metrics = getMetrics();

        MessageConsumer<T> consumer;
        if (clientFactory != null) {
            logger.info("Using existing client factory for outbox consumer on topic: {}", topic);
            consumer = new OutboxConsumer<>(clientFactory, objectMapper, topic, payloadType, metrics);
        } else if (databaseService != null) {
            logger.info("No client factory available, trying to create one from database service for topic: {}", topic);
            // Try to get or create a client factory for the database service
            PgClientFactory effectiveClientFactory = createFallbackClientFactory(databaseService);
            if (effectiveClientFactory != null) {
                logger.info("Successfully created client factory for outbox consumer on topic: {}", topic);
                consumer = new OutboxConsumer<>(effectiveClientFactory, objectMapper, topic, payloadType, metrics);
            } else {
                logger.warn("Failed to create client factory, falling back to database service for outbox consumer on topic: {}", topic);
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

        PeeGeeQMetrics metrics = getMetrics();

        PgClientFactory effectiveClientFactory = clientFactory;
        if (effectiveClientFactory == null && databaseService != null) {
            effectiveClientFactory = createFallbackClientFactory(databaseService);
            if (effectiveClientFactory == null) {
                throw new IllegalStateException("Cannot create consumer group without a valid client factory");
            }
        }

        ConsumerGroup<T> consumerGroup = new OutboxConsumerGroup<>(groupName, topic, payloadType,
            effectiveClientFactory, objectMapper, metrics);

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
     * Creates a default ObjectMapper with JSR310 support for Java 8 time types.
     */
    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
}
