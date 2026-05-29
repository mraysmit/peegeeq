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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(properties.getProfile(), configureSystemProperties(properties));
        return new PeeGeeQManager(config, meterRegistry);
    }

    /**
     * Manages PeeGeeQ Manager lifecycle via Spring's SmartLifecycle contract.
     *
     * <p>start() runs on the Spring refresh thread (not the Vert.x event loop) and blocks
     * for up to 60 seconds until manager.start() completes  the only acceptable bridge
     * in a synchronous Spring context. stop(Runnable) closes the manager reactively and
     * notifies Spring via the callback when teardown is complete.
     */
    @Bean
    public SmartLifecycle peeGeeQManagerLifecycle(PeeGeeQManager manager) {
        return new SmartLifecycle() {
            private volatile boolean running = false;

            @Override
            public void start() {
                log.info("Starting PeeGeeQ Manager via SmartLifecycle...");
                CountDownLatch latch = new CountDownLatch(1);
                AtomicReference<Throwable> error = new AtomicReference<>();
                manager.start()
                    .onSuccess(v -> latch.countDown())
                    .onFailure(e -> { error.set(e); latch.countDown(); });
                try {
                    if (!latch.await(60, TimeUnit.SECONDS)) {
                        throw new RuntimeException("PeeGeeQManager start timed out after 60 seconds");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("PeeGeeQManager start interrupted", e);
                }
                if (error.get() != null) {
                    throw new RuntimeException("PeeGeeQManager failed to start", error.get());
                }
                running = true;
                log.info("PeeGeeQ Manager started successfully");
            }

            @Override
            public void stop(Runnable callback) {
                log.info("Stopping PeeGeeQ Manager via SmartLifecycle...");
                manager.closeReactive()
                    .onSuccess(v -> {
                        log.info("PeeGeeQ Manager stopped successfully");
                        running = false;
                        callback.run();
                    })
                    .onFailure(e -> {
                        log.error("Error stopping PeeGeeQ Manager", e);
                        running = false;
                        callback.run();
                    });
            }

            @Override
            public void stop() {
                stop(() -> {});
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public boolean isAutoStartup() {
                return true;
            }

            @Override
            public int getPhase() {
                return Integer.MAX_VALUE;
            }
        };
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
        QueueFactoryProvider provider = new PgQueueFactoryProvider(manager.getConfiguration());
        
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
     * Configures system properties from Spring Boot configuration.
     * This allows PeeGeeQ to use Spring Boot's configuration management
     * while maintaining its internal configuration system.
     *
     * @param properties PeeGeeQ configuration properties
     */
    private java.util.Properties configureSystemProperties(PeeGeeQProperties properties) {
        java.util.Properties props = new java.util.Properties();
        props.setProperty("peegeeq.database.host", properties.getDatabase().getHost());
        props.setProperty("peegeeq.database.port", String.valueOf(properties.getDatabase().getPort()));
        props.setProperty("peegeeq.database.name", properties.getDatabase().getName());
        props.setProperty("peegeeq.database.username", properties.getDatabase().getUsername());
        props.setProperty("peegeeq.database.password", properties.getDatabase().getPassword());
        props.setProperty("peegeeq.database.schema", properties.getDatabase().getSchema());
        props.setProperty("peegeeq.database.pool.max-size", String.valueOf(properties.getPool().getMaxSize()));
        props.setProperty("peegeeq.database.pool.min-size", String.valueOf(properties.getPool().getMinSize()));
        props.setProperty("peegeeq.database.pool.max-wait-queue-size", String.valueOf(properties.getPool().getMaxWaitQueueSize()));
        props.setProperty("peegeeq.queue.max-retries", String.valueOf(properties.getQueue().getMaxRetries()));
        props.setProperty("peegeeq.queue.visibility-timeout", properties.getQueue().getVisibilityTimeout().toString());
        props.setProperty("peegeeq.queue.batch-size", String.valueOf(properties.getQueue().getBatchSize()));
        props.setProperty("peegeeq.queue.polling-interval", properties.getQueue().getPollingInterval().toString());
        return props;
    }
}
