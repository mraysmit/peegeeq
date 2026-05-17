package dev.mars.peegeeq.examples.springbootbitemporaltx.config;

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

import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.bitemporal.BiTemporalEventStoreFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.examples.springbootbitemporaltx.events.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Spring Boot Configuration for Advanced Bi-Temporal Event Store Patterns
 * with Multi-Event Store Transaction Coordination.
 * 
 * This configuration class sets up multiple bi-temporal event stores as Spring beans
 * and provides the infrastructure for coordinating transactions across them using
 * Vert.x 5.x reactive patterns and PostgreSQL ACID transactions.
 * 
 * <h2>Event Store Architecture</h2>
 * 
 * <h3>Domain-Specific Event Stores</h3>
 * <ul>
 *   <li><b>Order Event Store</b> - {@link OrderEvent} - Order lifecycle management</li>
 *   <li><b>Inventory Event Store</b> - {@link InventoryEvent} - Stock movements and reservations</li>
 *   <li><b>Payment Event Store</b> - {@link PaymentEvent} - Payment processing and refunds</li>
 *   <li><b>Audit Event Store</b> - {@link AuditEvent} - Regulatory compliance and investigation</li>
 * </ul>
 * 
 * <h3>Transaction Coordination</h3>
 * <ul>
 *   <li><b>Shared PeeGeeQ Manager</b> - Single connection pool and transaction context</li>
 *   <li><b>TransactionPropagation.CONTEXT</b> - Share transactions across event stores</li>
 *   <li><b>Automatic Rollback</b> - Any failure rolls back all event stores</li>
 *   <li><b>Event Ordering</b> - Consistent transaction time across all stores</li>
 * </ul>
 * 
 * <h3>Configuration Features</h3>
 * <ul>
 *   <li><b>Spring Boot Properties</b> - Database and pool configuration</li>
 *   <li><b>Schema Management</b> - Automatic schema initialization</li>
 *   <li><b>Metrics Integration</b> - Micrometer metrics for monitoring</li>
 *   <li><b>Production Ready</b> - Error handling and logging</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-03
 * @version 1.0
 */
@Configuration
@EnableConfigurationProperties(BiTemporalTxProperties.class)
public class BiTemporalTxConfig {
    private static final Logger logger = LoggerFactory.getLogger(BiTemporalTxConfig.class);



    /**
     * Creates and configures the PeeGeeQ Manager as a Spring bean.
     * This manager provides the foundation for all bi-temporal event stores
     * and handles transaction coordination across multiple stores.
     * 
     * @param properties Bi-temporal transaction configuration properties
     * @param meterRegistry Micrometer registry for metrics
     * @return Configured and started PeeGeeQ Manager
     */
    @Bean
    @Primary
    public PeeGeeQManager peeGeeQManager(BiTemporalTxProperties properties, MeterRegistry meterRegistry) {
        logger.info("Creating PeeGeeQ Manager for Bi-Temporal Transaction Coordination with profile: {}", 
                   properties.getProfile());
        
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(properties.getProfile(), configureSystemProperties(properties));
        PeeGeeQManager manager = new PeeGeeQManager(config, meterRegistry);

        // Start the manager - this handles all Vert.x setup internally
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> startError = new java.util.concurrent.atomic.AtomicReference<>();
        manager.start()
                .onSuccess(v -> startLatch.countDown())
                .onFailure(e -> { startError.set(e); startLatch.countDown(); });
        try { startLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException("PeeGeeQManager start interrupted", e); }
        if (startError.get() != null) { throw new RuntimeException("PeeGeeQManager failed to start", startError.get()); }
        logger.info("PeeGeeQ Manager started successfully for multi-event store coordination");

        return manager;
    }

    /**
     * Creates the DatabaseService bean for database operations.
     * This provides access to PeeGeeQ's connection management and transaction support
     * that will be shared across all bi-temporal event stores.
     *
     * @param manager PeeGeeQ Manager instance
     * @return Configured DatabaseService
     */
    @Bean
    public DatabaseService databaseService(PeeGeeQManager manager) {
        logger.info("Creating DatabaseService bean for bi-temporal event store coordination");
        DatabaseService service = new PgDatabaseService(manager);
        logger.info("DatabaseService bean created successfully");
        return service;
    }

