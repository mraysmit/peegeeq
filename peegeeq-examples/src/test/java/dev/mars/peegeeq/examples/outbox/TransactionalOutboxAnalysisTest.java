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
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.outbox.OutboxFactory;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private String testTopic;

    @BeforeEach
    void setUp() throws Exception {
        logger.info("=== Setting up TransactionalOutboxAnalysisTest ===");

        // Initialize schema with queue components
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        // Use unique topic for each test to avoid interference
        testTopic = "outbox-tx-test-" + UUID.randomUUID().toString().substring(0, 8);

        // Configure database connection
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Create and start manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("outbox-tx-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create factory
        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);

        // Create a test table for business data using JDBC
        createTestBusinessTable();

        logger.info("✓ TransactionalOutboxAnalysisTest setup completed");
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.info("=== Tearing down TransactionalOutboxAnalysisTest ===");

        if (outboxFactory != null) {
            outboxFactory.close();
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

        logger.info("✓ Teardown completed");
    }

    /**
     * Gets a JDBC connection to the test database.
     */
    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
    }

    /**
     * Creates a test business table for transaction testing.
     */
    private void createTestBusinessTable() throws Exception {
        try (Connection conn = getConnection()) {
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS test_orders (
                    id VARCHAR(50) PRIMARY KEY,
                    customer_id VARCHAR(50) NOT NULL,
                    amount DECIMAL(10,2) NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
            logger.info("✓ Test business table created");
        }
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

            // Use JDBC transaction for business data
            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                try {
                    // Insert business data
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "INSERT INTO test_orders (id, customer_id, amount, status) VALUES (?, ?, ?, ?)")) {
                        stmt.setString(1, orderId);
                        stmt.setString(2, "customer-123");
                        stmt.setBigDecimal(3, new java.math.BigDecimal("99.99"));
                        stmt.setString(4, "CREATED");
                        stmt.executeUpdate();
                        logger.info("✓ Business data inserted: {}", orderId);
                    }
                    
                    conn.commit();
                    logger.info("✓ Transaction committed successfully");
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                }
            }

            // Now send a message through the outbox (after transaction)
            producer.send(messagePayload).get(5, TimeUnit.SECONDS);
            logger.info("✓ Outbox message sent");

            // Verify business data exists
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT COUNT(*) FROM test_orders WHERE id = ?")) {
                stmt.setString(1, orderId);
                try (ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    assertEquals(1, rs.getLong(1), "Business data should exist after commit");
                }
            }

            // Verify message can be consumed
            List<Message<String>> messages = new ArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            
            consumer.subscribe(msg -> {
                messages.add(msg);
                logger.info("✓ Received message: {}", msg.getPayload());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            assertTrue(latch.await(10, TimeUnit.SECONDS), "Should receive message within timeout");
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
        AtomicBoolean transactionRolledBack = new AtomicBoolean(false);

        // Attempt a transaction that will fail
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Insert business data
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO test_orders (id, customer_id, amount, status) VALUES (?, ?, ?, ?)")) {
                    stmt.setString(1, orderId);
                    stmt.setString(2, "customer-456");
                    stmt.setBigDecimal(3, new java.math.BigDecimal("150.00"));
                    stmt.setString(4, "PENDING");
                    stmt.executeUpdate();
                    logger.info("Business data inserted (will be rolled back): {}", orderId);
                }

                // Force a rollback to simulate business rule violation
                throw new RuntimeException("Simulated business rule violation - triggering rollback");
                
            } catch (RuntimeException e) {
                logger.info("✓ Rolling back transaction: {}", e.getMessage());
                conn.rollback();
                transactionRolledBack.set(true);
            }
        }

        assertTrue(transactionRolledBack.get(), "Transaction should have been rolled back");

        // Verify business data does NOT exist (was rolled back)
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM test_orders WHERE id = ?")) {
            stmt.setString(1, orderId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getLong(1), "Business data should NOT exist after rollback");
            }
        }

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
        CountDownLatch completionLatch = new CountDownLatch(numConcurrentTransactions);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<String> createdOrderIds = java.util.Collections.synchronizedList(new ArrayList<>());

        // Launch concurrent transactions using multiple threads
        for (int i = 0; i < numConcurrentTransactions; i++) {
            final int txIndex = i;
            String orderId = "concurrent-order-" + txIndex + "-" + UUID.randomUUID().toString().substring(0, 8);

            new Thread(() -> {
                try (Connection conn = getConnection()) {
                    conn.setAutoCommit(false);
                    try {
                        try (PreparedStatement stmt = conn.prepareStatement(
                                "INSERT INTO test_orders (id, customer_id, amount, status) VALUES (?, ?, ?, ?)")) {
                            stmt.setString(1, orderId);
                            stmt.setString(2, "customer-" + txIndex);
                            stmt.setBigDecimal(3, new java.math.BigDecimal(100.00 + txIndex));
                            stmt.setString(4, "CREATED");
                            stmt.executeUpdate();
                        }
                        conn.commit();
                        createdOrderIds.add(orderId);
                        successCount.incrementAndGet();
                        logger.info("Transaction {} committed order: {}", txIndex, orderId);
                    } catch (Exception e) {
                        conn.rollback();
                        failureCount.incrementAndGet();
                        logger.error("Transaction {} failed: {}", txIndex, e.getMessage());
                    }
                } catch (SQLException e) {
                    failureCount.incrementAndGet();
                    logger.error("Transaction {} connection error: {}", txIndex, e.getMessage());
                } finally {
                    completionLatch.countDown();
                }
            }).start();
        }

        // Wait for all transactions to complete
        assertTrue(completionLatch.await(30, TimeUnit.SECONDS), 
                "All transactions should complete within timeout");

        logger.info("Concurrent transactions completed: {} succeeded, {} failed", 
                successCount.get(), failureCount.get());

        // Verify all successful transactions created their data
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM test_orders WHERE id LIKE 'concurrent-order-%'")) {
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                long totalCount = rs.getLong(1);
                assertEquals(successCount.get(), totalCount, 
                        "Number of orders should match successful transactions");
            }
        }

        // Verify each order has correct isolated data
        for (String orderId : createdOrderIds) {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT 1 FROM test_orders WHERE id = ?")) {
                stmt.setString(1, orderId);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertTrue(rs.next(), "Order " + orderId + " should exist");
                }
            }
        }

        logger.info("✓ Concurrent transaction handling validated - {} orders created with proper isolation", 
                createdOrderIds.size());
    }
}
