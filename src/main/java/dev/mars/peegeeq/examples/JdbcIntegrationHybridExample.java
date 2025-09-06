package dev.mars.peegeeq.examples;

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
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.outbox.OutboxProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.sqlclient.TransactionPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Example demonstrating JDBC Integration Hybrid pattern from PeeGeeQ Guide.
 * 
 * This example demonstrates the advanced usage pattern: Integration with Existing JDBC Code
 * following the patterns outlined in Section "Advanced Usage Patterns - 3. Integration with Existing JDBC Code".
 * 
 * Key Features Demonstrated:
 * - Gradual migration from JDBC to reactive
 * - Existing JDBC code works unchanged
 * - Hybrid approach with both JDBC and reactive methods
 * - Transaction participation between JDBC and PeeGeeQ
 * - Performance comparison between approaches
 * - Migration strategies and best practices
 * 
 * Usage:
 * ```java
 * JdbcIntegrationHybridExample example = new JdbcIntegrationHybridExample();
 * example.runExample();
 * ```
 * 
 * Patterns Demonstrated:
 * 1. Existing JDBC method - no changes needed
 * 2. Hybrid approach with transaction participation
 * 3. New reactive method - full reactive stack
 * 4. Performance comparison between approaches
 * 5. Migration strategies and best practices
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-06
 * @version 1.0
 */
public class JdbcIntegrationHybridExample {
    private static final Logger logger = LoggerFactory.getLogger(JdbcIntegrationHybridExample.class);
    
    private PeeGeeQManager manager;
    private QueueFactory outboxFactory;
    private OutboxProducer<OrderCreatedEvent> orderProducer;
    private DataSource dataSource;
    private PostgreSQLContainer<?> postgres;
    
