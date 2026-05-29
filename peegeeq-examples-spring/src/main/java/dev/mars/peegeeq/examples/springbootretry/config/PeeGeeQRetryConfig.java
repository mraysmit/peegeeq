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
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
        
        // Create and return PeeGeeQ Manager  SmartLifecycle bean handles start/stop
        return new PeeGeeQManager(new PeeGeeQConfiguration(profile, props), meterRegistry);
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
    /**
     * Manages PeeGeeQ Manager lifecycle via Spring's SmartLifecycle contract.
     *
     * <p>start() runs on the Spring refresh thread and blocks for up to 60 seconds
     * until manager.start() completes. stop(Runnable) closes the manager reactively
     * and notifies Spring via the callback when teardown is complete.
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
}

