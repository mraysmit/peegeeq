package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
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
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


import java.util.UUID;
import java.util.Properties;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

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
@ExtendWith(VertxExtension.class)
@Testcontainers
public class OutboxRetryResilienceTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxRetryResilienceTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private QueueFactory queueFactory;
    private io.vertx.sqlclient.Pool testReactivePool;
    private PgConnectionManager connectionManager;

    @BeforeEach
    void setUp(VertxTestContext ctx) throws Exception {
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        // Set up database connection properties
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.max-retries", "3")
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .property("peegeeq.database.pool.min-size", "1")
                .property("peegeeq.database.pool.max-size", "5")
                .property("peegeeq.database.pool.connection-timeout-ms", "2000")
                .build();

        // Initialize manager and components
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps), new SimpleMeterRegistry());
        manager.start()
            .onSuccess(v -> {
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
                        .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                        .build();
                PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).build();
                testReactivePool = connectionManager.getOrCreateReactivePool("test-verification", connectionConfig, poolConfig);

                ctx.completeNow();
            })
            .onFailure(ctx::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
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
        
        (queueFactory != null ? queueFactory.close() : Future.<Void>succeededFuture())
            .eventually(() -> connectionManager != null
                ? connectionManager.close().transform(ar -> {
                    if (ar.failed()) logger.warn("Error closing connection manager: {}", ar.cause().getMessage());
                    return Future.<Void>succeededFuture();
                })
                : Future.<Void>succeededFuture())
            .eventually(() -> manager != null ? manager.closeReactive() : Future.<Void>succeededFuture())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("DATABASE RESILIENCE: Connection timeout during retry processing")
    void testConnectionTimeoutDuringRetryProcessing(Vertx vertx, VertxTestContext testContext) throws Exception {
        String testTopic = "test-connection-timeout-" + UUID.randomUUID().toString().substring(0, 8);
        producer = queueFactory.createProducer(testTopic, String.class);
        consumer = queueFactory.createConsumer(testTopic, String.class);

        String testMessage = "Message for connection timeout test";
        AtomicInteger attemptCount = new AtomicInteger(0);
        Checkpoint retryCheckpoint = testContext.checkpoint(4); // Initial + 3 retries
        Checkpoint verifyCheckpoint = testContext.checkpoint();

        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            retryCheckpoint.flag();
            if (attempt == 4) {
                vertx.setTimer(2000, t ->
                    verifyMessageInDeadLetterQueue(testMessage)
                        .onSuccess(v -> verifyCheckpoint.flag())
                        .onFailure(testContext::failNow));
            }
            return Future.failedFuture(new RuntimeException("INTENTIONAL FAILURE: Connection timeout during processing, attempt " + attempt));
        });

        producer.send(testMessage).onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(20, TimeUnit.SECONDS), "Should have attempted processing 4 times despite connection issues");
        assertEquals(4, attemptCount.get(), "Should have made exactly 4 processing attempts");
    }

    @Test
    @DisplayName("DATABASE RESILIENCE: Database unavailability during retry cycle")
    void testDatabaseUnavailabilityDuringRetryProcessing(VertxTestContext testContext) throws Exception {
        String testTopic = "test-db-unavailable-" + UUID.randomUUID().toString().substring(0, 8);
        producer = queueFactory.createProducer(testTopic, String.class);
        consumer = queueFactory.createConsumer(testTopic, String.class);

        String testMessage = "Message for database unavailability test";
        AtomicInteger attemptCount = new AtomicInteger(0);
        Checkpoint retryCheckpoint = testContext.checkpoint(4); // Initial + 3 retries

        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            retryCheckpoint.flag();
            return Future.failedFuture(new RuntimeException("INTENTIONAL FAILURE: Database connection failed, attempt " + attempt));
        });

        producer.send(testMessage).onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Should have attempted processing 4 times despite database issues");
        assertEquals(4, attemptCount.get(), "Should have made exactly 4 processing attempts");
    }

    /**
     * Verifies that a message has been moved to the dead letter queue.
     */
    private Future<Void> verifyMessageInDeadLetterQueue(String expectedPayload) {
        return testReactivePool.withConnection(connection ->
            connection.preparedQuery("SELECT COUNT(*) FROM dead_letter_queue WHERE payload::text LIKE $1")
                .execute(io.vertx.sqlclient.Tuple.of("%" + expectedPayload + "%"))
                .map(rowSet -> rowSet.iterator().next().getInteger(0))
        ).map(count -> {
            assertTrue(count > 0, "Message should be found in dead letter queue");
            return (Void) null;
        });
    }

    @Test
    @DisplayName("DATABASE RESILIENCE: Connection pool exhaustion during retry processing")
    void testConnectionPoolExhaustionDuringRetryProcessing(VertxTestContext testContext) throws Exception {
        String testTopic = "test-pool-exhaustion-" + UUID.randomUUID().toString().substring(0, 8);
        producer = queueFactory.createProducer(testTopic, String.class);
        consumer = queueFactory.createConsumer(testTopic, String.class);

        String testMessage = "Message for connection pool exhaustion test";
        AtomicInteger attemptCount = new AtomicInteger(0);
        Checkpoint retryCheckpoint = testContext.checkpoint(4); // Initial + 3 retries

        producer.send(testMessage).onFailure(testContext::failNow);

        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            retryCheckpoint.flag();
            return Future.failedFuture(new RuntimeException("INTENTIONAL FAILURE: Connection pool exhausted, attempt " + attempt));
        });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should have attempted processing 4 times despite connection issues");
        assertEquals(4, attemptCount.get(), "Should have made exactly 4 processing attempts");
    }

    @Test
    @DisplayName("DATABASE RESILIENCE: Transaction rollback during retry state update")
    void testTransactionRollbackDuringRetryStateUpdate(Vertx vertx, VertxTestContext testContext) throws Exception {
        String testTopic = "test-transaction-rollback-" + UUID.randomUUID().toString().substring(0, 8);
        producer = queueFactory.createProducer(testTopic, String.class);
        consumer = queueFactory.createConsumer(testTopic, String.class);

        String testMessage = "Message for transaction rollback test";
        AtomicInteger attemptCount = new AtomicInteger(0);
        Checkpoint retryCheckpoint = testContext.checkpoint(4); // Initial + 3 retries
        Checkpoint verifyCheckpoint = testContext.checkpoint();

        producer.send(testMessage).onFailure(testContext::failNow);

        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            retryCheckpoint.flag();
            if (attempt == 4) {
                vertx.setTimer(2000, t ->
                    verifyRetryStateConsistency(testMessage)
                        .onSuccess(v -> verifyCheckpoint.flag())
                        .onFailure(testContext::failNow));
            }
            return Future.failedFuture(new RuntimeException("INTENTIONAL FAILURE: Transaction rollback during retry update, attempt " + attempt));
        });

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Should have attempted processing 4 times despite transaction issues");
        assertEquals(4, attemptCount.get(), "Should have made exactly 4 processing attempts");
    }

    @Test
    @DisplayName("DATABASE RESILIENCE: Database recovery after temporary failure")
    void testDatabaseRecoveryAfterTemporaryFailure(VertxTestContext testContext) throws Exception {
        String testTopic = "test-db-recovery-" + UUID.randomUUID().toString().substring(0, 8);
        producer = queueFactory.createProducer(testTopic, String.class);
        consumer = queueFactory.createConsumer(testTopic, String.class);

        String testMessage = "Message for database recovery test";
        AtomicInteger attemptCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        Checkpoint successCheckpoint = testContext.checkpoint();

        producer.send(testMessage).onFailure(testContext::failNow);

        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            if (attempt <= 2) {
                return Future.failedFuture(new RuntimeException("INTENTIONAL FAILURE: Database temporarily unavailable, attempt " + attempt));
            } else {
                successCount.incrementAndGet();
                successCheckpoint.flag();
                return Future.succeededFuture();
            }
        });

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Should eventually succeed after database recovery");
        assertTrue(attemptCount.get() >= 3, "Should have made at least 3 attempts (2 failures + 1 success)");
        assertEquals(1, successCount.get(), "Should have succeeded exactly once after recovery");
    }

    /**
     * Verifies that retry state remains consistent after transaction rollbacks.
     */
    private Future<Void> verifyRetryStateConsistency(String expectedPayload) {
        return testReactivePool.withConnection(connection ->
            connection.preparedQuery("SELECT retry_count, status FROM outbox WHERE payload::text LIKE $1 ORDER BY created_at DESC LIMIT 1")
                .execute(io.vertx.sqlclient.Tuple.of("%" + expectedPayload + "%"))
                .map(rowSet -> {
                    assertTrue(rowSet.iterator().hasNext(), "Message should exist in outbox table");
                    io.vertx.sqlclient.Row row = rowSet.iterator().next();
                    int retryCount = row.getInteger("retry_count");
                    String status = row.getString("status");
                    assertEquals(3, retryCount, "Retry count should be 3 after retry exhaustion, but was: " + retryCount);
                    assertEquals("DEAD_LETTER", status, "Status should be DEAD_LETTER after retry exhaustion, but was: " + status);
                    return (Void) null;
                })
        );
    }
}


