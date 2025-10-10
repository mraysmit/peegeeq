package dev.mars.peegeeq.examples.springbootconsumer.config;

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
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.examples.springbootconsumer.events.OrderEvent;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Spring Boot Configuration for PeeGeeQ Consumer Example.
 * 
 * This configuration demonstrates the CORRECT way to set up message consumers
 * in a Spring Boot application using PeeGeeQ's public API.
 * 
 * Key Principles:
 * - Uses DatabaseService (PeeGeeQ's public API)
 * - No separate connection pools
 * - Proper consumer lifecycle management
 * - Spring-managed bean lifecycle
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-02
 * @version 1.0
 */
@Configuration
@EnableConfigurationProperties(PeeGeeQConsumerProperties.class)
public class PeeGeeQConsumerConfig {
    private static final Logger log = LoggerFactory.getLogger(PeeGeeQConsumerConfig.class);

    @Value("${consumer.queue-name:order-events}")
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
    public PeeGeeQManager peeGeeQManager(PeeGeeQConsumerProperties properties, MeterRegistry meterRegistry) {
        log.info("Creating PeeGeeQ Manager for consumer with profile: {}", properties.getProfile());

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
     * Creates MessageConsumer bean for consuming order events.
     * 
     * @param factory QueueFactory instance
     * @return MessageConsumer for OrderEvent
     */
    @Bean
    public MessageConsumer<OrderEvent> orderEventConsumer(QueueFactory factory) {
        log.info("Creating MessageConsumer bean for queue: {}", queueName);
        MessageConsumer<OrderEvent> consumer = factory.createConsumer(queueName, OrderEvent.class);
        log.info("MessageConsumer created successfully");
        return consumer;
    }





    /**
     * Configure system properties from Spring configuration.
     */
    private void configureSystemProperties(PeeGeeQConsumerProperties properties) {
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

