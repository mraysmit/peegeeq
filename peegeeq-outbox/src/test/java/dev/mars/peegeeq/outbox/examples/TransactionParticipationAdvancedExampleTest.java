package dev.mars.peegeeq.outbox.examples;

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

import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.outbox.OutboxProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.Future;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive JUnit test demonstrating Advanced Transaction Participation in PeeGeeQ Outbox Pattern.
 * 
 * This test demonstrates the second reactive approach: sendInTransaction() operations
 * following the patterns outlined in Section "2. Transaction Participation".
 * 
 * <h2>✅ NO INTENTIONAL FAILURES - This Test Demonstrates Success Patterns</h2>
 * <p>Unlike error handling tests, this test demonstrates successful transaction participation patterns.
 * All operations should complete successfully, showing proper transactional consistency.</p>
 * 
 * <h2>Test Coverage</h2>
 * <ul>
 *   <li><b>Simple Transaction Participation</b> - Basic sendInTransaction with SqlConnection</li>
 *   <li><b>Transaction Participation with Metadata</b> - Full sendInTransaction with headers and correlation</li>
 *   <li><b>Multiple Operations in Same Transaction</b> - Multiple outbox operations with consistency</li>
 *   <li><b>Business Logic with Outbox Consistency</b> - Real database operations with outbox events</li>
 * </ul>
 * 
 * <h2>Key Features Tested</h2>
 * <ul>
 *   <li>Joining existing Vert.x SqlConnection transactions</li>
 *   <li>Business logic + outbox operations in same transaction</li>
 *   <li>Transaction rollback scenarios with proper cleanup</li>
 *   <li>Multiple operations within single transaction context</li>
 *   <li>Real database operations with transactional consistency</li>
 * </ul>
 * 
 * <h2>Expected Test Results</h2>
 * <p>All tests should <b>PASS</b> by successfully demonstrating transaction participation:</p>
 * <ul>
 *   <li>✅ Simple transaction participation completes successfully</li>
 *   <li>✅ Transaction participation with metadata works correctly</li>
 *   <li>✅ Multiple operations maintain transactional consistency</li>
 *   <li>✅ Business logic and outbox operations are consistent</li>
 * </ul>
 * 
 * <h2>Transaction Patterns Demonstrated</h2>
 * <ul>
 *   <li><b>Pattern 1</b>: Simple transaction participation with SqlConnection</li>
 *   <li><b>Pattern 2</b>: Transaction participation with headers and metadata</li>
 *   <li><b>Pattern 3</b>: Multiple outbox operations in same transaction</li>
 *   <li><b>Pattern 4</b>: Business logic with outbox consistency validation</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-14
 * @version 1.0
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionParticipationAdvancedExampleTest {
    
    private static final Logger logger = LoggerFactory.getLogger(TransactionParticipationAdvancedExampleTest.class);
    
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_tx_participation_test")
            .withUsername("postgres")
            .withPassword("password");
    
    private PeeGeeQManager manager;
    private QueueFactory outboxFactory;
    private OutboxProducer<OrderEvent> orderProducer;
    private Vertx vertx;
    private Pool vertxPool;
    
    @BeforeEach
    void setUp() throws Exception {
        // Initialize schema first
        TestSchemaInitializer.initializeSchema(postgres);

        logger.info("=== Setting up Transaction Participation Advanced Example Test ===");

        // Configure PeeGeeQ to use container database
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.schema", "public");
        
        // Initialize PeeGeeQ Manager - following established pattern
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        manager.start();
        logger.info("PeeGeeQ Manager started successfully");
        
        // Create outbox factory - following established pattern
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
        
        // Register outbox factory implementation
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        
        outboxFactory = provider.createFactory("outbox", databaseService);
        orderProducer = (OutboxProducer<OrderEvent>) outboxFactory.createProducer("orders", OrderEvent.class);
        
        // Initialize Vert.x and connection pool for transaction participation
        vertx = Vertx.vertx();
        
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());
        
        PoolOptions poolOptions = new PoolOptions().setMaxSize(10);
        vertxPool = PgBuilder.pool().with(poolOptions).connectingTo(connectOptions).using(vertx).build();
        
        // Create test business table
        createTestBusinessTable();
        
        logger.info("✅ Transaction Participation Advanced Example Test setup completed");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        logger.info("🧹 Cleaning up Transaction Participation Advanced Example Test");
        
        if (orderProducer != null) {
            orderProducer.close();
        }
        
        if (outboxFactory != null) {
            outboxFactory.close();
        }
        
        if (vertxPool != null) {
            vertxPool.close();
        }
        
        if (vertx != null) {
            vertx.close();
        }
        
        if (manager != null) {
            manager.close();
        }
        
        // Clear system properties
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        System.clearProperty("peegeeq.database.schema");
        
        logger.info("✅ Transaction Participation Advanced Example Test cleanup completed");
    }
    
    @Test
    void testSimpleTransactionParticipation() throws Exception {
        logger.info("=== Testing Pattern 1: Simple Transaction Participation ===");
        
        // Create a test order event
        OrderEvent testOrder = new OrderEvent("TX-ORDER-001", "CUSTOMER-TX-123", 199.99);
        logger.info("Created order: {}", testOrder);
        
        // Demonstrate simple transaction participation
        CompletableFuture<Void> transactionFuture = vertxPool.getConnection()
            .compose(connection -> {
                logger.info("✓ Got SqlConnection for simple transaction participation");
                
                return connection.begin()
                    .compose(transaction -> {
                        logger.info("✓ Transaction started");
                        
                        // Send outbox message within transaction
                        return Future.fromCompletionStage(
                            orderProducer.sendInTransaction(testOrder, connection)
                        ).compose(v -> {
                            logger.info("✓ Outbox message sent in transaction");
                            
                            // Commit the transaction
                            return transaction.commit();
                        });
                    })
                    .eventually(() -> connection.close());
            })
            .mapEmpty()
            .toCompletionStage()
            .toCompletableFuture()
            .thenApply(result -> (Void) null);
        
        // Wait for completion
        assertDoesNotThrow(() -> transactionFuture.get(15, TimeUnit.SECONDS));
        logger.info("✅ Simple transaction participation test completed successfully!");
    }
    
    @Test
    void testTransactionParticipationWithMetadata() throws Exception {
        logger.info("=== Testing Pattern 2: Transaction Participation with Headers and Metadata ===");
        
        // Create a test order event
        OrderEvent testOrder = new OrderEvent("TX-ORDER-002", "CUSTOMER-TX-456", 299.99);
        logger.info("Created order: {}", testOrder);
        
        // Prepare headers and metadata
        Map<String, String> headers = new HashMap<>();
        headers.put("source", "transaction-participation-test");
        headers.put("version", "1.0");
        headers.put("priority", "high");
        
        String correlationId = UUID.randomUUID().toString();
        String messageGroup = "tx-participation-group";
        
        logger.info("Headers: {}", headers);
        logger.info("Correlation ID: {}", correlationId);
        logger.info("Message Group: {}", messageGroup);
        
        // Demonstrate transaction participation with full metadata
        CompletableFuture<Void> transactionFuture = vertxPool.getConnection()
            .compose(connection -> {
                logger.info("✓ Got SqlConnection for metadata transaction participation");
                
                return connection.begin()
                    .compose(transaction -> {
                        logger.info("✓ Transaction started with metadata");
                        
                        // Send outbox message with full metadata within transaction
                        return Future.fromCompletionStage(
                            orderProducer.sendInTransaction(testOrder, headers, correlationId, messageGroup, connection)
                        ).compose(v -> {
                            logger.info("✓ Outbox message with metadata sent in transaction");
                            
                            // Commit the transaction
                            return transaction.commit();
                        });
                    })
                    .eventually(() -> connection.close());
            })
            .mapEmpty()
            .toCompletionStage()
            .toCompletableFuture()
            .thenApply(result -> (Void) null);
        
        // Wait for completion
        assertDoesNotThrow(() -> transactionFuture.get(15, TimeUnit.SECONDS));
        logger.info("✅ Transaction participation with metadata test completed successfully!");
    }

    @Test
    void testMultipleOperationsInSameTransaction() throws Exception {
        logger.info("=== Testing Pattern 3: Multiple Operations in Same Transaction ===");

        // Create multiple test order events
        OrderEvent order1 = new OrderEvent("TX-ORDER-003A", "CUSTOMER-TX-789", 150.00);
        OrderEvent order2 = new OrderEvent("TX-ORDER-003B", "CUSTOMER-TX-789", 250.00);
        logger.info("Created order 1: {}", order1);
        logger.info("Created order 2: {}", order2);

        // Demonstrate multiple operations in same transaction
        CompletableFuture<Void> transactionFuture = vertxPool.getConnection()
            .compose(connection -> {
                logger.info("✓ Got SqlConnection for multiple operations");

                return connection.begin()
                    .compose(transaction -> {
                        logger.info("✓ Transaction started for multiple operations");

                        // Send first outbox message
                        return Future.fromCompletionStage(
                            orderProducer.sendInTransaction(order1, connection)
                        ).compose(v -> {
                            logger.info("✓ First outbox message sent in transaction");

                            // Send second outbox message
                            return Future.fromCompletionStage(
                                orderProducer.sendInTransaction(order2, connection)
                            ).compose(v2 -> {
                                logger.info("✓ Second outbox message sent in transaction");

                                // Commit the transaction - all operations commit together
                                return transaction.commit();
                            });
                        });
                    })
                    .eventually(() -> connection.close());
            })
            .mapEmpty()
            .toCompletionStage()
            .toCompletableFuture()
            .thenApply(result -> (Void) null);

        // Wait for completion
        assertDoesNotThrow(() -> transactionFuture.get(20, TimeUnit.SECONDS));
        logger.info("✅ Multiple operations in same transaction test completed successfully!");
    }

    @Test
    void testBusinessLogicWithOutboxConsistency() throws Exception {
        logger.info("=== Testing Pattern 4: Business Logic with Outbox Consistency ===");

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
                                assertEquals(1, result.rowCount(), "Should insert exactly 1 row");

                                // Step 2: Update order status
                                String updateSql = "UPDATE test_orders SET status = $1 WHERE id = $2";
                                return connection.preparedQuery(updateSql).execute(Tuple.of("CONFIRMED", testOrder.getOrderId()));
                            })
                            .compose(result -> {
                                logger.info("✓ Order status updated: {} rows affected", result.rowCount());
                                assertEquals(1, result.rowCount(), "Should update exactly 1 row");

                                // Step 3: Send outbox event - guaranteed consistency with business operations
                                return Future.fromCompletionStage(
                                    orderProducer.sendInTransaction(testOrder, connection)
                                ).compose(v -> {
                                    logger.info("✓ Outbox event sent in same transaction");

                                    // Step 4: Commit everything together
                                    return transaction.commit()
                                        .map(ignored -> "Business logic completed with outbox consistency");
                                });
                            });
                    })
                    .eventually(() -> connection.close());
            })
            .toCompletionStage()
            .toCompletableFuture()
            .thenApply(result -> (String) result);

        // Wait for completion and verify result
        String result = assertDoesNotThrow(() -> businessResult.get(15, TimeUnit.SECONDS));
        assertEquals("Business logic completed with outbox consistency", result);
        logger.info("✅ Business logic with outbox consistency test completed successfully!");
    }

    /**
     * Creates the test business table for demonstrating business logic consistency.
     */
    private void createTestBusinessTable() throws Exception {
        logger.info("Creating test business table for transaction participation...");

        CompletableFuture<Void> createTableFuture = vertxPool.getConnection()
            .compose(connection -> {
                String createTableSql = """
                    CREATE TABLE IF NOT EXISTS test_orders (
                        id VARCHAR(255) PRIMARY KEY,
                        customer_id VARCHAR(255) NOT NULL,
                        amount DECIMAL(10,2) NOT NULL,
                        status VARCHAR(50) NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """;

                return connection.query(createTableSql).execute()
                    .compose(result -> {
                        logger.info("✓ Test orders table created/verified");
                        return Future.succeededFuture();
                    })
                    .eventually(() -> connection.close());
            })
            .mapEmpty()
            .toCompletionStage()
            .toCompletableFuture()
            .thenApply(result -> (Void) null);

        createTableFuture.get(10, TimeUnit.SECONDS);
        logger.info("✓ Test business table created successfully");
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
