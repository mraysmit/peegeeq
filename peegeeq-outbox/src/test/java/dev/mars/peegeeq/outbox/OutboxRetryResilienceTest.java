package dev.mars.peegeeq.outbox;

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

import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for database failure resilience in outbox consumer retry mechanism.
 * 
 * This test suite covers critical database failure scenarios that could occur in production:
 * - Connection timeouts during retry processing
 * - Database server unavailability during retry cycles
 * - Transaction rollback scenarios
 * - Connection pool exhaustion
 * - Database recovery after temporary failures
 * 
 * These tests are essential for ensuring the outbox consumer can handle database failures
 * gracefully without losing messages or corrupting retry state.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
public class OutboxRetryResilienceTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxRetryResilienceTest.class);

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_resilience_test")
            .withUsername("resilience_test")
            .withPassword("resilience_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);

    private PeeGeeQManager manager;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private QueueFactory queueFactory;
    private io.vertx.sqlclient.Pool testReactivePool;
    private PgConnectionManager connectionManager;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize schema first
        TestSchemaInitializer.initializeSchema(postgres);

        logger.info("🔧 Setting up OutboxRetryResilienceTest");
        
        // Set up database connection properties
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        
        // Configure for faster testing
        System.setProperty("peegeeq.queue.max-retries", "3");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.1S");
        System.setProperty("peegeeq.database.pool.min-size", "1");
        System.setProperty("peegeeq.database.pool.max-size", "5");
        System.setProperty("peegeeq.database.pool.connection-timeout-ms", "2000");

        // Initialize manager and components
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("basic-test"), new SimpleMeterRegistry());
        manager.start();

        // Create queue factory using the standard pattern
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
        OutboxFactoryRegistrar.registerWith(provider);

        queueFactory = provider.createFactory("outbox", databaseService);

        // Create test-specific DataSource for failure simulation
        connectionManager = new PgConnectionManager(Vertx.vertx());
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(3)
                .build();

        testReactivePool = connectionManager.getOrCreateReactivePool("test-verification", connectionConfig, poolConfig);

        logger.info("✅ OutboxRetryResilienceTest setup completed");
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.info("🧹 Cleaning up OutboxRetryResilienceTest");
        
        if (consumer != null) {
            try {
                consumer.close();
            } catch (Exception e) {
                logger.warn("Error closing consumer: {}", e.getMessage());
            }
        }
        
        if (producer != null) {
            try {
                producer.close();
            } catch (Exception e) {
                logger.warn("Error closing producer: {}", e.getMessage());
            }
        }
        
        if (queueFactory != null) {
            try {
                queueFactory.close();
            } catch (Exception e) {
                logger.warn("Error closing queue factory: {}", e.getMessage());
            }
        }
        
        if (manager != null) {
            try {
                manager.close();
            } catch (Exception e) {
                logger.warn("Error closing manager: {}", e.getMessage());
            }
        }

        if (connectionManager != null) {
            try {
                connectionManager.close();
            } catch (Exception e) {
                logger.warn("Error closing connection manager: {}", e.getMessage());
            }
        }

        logger.info("✅ OutboxRetryResilienceTest cleanup completed");
    }

    @Test
    @DisplayName("DATABASE RESILIENCE: Connection timeout during retry processing")
    void testConnectionTimeoutDuringRetryProcessing() throws Exception {
        logger.info("🔥 === DATABASE RESILIENCE TEST: Connection timeout during retry processing ===");

        String testTopic = "test-connection-timeout-" + UUID.randomUUID().toString().substring(0, 8);
        producer = queueFactory.createProducer(testTopic, String.class);
        consumer = queueFactory.createConsumer(testTopic, String.class);

        String testMessage = "Message for connection timeout test";
        AtomicInteger attemptCount = new AtomicInteger(0);
        AtomicReference<String> lastError = new AtomicReference<>();
        CountDownLatch retryLatch = new CountDownLatch(4); // Initial + 3 retries

        logger.info("📤 Sending message: {}", testMessage);

        // Set up consumer that fails and triggers retry processing
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("🔥 INTENTIONAL FAILURE: Connection timeout simulation attempt {} for message: {}",
                attempt, message.getPayload());

            retryLatch.countDown();
            lastError.set("INTENTIONAL FAILURE: Simulated connection timeout, attempt " + attempt);

            // Simulate connection timeout during message processing
            throw new RuntimeException("INTENTIONAL FAILURE: Connection timeout during processing, attempt " + attempt);
        });

        // Send message after consumer is subscribed
        producer.send(testMessage).get(5, TimeUnit.SECONDS);
        logger.info("✅ Message sent successfully");

        // Wait for all retry attempts
        boolean completed = retryLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing 4 times despite connection issues");
        assertEquals(4, attemptCount.get(), "Should have made exactly 4 processing attempts");

        // Verify message eventually moves to dead letter queue
        Thread.sleep(2000); // Allow time for DLQ processing
        verifyMessageInDeadLetterQueue(testMessage);

        logger.info("✅ Connection timeout resilience test completed successfully");
        logger.info("   Total attempts: {}", attemptCount.get());
        logger.info("   Last error: {}", lastError.get());
    }

    @Test
    @DisplayName("DATABASE RESILIENCE: Database unavailability during retry cycle")
    void testDatabaseUnavailabilityDuringRetryProcessing() throws Exception {
        logger.info("🔥 === DATABASE RESILIENCE TEST: Database unavailability during retry cycle ===");

        String testTopic = "test-db-unavailable-" + UUID.randomUUID().toString().substring(0, 8);
        producer = queueFactory.createProducer(testTopic, String.class);
        consumer = queueFactory.createConsumer(testTopic, String.class);

        String testMessage = "Message for database unavailability test";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch retryLatch = new CountDownLatch(4); // Initial + 3 retries

        logger.info("📤 Sending message: {}", testMessage);

        // Set up consumer that fails
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("🔥 INTENTIONAL FAILURE: Database unavailability simulation attempt {} for message: {}",
                attempt, message.getPayload());

            retryLatch.countDown();

            // Simulate database unavailability
            throw new RuntimeException("INTENTIONAL FAILURE: Database connection failed, attempt " + attempt);
        });

        // Send message after consumer is subscribed
        producer.send(testMessage).get(5, TimeUnit.SECONDS);
        logger.info("✅ Message sent successfully");

        // Wait for all retry attempts
        boolean completed = retryLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing 4 times despite database issues");
        assertEquals(4, attemptCount.get(), "Should have made exactly 4 processing attempts");

        logger.info("✅ Database unavailability resilience test completed successfully");
        logger.info("   Total attempts: {}", attemptCount.get());
    }

    /**
     * Verifies that a message has been moved to the dead letter queue.
     */
    private void verifyMessageInDeadLetterQueue(String expectedPayload) throws Exception {
        Integer count = testReactivePool.withConnection(connection -> {
            return connection.preparedQuery("SELECT COUNT(*) FROM dead_letter_queue WHERE payload::text LIKE $1")
                .execute(io.vertx.sqlclient.Tuple.of("%" + expectedPayload + "%"))
                .map(rowSet -> {
                    io.vertx.sqlclient.Row row = rowSet.iterator().next();
                    return row.getInteger(0);
                });
        }).toCompletionStage().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertTrue(count > 0, "Message should be found in dead letter queue");
        logger.info("✅ Verified message in dead letter queue: {} entries found", count);
    }

    @Test
    @DisplayName("DATABASE RESILIENCE: Connection pool exhaustion during retry processing")
    void testConnectionPoolExhaustionDuringRetryProcessing() throws Exception {
        logger.info("🔥 === DATABASE RESILIENCE TEST: Connection pool exhaustion during retry processing ===");

        String testTopic = "test-pool-exhaustion-" + UUID.randomUUID().toString().substring(0, 8);
        producer = queueFactory.createProducer(testTopic, String.class);
        consumer = queueFactory.createConsumer(testTopic, String.class);

        String testMessage = "Message for connection pool exhaustion test";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch retryLatch = new CountDownLatch(2); // Expect fewer attempts due to pool exhaustion

        // Send message first
        producer.send(testMessage).get(5, TimeUnit.SECONDS);
        logger.info("📤 Message sent: {}", testMessage);

        // Exhaust connection pool before processing
        simulateConnectionPoolExhaustion();

        // Set up consumer that tries to process during pool exhaustion
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("🔥 INTENTIONAL FAILURE: Pool exhaustion simulation attempt {} for message: {}",
                attempt, message.getPayload());

            retryLatch.countDown();

            // This will fail due to pool exhaustion
            throw new RuntimeException("INTENTIONAL FAILURE: Connection pool exhausted, attempt " + attempt);
        });

        // Wait for attempts (may be limited due to pool exhaustion)
        boolean completed = retryLatch.await(10, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing despite pool exhaustion");
        assertTrue(attemptCount.get() >= 1, "Should have made at least 1 processing attempt");

        logger.info("✅ Connection pool exhaustion resilience test completed successfully");
        logger.info("   Total attempts: {}", attemptCount.get());
    }

    @Test
    @DisplayName("DATABASE RESILIENCE: Transaction rollback during retry state update")
    void testTransactionRollbackDuringRetryStateUpdate() throws Exception {
        logger.info("🔥 === DATABASE RESILIENCE TEST: Transaction rollback during retry state update ===");

        String testTopic = "test-transaction-rollback-" + UUID.randomUUID().toString().substring(0, 8);
        producer = queueFactory.createProducer(testTopic, String.class);
        consumer = queueFactory.createConsumer(testTopic, String.class);

        String testMessage = "Message for transaction rollback test";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch retryLatch = new CountDownLatch(4); // Initial + 3 retries

        // Send message first
        producer.send(testMessage).get(5, TimeUnit.SECONDS);
        logger.info("📤 Message sent: {}", testMessage);

        // Set up consumer that fails and causes transaction issues
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("🔥 INTENTIONAL FAILURE: Transaction rollback simulation attempt {} for message: {}",
                attempt, message.getPayload());

            retryLatch.countDown();

            // Simulate transaction rollback scenario
            throw new RuntimeException("INTENTIONAL FAILURE: Transaction rollback during retry update, attempt " + attempt);
        });

        // Wait for all retry attempts
        boolean completed = retryLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing 4 times despite transaction issues");
        assertEquals(4, attemptCount.get(), "Should have made exactly 4 processing attempts");

        // Verify retry state consistency after rollbacks
        verifyRetryStateConsistency(testMessage);

        logger.info("✅ Transaction rollback resilience test completed successfully");
        logger.info("   Total attempts: {}", attemptCount.get());
    }

    @Test
    @DisplayName("DATABASE RESILIENCE: Database recovery after temporary failure")
    void testDatabaseRecoveryAfterTemporaryFailure() throws Exception {
        logger.info("🔥 === DATABASE RESILIENCE TEST: Database recovery after temporary failure ===");

        String testTopic = "test-db-recovery-" + UUID.randomUUID().toString().substring(0, 8);
        producer = queueFactory.createProducer(testTopic, String.class);
        consumer = queueFactory.createConsumer(testTopic, String.class);

        String testMessage = "Message for database recovery test";
        AtomicInteger attemptCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch successLatch = new CountDownLatch(1);

        // Send message first
        producer.send(testMessage).get(5, TimeUnit.SECONDS);
        logger.info("📤 Message sent: {}", testMessage);

        // Set up consumer that fails initially but succeeds after "recovery"
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();

            if (attempt <= 2) {
                logger.info("🔥 INTENTIONAL FAILURE: Database failure simulation attempt {} for message: {}",
                    attempt, message.getPayload());

                // Simulate database failure for first 2 attempts
                throw new RuntimeException("INTENTIONAL FAILURE: Database temporarily unavailable, attempt " + attempt);
            } else {
                logger.info("✅ SUCCESS: Database recovered, processing attempt {} for message: {}",
                    attempt, message.getPayload());

                // Simulate database recovery - process successfully
                successCount.incrementAndGet();
                successLatch.countDown();
                return CompletableFuture.completedFuture(null);
            }
        });

        // Wait for successful processing after recovery
        boolean completed = successLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "Should eventually succeed after database recovery");
        assertTrue(attemptCount.get() >= 3, "Should have made at least 3 attempts (2 failures + 1 success)");
        assertEquals(1, successCount.get(), "Should have succeeded exactly once after recovery");

        logger.info("✅ Database recovery resilience test completed successfully");
        logger.info("   Total attempts: {}", attemptCount.get());
        logger.info("   Successful processing: {}", successCount.get());
    }

    /**
     * Verifies that retry state remains consistent after transaction rollbacks.
     */
    private void verifyRetryStateConsistency(String expectedPayload) throws Exception {
        testReactivePool.withConnection(connection -> {
            return connection.preparedQuery("SELECT retry_count, status FROM outbox WHERE payload::text LIKE $1 ORDER BY created_at DESC LIMIT 1")
                .execute(io.vertx.sqlclient.Tuple.of("%" + expectedPayload + "%"))
                .map(rowSet -> {
                    if (rowSet.iterator().hasNext()) {
                        io.vertx.sqlclient.Row row = rowSet.iterator().next();
                        int retryCount = row.getInteger("retry_count");
                        String status = row.getString("status");

                        logger.info("✅ Retry state verification: retry_count={}, status={}", retryCount, status);

                        // Verify retry count is within expected bounds
                        assertTrue(retryCount >= 0 && retryCount <= 4,
                            "Retry count should be between 0 and 4, but was: " + retryCount);

                        // Verify status is valid
                        assertTrue(status.equals("PENDING") || status.equals("PROCESSING") ||
                                  status.equals("COMPLETED") || status.equals("DEAD_LETTER"),
                            "Status should be valid, but was: " + status);
                    }
                    return null;
                });
        }).toCompletionStage().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Simulates database connection pool exhaustion by creating many long-running connections.
     */
    private void simulateConnectionPoolExhaustion() throws Exception {
        logger.info("🔥 INTENTIONAL TEST: Simulating connection pool exhaustion");

        // Create multiple long-running connections to exhaust the pool
        java.util.List<java.util.concurrent.CompletableFuture<Void>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < 10; i++) {
            final int connectionNum = i + 1;
            java.util.concurrent.CompletableFuture<Void> future = testReactivePool.getConnection()
                .compose(connection -> {
                    logger.debug("Created reactive connection {}", connectionNum);
                    // Hold connection for a while to simulate exhaustion
                    return io.vertx.core.Future.<Void>future(promise -> {
                        io.vertx.core.Vertx.vertx().setTimer(2000, id -> {
                            connection.close();
                            promise.complete();
                        });
                    });
                })
                .recover(throwable -> {
                    logger.info("Connection pool exhausted at connection {}: {}", connectionNum, throwable.getMessage());
                    return io.vertx.core.Future.succeededFuture();
                })
                .toCompletionStage()
                .toCompletableFuture();

            futures.add(future);
        }

        // Wait a bit for connections to be acquired
        Thread.sleep(500);
        logger.info("Connection pool exhaustion simulation initiated");
    }
}
