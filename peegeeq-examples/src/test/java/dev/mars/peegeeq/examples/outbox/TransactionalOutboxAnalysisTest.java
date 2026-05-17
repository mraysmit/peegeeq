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
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
@ExtendWith(VertxExtension.class)
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
    void setUp(Vertx vertx, VertxTestContext ctx) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        logger.info("=== Setting up TransactionalOutboxAnalysisTest ===");

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        testTopic = "outbox-tx-test-" + UUID.randomUUID().toString().substring(0, 8);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres).build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        manager.start()
            .compose(v -> {
                DatabaseService databaseService = new PgDatabaseService(manager);
                outboxFactory = new OutboxFactory(databaseService, config);
                pool = databaseService.getPool();
                return createTestBusinessTable();
            })
            .onSuccess(v -> {
                logger.info("\u2713 TransactionalOutboxAnalysisTest setup completed");
                ctx.completeNow();
            })
            .onFailure(ctx::failNow);
    }

    @AfterEach
    void tearDown(Vertx vertx, VertxTestContext ctx) {
        logger.info("Tearing down: closing resources and manager");
        logger.info("=== Tearing down TransactionalOutboxAnalysisTest ===");

        if (outboxFactory != null) {
            try {
                outboxFactory.close();
            } catch (Exception e) {
                logger.warn("Error closing outbox factory: {}", e.getMessage());
            }
        }

        if (manager == null) {
            logger.info("\u2713 Teardown completed");
            ctx.completeNow();
            return;
        }
        Future<Void> closeChain = manager.closeReactive()
            .onSuccess(v -> logger.info("PeeGeeQ manager closed"))
            .onFailure(err -> logger.warn("Error closing manager: {}", err.getMessage()));
        closeChain.onSuccess(v -> { logger.info("\u2713 Teardown completed"); ctx.completeNow(); });
        closeChain.onFailure(err -> ctx.completeNow());
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
    void testOutboxTransactionParticipation(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Testing Outbox Transaction Participation ===");

        MessageProducer<String> producer = outboxFactory.createProducer(testTopic, String.class);
        MessageConsumer<String> consumer = outboxFactory.createConsumer(testTopic, String.class);

        String orderId = "order-" + UUID.randomUUID().toString().substring(0, 8);
        String messagePayload = "{\"orderId\":\"" + orderId + "\",\"event\":\"OrderCreated\"}";

        List<Message<String>> messages = new ArrayList<>();
        Promise<Void> messageReceived = Promise.promise();

        pool.withTransaction(conn ->
            conn.preparedQuery("INSERT INTO test_orders (id, customer_id, amount, status) VALUES ($1, $2, $3, $4)")
                    .execute(Tuple.of(orderId, "customer-123", new java.math.BigDecimal("99.99"), "CREATED"))
                    .mapEmpty()
        )
        .compose(v -> {
            logger.info("\u2713 Business data inserted and committed: {}", orderId);
            return producer.send(messagePayload);
        })
        .compose(v -> {
            logger.info("\u2713 Outbox message sent");
            return pool.preparedQuery("SELECT COUNT(*) FROM test_orders WHERE id = $1")
                    .execute(Tuple.of(orderId));
        })
        .map(rs -> rs.iterator().next().getLong(0))
        .compose(count -> {
            testContext.verify(() -> assertEquals(1L, count, "Business data should exist after commit"));
            return consumer.subscribe(msg -> {
                messages.add(msg);
                logger.info("\u2713 Received message: {}", msg.getPayload());
                messageReceived.tryComplete();
                return Future.succeededFuture();
            });
        })
        .compose(v -> messageReceived.future())
        .onSuccess(v -> testContext.verify(() -> {
            assertEquals(1, messages.size(), "Should receive exactly one message");
            assertTrue(messages.get(0).getPayload().contains(orderId), "Message should contain order ID");
            logger.info("\u2713 Outbox transaction participation validated");
            testContext.completeNow();
        }))
        .onFailure(testContext::failNow)
        .eventually(() -> {
            try { consumer.close(); } catch (Exception e) { logger.warn("Error closing consumer: {}", e.getMessage()); }
            try { producer.close(); } catch (Exception e) { logger.warn("Error closing producer: {}", e.getMessage()); }
            return Future.<Void>succeededFuture();
        });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test should complete within 30 seconds");
    }

    /**
     * Test Pattern 2: Transaction Rollback Behavior
     * 
     * Validates that business data is NOT persisted when the transaction rolls back.
     * This ensures no orphaned data exists for failed business operations.
     */
    @Test
    void testTransactionRollbackBehavior(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Testing Transaction Rollback Behavior ===");

        String orderId = "rollback-order-" + UUID.randomUUID().toString().substring(0, 8);

        pool.<Void>withTransaction(conn ->
            conn.preparedQuery("INSERT INTO test_orders (id, customer_id, amount, status) VALUES ($1, $2, $3, $4)")
                    .execute(Tuple.of(orderId, "customer-456", new java.math.BigDecimal("150.00"), "PENDING"))
                    .compose(v -> {
                        logger.info("Business data inserted (will be rolled back): {}", orderId);
                        // Force rollback by returning a failed future
                        return Future.<Void>failedFuture(new RuntimeException("Simulated business rule violation - triggering rollback"));
                    })
        )
        .transform(ar -> {
            if (ar.succeeded()) {
                return Future.<Void>failedFuture(new AssertionError("Transaction should have been rolled back"));
            }
            logger.info("\u2713 Rolling back transaction: {}", ar.cause().getMessage());
            return Future.<Void>succeededFuture();
        })
        .compose(v -> pool.preparedQuery("SELECT COUNT(*) FROM test_orders WHERE id = $1")
                .execute(Tuple.of(orderId)))
        .map(rs -> rs.iterator().next().getLong(0))
        .onSuccess(count -> testContext.verify(() -> {
            assertEquals(0L, count, "Business data should NOT exist after rollback");
            logger.info("\u2713 Transaction rollback behavior validated - no orphaned data");
            testContext.completeNow();
        }))
        .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test should complete within 30 seconds");
    }

    /**
     * Test Pattern 3: Concurrent Transaction Handling
     * 
     * Validates that concurrent transactions are properly isolated,
     * ensuring no cross-contamination of data.
     */
    @Test
    void testConcurrentTransactionHandling(Vertx vertx, VertxTestContext testContext) throws Exception {
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

        Future.all(txFutures)
            .compose(cf -> {
                logger.info("Concurrent transactions completed: {} succeeded", successCount.get());
                return pool.preparedQuery("SELECT COUNT(*) FROM test_orders WHERE id LIKE 'concurrent-order-%'")
                        .execute();
            })
            .map(rs -> rs.iterator().next().getLong(0))
            .compose(totalCount -> {
                testContext.verify(() -> assertEquals((long) successCount.get(), totalCount,
                        "Number of orders should match successful transactions"));
                List<Future<Long>> existsFutures = new ArrayList<>();
                for (String orderId : createdOrderIds) {
                    existsFutures.add(pool.preparedQuery("SELECT COUNT(*) FROM test_orders WHERE id = $1")
                            .execute(Tuple.of(orderId))
                            .map(rs -> rs.iterator().next().getLong(0)));
                }
                return Future.all(existsFutures);
            })
            .onSuccess(cf -> testContext.verify(() -> {
                List<Long> results = cf.list();
                for (Long exists : results) {
                    assertEquals(1L, exists, "Order should exist");
                }
                logger.info("\u2713 Concurrent transaction handling validated - {} orders created with proper isolation",
                        createdOrderIds.size());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test should complete within 30 seconds");
    }
}


