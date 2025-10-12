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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;

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
    public PeeGeeQManager peeGeeQManager(IntegratedProperties properties, MeterRegistry meterRegistry) throws Exception {
        logger.info("Initializing PeeGeeQManager with profile: {}", properties.getProfile());

        // Configure system properties from Spring Boot configuration
        // This bridges Spring's @ConfigurationProperties to PeeGeeQConfiguration's system property reading
        configureSystemProperties(properties);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration(properties.getProfile());
        manager = new PeeGeeQManager(config, meterRegistry);
        manager.start();

        logger.info("PeeGeeQManager started successfully");
        return manager;
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
        return new BiTemporalEventStoreFactory(manager);
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
        eventStore = factory.createEventStore(OrderEvent.class);
        logger.info("OrderEvent event store created successfully");
        return eventStore;
    }
    
    /**
     * Cleanup resources on application shutdown.
     */
    @PreDestroy
    public void cleanup() {
        logger.info("Shutting down integrated resources");
        
        try {
            if (eventStore != null) {
                eventStore.close();
                logger.info("Event store closed successfully");
            }
        } catch (Exception e) {
            logger.error("Error closing event store", e);
        }
        
        try {
            if (manager != null) {
                manager.close();
                logger.info("PeeGeeQManager closed successfully");
            }
        } catch (Exception e) {
            logger.error("Error closing PeeGeeQManager", e);
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
    private void configureSystemProperties(IntegratedProperties properties) {
        logger.debug("Configuring system properties from Spring Boot configuration");

        System.setProperty("peegeeq.database.host", properties.getDatabase().getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(properties.getDatabase().getPort()));
        System.setProperty("peegeeq.database.name", properties.getDatabase().getName());
        System.setProperty("peegeeq.database.username", properties.getDatabase().getUsername());
        System.setProperty("peegeeq.database.password", properties.getDatabase().getPassword());
        System.setProperty("peegeeq.database.schema", properties.getDatabase().getSchema());

        logger.debug("System properties configured successfully");
    }
}

