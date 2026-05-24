package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.extension.ExtendWith;

import org.junit.jupiter.api.Disabled;

import java.lang.reflect.Field;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Tests for OutboxConsumer failure handling paths to increase coverage.
 * Specifically targets:
 * - markMessageFailed() - 0% coverage (38 instructions)
 * - Error handler lambdas - 0% coverage (~93 instructions)
 * - processAvailableMessages() edge cases - 33% → 80% (+92 instructions)
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxConsumerFailureHandlingTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerFailureHandlingTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;
    private PgConnectionManager connectionManager;
    private Pool reactivePool;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) throws Exception {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        testTopic = "failure-test-" + UUID.randomUUID().toString().substring(0, 8);

        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .property("peegeeq.queue.polling-interval", "PT0.5S")
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        connectionManager = new PgConnectionManager(vertx);
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();
        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).build();
        reactivePool = connectionManager.getOrCreateReactivePool("test-verification", connectionConfig, poolConfig);

        manager.start()
                .onSuccess(v -> {
                    DatabaseService databaseService = new PgDatabaseService(manager);
                    outboxFactory = new OutboxFactory(databaseService, config);
                    producer = outboxFactory.createProducer(testTopic, String.class);
                    consumer = outboxFactory.createConsumer(testTopic, String.class);
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Tearing down OutboxConsumerFailureHandlingTest");
        if (consumer != null) {
            try { consumer.close(); } catch (Exception e) { logger.warn("Error closing consumer", e); }
        }
        if (producer != null) {
            try { producer.close(); } catch (Exception e) { logger.warn("Error closing producer", e); }
        }
        Future<Void> closeChain = outboxFactory != null
                ? outboxFactory.close()
                : Future.succeededFuture();
        closeChain
                .compose(v -> connectionManager != null ? connectionManager.close() : Future.succeededFuture())
                .compose(v -> manager != null ? manager.closeReactive() : Future.succeededFuture())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        // Do NOT close vertx — VertxExtension manages its lifecycle
    }

    /**
     * Test that exercises error handling paths by processing messages that consistently fail.
     * This tests retry logic, error lambdas, and failure tracking.
     * 
     * NOTE: Temporarily disabled - timing sensitive, requires investigation
     */
    @Test
    @Disabled("Timing sensitive requires investigation")
    void testRetryLogicWithFailingMessages(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint latch = testContext.checkpoint(4); // Initial + 3 retries
        AtomicInteger attemptCount = new AtomicInteger(0);
        
        // Subscribe with handler that always fails - triggers retry logic and error paths
        consumer.subscribe(message -> {
        logger.info("Test: retry logic with failing messages");
            int attempt = attemptCount.incrementAndGet();
            latch.flag();
            throw new RuntimeException("Intentional failure attempt " + attempt);
        });

        // Send message that will fail
        producer.send("test-message").await();
        
        // Wait for all retry attempts
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Should attempt 4 times");
        assertEquals(4, attemptCount.get(), "Should have 4 processing attempts");
        
        // Allow final state transition
        vertx.timer(2000).await();
        
        // Verify retry count after exhaustion
        io.vertx.sqlclient.Row row = reactivePool.withConnection(conn ->
            conn.preparedQuery("SELECT retry_count FROM outbox WHERE topic = $1 ORDER BY id DESC LIMIT 1")
                .execute(Tuple.of(testTopic))
                .map(rows -> {
                    assertTrue(rows.size() > 0, "Message should exist");
                    return rows.iterator().next();
                })
        ).await();

        assertEquals(3, row.getInteger("retry_count"), "Should have retry_count=3");
    }

    /**
     * Test processAvailableMessages() when consumer is closed during processing.
     * This tests the closed.get() check at line 249-251 and 277-280.
     */
    @Test
    void testProcessAvailableMessages_ConsumerClosedDuringProcessing(io.vertx.core.Vertx vertx, VertxTestContext testContext) {
        Promise<Void> startSignal = Promise.promise();
        Promise<Void> finishGate = Promise.promise();

        consumer.subscribe(message -> {
            logger.info("Test: process available messages  consumer closed during processing");
            startSignal.tryComplete();
            return finishGate.future();
        });

        producer.send("message1").onFailure(testContext::failNow);
        producer.send("message2").onFailure(testContext::failNow);

        startSignal.future()
                .onSuccess(v -> testContext.verify(() -> {
                    consumer.close();
                    finishGate.tryComplete();
                    AtomicBoolean subscribedState = getPrivateField((OutboxConsumer<String>) consumer, "subscribed", AtomicBoolean.class);
                    assertFalse(subscribedState.get(),
                            "Consumer should not be subscribed after close");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    /**
     * Test processAvailableMessages() with batch processing.
     * This tests the batch size logic at line 257.
     */
    @Test
    void testProcessAvailableMessages_BatchProcessing(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        int messageCount = 5;
        Checkpoint latch = testContext.checkpoint(messageCount);

        consumer.subscribe(message -> {
            logger.info("Test: process available messages  batch processing");
            latch.flag();
            return Future.succeededFuture();
        });

        for (int i = 0; i < messageCount; i++) {
            producer.send("batch-message-" + i).onFailure(testContext::failNow);
        }

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS),
            "All " + messageCount + " messages should be processed in batch");
    }

    /**
     * Test error handling in getReactivePoolFuture() when pool creation fails.
     * Subscribes while the pool is still available, then closes the manager to
     * simulate pool failure. Verifies the consumer reaches a clean closed state
     * without throwing during shutdown.
     */
    @Test
    void testGetReactivePoolFuture_ErrorHandling(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        OutboxConsumer<String> typedConsumer = (OutboxConsumer<String>) consumer;

        // Subscribe first consumer must be active when pool fails
        typedConsumer.subscribe(message -> Future.succeededFuture());

        // Close manager to simulate pool becoming unavailable
        manager.closeReactive()
                .compose(ignored -> {
                    logger.info("Test: get reactive pool future  error handling");
                    Promise<Void> timer = Promise.promise();
                    vertx.setTimer(500, id -> timer.complete());
                    return timer.future();
                })
                .onComplete(testContext.succeeding(ignored -> testContext.verify(() -> {
                    // After manager shutdown, consumer should be cleanly closed
                    AtomicBoolean closedState = getPrivateField(typedConsumer, "closed", AtomicBoolean.class);
                    assertTrue(closedState.get(),
                            "Consumer should be cleanly closed after manager shutdown");
                    testContext.completeNow();
                })));

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS),
                "Consumer should handle pool failures gracefully without crashing");
    }

    /**
     * Test close() method with multiple calls and during processing.
     * This tests close() at 58% coverage to reach 80%+.
     */
    @Test
    void testClose_WhileProcessing(io.vertx.core.Vertx vertx, VertxTestContext testContext) {
        Promise<Void> startSignal2 = Promise.promise();
        Promise<Void> blockGate = Promise.promise();

        consumer.subscribe(message -> {
            logger.info("Test: close  while processing");
            startSignal2.tryComplete();
            return blockGate.future();
        });

        producer.send("test").onFailure(testContext::failNow);

        startSignal2.future()
                .onSuccess(v -> testContext.verify(() -> {
                    consumer.close();
                    blockGate.tryComplete();
                    consumer.close(); // idempotent
                    AtomicBoolean subscribedState = getPrivateField((OutboxConsumer<String>) consumer, "subscribed", AtomicBoolean.class);
                    assertFalse(subscribedState.get(),
                            "Consumer should not be subscribed after multiple closes");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    /**
     * Test parsePayloadFromJsonObject() with edge cases.
     * Current coverage: 64% → Target: 80%+
     */
    @Test
    void testParsePayloadFromJsonObject_EdgeCases(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint latch = testContext.checkpoint(3);
        CopyOnWriteArrayList<String> receivedPayloads = new CopyOnWriteArrayList<>();

        consumer.subscribe(message -> {
            logger.info("Test: parse payload from json object  edge cases");
            receivedPayloads.add(message.getPayload());
            latch.flag();
            return Future.succeededFuture();
        });

        String[] payloads = {"simple-string", "{\"complex\":\"json\"}", ""};
        for (String payload : payloads) {
            producer.send(payload).onFailure(testContext::failNow);
        }

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should process all payload types");
        assertEquals(3, receivedPayloads.size());
    }

    /**
     * Test connection failure during DLQ operation to trigger error lambdas.
     * Forces message to DLQ by exhausting retries, then kills database connections
     * during the DLQ move operation to trigger error handler lambdas.
     * 
     * Coverage targets:
    * - lambda$storeDeadLetterMessage$26 (line 661, 31 instructions)
    * - lambda$storeDeadLetterMessage$29 (line 677, 31 instructions)
     */
    @Test
    void testDLQConnectionFailure(io.vertx.core.Vertx vertx, VertxTestContext testContext) {
        logger.info("ERROR ===== INTENTIONAL ERROR TEST ===== The next ERROR logs (INTENTIONAL: Force DLQ) are EXPECTED");
        Properties dlqProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .property("peegeeq.queue.max-retries", "1")
                .property("peegeeq.queue.retry-delay-ms", "100")
                .build();
        PeeGeeQConfiguration dlqConfig = new PeeGeeQConfiguration("default", dlqProps);
        PeeGeeQManager dlqManager = new PeeGeeQManager(dlqConfig, new SimpleMeterRegistry());

        dlqManager.start()
                .compose(v -> {
                    DatabaseService dbService = new PgDatabaseService(dlqManager);
                    OutboxFactory dlqFactory = new OutboxFactory(dbService, dlqConfig);
                    String topic = "dlq-conn-test-" + UUID.randomUUID();
                    MessageProducer<String> dlqProducer = dlqFactory.createProducer(topic, String.class);
                    MessageConsumer<String> dlqConsumer = dlqFactory.createConsumer(topic, String.class);

                    AtomicInteger attempts = new AtomicInteger(0);
                    Promise<Void> retriesExhausted = Promise.promise();

                    dlqConsumer.subscribe(message -> {
                        if (attempts.incrementAndGet() >= 2) {
                            retriesExhausted.tryComplete();
                        }
                        throw new RuntimeException("INTENTIONAL: Force DLQ");
                    });

                    return dlqProducer.send("test-dlq-failure")
                            .compose(sent -> retriesExhausted.future())
                            .compose(done -> vertx.timer(200))
                            .compose(t -> reactivePool.withConnection(conn ->
                                conn.preparedQuery(
                                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
                                    "WHERE datname = $1 AND pid != pg_backend_pid() AND application_name LIKE 'PeeGeeQ%'")
                                    .execute(Tuple.of(postgres.getDatabaseName()))))
                            .compose(rows -> vertx.timer(1000))
                            .eventually(() -> {
                                try { dlqConsumer.close(); } catch (Exception e) { logger.warn("Error closing dlqConsumer", e); }
                                try { dlqProducer.close(); } catch (Exception e) { logger.warn("Error closing dlqProducer", e); }
                                return Future.succeededFuture();
                            });
                })
                .eventually(() -> dlqManager.closeReactive())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    /**
     * Test connection failure during retry increment operation.
     * Processes message with failing handler to trigger retry, then kills
     * connections to force retry increment to fail.
     * 
     * Coverage target:
     * - lambda$incrementRetryAndReset (line 591, 31 instructions)
     */
    @Test
    void testRetryIncrementConnectionFailure(io.vertx.core.Vertx vertx, VertxTestContext testContext) {
        logger.info("ERROR ===== INTENTIONAL ERROR TEST ===== The next ERROR logs (INTENTIONAL: Force retry) are EXPECTED");
        Properties retryProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .property("peegeeq.queue.max-retries", "3")
                .property("peegeeq.queue.retry-delay-ms", "500")
                .build();
        PeeGeeQConfiguration retryConfig = new PeeGeeQConfiguration("default", retryProps);
        PeeGeeQManager retryManager = new PeeGeeQManager(retryConfig, new SimpleMeterRegistry());

        retryManager.start()
                .compose(v -> {
                    DatabaseService dbService = new PgDatabaseService(retryManager);
                    OutboxFactory retryFactory = new OutboxFactory(dbService, retryConfig);
                    String topic = "retry-conn-test-" + UUID.randomUUID();
                    MessageProducer<String> retryProducer = retryFactory.createProducer(topic, String.class);
                    MessageConsumer<String> retryConsumer = retryFactory.createConsumer(topic, String.class);

                    Promise<Void> firstAttempt = Promise.promise();
                    AtomicInteger attempts = new AtomicInteger(0);

                    retryConsumer.subscribe(message -> {
                        int attempt = attempts.incrementAndGet();
                        if (attempt == 1) {
                            firstAttempt.tryComplete();
                            throw new RuntimeException("INTENTIONAL: Force retry");
                        }
                        return Future.succeededFuture();
                    });

                    return retryProducer.send("test-retry-failure")
                            .compose(sent -> firstAttempt.future())
                            .compose(done -> reactivePool.withConnection(conn ->
                                conn.preparedQuery(
                                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
                                    "WHERE datname = $1 AND pid != pg_backend_pid() AND application_name LIKE 'PeeGeeQ%'")
                                    .execute(Tuple.of(postgres.getDatabaseName()))))
                            .compose(rows -> vertx.timer(1000))
                            .eventually(() -> {
                                try { retryConsumer.close(); } catch (Exception e) { logger.warn("Error closing retryConsumer", e); }
                                try { retryProducer.close(); } catch (Exception e) { logger.warn("Error closing retryProducer", e); }
                                return Future.succeededFuture();
                            });
                })
                .eventually(() -> retryManager.closeReactive())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @SuppressWarnings("unchecked")
    private static <T> T getPrivateField(Object target, String fieldName, Class<T> type) throws Exception {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return (T) field.get(target);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field '" + fieldName + "' not found on " + target.getClass().getName());
    }
}


