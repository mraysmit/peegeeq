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
import dev.mars.peegeeq.native.pgqueue.VertxPoolAdapter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * Integration test demonstrating Advanced Transaction Participation pattern from PeeGeeQ Guide.
 * 
 * This test validates the second reactive approach: sendInTransaction() operations
 * following the patterns outlined in Section "2. Transaction Participation".
 * 
 * Key Features Demonstrated:
 * - Joining existing Vert.x SqlConnection transactions
 * - Business logic + outbox operations in same transaction
 * - Transaction rollback scenarios with proper cleanup
 * - Multiple operations within single transaction context
 * - Real database operations with transactional consistency
 * 
 * Requirements:
 * - Docker must be available for TestContainers
 * - Test validates actual database operations and transaction participation
 * 
 * Test Scenarios:
 * - Simple transaction participation with SqlConnection
 * - Transaction participation with headers and metadata
 * - Multiple outbox operations in same transaction
 * - Transaction rollback scenarios
 * - Business logic + outbox consistency validation
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-06
 * @version 1.0
 */
@Testcontainers
class TransactionParticipationAdvancedExampleTest {
    private static final Logger logger = LoggerFactory.getLogger(TransactionParticipationAdvancedExampleTest.class);
    
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_tx_participation_advanced")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);
    
    private PeeGeeQManager manager;
    private QueueFactory outboxFactory;
    private OutboxProducer<OrderEvent> orderProducer;
    private Vertx vertx;
    private Pool vertxPool;
    
    @BeforeEach
    void setUp() throws Exception {
        logger.info("Setting up TransactionParticipationAdvancedExampleTest");
        logger.info("Container started: {}:{}", postgres.getHost(), postgres.getFirstMappedPort());
        
        // Configure system properties from TestContainers - following established pattern
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        
        // Initialize PeeGeeQ Manager - following established pattern
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        manager.start();
        logger.info("PeeGeeQ Manager started successfully");
        
        // Create outbox factory - following established pattern
        DatabaseService databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();
        
        // Register outbox factory implementation
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        
        outboxFactory = provider.createFactory("outbox", databaseService);
        orderProducer = (OutboxProducer<OrderEvent>) outboxFactory.createProducer("orders", OrderEvent.class);
        
        // Create Vert.x instance and Pool for SqlConnection operations
        vertx = Vertx.vertx();
        
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());
        
        PoolOptions poolOptions = new PoolOptions()
            .setMaxSize(10);
        
        vertxPool = PgBuilder.pool()
            .with(poolOptions)
            .connectingTo(connectOptions)
            .using(vertx)
            .build();
        
        logger.info("Vert.x Pool created for SqlConnection operations");
        
        // Create test business table for transaction validation
        createBusinessTable();
        
        logger.info("Test setup completed successfully");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        logger.info("Tearing down TransactionParticipationAdvancedExampleTest");
        
        if (orderProducer != null) {
            orderProducer.close();
            logger.info("Order producer closed");
        }
        
        if (vertxPool != null) {
            vertxPool.close();
            logger.info("Vert.x Pool closed");
        }
        
        if (vertx != null) {
            vertx.close();
            logger.info("Vert.x instance closed");
        }
        
        if (manager != null) {
            manager.stop();
            logger.info("PeeGeeQ Manager stopped");
        }
        
