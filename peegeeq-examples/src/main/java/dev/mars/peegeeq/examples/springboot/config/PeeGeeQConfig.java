package dev.mars.peegeeq.examples.springboot.config;

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
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Spring Boot Configuration for PeeGeeQ Transactional Outbox Pattern.
 * 
 * This configuration class sets up PeeGeeQ components as Spring beans following
 * the patterns outlined in the PeeGeeQ Transactional Outbox Patterns Guide.
 * 
 * Key Features:
 * - Automatic PeeGeeQ Manager lifecycle management
 * - Outbox factory and producer bean creation
 * - System properties configuration from Spring properties
 * - Zero Vert.x exposure to application developers
 * - Production-ready configuration with proper error handling
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-06
 * @version 1.0
 */
@Configuration
@EnableConfigurationProperties(PeeGeeQProperties.class)
public class PeeGeeQConfig {
    private static final Logger log = LoggerFactory.getLogger(PeeGeeQConfig.class);

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
        log.info("Creating PeeGeeQ Manager with profile: {}", properties.getProfile());
        
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
        log.info("Creating outbox factory");
        
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
        log.info("Creating order event producer");
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
        log.info("Creating payment event producer");
        OutboxProducer<PaymentEvent> producer = (OutboxProducer<PaymentEvent>) factory.createProducer("payments", PaymentEvent.class);
        log.info("Payment event producer created successfully");
        return producer;
    }

    /**
     * Provides DatabaseService for database operations.
     * This is the correct PeeGeeQ API entry point for database access.
     * Applications should use DatabaseService.getConnectionProvider() to execute database operations.
     *
     * @param manager PeeGeeQ Manager instance
     * @return DatabaseService for database operations
     */
    @Bean
    public DatabaseService databaseService(PeeGeeQManager manager) {
        log.info("Creating DatabaseService bean for database operations");
        DatabaseService service = new PgDatabaseService(manager);
        log.info("DatabaseService bean created successfully");
        return service;
    }

    /**
     * Initializes the database schema on application startup.
     * This method is called after the application context is fully initialized.
     * Uses DatabaseService to access the connection provider.
     *
     * @param event Application ready event (automatically injected by Spring)
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeSchema(ApplicationReadyEvent event) {
        log.info("Initializing database schema from schema-springboot.sql");

        try {
            // Read schema file from classpath
            ClassPathResource resource = new ClassPathResource("schema-springboot.sql");
            String schemaSql;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                schemaSql = reader.lines().collect(Collectors.joining("\n"));
            }

            // Get DatabaseService bean from Spring context
            DatabaseService databaseService = event.getApplicationContext().getBean(DatabaseService.class);

            // Get ConnectionProvider and execute schema SQL using withConnection
            var connectionProvider = databaseService.getConnectionProvider();
            connectionProvider.withConnection("peegeeq-main", connection ->
                connection.query(schemaSql).execute().mapEmpty()
            )
                .onSuccess(result -> log.info("Database schema initialized successfully"))
                .onFailure(error -> log.error("Failed to initialize database schema: {}", error.getMessage(), error))
                .toCompletionStage()
                .toCompletableFuture()
                .get(); // Wait for completion

        } catch (Exception e) {
            log.error("Error initializing database schema: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize database schema", e);
        }
    }

    /**
     * Configures system properties from Spring Boot configuration.
     * This allows PeeGeeQ to use Spring Boot's configuration management
     * while maintaining its internal configuration system.
     *
     * @param properties PeeGeeQ configuration properties
     */
    private void configureSystemProperties(PeeGeeQProperties properties) {
        log.debug("Configuring system properties from Spring Boot configuration");

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

        log.debug("System properties configured successfully");
    }
}
