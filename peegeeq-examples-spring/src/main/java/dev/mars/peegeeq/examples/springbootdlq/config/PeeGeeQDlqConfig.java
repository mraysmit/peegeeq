package dev.mars.peegeeq.examples.springbootdlq.config;

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
import dev.mars.peegeeq.examples.springbootdlq.events.PaymentEvent;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PeeGeeQ configuration for DLQ example.
 * 
 * Demonstrates the CORRECT way to configure PeeGeeQ with DLQ support:
 * - Creates PeeGeeQManager with retry configuration
 * - Creates DatabaseService for transaction management
 * - Creates QueueFactory with outbox support
 * - Creates MessageConsumer and MessageProducer
 * - Initializes database schema on application startup
 */
@Configuration
public class PeeGeeQDlqConfig {
    
    private static final Logger log = LoggerFactory.getLogger(PeeGeeQDlqConfig.class);
    
    private final PeeGeeQDlqProperties properties;
    private DatabaseService databaseServiceInstance;
    
    public PeeGeeQDlqConfig(PeeGeeQDlqProperties properties) {
        this.properties = properties;
    }
    
    /**
     * Create PeeGeeQ Manager with retry configuration.
     */
    @Bean
    public PeeGeeQManager peeGeeQManager() {
        log.info("Creating PeeGeeQ Manager for DLQ with profile: development");
        
        // Configure PeeGeeQ properties from Spring configuration
        java.util.Properties props = new java.util.Properties();
        props.setProperty("peegeeq.database.host", properties.getDatabase().getHost());
        props.setProperty("peegeeq.database.port", String.valueOf(properties.getDatabase().getPort()));
        props.setProperty("peegeeq.database.name", properties.getDatabase().getName());
        props.setProperty("peegeeq.database.username", properties.getDatabase().getUsername());
        props.setProperty("peegeeq.database.password", properties.getDatabase().getPassword());
        props.setProperty("peegeeq.database.schema", properties.getDatabase().getSchema());
        
        // Configure retry settings
        props.setProperty("peegeeq.queue.max-retries", String.valueOf(properties.getMaxRetries()));
        // Convert milliseconds to seconds for Duration format (e.g., 500ms -> PT0.5S)
        double seconds = properties.getPollingIntervalMs() / 1000.0;
        props.setProperty("peegeeq.queue.polling-interval", "PT" + seconds + "S");
        
        return new PeeGeeQManager(
            new PeeGeeQConfiguration("development", props),
            new SimpleMeterRegistry()
        );
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
    
    /**
     * Create DatabaseService for transaction management.
     */
    @Bean
    public DatabaseService databaseService(PeeGeeQManager manager) {
        log.info("Creating DatabaseService bean");
        databaseServiceInstance = new PgDatabaseService(manager);
        return databaseServiceInstance;
    }
    
    /**
     * Create QueueFactory with outbox support.
     */
    @Bean
    public QueueFactory queueFactory(DatabaseService databaseService) {
        log.info("Creating QueueFactory bean");
        
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
        
        // Register outbox factory implementation
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        
        QueueFactory factory = provider.createFactory("outbox", databaseService);
        log.info("QueueFactory created successfully");
        
        return factory;
    }
    
    /**
     * Create MessageConsumer for payment events.
     */
    @Bean
    public MessageConsumer<PaymentEvent> paymentEventConsumer(QueueFactory factory) {
        log.info("Creating MessageConsumer bean for queue: {}", properties.getQueueName());
        MessageConsumer<PaymentEvent> consumer = factory.createConsumer(
            properties.getQueueName(), 
            PaymentEvent.class
        );
        log.info("MessageConsumer created successfully");
        return consumer;
    }
    
    /**
     * Create MessageProducer for payment events (for testing).
     */
    @Bean
    public MessageProducer<PaymentEvent> paymentEventProducer(QueueFactory factory) {
        log.info("Creating MessageProducer bean for queue: {}", properties.getQueueName());
        MessageProducer<PaymentEvent> producer = factory.createProducer(
            properties.getQueueName(), 
            PaymentEvent.class
        );
        log.info("MessageProducer created successfully");
        return producer;
    }

}

