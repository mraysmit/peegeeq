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
import dev.mars.peegeeq.bitemporal.ReactiveUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Example demonstrating Advanced Transaction Participation pattern from PeeGeeQ Guide.
 * 
 * This example demonstrates the second reactive approach: sendInTransaction() operations
 * following the patterns outlined in Section "2. Transaction Participation".
 * 
 * Key Features Demonstrated:
 * - Joining existing Vert.x SqlConnection transactions
 * - Business logic + outbox operations in same transaction
 * - Transaction rollback scenarios with proper cleanup
 * - Multiple operations within single transaction context
 * - Real database operations with transactional consistency
 * 
 * Usage:
 * ```java
 * TransactionParticipationAdvancedExample example = new TransactionParticipationAdvancedExample();
 * example.runExample();
 * ```
 * 
 * Patterns Demonstrated:
 * 1. Simple transaction participation with SqlConnection
 * 2. Transaction participation with headers and metadata
 * 3. Multiple outbox operations in same transaction
 * 4. Business logic + outbox consistency validation
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-06
 * @version 1.0
 */
public class TransactionParticipationAdvancedExample {
    private static final Logger logger = LoggerFactory.getLogger(TransactionParticipationAdvancedExample.class);
    
    private PeeGeeQManager manager;
    private QueueFactory outboxFactory;
    private OutboxProducer<OrderEvent> orderProducer;
    private Vertx vertx;
    private Pool vertxPool;
    