    /**
     * Creates the BiTemporalEventStoreFactory for creating typed event stores.
     * This factory will be used to create all domain-specific event stores
     * that participate in coordinated transactions.
     *
     * @param manager PeeGeeQ Manager instance
     * @return Configured BiTemporalEventStoreFactory
     */
    @Bean
    public BiTemporalEventStoreFactory eventStoreFactory(PeeGeeQManager manager) {
        logger.info("Creating BiTemporalEventStoreFactory for multi-store coordination");
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(manager.getVertx(), manager);
        logger.info("BiTemporalEventStoreFactory created successfully");
        return factory;
    }

    /**
     * Creates the Order Event Store for order lifecycle management.
     * This store handles all order-related events: created, validated, shipped, delivered, cancelled.
     *
     * @param factory BiTemporalEventStoreFactory instance
     * @return Configured Order Event Store
     */
    @Bean
    public EventStore<OrderEvent> orderEventStore(BiTemporalEventStoreFactory factory) {
        logger.info("Creating Order Event Store for order lifecycle management");
        EventStore<OrderEvent> store = factory.createEventStore(OrderEvent.class, "bitemporal_event_log");
        logger.info("Order Event Store created successfully");
        return store;
    }

    /**
     * Creates the Inventory Event Store for stock movement tracking.
     * This store handles all inventory-related events: reserved, allocated, released, adjusted.
     *
     * @param factory BiTemporalEventStoreFactory instance
     * @return Configured Inventory Event Store
     */
    @Bean
    public EventStore<InventoryEvent> inventoryEventStore(BiTemporalEventStoreFactory factory) {
        logger.info("Creating Inventory Event Store for stock movement tracking");
        EventStore<InventoryEvent> store = factory.createEventStore(InventoryEvent.class, "bitemporal_event_log");
        logger.info("Inventory Event Store created successfully");
        return store;
    }

    /**
     * Creates the Payment Event Store for payment processing.
     * This store handles all payment-related events: authorized, captured, refunded, failed.
     *
     * @param factory BiTemporalEventStoreFactory instance
     * @return Configured Payment Event Store
     */
    @Bean
    public EventStore<PaymentEvent> paymentEventStore(BiTemporalEventStoreFactory factory) {
        logger.info("Creating Payment Event Store for payment processing");
        EventStore<PaymentEvent> store = factory.createEventStore(PaymentEvent.class, "bitemporal_event_log");
        logger.info("Payment Event Store created successfully");
        return store;
    }

    /**
     * Creates the Audit Event Store for regulatory compliance.
     * This store handles all audit-related events: compliance checks, regulatory reports, investigations.
     *
     * @param factory BiTemporalEventStoreFactory instance
     * @return Configured Audit Event Store
     */
    @Bean
    public EventStore<AuditEvent> auditEventStore(BiTemporalEventStoreFactory factory) {
        logger.info("Creating Audit Event Store for regulatory compliance");
        EventStore<AuditEvent> store = factory.createEventStore(AuditEvent.class, "bitemporal_event_log");
        logger.info("Audit Event Store created successfully");
        return store;
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
     * @param properties Bi-temporal transaction configuration properties
     */
    private java.util.Properties configureSystemProperties(BiTemporalTxProperties properties) {
        java.util.Properties props = new java.util.Properties();
        props.setProperty("peegeeq.database.host", properties.getDatabase().getHost());
        props.setProperty("peegeeq.database.port", String.valueOf(properties.getDatabase().getPort()));
        props.setProperty("peegeeq.database.name", properties.getDatabase().getName());
        props.setProperty("peegeeq.database.username", properties.getDatabase().getUsername());
        props.setProperty("peegeeq.database.password", properties.getDatabase().getPassword());
        props.setProperty("peegeeq.database.schema", properties.getDatabase().getSchema());
        props.setProperty("peegeeq.database.pool.max-size", String.valueOf(properties.getPool().getMaxSize()));
        props.setProperty("peegeeq.database.pool.min-size", String.valueOf(properties.getPool().getMinSize()));
        props.setProperty("peegeeq.transaction.timeout", properties.getTransaction().getTimeout().toString());
        props.setProperty("peegeeq.transaction.retry-attempts", String.valueOf(properties.getTransaction().getRetryAttempts()));
        return props;
    }
}

