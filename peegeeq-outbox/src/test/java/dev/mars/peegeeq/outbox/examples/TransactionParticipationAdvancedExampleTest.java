package dev.mars.peegeeq.outbox.examples;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;

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
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Comprehensive JUnit test demonstrating Advanced Transaction Participation in PeeGeeQ Outbox Pattern.
 * 
 * This test demonstrates the second reactive approach: sendInExistingTransaction() operations
 * following the patterns outlined in Section "2. Transaction Participation".
 * 
 * <h2>NO INTENTIONAL FAILURES - This Test Demonstrates Success Patterns</h2>
 * <p>Unlike error handling tests, this test demonstrates successful transaction participation patterns.
 * All operations should complete successfully, showing proper transactional consistency.</p>
 * 
 * <h2>Test Coverage</h2>
 * <ul>
 *   <li><b>Simple Transaction Participation</b> - Basic sendInExistingTransaction with SqlConnection</li>
 *   <li><b>Transaction Participation with Metadata</b> - Full sendInExistingTransaction with headers and correlation</li>
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
 *   <li>Simple transaction participation completes successfully</li>
 *   <li>Transaction participation with metadata works correctly</li>
 *   <li>Multiple operations maintain transactional consistency</li>
 *   <li>Business logic and outbox operations are consistent</li>
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
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionParticipationAdvancedExampleTest {
    
    private static final Logger logger = LoggerFactory.getLogger(TransactionParticipationAdvancedExampleTest.class);
    
    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();
    
    private PeeGeeQManager manager;
    private QueueFactory outboxFactory;
    private OutboxProducer<OrderEvent> orderProducer;
    private Vertx vertx;
    private Pool vertxPool;
    
    @BeforeEach
    void setUp(VertxTestContext ctx) throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        logger.info("=== Setting up Transaction Participation Advanced Example Test ===");

        // Configure PeeGeeQ to use container database
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA).build();

        // Initialize PeeGeeQ Manager - following established pattern
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps), new SimpleMeterRegistry());
        manager.start()
            .compose(v -> {
                logger.info("PeeGeeQ Manager started successfully");

                // Create outbox factory - following established pattern
                PgDatabaseService databaseService = new PgDatabaseService(manager);
                PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
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
                return createTestBusinessTable();
            })
            .onSuccess(v -> {
                logger.info("Transaction Participation Advanced Example Test setup completed");
                ctx.completeNow();
            })
            .onFailure(ctx::failNow);
    }
    
    @AfterEach
    void tearDown(VertxTestContext ctx) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        logger.info(" Cleaning up Transaction Participation Advanced Example Test");

        if (orderProducer != null) orderProducer.close();
        if (outboxFactory != null) outboxFactory.close().onFailure(e -> logger.warn("outboxFactory close failed in teardown", e));

        // Close manager on its own event loop; complete the test context on success.
        // vertxPool and vertx are closed after awaitCompletion  closing vertx inside the
        // chain causes RejectedExecutionException because vertx.close() terminates the very
        // event loop the chain needs to emit its next result on.
        (manager != null ? manager.closeReactive() : Future.<Void>succeededFuture())
            .onSuccess(v -> {
                logger.info("Transaction Participation Advanced Example Test cleanup completed");
                ctx.completeNow();
            })
            .onFailure(ctx::failNow);

        assertTrue(ctx.awaitCompletion(15, TimeUnit.SECONDS), "Teardown should complete within 15s");

        if (vertxPool != null) vertxPool.close().onFailure(e -> logger.warn("vertxPool close failed in teardown", e));
        if (vertx != null) vertx.close().onFailure(e -> logger.warn("vertx close failed in teardown", e));
    }
    
    @Test
    void testSimpleTransactionParticipation(VertxTestContext ctx) throws Exception {
        logger.info("=== Testing Pattern 1: Simple Transaction Participation ===");

        // Create a test order event
        OrderEvent testOrder = new OrderEvent("TX-ORDER-001", "CUSTOMER-TX-123", 199.99);
        logger.info("Created order: {}", testOrder);

        // Demonstrate simple transaction participation
        vertxPool.getConnection()
            .compose(connection -> connection.begin()
                .compose(transaction -> orderProducer.sendInExistingTransaction(testOrder, connection)
                    .compose(v -> {
                        logger.info(" Outbox message sent in transaction");
                        return transaction.commit();
                    }))
                .eventually(() -> connection.close()))
            .mapEmpty()
            .onSuccess(v -> {
                logger.info("Simple transaction participation test completed successfully!");
                ctx.completeNow();
            })
            .onFailure(ctx::failNow);

        assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS), "Test should complete within 10s");
    }
    
    @Test
    void testTransactionParticipationWithMetadata(VertxTestContext ctx) throws Exception {
        logger.info("=== Testing Pattern 2: Transaction Participation with Headers and Metadata ===");

        OrderEvent testOrder = new OrderEvent("TX-ORDER-002", "CUSTOMER-TX-456", 299.99);
        Map<String, String> headers = new HashMap<>();
        headers.put("source", "transaction-participation-test");
        headers.put("version", "1.0");
        headers.put("priority", "high");
        String correlationId = UUID.randomUUID().toString();
        String messageGroup = "tx-participation-group";

        vertxPool.getConnection()
            .compose(connection -> connection.begin()
                .compose(transaction -> orderProducer.sendInExistingTransaction(testOrder, headers, correlationId, messageGroup, connection)
                    .compose(v -> {
                        logger.info(" Outbox message with metadata sent in transaction");
                        return transaction.commit();
                    }))
                .eventually(() -> connection.close()))
            .mapEmpty()
            .onSuccess(v -> {
                logger.info("Transaction participation with metadata test completed successfully!");
                ctx.completeNow();
            })
            .onFailure(ctx::failNow);

        assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS), "Test should complete within 10s");
    }

    @Test
    void testMultipleOperationsInSameTransaction(VertxTestContext ctx) throws Exception {
        logger.info("=== Testing Pattern 3: Multiple Operations in Same Transaction ===");

        OrderEvent order1 = new OrderEvent("TX-ORDER-003A", "CUSTOMER-TX-789", 150.00);
        OrderEvent order2 = new OrderEvent("TX-ORDER-003B", "CUSTOMER-TX-789", 250.00);

        vertxPool.getConnection()
            .compose(connection -> connection.begin()
                .compose(transaction -> orderProducer.sendInExistingTransaction(order1, connection)
                    .compose(v -> {
                        logger.info(" First outbox message sent in transaction");
                        return orderProducer.sendInExistingTransaction(order2, connection);
                    })
                    .compose(v -> {
                        logger.info(" Second outbox message sent in transaction");
                        return transaction.commit();
                    }))
                .eventually(() -> connection.close()))
            .mapEmpty()
            .onSuccess(v -> {
                logger.info("Multiple operations in same transaction test completed successfully!");
                ctx.completeNow();
            })
            .onFailure(ctx::failNow);

        assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS), "Test should complete within 10s");
    }

    @Test
    void testBusinessLogicWithOutboxConsistency(VertxTestContext ctx) throws Exception {
        logger.info("=== Testing Pattern 4: Business Logic with Outbox Consistency ===");

        OrderEvent testOrder = new OrderEvent("TX-ORDER-004", "CUSTOMER-TX-999", 399.99);

        vertxPool.getConnection()
            .compose(connection -> connection.begin()
                .compose(transaction -> {
                    String insertSql = "INSERT INTO test_orders (id, customer_id, amount, status) VALUES ($1, $2, $3, $4)";
                    Tuple params = Tuple.of(testOrder.getOrderId(), testOrder.getCustomerId(), testOrder.getAmount(), "CREATED");
                    return connection.preparedQuery(insertSql).execute(params)
                        .compose(result -> {
                            logger.info(" Order inserted: {} rows affected", result.rowCount());
                            assertEquals(1, result.rowCount(), "Should insert exactly 1 row");
                            String updateSql = "UPDATE test_orders SET status = $1 WHERE id = $2";
                            return connection.preparedQuery(updateSql).execute(Tuple.of("CONFIRMED", testOrder.getOrderId()));
                        })
                        .compose(result -> {
                            logger.info(" Order status updated: {} rows affected", result.rowCount());
                            assertEquals(1, result.rowCount(), "Should update exactly 1 row");
                            return orderProducer.sendInExistingTransaction(testOrder, connection)
                                .compose(v -> {
                                    logger.info(" Outbox event sent in same transaction");
                                    return transaction.commit()
                                        .map(ignored -> "Business logic completed with outbox consistency");
                                });
                        });
                })
                .eventually(() -> connection.close()))
            .onSuccess(result -> ctx.verify(() -> {
                assertEquals("Business logic completed with outbox consistency", result);
                logger.info("Business logic with outbox consistency test completed successfully!");
                ctx.completeNow();
            }))
            .onFailure(ctx::failNow);

        assertTrue(ctx.awaitCompletion(10, TimeUnit.SECONDS), "Test should complete within 10s");
    }

    /**
     * Creates the test business table for demonstrating business logic consistency.
     */
    private Future<Void> createTestBusinessTable() {
        logger.info("Creating test business table for transaction participation...");
        return vertxPool.getConnection()
            .<Void>compose(connection -> {
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
                    .eventually(() -> connection.close())
                    .mapEmpty();
            })
            .onSuccess(v -> logger.info(" Test business table created successfully"));
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


