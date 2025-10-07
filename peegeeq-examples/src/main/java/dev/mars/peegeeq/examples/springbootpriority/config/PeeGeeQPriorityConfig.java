package dev.mars.peegeeq.examples.springbootpriority.config;

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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.examples.springbootpriority.events.TradeSettlementEvent;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Spring Boot Configuration for PeeGeeQ Priority Example.
 * 
 * This configuration demonstrates the CORRECT way to set up priority-based
 * message processing in a Spring Boot application using PeeGeeQ's public API.
 * 
 * Key Principles:
 * - Uses DatabaseService (PeeGeeQ's public API)
 * - No separate connection pools
 * - Proper producer and consumer lifecycle management
 * - Spring-managed bean lifecycle
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@Configuration
@EnableConfigurationProperties(PeeGeeQPriorityProperties.class)
public class PeeGeeQPriorityConfig {
    private static final Logger log = LoggerFactory.getLogger(PeeGeeQPriorityConfig.class);

    @Value("${priority.queue-name:trade-settlements}")
    private String queueName;

    private DatabaseService databaseServiceInstance;

    /**
     * Creates and configures the PeeGeeQ Manager as a Spring bean.
     * 
     * @param properties PeeGeeQ configuration properties
     * @param meterRegistry Micrometer registry for metrics
     * @return Configured and started PeeGeeQ Manager
     */
    @Bean(destroyMethod = "close")
    @Primary
    public PeeGeeQManager peeGeeQManager(PeeGeeQPriorityProperties properties, MeterRegistry meterRegistry) {
        log.info("Creating PeeGeeQ Manager for priority example with profile: {}", properties.getProfile());

        // Configure system properties from Spring configuration
        configureSystemProperties(properties);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration(properties.getProfile());
        PeeGeeQManager manager = new PeeGeeQManager(config, meterRegistry);

        // Start the manager
        manager.start();
        log.info("PeeGeeQ Manager started successfully");

        return manager;
    }

    /**
     * Creates DatabaseService bean using PeeGeeQ's public API.
     * This is the CORRECT entry point for database operations.
     * 
     * @param manager PeeGeeQ Manager
     * @return DatabaseService instance
     */
    @Bean
    public DatabaseService databaseService(PeeGeeQManager manager) {
        log.info("Creating DatabaseService bean");
        databaseServiceInstance = new PgDatabaseService(manager);
        return databaseServiceInstance;
    }

    /**
     * Creates QueueFactory for outbox pattern.
     * 
     * @param databaseService DatabaseService instance
     * @return QueueFactory for creating producers and consumers
     */
    @Bean
    public QueueFactory queueFactory(DatabaseService databaseService) {
        log.info("Creating QueueFactory bean");
        
        // Create provider and register outbox factory
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        
        // Create factory using DatabaseService
        QueueFactory factory = provider.createFactory("outbox", databaseService);
        log.info("QueueFactory created successfully");
        
        return factory;
    }

    /**
     * Creates MessageProducer bean for sending trade settlement events.
     * 
     * @param factory QueueFactory instance
     * @return MessageProducer for TradeSettlementEvent
     */
    @Bean
    public MessageProducer<TradeSettlementEvent> tradeSettlementProducer(QueueFactory factory) {
        log.info("Creating MessageProducer bean for queue: {}", queueName);
        MessageProducer<TradeSettlementEvent> producer = factory.createProducer(queueName, TradeSettlementEvent.class);
        log.info("MessageProducer created successfully");
        return producer;
    }

    /**
     * Creates MessageConsumer bean for critical trades consumer.
     * 
     * @param factory QueueFactory instance
     * @return MessageConsumer for TradeSettlementEvent
     */
    @Bean(name = "criticalTradesConsumer")
    public MessageConsumer<TradeSettlementEvent> criticalTradesConsumer(QueueFactory factory) {
        log.info("Creating MessageConsumer bean for critical trades: {}", queueName);
        MessageConsumer<TradeSettlementEvent> consumer = factory.createConsumer(queueName, TradeSettlementEvent.class);
        log.info("Critical trades MessageConsumer created successfully");
        return consumer;
    }

    /**
     * Creates MessageConsumer bean for high priority trades consumer.
     * 
     * @param factory QueueFactory instance
     * @return MessageConsumer for TradeSettlementEvent
     */
    @Bean(name = "highPriorityTradesConsumer")
    public MessageConsumer<TradeSettlementEvent> highPriorityTradesConsumer(QueueFactory factory) {
        log.info("Creating MessageConsumer bean for high priority trades: {}", queueName);
        MessageConsumer<TradeSettlementEvent> consumer = factory.createConsumer(queueName, TradeSettlementEvent.class);
        log.info("High priority trades MessageConsumer created successfully");
        return consumer;
    }

    /**
     * Creates MessageConsumer bean for all trades consumer.
     * 
     * @param factory QueueFactory instance
     * @return MessageConsumer for TradeSettlementEvent
     */
    @Bean(name = "allTradesConsumer")
    public MessageConsumer<TradeSettlementEvent> allTradesConsumer(QueueFactory factory) {
        log.info("Creating MessageConsumer bean for all trades: {}", queueName);
        MessageConsumer<TradeSettlementEvent> consumer = factory.createConsumer(queueName, TradeSettlementEvent.class);
        log.info("All trades MessageConsumer created successfully");
        return consumer;
    }

    /**
     * Initialize database schema when application is ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeSchema() {
        log.info("Initializing database schema");
        
        try {
            String schema = loadSchemaFile();

            databaseServiceInstance.getConnectionProvider()
                .withTransaction("peegeeq-main", connection -> {
                    return connection.query(schema).execute()
                        .map(result -> {
                            log.info("Database schema initialized successfully");
                            return null;
                        });
                })
                .toCompletionStage()
                .toCompletableFuture()
                .join();
                
        } catch (Exception e) {
            log.error("Failed to initialize database schema", e);
            throw new RuntimeException("Schema initialization failed", e);
        }
    }

    /**
     * Load schema SQL file from classpath.
     */
    private String loadSchemaFile() {
        try {
            ClassPathResource resource = new ClassPathResource("schema-springboot-priority.sql");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load schema file", e);
        }
    }

    /**
     * Configure system properties from Spring configuration.
     */
    private void configureSystemProperties(PeeGeeQPriorityProperties properties) {
        System.setProperty("peegeeq.database.host", properties.getDatabase().getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(properties.getDatabase().getPort()));
        System.setProperty("peegeeq.database.name", properties.getDatabase().getName());
        System.setProperty("peegeeq.database.username", properties.getDatabase().getUsername());
        System.setProperty("peegeeq.database.password", properties.getDatabase().getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", String.valueOf(properties.getDatabase().getSsl().isEnabled()));
        System.setProperty("peegeeq.database.schema", properties.getDatabase().getSchema());
        System.setProperty("peegeeq.database.pool.max-size", String.valueOf(properties.getDatabase().getPool().getMaxSize()));
        System.setProperty("peegeeq.database.pool.min-size", String.valueOf(properties.getDatabase().getPool().getMinSize()));
        System.setProperty("peegeeq.queue.polling-interval", properties.getQueue().getPollingInterval());
        System.setProperty("peegeeq.queue.max-retries", String.valueOf(properties.getQueue().getMaxRetries()));
        System.setProperty("peegeeq.queue.batch-size", String.valueOf(properties.getQueue().getBatchSize()));
        
        log.info("System properties configured from Spring configuration");
    }
}