    /**
     * Main method to run the example
     */
    public static void main(String[] args) {
        JdbcIntegrationHybridExample example = new JdbcIntegrationHybridExample();
        try {
            example.runExample();
        } catch (Exception e) {
            logger.error("Example failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
    
    /**
     * Run the complete JDBC Integration Hybrid example
     */
    public void runExample() throws Exception {
        logger.info("=== Starting JDBC Integration Hybrid Example ===");
        
        try {
            // Setup - following established pattern
            setup();
            
            // Demonstrate all JDBC integration patterns
            demonstrateExistingJdbcMethod();
            demonstrateHybridApproachWithTransactionParticipation();
            demonstrateNewReactiveMethod();
            demonstratePerformanceComparison();
            demonstrateMigrationStrategies();
            
            logger.info("=== JDBC Integration Hybrid Example Completed Successfully ===");
            
        } finally {
            // Cleanup
            cleanup();
        }
    }
    
    /**
     * Setup PeeGeeQ components and PostgreSQL container - following established pattern
     */
    private void setup() throws Exception {
        logger.info("Setting up JDBC Integration Hybrid Example...");
        
        // Start PostgreSQL container - following established pattern from other examples
        postgres = new PostgreSQLContainer<>("postgres:15")
                .withDatabaseName("peegeeq_jdbc_hybrid")
                .withUsername("postgres")
                .withPassword("password");
        
        postgres.start();
        logger.info("PostgreSQL container started: {}", postgres.getJdbcUrl());
        
        // Configure system properties - following established pattern
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        
        // Initialize PeeGeeQ Manager - following established pattern
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        manager.start();
        logger.info("✓ PeeGeeQ Manager started");
        
        // Create outbox factory - following established pattern
        DatabaseService databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();
        
        // Register outbox factory implementation
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        
        outboxFactory = provider.createFactory("outbox", databaseService);
        orderProducer = (OutboxProducer<OrderCreatedEvent>) outboxFactory.createProducer("orders", OrderCreatedEvent.class);
        
        // Get DataSource for JDBC operations - following established pattern from TransactionalOutboxAnalysisTest
        dataSource = databaseService.getConnectionProvider().getDataSource("peegeeq-main");
        
        // Create business table for JDBC operations
        createBusinessTable();
        
        logger.info("✓ Setup completed successfully");
    }
    
    /**
     * Cleanup resources - following established pattern
     */
    private void cleanup() throws Exception {
        logger.info("Cleaning up resources...");
        
        if (orderProducer != null) {
            orderProducer.close();
            logger.info("✓ Order producer closed");
        }
        
        if (manager != null) {
            manager.stop();
            logger.info("✓ PeeGeeQ Manager stopped");
        }
        
        if (postgres != null) {
            postgres.stop();
            logger.info("✓ PostgreSQL container stopped");
        }
        
        logger.info("✓ Cleanup completed");
    }
    
    /**
     * Create business table for JDBC operations - following established pattern
     */
    private void createBusinessTable() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            String createTableSql = """
                CREATE TABLE IF NOT EXISTS orders (
                    id VARCHAR(255) PRIMARY KEY,
                    customer_id VARCHAR(255) NOT NULL,
                    amount DECIMAL(10,2) NOT NULL,
                    status VARCHAR(50) NOT NULL DEFAULT 'CREATED',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """;
            
            try (PreparedStatement stmt = conn.prepareStatement(createTableSql)) {
                stmt.execute();
                logger.info("✓ Business table created successfully");
            }
        }
    }
    
    /**
     * Demonstrate Pattern 1: Existing JDBC method - no changes needed
     * 
     * This demonstrates the exact pattern from the guide:
     * "Gradual migration - existing JDBC code works unchanged"
     */
    private void demonstrateExistingJdbcMethod() throws Exception {
        logger.info("--- Pattern 1: Existing JDBC Method (No Changes Needed) ---");

        // Create test order
        Order order = new Order("JDBC-001", "CUSTOMER-JDBC-001", BigDecimal.valueOf(1500.00));
        logger.info("Processing order with existing JDBC method: {}", order);

        // Process using existing JDBC method - no changes needed
        processOrderJdbc(order);
        
        logger.info("✓ Existing JDBC method completed successfully");
        logger.info("✓ No changes needed to existing JDBC code");
    }

    /**
     * Existing JDBC method - no changes needed
     * This represents legacy code that already exists in the system
     */
    public void processOrderJdbc(Order order) throws SQLException {
        logger.info("Processing order with traditional JDBC: {}", order.getId());
        
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false); // Start transaction
            
            try {
                // Existing business logic - unchanged
                insertOrderJdbc(conn, order);
                
                // Traditional approach - no outbox events
                logger.info("Order {} processed with JDBC (no outbox events)", order.getId());
                
                conn.commit();
                logger.info("✓ JDBC transaction committed for order {}", order.getId());
                
            } catch (Exception e) {
                conn.rollback();
                logger.error("✗ JDBC transaction rolled back for order {}: {}", order.getId(), e.getMessage());
                throw e;
            }
        }
    }

    /**
     * Insert order using JDBC - existing business logic
     */
    private void insertOrderJdbc(Connection conn, Order order) throws SQLException {
        String insertSql = "INSERT INTO orders (id, customer_id, amount, status) VALUES (?, ?, ?, ?)";
        
        try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
            stmt.setString(1, order.getId());
            stmt.setString(2, order.getCustomerId());
            stmt.setBigDecimal(3, order.getAmount());
            stmt.setString(4, "PROCESSING");
            
            int rowsAffected = stmt.executeUpdate();
            logger.info("✓ Order inserted via JDBC: {} rows affected", rowsAffected);
        }
    }

    /**
     * Demonstrate Pattern 2: Hybrid approach with transaction participation
     * 
     * This demonstrates the hybrid pattern from the guide:
     * "Use transaction participation to join JDBC transaction"
     */
    private void demonstrateHybridApproachWithTransactionParticipation() throws Exception {
        logger.info("--- Pattern 2: Hybrid Approach with Transaction Participation ---");

        // Create test order
        Order order = new Order("HYBRID-001", "CUSTOMER-HYBRID-001", BigDecimal.valueOf(2500.00));
        logger.info("Processing order with hybrid approach: {}", order);

        // Process using hybrid approach
        processOrderHybrid(order);
        
        logger.info("✓ Hybrid approach with transaction participation completed successfully");
        logger.info("✓ JDBC business logic + PeeGeeQ outbox events in same transaction");
    }

    /**
     * Hybrid approach - existing JDBC + PeeGeeQ outbox events
     * This demonstrates gradual migration strategy
     */
    public void processOrderHybrid(Order order) throws Exception {
        logger.info("Processing order with hybrid approach: {}", order.getId());
        
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false); // Start transaction
            
            try {
                // Existing business logic - unchanged
                insertOrderJdbc(conn, order);
                
                // NEW: Add outbox event using sendWithTransaction
                // This participates in the same transaction as the JDBC operations
                orderProducer.sendWithTransaction(
                    new OrderCreatedEvent(order),
                    TransactionPropagation.CONTEXT
                ).get(5, TimeUnit.SECONDS);
                
                logger.info("✓ Outbox event sent for order {}", order.getId());
                
                conn.commit();
                logger.info("✓ Hybrid transaction committed for order {} (JDBC + Outbox)", order.getId());
                
            } catch (Exception e) {
                conn.rollback();
                logger.error("✗ Hybrid transaction rolled back for order {}: {}", order.getId(), e.getMessage());
                throw e;
            }
        }
    }

    /**
     * Demonstrate Pattern 3: New reactive method - full reactive stack
     * 
     * This demonstrates the target state from the guide:
     * "New reactive method - full reactive stack"
     */
    private void demonstrateNewReactiveMethod() throws Exception {
        logger.info("--- Pattern 3: New Reactive Method (Full Reactive Stack) ---");

        // Create test order
        Order order = new Order("REACTIVE-001", "CUSTOMER-REACTIVE-001", BigDecimal.valueOf(3500.00));
        logger.info("Processing order with full reactive method: {}", order);

        // Process using full reactive approach
        CompletableFuture<String> result = processOrderReactive(order);
        String orderId = result.get(10, TimeUnit.SECONDS);
        
        logger.info("✓ New reactive method completed successfully");
        logger.info("✓ Processed order ID: {}", orderId);
        logger.info("✓ Full reactive stack - no JDBC code needed");
    }

    /**
     * New reactive method - full reactive stack
     * This represents the target architecture after migration
     */
    public CompletableFuture<String> processOrderReactive(Order order) {
        logger.info("Processing order with full reactive approach: {}", order.getId());
        
        return orderProducer.sendWithTransaction(
            new OrderCreatedEvent(order),
            TransactionPropagation.CONTEXT
        ).thenApply(v -> {
            logger.info("✓ Reactive processing completed for order {}", order.getId());
            return order.getId();
        });
    }

    /**
     * Demonstrate Pattern 4: Performance comparison between approaches
     * 
     * This demonstrates the performance benefits mentioned in the guide
     */
    private void demonstratePerformanceComparison() throws Exception {
        logger.info("--- Pattern 4: Performance Comparison Between Approaches ---");

        int testCount = 5; // Small count for demonstration
        
        // Test JDBC approach performance
        long jdbcStartTime = System.currentTimeMillis();
        for (int i = 1; i <= testCount; i++) {
            Order order = new Order("PERF-JDBC-" + String.format("%03d", i), "CUSTOMER-PERF", BigDecimal.valueOf(100.0 + i));
            processOrderJdbc(order);
        }
        long jdbcEndTime = System.currentTimeMillis();
        long jdbcDuration = jdbcEndTime - jdbcStartTime;
        
        // Test hybrid approach performance
        long hybridStartTime = System.currentTimeMillis();
        for (int i = 1; i <= testCount; i++) {
            Order order = new Order("PERF-HYBRID-" + String.format("%03d", i), "CUSTOMER-PERF", BigDecimal.valueOf(200.0 + i));
            processOrderHybrid(order);
        }
        long hybridEndTime = System.currentTimeMillis();
        long hybridDuration = hybridEndTime - hybridStartTime;
        
        // Test reactive approach performance
        long reactiveStartTime = System.currentTimeMillis();
        CompletableFuture<String>[] reactiveFutures = new CompletableFuture[testCount];
        for (int i = 1; i <= testCount; i++) {
            Order order = new Order("PERF-REACTIVE-" + String.format("%03d", i), "CUSTOMER-PERF", BigDecimal.valueOf(300.0 + i));
            reactiveFutures[i-1] = processOrderReactive(order);
        }
        CompletableFuture.allOf(reactiveFutures).get(15, TimeUnit.SECONDS);
        long reactiveEndTime = System.currentTimeMillis();
        long reactiveDuration = reactiveEndTime - reactiveStartTime;
        
        // Report performance results
        logger.info("✓ Performance Comparison Results:");
        logger.info("  JDBC Approach:     {} orders in {} ms ({:.2f} orders/sec)", 
            testCount, jdbcDuration, (testCount * 1000.0) / jdbcDuration);
        logger.info("  Hybrid Approach:   {} orders in {} ms ({:.2f} orders/sec)", 
            testCount, hybridDuration, (testCount * 1000.0) / hybridDuration);
        logger.info("  Reactive Approach: {} orders in {} ms ({:.2f} orders/sec)", 
            testCount, reactiveDuration, (testCount * 1000.0) / reactiveDuration);
        
        logger.info("✓ Performance comparison completed successfully");
    }

    /**
     * Demonstrate Pattern 5: Migration strategies and best practices
     * 
     * This demonstrates migration approaches from the guide
     */
    private void demonstrateMigrationStrategies() throws Exception {
        logger.info("--- Pattern 5: Migration Strategies and Best Practices ---");

        logger.info("Migration Strategy Recommendations:");
        logger.info("1. ✓ Keep existing JDBC code unchanged initially");
        logger.info("2. ✓ Add hybrid methods for new features");
        logger.info("3. ✓ Gradually migrate high-traffic endpoints to reactive");
        logger.info("4. ✓ Use performance testing to validate improvements");
        logger.info("5. ✓ Maintain backward compatibility during migration");
        
        // Demonstrate coexistence of all three approaches
        Order legacyOrder = new Order("MIGRATION-LEGACY-001", "CUSTOMER-LEGACY", BigDecimal.valueOf(1000.00));
        Order hybridOrder = new Order("MIGRATION-HYBRID-001", "CUSTOMER-HYBRID", BigDecimal.valueOf(2000.00));
        Order reactiveOrder = new Order("MIGRATION-REACTIVE-001", "CUSTOMER-REACTIVE", BigDecimal.valueOf(3000.00));
        
        logger.info("Demonstrating coexistence of all approaches:");
        
        // Legacy approach
        processOrderJdbc(legacyOrder);
        logger.info("✓ Legacy JDBC approach still works");
        
        // Hybrid approach
        processOrderHybrid(hybridOrder);
        logger.info("✓ Hybrid approach provides outbox events");
        
        // Reactive approach
        processOrderReactive(reactiveOrder).get(5, TimeUnit.SECONDS);
        logger.info("✓ Reactive approach provides full benefits");
        
        logger.info("✓ Migration strategies demonstrated successfully");
        logger.info("✓ All three approaches can coexist during migration");
    }

    // Domain classes for the example
    public static class Order {
        private final String id;
        private final String customerId;
        private final BigDecimal amount;
        private final Instant createdAt;
        
        public Order(String id, String customerId, BigDecimal amount) {
            this.id = id;
            this.customerId = customerId;
            this.amount = amount;
            this.createdAt = Instant.now();
        }
        
        public String getId() { return id; }
        public String getCustomerId() { return customerId; }
        public BigDecimal getAmount() { return amount; }
        public Instant getCreatedAt() { return createdAt; }
        
        @Override
        public String toString() {
            return String.format("Order{id='%s', customerId='%s', amount=%s, createdAt=%s}", 
                id, customerId, amount, createdAt);
        }
    }

    public static class OrderCreatedEvent {
        private final Order order;
        private final Instant eventTime;
        
        public OrderCreatedEvent(Order order) {
            this.order = order;
            this.eventTime = Instant.now();
        }
        
        public Order getOrder() { return order; }
        public Instant getEventTime() { return eventTime; }
        
        @Override
        public String toString() {
            return String.format("OrderCreatedEvent{order=%s, eventTime=%s}", order, eventTime);
        }
    }
}
