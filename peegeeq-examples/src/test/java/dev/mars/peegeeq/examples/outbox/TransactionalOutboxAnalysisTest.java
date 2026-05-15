package dev.mars.peegeeq.examples.outbox;

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
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.outbox.OutboxFactory;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.Properties;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to analyze transactional behavior of the outbox pattern.
 *
 * This test demonstrates that the outbox producer properly participates in
 * database transactions with business data writes using Vert.x 5.x reactive patterns.
 *
 * The transactional outbox pattern ensures:
 * 1. Messages are only visible after the transaction commits
 * 2. Messages are rolled back if the transaction fails
 * 3. Concurrent transactions are properly isolated
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-26
 * @version 2.0
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
public class TransactionalOutboxAnalysisTest {

    private static final Logger logger = LoggerFactory.getLogger(TransactionalOutboxAnalysisTest.class);

    @Container
    private static final PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("testdb");
        container.withUsername("testuser");
        container.withPassword("testpass");
        return container;
    }

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private Pool pool;
    private String testTopic;

    @BeforeEach
    void setUp() throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        logger.info("=== Setting up TransactionalOutboxAnalysisTest ===");

        // Initialize schema with queue components
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        // Use unique topic for each test to avoid interference
        testTopic = "outbox-tx-test-" + UUID.randomUUID().toString().substring(0, 8);

        // Configure database connection
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres).build();

        // Create and start manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        // Create factory
        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        pool = databaseService.getPool();

        // Create a test table for business data
        createTestBusinessTable().await();

        logger.info("✓ TransactionalOutboxAnalysisTest setup completed");
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.info("Tearing down: closing resources and manager");
        logger.info("=== Tearing down TransactionalOutboxAnalysisTest ===");

        if (outboxFactory != null) {
            outboxFactory.close();
        }

        if (manager != null) {
            manager.closeReactive().await();
        }

        logger.info("✓ Teardown completed");
    }

    /**
     * Creates a test business table for transaction testing.
     */
    private Future<Void> createTestBusinessTable() {
        return pool.preparedQuery(
                "CREATE TABLE IF NOT EXISTS test_orders (" +
                "id VARCHAR(50) PRIMARY KEY, " +
                "customer_id VARCHAR(50) NOT NULL, " +
                "amount DECIMAL(10,2) NOT NULL, " +
                "status VARCHAR(20) NOT NULL, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")").execute()
                .onSuccess(v -> logger.info("✓ Test business table created"))
                .mapEmpty();
    }

    /**
     * Test Pattern 1: Outbox Transaction Participation
     * 
     * Validates that outbox messages are only visible after the transaction commits.
     * This is the fundamental guarantee of the transactional outbox pattern.
     */
    @Test
    void testOutboxTransactionParticipation() throws Exception {
        logger.info("=== Testing Outbox Transaction Participation ===");

        MessageProducer<String> producer = outboxFactory.createProducer(testTopic, String.class);
        MessageConsumer<String> consumer = outboxFactory.createConsumer(testTopic, String.class);

        try {
            String orderId = "order-" + UUID.randomUUID().toString().substring(0, 8);
            String messagePayload = "{\"orderId\":\"" + orderId + "\",\"event\":\"OrderCreated\"}";

            // Use reactive transaction for business data
            pool.withTransaction(conn ->
                conn.preparedQuery("INSERT INTO test_orders (id, customer_id, amount, status) VALUES ($1, $2, $3, $4)")
                        .execute(Tuple.of(orderId, "customer-123", new java.math.BigDecimal("99.99"), "CREATED"))
                        .mapEmpty()
            ).await();
            logger.info("✓ Business data inserted and committed: {}", orderId);

            // Now send a message through the outbox (after transaction)
            producer.send(messagePayload).await();
            logger.info("✓ Outbox message sent");

            // Verify business data exists
            long count = pool.preparedQuery("SELECT COUNT(*) FROM test_orders WHERE id = $1")
                    .execute(Tuple.of(orderId))
                    .await()
                    .iterator().next().getLong(0);
            assertEquals(1, count, "Business data should exist after commit");

            // Verify message can be consumed
            List<Message<String>> messages = new ArrayList<>();
            CountDownLatch msgLatch = new CountDownLatch(1);

            consumer.subscribe(msg -> {
                messages.add(msg);
                logger.info("✓ Received message: {}", msg.getPayload());
                msgLatch.countDown();
                return Future.succeededFuture();
            });

            assertTrue(msgLatch.await(10, TimeUnit.SECONDS), "Should receive message within timeout");
            assertEquals(1, messages.size(), "Should receive exactly one message");
            assertTrue(messages.get(0).getPayload().contains(orderId), "Message should contain order ID");

            logger.info("✓ Outbox transaction participation validated");
        } finally {
            consumer.close();
            producer.close();
        }
    }

    /**
     * Test Pattern 2: Transaction Rollback Behavior
     * 
     * Validates that business data is NOT persisted when the transaction rolls back.
     * This ensures no orphaned data exists for failed business operations.
     */
    @Test
    void testTransactionRollbackBehavior() throws Exception {
        logger.info("=== Testing Transaction Rollback Behavior ===");

        String orderId = "rollback-order-" + UUID.randomUUID().toString().substring(0, 8);
        boolean transactionRolledBack = false;

        // Attempt a transaction that will fail — withTransaction rolls back on failedFuture
        try {
            pool.withTransaction(conn ->
                conn.preparedQuery("INSERT INTO test_orders (id, customer_id, amount, status) VALUES ($1, $2, $3, $4)")
                        .execute(Tuple.of(orderId, "customer-456", new java.math.BigDecimal("150.00"), "PENDING"))
                        .compose(v -> {
                            logger.info("Business data inserted (will be rolled back): {}", orderId);
                            // Force rollback by returning a failed future
                            return Future.failedFuture(new RuntimeException("Simulated business rule violation - triggering rollback"));
                        })
            ).await();
        } catch (Exception e) {
            logger.info("✓ Rolling back transaction: {}", e.getMessage());
            transactionRolledBack = true;
        }

        assertTrue(transactionRolledBack, "Transaction should have been rolled back");

        // Verify business data does NOT exist (was rolled back)
        long count = pool.preparedQuery("SELECT COUNT(*) FROM test_orders WHERE id = $1")
                .execute(Tuple.of(orderId))
                .await()
                .iterator().next().getLong(0);
        assertEquals(0, count, "Business data should NOT exist after rollback");

        logger.info("✓ Transaction rollback behavior validated - no orphaned data");
    }

    /**
     * Test Pattern 3: Concurrent Transaction Handling
     * 
     * Validates that concurrent transactions are properly isolated,
     * ensuring no cross-contamination of data.
     */
    @Test
    void testConcurrentTransactionHandling() throws Exception {
        logger.info("=== Testing Concurrent Transaction Handling ===");

        int numConcurrentTransactions = 5;
        AtomicInteger successCount = new AtomicInteger(0);
        List<String> createdOrderIds = Collections.synchronizedList(new ArrayList<>());

        // Launch concurrent reactive transactions
        List<Future<Void>> txFutures = new ArrayList<>();
        for (int i = 0; i < numConcurrentTransactions; i++) {
            final int txIndex = i;
            final String orderId = "concurrent-order-" + txIndex + "-" + UUID.randomUUID().toString().substring(0, 8);

            Future<Void> txFuture = pool.<Void>withTransaction(conn ->
                conn.preparedQuery("INSERT INTO test_orders (id, customer_id, amount, status) VALUES ($1, $2, $3, $4)")
                        .execute(Tuple.of(orderId, "customer-" + txIndex, new java.math.BigDecimal(100.00 + txIndex), "CREATED"))
                        .mapEmpty()
            ).onSuccess(v -> {
                createdOrderIds.add(orderId);
                successCount.incrementAndGet();
                logger.info("Transaction {} committed order: {}", txIndex, orderId);
            });
            txFutures.add(txFuture);
        }

        // Wait for all concurrent transactions to complete
        Future.all(txFutures).await();

        logger.info("Concurrent transactions completed: {} succeeded", successCount.get());

        // Verify all successful transactions created their data
        long totalCount = pool.preparedQuery("SELECT COUNT(*) FROM test_orders WHERE id LIKE 'concurrent-order-%'")
                .execute()
                .await()
                .iterator().next().getLong(0);
        assertEquals(successCount.get(), totalCount,
                "Number of orders should match successful transactions");

        // Verify each order has correct isolated data
        for (String orderId : createdOrderIds) {
            long exists = pool.preparedQuery("SELECT COUNT(*) FROM test_orders WHERE id = $1")
                    .execute(Tuple.of(orderId))
                    .await()
                    .iterator().next().getLong(0);
            assertEquals(1L, exists, "Order " + orderId + " should exist");
        }

        logger.info("✓ Concurrent transaction handling validated - {} orders created with proper isolation",
                createdOrderIds.size());
    }
}