    /**
     * Main method to run the example
     */
    public static void main(String[] args) {
        TransactionParticipationAdvancedExample example = new TransactionParticipationAdvancedExample();
        try {
            example.runExample();
        } catch (Exception e) {
            logger.error("Example failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
    
    /**
     * Run the complete Transaction Participation Advanced example
     */
    public void runExample() throws Exception {
        logger.info("=== Starting Transaction Participation Advanced Example ===");
        
        try {
            // Setup
            setup();
            
            // Demonstrate all transaction participation patterns
            demonstrateSimpleTransactionParticipation();
            demonstrateTransactionParticipationWithMetadata();
            demonstrateMultipleOperationsInSameTransaction();
            demonstrateBusinessLogicWithOutboxConsistency();
            
            logger.info("=== Transaction Participation Advanced Example Completed Successfully ===");
            
        } finally {
            // Cleanup
            cleanup();
        }
    }
    
    /**
     * Setup PeeGeeQ components and Vert.x Pool
     */
    private void setup() throws Exception {
        logger.info("Setting up PeeGeeQ components and Vert.x Pool...");
        
        // Note: In a real application, these would come from configuration
        System.setProperty("peegeeq.database.host", "localhost");
        System.setProperty("peegeeq.database.port", "5432");
        System.setProperty("peegeeq.database.name", "peegeeq_examples");
        System.setProperty("peegeeq.database.username", "peegeeq_user");
        System.setProperty("peegeeq.database.password", "peegeeq_password");
        
        // Initialize PeeGeeQ Manager
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        manager.start();
        logger.info("✓ PeeGeeQ Manager started");
        
        // Create outbox factory
        DatabaseService databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();
        
        // Register outbox factory implementation
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        
        outboxFactory = provider.createFactory("outbox", databaseService);
        orderProducer = (OutboxProducer<OrderEvent>) outboxFactory.createProducer("orders", OrderEvent.class);
        
        // Create Vert.x instance and Pool for SqlConnection operations
        vertx = Vertx.vertx();
        
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost("localhost")
            .setPort(5432)
            .setDatabase("peegeeq_examples")
            .setUser("peegeeq_user")
            .setPassword("peegeeq_password");
        
        PoolOptions poolOptions = new PoolOptions()
            .setMaxSize(10);
        
        vertxPool = PgBuilder.pool()
            .with(poolOptions)
            .connectingTo(connectOptions)
            .using(vertx)
            .build();
        
        logger.info("✓ Vert.x Pool created for SqlConnection operations");
        
        // Create test business table for transaction validation
        createBusinessTable();
        
        logger.info("✓ Setup completed successfully");
    }
    
    /**
     * Cleanup resources
     */
    private void cleanup() throws Exception {
        logger.info("Cleaning up resources...");
        
        if (orderProducer != null) {
            orderProducer.close();
            logger.info("✓ Order producer closed");
        }
        
        if (vertxPool != null) {
            vertxPool.close();
            logger.info("✓ Vert.x Pool closed");
        }
        
        if (vertx != null) {
            vertx.close();
            logger.info("✓ Vert.x instance closed");
        }
        
        if (manager != null) {
            manager.stop();
            logger.info("✓ PeeGeeQ Manager stopped");
        }
        
        logger.info("✓ Cleanup completed");
    }
    
    /**
     * Creates a test business table for transaction validation
     */
    private void createBusinessTable() throws Exception {
        vertxPool.getConnection()
            .compose(connection -> {
                String createTableSql = """
                    CREATE TABLE IF NOT EXISTS test_orders (
                        id VARCHAR(255) PRIMARY KEY,
                        customer_id VARCHAR(255) NOT NULL,
                        amount DECIMAL(10,2) NOT NULL,
                        status VARCHAR(50) NOT NULL DEFAULT 'CREATED',
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """;
                
                return connection.query(createTableSql).execute()
                    .eventually(v -> connection.close());
            })
            .mapEmpty()
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
        
        logger.info("✓ Test business table created successfully");
    }
    
    /**
     * Demonstrate Pattern 1: Simple transaction participation with SqlConnection
     * 
     * This demonstrates the second reactive approach from the guide:
     * "Transaction Participation (sendInTransaction) - Join existing transactions"
     */
    private void demonstrateSimpleTransactionParticipation() throws Exception {
        logger.info("--- Pattern 1: Simple Transaction Participation ---");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("TX-ORDER-001", "CUSTOMER-TX-123", 199.99);
        logger.info("Created order: {}", testOrder);

        // Use Vert.x Pool to get SqlConnection and perform transaction
        CompletableFuture<Void> transactionFuture = vertxPool.getConnection()
            .compose(connection -> {
                logger.info("✓ Got SqlConnection for transaction");
                
                // Start transaction
                return connection.begin()
                    .compose(transaction -> {
                        logger.info("✓ Transaction started");
                        
                        // Insert business data in transaction
                        String insertSql = "INSERT INTO test_orders (id, customer_id, amount, status) VALUES ($1, $2, $3, $4)";
                        Tuple params = Tuple.of(testOrder.getOrderId(), testOrder.getCustomerId(), testOrder.getAmount(), "PROCESSING");
                        
                        return connection.preparedQuery(insertSql).execute(params)
                            .compose(result -> {
                                logger.info("✓ Business data inserted: {} rows affected", result.rowCount());
                                
                                // Use sendInTransaction to join the existing transaction
                                return ReactiveUtils.fromCompletableFuture(
                                    orderProducer.sendInTransaction(testOrder, connection)
                                ).compose(v -> {
                                    logger.info("✓ Outbox message sent in transaction");

                                    // Commit the transaction
                                    return transaction.commit();
                                });
                            });
                    })
                    .eventually(v -> connection.close());
            })
            .mapEmpty()
            .toCompletionStage()
            .toCompletableFuture();

        // Wait for completion
        transactionFuture.get(15, TimeUnit.SECONDS);
        logger.info("✓ Simple transaction participation completed successfully");
    }

    /**
     * Demonstrate Pattern 2: Transaction participation with headers and metadata
     * 
     * This demonstrates the full sendInTransaction method from the guide:
     * "sendInTransaction(payload, headers, correlationId, messageGroup, sqlConnection)"
     */
    private void demonstrateTransactionParticipationWithMetadata() throws Exception {
        logger.info("--- Pattern 2: Transaction Participation with Headers and Metadata ---");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("TX-ORDER-002", "CUSTOMER-TX-456", 299.99);
        logger.info("Created order: {}", testOrder);

        // Create headers map
        Map<String, String> headers = new HashMap<>();
        headers.put("source", "order-service");
        headers.put("transaction-type", "participation");
        headers.put("version", "1.0");
        logger.info("Created headers: {}", headers);

        // Create correlation ID and message group
        String correlationId = "TX-CORR-" + UUID.randomUUID().toString().substring(0, 8);
        String messageGroup = "tx-order-group";
        logger.info("Using correlation ID: {}", correlationId);
        logger.info("Using message group: {}", messageGroup);

        // Use Vert.x Pool to get SqlConnection and perform transaction
        CompletableFuture<Void> transactionFuture = vertxPool.getConnection()
            .compose(connection -> {
                logger.info("✓ Got SqlConnection for transaction with metadata");
                
                // Start transaction
                return connection.begin()
                    .compose(transaction -> {
                        logger.info("✓ Transaction started");
                        
                        // Insert business data in transaction
                        String insertSql = "INSERT INTO test_orders (id, customer_id, amount, status) VALUES ($1, $2, $3, $4)";
                        Tuple params = Tuple.of(testOrder.getOrderId(), testOrder.getCustomerId(), testOrder.getAmount(), "PROCESSING");
                        
                        return connection.preparedQuery(insertSql).execute(params)
                            .compose(result -> {
                                logger.info("✓ Business data inserted: {} rows affected", result.rowCount());
                                
                                // Use sendInTransaction with full parameters
                                return ReactiveUtils.fromCompletableFuture(
                                    orderProducer.sendInTransaction(testOrder, headers, correlationId, messageGroup, connection)
                                ).compose(v -> {
                                    logger.info("✓ Outbox message sent with metadata in transaction");

                                    // Commit the transaction
                                    return transaction.commit();
                                });
                            });
                    })
                    .eventually(v -> connection.close());
            })
            .mapEmpty()
            .toCompletionStage()
            .toCompletableFuture();

        // Wait for completion
        transactionFuture.get(15, TimeUnit.SECONDS);
        logger.info("✓ Transaction participation with metadata completed successfully");
    }

    /**
     * Demonstrate Pattern 3: Multiple outbox operations in same transaction
     * 
     * This demonstrates the transaction consistency from the guide:
     * "Multiple operations within logical boundary commit/rollback together"
     */
    private void demonstrateMultipleOperationsInSameTransaction() throws Exception {
        logger.info("--- Pattern 3: Multiple Operations in Same Transaction ---");

        // Create multiple test order events
        OrderEvent order1 = new OrderEvent("TX-ORDER-003A", "CUSTOMER-TX-789", 150.00);
        OrderEvent order2 = new OrderEvent("TX-ORDER-003B", "CUSTOMER-TX-789", 250.00);
        logger.info("Created orders: {} and {}", order1, order2);

        // Use Vert.x Pool to get SqlConnection and perform transaction
        CompletableFuture<Void> transactionFuture = vertxPool.getConnection()
            .compose(connection -> {
                logger.info("✓ Got SqlConnection for multi-operation transaction");
                
                // Start transaction
                return connection.begin()
                    .compose(transaction -> {
                        logger.info("✓ Transaction started for multiple operations");
                        
                        // Insert first business record
                        String insertSql = "INSERT INTO test_orders (id, customer_id, amount, status) VALUES ($1, $2, $3, $4)";
                        Tuple params1 = Tuple.of(order1.getOrderId(), order1.getCustomerId(), order1.getAmount(), "PROCESSING");
                        
                        return connection.preparedQuery(insertSql).execute(params1)
                            .compose(result1 -> {
                                logger.info("✓ First business record inserted: {} rows affected", result1.rowCount());
                                
                                // Send first outbox message
                                return ReactiveUtils.fromCompletableFuture(
                                    orderProducer.sendInTransaction(order1, connection)
                                ).compose(v -> {
                                    logger.info("✓ First outbox message sent in transaction");

                                    // Insert second business record
                                    Tuple params2 = Tuple.of(order2.getOrderId(), order2.getCustomerId(), order2.getAmount(), "PROCESSING");
                                    return connection.preparedQuery(insertSql).execute(params2);
                                });
                            })
                            .compose(result2 -> {
                                logger.info("✓ Second business record inserted: {} rows affected", result2.rowCount());
                                
                                // Send second outbox message
                                return ReactiveUtils.fromCompletableFuture(
                                    orderProducer.sendInTransaction(order2, connection)
                                ).compose(v -> {
                                    logger.info("✓ Second outbox message sent in transaction");

                                    // Commit the transaction - all operations commit together
                                    return transaction.commit();
                                });
                            });
                    })
                    .eventually(v -> connection.close());
            })
            .mapEmpty()
            .toCompletionStage()
            .toCompletableFuture();

        // Wait for completion
        transactionFuture.get(20, TimeUnit.SECONDS);
        logger.info("✓ Multiple operations in same transaction completed successfully");
    }

    /**
     * Demonstrate Pattern 4: Business logic with outbox consistency
     * 
     * This demonstrates the consistency guarantees from the guide:
     * "Business operations and outbox operations are guaranteed to be consistent"
     */
    private void demonstrateBusinessLogicWithOutboxConsistency() throws Exception {
        logger.info("--- Pattern 4: Business Logic with Outbox Consistency ---");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("TX-ORDER-004", "CUSTOMER-TX-999", 399.99);
        logger.info("Created order: {}", testOrder);

        // Demonstrate business logic + outbox consistency
        CompletableFuture<String> businessResult = vertxPool.getConnection()
            .compose(connection -> {
                logger.info("✓ Got SqlConnection for business logic consistency");
                
                return connection.begin()
                    .compose(transaction -> {
                        logger.info("✓ Transaction started for business logic");
                        
                        // Step 1: Insert order
                        String insertSql = "INSERT INTO test_orders (id, customer_id, amount, status) VALUES ($1, $2, $3, $4)";
                        Tuple params = Tuple.of(testOrder.getOrderId(), testOrder.getCustomerId(), testOrder.getAmount(), "CREATED");
                        
                        return connection.preparedQuery(insertSql).execute(params)
                            .compose(result -> {
                                logger.info("✓ Order inserted: {} rows affected", result.rowCount());
                                
                                // Step 2: Update order status
                                String updateSql = "UPDATE test_orders SET status = $1 WHERE id = $2";
                                return connection.preparedQuery(updateSql).execute(Tuple.of("CONFIRMED", testOrder.getOrderId()));
                            })
                            .compose(result -> {
                                logger.info("✓ Order status updated: {} rows affected", result.rowCount());
                                
                                // Step 3: Send outbox event - guaranteed consistency with business operations
                                return ReactiveUtils.fromCompletableFuture(
                                    orderProducer.sendInTransaction(testOrder, connection)
                                ).compose(v -> {
                                    logger.info("✓ Outbox event sent in same transaction");

                                    // Step 4: Commit everything together
                                    return transaction.commit()
                                        .map(ignored -> "Business logic completed with outbox consistency");
                                });
                            });
                    })
                    .eventually(v -> connection.close());
            })
            .toCompletionStage()
            .toCompletableFuture();

        // Wait for completion
        String result = businessResult.get(15, TimeUnit.SECONDS);
        logger.info("✓ {}", result);
    }

    /**
     * Simple event class for demonstration
     */
    public static class OrderEvent {
        private final String orderId;
        private final String customerId;
        private final double amount;
        private final Instant timestamp;
        
        public OrderEvent(String orderId, String customerId, double amount) {
            this.orderId = orderId;
            this.customerId = customerId;
            this.amount = amount;
            this.timestamp = Instant.now();
        }
        
        // Getters
        public String getOrderId() { return orderId; }
        public String getCustomerId() { return customerId; }
        public double getAmount() { return amount; }
        public Instant getTimestamp() { return timestamp; }
        
        @Override
        public String toString() {
            return String.format("OrderEvent{orderId='%s', customerId='%s', amount=%.2f, timestamp=%s}", 
                orderId, customerId, amount, timestamp);
        }
    }
}
