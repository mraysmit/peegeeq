package dev.mars.peegeeq.examples.springbootretry.config;

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

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.examples.springbootretry.events.TransactionEvent;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import jakarta.annotation.PreDestroy;

/**
 * PeeGeeQ configuration for Retry example.
 * 
 * Demonstrates the CORRECT way to configure PeeGeeQ with retry strategies:
 * - Configure max retries via system properties
 * - Set up exponential backoff parameters
 * - Initialize circuit breaker settings
 * - Create message consumers and producers
 */
@Configuration
@EnableConfigurationProperties(PeeGeeQRetryProperties.class)
public class PeeGeeQRetryConfig {
    
    private static final Logger log = LoggerFactory.getLogger(PeeGeeQRetryConfig.class);

    
    private final PeeGeeQRetryProperties properties;
    private PeeGeeQManager manager;
    private DatabaseService databaseServiceInstance;
    
    public PeeGeeQRetryConfig(PeeGeeQRetryProperties properties) {
        this.properties = properties;
    }
    
    @Bean
    @Primary
    public PeeGeeQManager peeGeeQManager(
            @Value("${spring.profiles.active:development}") String profile,
            MeterRegistry meterRegistry) {
        
        log.info("Creating PeeGeeQ Manager for Retry with profile: {}", profile);
        
        // Configure PeeGeeQ properties from Spring configuration
        java.util.Properties props = new java.util.Properties();
        props.setProperty("peegeeq.database.host", properties.getDatabase().getHost());
        props.setProperty("peegeeq.database.port", String.valueOf(properties.getDatabase().getPort()));
        props.setProperty("peegeeq.database.name", properties.getDatabase().getName());
        props.setProperty("peegeeq.database.username", properties.getDatabase().getUsername());
        props.setProperty("peegeeq.database.password", properties.getDatabase().getPassword());
        
        // Configure retry settings
        props.setProperty("peegeeq.queue.max-retries", String.valueOf(properties.getMaxRetries()));
        // Convert milliseconds to seconds for Duration format (e.g., 500ms -> PT0.5S)
        double seconds = properties.getPollingIntervalMs() / 1000.0;
        props.setProperty("peegeeq.queue.polling-interval", "PT" + seconds + "S");
        
        // Create and start PeeGeeQ Manager
        manager = new PeeGeeQManager(new PeeGeeQConfiguration(profile, props), meterRegistry);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> startError = new java.util.concurrent.atomic.AtomicReference<>();
        manager.start()
                .onSuccess(v -> startLatch.countDown())
                .onFailure(e -> { startError.set(e); startLatch.countDown(); });
        try { startLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException("PeeGeeQManager start interrupted", e); }
        if (startError.get() != null) { throw new RuntimeException("PeeGeeQManager failed to start", startError.get()); }

        log.info("PeeGeeQ Manager started successfully with max retries: {}", properties.getMaxRetries());
        
        return manager;
    }
    
    @Bean
    public DatabaseService databaseService(PeeGeeQManager manager) {
        log.info("Creating DatabaseService bean");
        databaseServiceInstance = new PgDatabaseService(manager);
        return databaseServiceInstance;
    }
    
    @Bean
    public QueueFactory queueFactory(DatabaseService databaseService) {
        log.info("Creating QueueFactory bean");
        
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
        OutboxFactoryRegistrar.registerWith(provider);
        
        QueueFactory factory = provider.createFactory("outbox", databaseService);
        
        log.info("QueueFactory created successfully");
        return factory;
    }
    
    @Bean
    public MessageConsumer<TransactionEvent> transactionConsumer(QueueFactory factory) {
        log.info("Creating MessageConsumer bean for queue: {}", properties.getQueueName());
        
        MessageConsumer<TransactionEvent> consumer = factory.createConsumer(
            properties.getQueueName(),
            TransactionEvent.class
        );
        
        log.info("MessageConsumer created successfully");
        return consumer;
    }
    
    @Bean
    public MessageProducer<TransactionEvent> transactionProducer(QueueFactory factory) {
        log.info("Creating MessageProducer bean for queue: {}", properties.getQueueName());
        
        MessageProducer<TransactionEvent> producer = factory.createProducer(
            properties.getQueueName(),
            TransactionEvent.class
        );
        
        log.info("MessageProducer created successfully");
        return producer;
    }
    

    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down PeeGeeQ Manager");
        if (manager != null) {
            manager.closeReactive()
                    .onFailure(e -> log.error("Error closing PeeGeeQManager on shutdown", e));
        }
    }
}

