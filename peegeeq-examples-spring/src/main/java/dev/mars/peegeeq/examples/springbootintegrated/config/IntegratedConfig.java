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

package dev.mars.peegeeq.examples.springbootintegrated.config;

import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.bitemporal.BiTemporalEventStoreFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.examples.springbootintegrated.events.OrderEvent;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.outbox.OutboxProducer;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spring Boot configuration for integrated outbox + bi-temporal pattern.
 * 
 * <p>This configuration creates:
 * <ul>
 *   <li><b>PeeGeeQManager</b> - Foundation for all PeeGeeQ operations</li>
 *   <li><b>DatabaseService</b> - Database connection management</li>
 *   <li><b>QueueFactory</b> - Outbox factory for message producers</li>
 *   <li><b>MessageProducer</b> - For sending events to outbox</li>
 *   <li><b>BiTemporalEventStoreFactory</b> - Factory for event stores</li>
 *   <li><b>EventStore</b> - For bi-temporal event storage</li>
 * </ul>
 * 
 * <p>All components share the SAME connection pool and can participate
 * in the SAME transactions.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@Configuration
public class IntegratedConfig {

    private static final Logger logger = LoggerFactory.getLogger(IntegratedConfig.class);

    private PeeGeeQManager manager;
    private EventStore<OrderEvent> eventStore;

    /**
     * Creates and configures the PeeGeeQManager.
     *
     * <p>This method uses Pattern 1 (Full Spring Boot Integration) where database
     * configuration is read from @ConfigurationProperties and bridged to PeeGeeQ's
     * system properties via the configureSystemProperties() method.
     *
     * @param properties Application properties
     * @param meterRegistry Micrometer registry for metrics
     * @return Configured PeeGeeQManager
     * @throws Exception if initialization fails
     */
    @Bean
    public PeeGeeQManager peeGeeQManager(IntegratedProperties properties, MeterRegistry meterRegistry) {
        logger.info("Initializing PeeGeeQManager with profile: {}", properties.getProfile());
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(properties.getProfile(), configureSystemProperties(properties));
        return new PeeGeeQManager(config, meterRegistry);
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
                logger.info("Starting PeeGeeQ Manager via SmartLifecycle...");
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
                logger.info("PeeGeeQ Manager started successfully");
            }

            @Override
            public void stop(Runnable callback) {
                logger.info("Stopping PeeGeeQ Manager via SmartLifecycle...");
                manager.closeReactive()
                    .onSuccess(v -> {
                        logger.info("PeeGeeQ Manager stopped successfully");
                        running = false;
                        callback.run();
                    })
                    .onFailure(e -> {
                        logger.error("Error stopping PeeGeeQ Manager", e);
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
     * Creates the database service.
     * 
     * @param manager PeeGeeQManager instance
     * @return DatabaseService for connection management
     */
    @Bean
    public DatabaseService databaseService(PeeGeeQManager manager) {
        logger.info("Creating DatabaseService");
        DatabaseService service = new PgDatabaseService(manager);
        logger.info("DatabaseService created successfully");
        return service;
    }
    
    /**
     * Creates the outbox factory for message producers.
     * 
     * @param databaseService DatabaseService instance
     * @return QueueFactory for outbox operations
     */
    @Bean
    public QueueFactory outboxFactory(DatabaseService databaseService) {
        logger.info("Creating outbox factory");
        
        QueueFactoryProvider provider = new PgQueueFactoryProvider();
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        QueueFactory factory = provider.createFactory("outbox", databaseService);
        
        logger.info("Outbox factory created successfully");
        return factory;
    }
    
    /**
     * Creates the order event producer.
     *
     * @param factory QueueFactory instance
     * @return OutboxProducer for OrderEvent
     */
    @Bean
    public OutboxProducer<OrderEvent> orderEventProducer(QueueFactory factory) {
        logger.info("Creating order event producer");
        OutboxProducer<OrderEvent> producer = (OutboxProducer<OrderEvent>) factory.createProducer("orders", OrderEvent.class);
        logger.info("Order event producer created successfully");
        return producer;
    }
    
    /**
     * Creates the bi-temporal event store factory.
     * 
     * @param manager PeeGeeQManager instance
     * @return BiTemporalEventStoreFactory
     */
    @Bean
    public BiTemporalEventStoreFactory biTemporalEventStoreFactory(PeeGeeQManager manager) {
        logger.info("Creating BiTemporalEventStoreFactory");
        return new BiTemporalEventStoreFactory(manager.getVertx(), manager);
    }
    
    /**
     * Creates the order event store.
     * 
     * @param factory BiTemporalEventStoreFactory
     * @return EventStore for OrderEvent
     */
    @Bean
    public EventStore<OrderEvent> orderEventStore(BiTemporalEventStoreFactory factory) {
        logger.info("Creating OrderEvent event store");
        eventStore = factory.createEventStore(OrderEvent.class, "bitemporal_event_log");
        logger.info("OrderEvent event store created successfully");
        return eventStore;
    }
    
    /**
     * Cleanup resources on application shutdown.
     */
    @PreDestroy
    public void cleanup() {
        logger.info("Shutting down integrated resources");
        
        if (eventStore != null) {
            eventStore.close()
                    .onSuccess(v -> logger.info("Event store closed successfully"))
                    .onFailure(e -> logger.error("Error closing event store", e));
        }
    }

    /**
     * Configures system properties from Spring Boot configuration.
     *
     * <p>This bridges Spring Boot's @ConfigurationProperties (which picks up @DynamicPropertySource values)
     * to PeeGeeQConfiguration's system property reading mechanism.
     *
     * <p>This is Pattern 1 (Full Spring Boot Integration) where database properties are managed
     * through Spring's configuration system and automatically bridged to PeeGeeQ.
     *
     * @param properties Integrated configuration properties from Spring
     */
    private java.util.Properties configureSystemProperties(IntegratedProperties properties) {
        java.util.Properties props = new java.util.Properties();
        props.setProperty("peegeeq.database.host", properties.getDatabase().getHost());
        props.setProperty("peegeeq.database.port", String.valueOf(properties.getDatabase().getPort()));
        props.setProperty("peegeeq.database.name", properties.getDatabase().getName());
        props.setProperty("peegeeq.database.username", properties.getDatabase().getUsername());
        props.setProperty("peegeeq.database.password", properties.getDatabase().getPassword());
        props.setProperty("peegeeq.database.schema", properties.getDatabase().getSchema());
        return props;
    }
}