        logger.info("Test teardown completed");
    }
    
    /**
     * Creates a test business table for transaction validation
     */
    private void createBusinessTable() throws Exception {
        CompletableFuture<Void> future = vertxPool.getConnection()
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
            .mapEmpty();
        
        future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        logger.info("Test business table created successfully");
    }
    
    /**
     * Test Step 1: Verify infrastructure setup
     * Principle: "Validate Each Step" - Test infrastructure before functionality
     */
    @Test
    void testInfrastructureSetup() {
        logger.info("=== Testing Infrastructure Setup ===");
        
        // Verify container is running
        assertTrue(postgres.isRunning(), "PostgreSQL container should be running");
        logger.info("✓ Container is running: {}:{}", postgres.getHost(), postgres.getFirstMappedPort());
        
        // Verify manager is started
        assertNotNull(manager, "PeeGeeQ Manager should be initialized");
        logger.info("✓ PeeGeeQ Manager is initialized");
        
        // Verify factory is created
        assertNotNull(outboxFactory, "Outbox factory should be created");
        logger.info("✓ Outbox factory is created");
        
        // Verify producer is created and supports transaction methods
        assertNotNull(orderProducer, "Order producer should be created");
        assertTrue(orderProducer instanceof OutboxProducer, "Producer should be OutboxProducer for transaction methods");
        logger.info("✓ Order producer is created and supports transaction methods");
        
        // Verify Vert.x components
        assertNotNull(vertx, "Vert.x instance should be created");
        assertNotNull(vertxPool, "Vert.x Pool should be created");
        logger.info("✓ Vert.x components are initialized");
        
        logger.info("=== Infrastructure Setup Test PASSED ===");
    }

    /**
     * Test Step 2: Simple transaction participation with SqlConnection
     * Principle: "Validate Each Step" - Test basic sendInTransaction functionality
     *
     * This demonstrates the second reactive approach from the guide:
     * "Transaction Participation (sendInTransaction) - Join existing transactions"
     */
    @Test
    void testSimpleTransactionParticipation() throws Exception {
        logger.info("=== Testing Simple Transaction Participation ===");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("TX-ORDER-001", "CUSTOMER-TX-123", 199.99);
        logger.info("Created test order: {}", testOrder);

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
                                return orderProducer.sendInTransaction(testOrder, connection)
                                    .toCompletionStage().toCompletableFuture()
                                    .thenCompose(v -> {
                                        logger.info("✓ Outbox message sent in transaction");
                                        
                                        // Commit the transaction
                                        return transaction.commit().toCompletionStage().toCompletableFuture();
                                    });
                            });
                    })
                    .eventually(v -> connection.close());
            })
            .toCompletionStage().toCompletableFuture();

        // Wait for completion with timeout
        assertDoesNotThrow(() -> transactionFuture.get(15, TimeUnit.SECONDS));
        logger.info("✓ Simple transaction participation completed successfully");

        // Give a moment for any background processing
        Thread.sleep(1000);

        logger.info("=== Simple Transaction Participation Test PASSED ===");
    }

    /**
     * Test Step 3: Transaction participation with headers and metadata
     * Principle: "Validate Each Step" - Test sendInTransaction with full parameters
     *
     * This demonstrates the full sendInTransaction method from the guide:
     * "sendInTransaction(payload, headers, correlationId, messageGroup, sqlConnection)"
     */
    @Test
    void testTransactionParticipationWithMetadata() throws Exception {
        logger.info("=== Testing Transaction Participation with Headers and Metadata ===");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("TX-ORDER-002", "CUSTOMER-TX-456", 299.99);
        logger.info("Created test order: {}", testOrder);

        // Create headers map
        Map<String, String> headers = new HashMap<>();
        headers.put("source", "order-service");
        headers.put("transaction-type", "participation");
        headers.put("version", "1.0");
        logger.info("Created headers: {}", headers);

        // Create correlation ID and message group
        String correlationId = "TX-CORR-" + UUID.randomUUID().toString().substring(0, 8);
        String messageGroup = "tx-order-group";
        logger.info("Created correlation ID: {}", correlationId);
        logger.info("Created message group: {}", messageGroup);

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
                                return orderProducer.sendInTransaction(testOrder, headers, correlationId, messageGroup, connection)
                                    .toCompletionStage().toCompletableFuture()
                                    .thenCompose(v -> {
                                        logger.info("✓ Outbox message sent with metadata in transaction");
                                        logger.info("✓ Used correlation ID: {}", correlationId);
                                        logger.info("✓ Used message group: {}", messageGroup);

                                        // Commit the transaction
                                        return transaction.commit().toCompletionStage().toCompletableFuture();
                                    });
                            });
                    })
                    .eventually(v -> connection.close());
            })
            .toCompletionStage().toCompletableFuture();

        // Wait for completion with timeout
        assertDoesNotThrow(() -> transactionFuture.get(15, TimeUnit.SECONDS));
        logger.info("✓ Transaction participation with metadata completed successfully");

        // Give a moment for any background processing
        Thread.sleep(1000);

        logger.info("=== Transaction Participation with Headers and Metadata Test PASSED ===");
    }

    /**
     * Test Step 4: Multiple outbox operations in same transaction
     * Principle: "Validate Each Step" - Test multiple sendInTransaction calls in one transaction
     *
     * This demonstrates the transaction consistency from the guide:
     * "Multiple operations within logical boundary commit/rollback together"
     */
    @Test
    void testMultipleOperationsInSameTransaction() throws Exception {
        logger.info("=== Testing Multiple Operations in Same Transaction ===");

        // Create multiple test order events
        OrderEvent order1 = new OrderEvent("TX-ORDER-003A", "CUSTOMER-TX-789", 150.00);
        OrderEvent order2 = new OrderEvent("TX-ORDER-003B", "CUSTOMER-TX-789", 250.00);
        logger.info("Created test orders: {} and {}", order1, order2);

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
                                return orderProducer.sendInTransaction(order1, connection)
                                    .toCompletionStage().toCompletableFuture()
                                    .thenCompose(v -> {
                                        logger.info("✓ First outbox message sent in transaction");

                                        // Insert second business record
                                        Tuple params2 = Tuple.of(order2.getOrderId(), order2.getCustomerId(), order2.getAmount(), "PROCESSING");
                                        return connection.preparedQuery(insertSql).execute(params2);
                                    });
                            })
                            .compose(result2 -> {
                                logger.info("✓ Second business record inserted: {} rows affected", result2.rowCount());

                                // Send second outbox message
                                return orderProducer.sendInTransaction(order2, connection)
                                    .toCompletionStage().toCompletableFuture()
                                    .thenCompose(v -> {
                                        logger.info("✓ Second outbox message sent in transaction");

                                        // Commit the transaction - all operations commit together
                                        return transaction.commit().toCompletionStage().toCompletableFuture();
                                    });
                            });
                    })
                    .eventually(v -> connection.close());
            })
            .toCompletionStage().toCompletableFuture();

        // Wait for completion with timeout
        assertDoesNotThrow(() -> transactionFuture.get(20, TimeUnit.SECONDS));
        logger.info("✓ Multiple operations in same transaction completed successfully");

        // Give a moment for any background processing
        Thread.sleep(1000);

        logger.info("=== Multiple Operations in Same Transaction Test PASSED ===");
    }

    /**
     * Test Step 5: Transaction rollback scenario
     * Principle: "Honest Error Handling" - Test real failure scenarios, don't hide them
     *
     * This demonstrates the transaction rollback from the guide:
     * "Automatic rollback on failure - all operations rollback together"
     */
    @Test
    void testTransactionRollbackScenario() throws Exception {
        logger.info("=== Testing Transaction Rollback Scenario ===");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("TX-ORDER-004", "CUSTOMER-TX-999", 399.99);
        logger.info("Created test order: {}", testOrder);

        // Use Vert.x Pool to get SqlConnection and perform transaction that will fail
        CompletableFuture<Void> transactionFuture = vertxPool.getConnection()
            .compose(connection -> {
                logger.info("✓ Got SqlConnection for rollback scenario");

                // Start transaction
                return connection.begin()
                    .compose(transaction -> {
                        logger.info("✓ Transaction started for rollback test");

                        // Insert business data in transaction
                        String insertSql = "INSERT INTO test_orders (id, customer_id, amount, status) VALUES ($1, $2, $3, $4)";
                        Tuple params = Tuple.of(testOrder.getOrderId(), testOrder.getCustomerId(), testOrder.getAmount(), "PROCESSING");

                        return connection.preparedQuery(insertSql).execute(params)
                            .compose(result -> {
                                logger.info("✓ Business data inserted: {} rows affected", result.rowCount());

                                // Send outbox message in transaction
                                return orderProducer.sendInTransaction(testOrder, connection)
                                    .toCompletionStage().toCompletableFuture()
                                    .thenCompose(v -> {
                                        logger.info("✓ Outbox message sent in transaction");

                                        // Simulate a failure that causes rollback
                                        logger.info("⚠ Simulating failure to trigger rollback");
                                        return transaction.rollback().toCompletionStage().toCompletableFuture();
                                    });
                            });
                    })
                    .eventually(v -> connection.close());
            })
            .toCompletionStage().toCompletableFuture();

        // Wait for completion - this should succeed (rollback is successful)
        assertDoesNotThrow(() -> transactionFuture.get(15, TimeUnit.SECONDS));
        logger.info("✓ Transaction rollback scenario completed successfully");

        // Verify that the business data was rolled back by checking it doesn't exist
        CompletableFuture<Integer> verifyFuture = vertxPool.getConnection()
            .compose(connection -> {
                String selectSql = "SELECT COUNT(*) FROM test_orders WHERE id = $1";
                Tuple params = Tuple.of(testOrder.getOrderId());

                return connection.preparedQuery(selectSql).execute(params)
                    .map(result -> result.iterator().next().getInteger(0))
                    .eventually(v -> connection.close());
            })
            .toCompletionStage().toCompletableFuture();

        Integer count = verifyFuture.get(10, TimeUnit.SECONDS);
        assertEquals(0, count, "Business data should have been rolled back");
        logger.info("✓ Verified business data was rolled back (count: {})", count);

        // Give a moment for any background processing
        Thread.sleep(1000);

        logger.info("=== Transaction Rollback Scenario Test PASSED ===");
    }

    /**
     * Test Step 6: Transaction participation progression test
     * Principle: "Iterative Validation" - Test each step builds on the previous
     *
     * This demonstrates progressive complexity following the guide patterns
     */
    @Test
    void testTransactionParticipationProgression() throws Exception {
        logger.info("=== Testing Transaction Participation Progression ===");

        // Step 1: Simple participation - verify it works
        OrderEvent order1 = new OrderEvent("PROG-TX-001", "CUSTOMER-PROG-001", 50.00);
        CompletableFuture<Void> step1Future = performSimpleTransactionParticipation(order1);
        assertDoesNotThrow(() -> step1Future.get(10, TimeUnit.SECONDS));
        logger.info("✅ Step 1: Simple transaction participation successful");

        // Step 2: Participation with headers - verify metadata works
        OrderEvent order2 = new OrderEvent("PROG-TX-002", "CUSTOMER-PROG-002", 75.00);
        Map<String, String> headers = Map.of("source", "progression-test", "version", "1.0");
        CompletableFuture<Void> step2Future = performTransactionParticipationWithHeaders(order2, headers);
        assertDoesNotThrow(() -> step2Future.get(10, TimeUnit.SECONDS));
        logger.info("✅ Step 2: Transaction participation with headers successful");

        // Step 3: Full parameters - verify complete functionality
        OrderEvent order3 = new OrderEvent("PROG-TX-003", "CUSTOMER-PROG-003", 100.00);
        String correlationId = "PROG-TX-CORR-123";
        String messageGroup = "prog-tx-group";
        CompletableFuture<Void> step3Future = performFullTransactionParticipation(order3, headers, correlationId, messageGroup);
        assertDoesNotThrow(() -> step3Future.get(10, TimeUnit.SECONDS));
        logger.info("✅ Step 3: Full parameter transaction participation successful");

        logger.info("=== Transaction Participation Progression Test PASSED ===");
    }

    /**
     * Helper method for simple transaction participation
     */
    private CompletableFuture<Void> performSimpleTransactionParticipation(OrderEvent order) {
        return vertxPool.getConnection()
            .compose(connection -> connection.begin()
                .compose(transaction -> {
                    String insertSql = "INSERT INTO test_orders (id, customer_id, amount, status) VALUES ($1, $2, $3, $4)";
                    Tuple params = Tuple.of(order.getOrderId(), order.getCustomerId(), order.getAmount(), "PROCESSING");

                    return connection.preparedQuery(insertSql).execute(params)
                        .compose(result -> orderProducer.sendInTransaction(order, connection)
                            .toCompletionStage().toCompletableFuture()
                            .thenCompose(v -> transaction.commit().toCompletionStage().toCompletableFuture()));
                })
                .eventually(v -> connection.close()))
            .toCompletionStage().toCompletableFuture();
    }

    /**
     * Helper method for transaction participation with headers
     */
    private CompletableFuture<Void> performTransactionParticipationWithHeaders(OrderEvent order, Map<String, String> headers) {
        return vertxPool.getConnection()
            .compose(connection -> connection.begin()
                .compose(transaction -> {
                    String insertSql = "INSERT INTO test_orders (id, customer_id, amount, status) VALUES ($1, $2, $3, $4)";
                    Tuple params = Tuple.of(order.getOrderId(), order.getCustomerId(), order.getAmount(), "PROCESSING");

                    return connection.preparedQuery(insertSql).execute(params)
                        .compose(result -> orderProducer.sendInTransaction(order, headers, connection)
                            .toCompletionStage().toCompletableFuture()
                            .thenCompose(v -> transaction.commit().toCompletionStage().toCompletableFuture()));
                })
                .eventually(v -> connection.close()))
            .toCompletionStage().toCompletableFuture();
    }

    /**
     * Helper method for full transaction participation
     */
    private CompletableFuture<Void> performFullTransactionParticipation(OrderEvent order, Map<String, String> headers,
                                                                        String correlationId, String messageGroup) {
        return vertxPool.getConnection()
            .compose(connection -> connection.begin()
                .compose(transaction -> {
                    String insertSql = "INSERT INTO test_orders (id, customer_id, amount, status) VALUES ($1, $2, $3, $4)";
                    Tuple params = Tuple.of(order.getOrderId(), order.getCustomerId(), order.getAmount(), "PROCESSING");

                    return connection.preparedQuery(insertSql).execute(params)
                        .compose(result -> orderProducer.sendInTransaction(order, headers, correlationId, messageGroup, connection)
                            .toCompletionStage().toCompletableFuture()
                            .thenCompose(v -> transaction.commit().toCompletionStage().toCompletableFuture()));
                })
                .eventually(v -> connection.close()))
            .toCompletionStage().toCompletableFuture();
    }

    /**
     * Simple event class for testing - following established pattern
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
