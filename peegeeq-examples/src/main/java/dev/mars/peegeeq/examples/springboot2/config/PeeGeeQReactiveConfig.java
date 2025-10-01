package dev.mars.peegeeq.examples.springboot2.config;

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
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.examples.springboot.events.OrderEvent;
import dev.mars.peegeeq.examples.springboot.events.PaymentEvent;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.outbox.OutboxProducer;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Spring Boot Reactive Configuration for PeeGeeQ Transactional Outbox Pattern.
 * 
 * This configuration class sets up PeeGeeQ components as Spring beans for reactive
 * applications using WebFlux and R2DBC.
 * 
 * Key Features:
 * - Automatic PeeGeeQ Manager lifecycle management
 * - Outbox factory and producer bean creation
 * - System properties configuration from Spring properties
 * - Zero Vert.x exposure to application developers
 * - Production-ready configuration with proper error handling
 * - Integration with reactive Spring Boot stack (WebFlux, R2DBC)
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-01
 * @version 1.0
 */
@Configuration
@EnableConfigurationProperties(PeeGeeQProperties.class)
public class PeeGeeQReactiveConfig {
    private static final Logger log = LoggerFactory.getLogger(PeeGeeQReactiveConfig.class);

    /**
     * Creates and configures the PeeGeeQ Manager as a Spring bean.
     * The manager handles all Vert.x setup internally and provides the foundation
     * for all PeeGeeQ operations.
     * 
     * @param properties PeeGeeQ configuration properties
     * @param meterRegistry Micrometer registry for metrics
     * @return Configured and started PeeGeeQ Manager
     */
    @Bean
    @Primary
    public PeeGeeQManager peeGeeQManager(PeeGeeQProperties properties, MeterRegistry meterRegistry) {
        log.info("Creating PeeGeeQ Manager for Reactive Application with profile: {}", properties.getProfile());
        
        // Configure system properties from Spring configuration
        configureSystemProperties(properties);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration(properties.getProfile());
        PeeGeeQManager manager = new PeeGeeQManager(config, meterRegistry);

        // Start the manager - this handles all Vert.x setup internally
        manager.start();
        log.info("PeeGeeQ Manager started successfully with profile: {}", properties.getProfile());

        return manager;
    }

    /**
     * Creates the outbox factory for transactional outbox operations.
     * 
     * @param manager PeeGeeQ Manager instance
     * @return Configured outbox factory
     */
    @Bean
    public QueueFactory outboxFactory(PeeGeeQManager manager) {
        log.info("Creating outbox factory for reactive application");
        
        DatabaseService databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();
        
        // Register outbox factory implementation
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        
        QueueFactory factory = provider.createFactory("outbox", databaseService);
        log.info("Outbox factory created successfully");
        
        return factory;
    }

    /**
     * Creates the order event producer for publishing order-related events.
     * 
     * @param factory Outbox factory instance
     * @return Configured order event producer
     */
    @Bean
    public OutboxProducer<OrderEvent> orderEventProducer(QueueFactory factory) {
        log.info("Creating order event producer for reactive application");
        OutboxProducer<OrderEvent> producer = (OutboxProducer<OrderEvent>) factory.createProducer("orders", OrderEvent.class);
        log.info("Order event producer created successfully");
        return producer;
    }

    /**
     * Creates the payment event producer for publishing payment-related events.
     * 
     * @param factory Outbox factory instance
     * @return Configured payment event producer
     */
    @Bean
    public OutboxProducer<PaymentEvent> paymentEventProducer(QueueFactory factory) {
        log.info("Creating payment event producer for reactive application");
        OutboxProducer<PaymentEvent> producer = (OutboxProducer<PaymentEvent>) factory.createProducer("payments", PaymentEvent.class);
        log.info("Payment event producer created successfully");
        return producer;
    }

    /**
     * Configures system properties from Spring Boot configuration.
     * This allows PeeGeeQ to use Spring Boot's configuration management
     * while maintaining its internal configuration system.
     * 
     * @param properties PeeGeeQ configuration properties
     */
    private void configureSystemProperties(PeeGeeQProperties properties) {
        log.debug("Configuring system properties from Spring Boot Reactive configuration");
        
        System.setProperty("peegeeq.database.host", properties.getDatabase().getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(properties.getDatabase().getPort()));
        System.setProperty("peegeeq.database.name", properties.getDatabase().getName());
        System.setProperty("peegeeq.database.username", properties.getDatabase().getUsername());
        System.setProperty("peegeeq.database.password", properties.getDatabase().getPassword());
        System.setProperty("peegeeq.database.schema", properties.getDatabase().getSchema());

        // Configure pool settings
        System.setProperty("peegeeq.database.pool.max-size", String.valueOf(properties.getPool().getMaxSize()));
        System.setProperty("peegeeq.database.pool.min-size", String.valueOf(properties.getPool().getMinSize()));

        // Configure queue settings
        System.setProperty("peegeeq.queue.max-retries", String.valueOf(properties.getQueue().getMaxRetries()));
        System.setProperty("peegeeq.queue.visibility-timeout", properties.getQueue().getVisibilityTimeout().toString());
        System.setProperty("peegeeq.queue.batch-size", String.valueOf(properties.getQueue().getBatchSize()));
        System.setProperty("peegeeq.queue.polling-interval", properties.getQueue().getPollingInterval().toString());
        
        log.debug("System properties configured successfully for reactive application");
    }
}

