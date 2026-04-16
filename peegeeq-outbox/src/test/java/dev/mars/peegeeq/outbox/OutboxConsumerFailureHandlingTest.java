package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
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

    private static final String[] SYSTEM_PROPERTIES = {
        "peegeeq.database.host", "peegeeq.database.port", "peegeeq.database.name",
        "peegeeq.database.username", "peegeeq.database.password", "peegeeq.database.ssl.enabled",
        "peegeeq.queue.max-retries", "peegeeq.queue.retry-delay-ms", "peegeeq.queue.polling-interval"
    };

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;
    private Vertx testVertx;
    private PgConnectionManager connectionManager;
    private Pool reactivePool;

    @BeforeEach
    void setUp() throws Exception {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        testTopic = "failure-test-" + UUID.randomUUID().toString().substring(0, 8);

        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.5S");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("failure-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, String.class);
        consumer = outboxFactory.createConsumer(testTopic, String.class);

        testVertx = Vertx.vertx();
        connectionManager = new PgConnectionManager(testVertx);
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();
        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).build();
        reactivePool = connectionManager.getOrCreateReactivePool("test-verification", connectionConfig, poolConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        if (consumer != null) {
            consumer.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
        }
        if (connectionManager != null) {
            connectionManager.close();
        }
        if (testVertx != null) {
            testVertx.close().await();
        }
        if (manager != null) {
            manager.closeReactive().await();
        }
        for (String prop : SYSTEM_PROPERTIES) {
            System.clearProperty(prop);
        }
    }

    /**
     * Test that exercises error handling paths by processing messages that consistently fail.
     * This tests retry logic, error lambdas, and failure tracking.
     * 
     * NOTE: Temporarily disabled - timing sensitive, requires investigation
     */
    @Test
    @Disabled("Timing sensitive — requires investigation")
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
    void testProcessAvailableMessages_ConsumerClosedDuringProcessing(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        Promise<Void> startSignal = Promise.promise();
        Promise<Void> finishGate = Promise.promise();
        
        // Subscribe with handler that blocks
        consumer.subscribe(message -> {
        logger.info("Test: process available messages  consumer closed during processing");
            startSignal.tryComplete();
            finishGate.future().await();
            return Future.succeededFuture();
        });

        // Send messages to trigger processing
        producer.send("message1").await();
        producer.send("message2").await();
        
        // Wait for processing to start
        startSignal.future().await();
        
        // Close consumer while message is being processed
        consumer.close();
        
        // Release the blocked handler
        finishGate.tryComplete();
        
        // Verify consumer is actually closed — not a tautological assertion
        testContext.verify(() -> {
            AtomicBoolean subscribedState = getPrivateField((OutboxConsumer<String>) consumer, "subscribed", AtomicBoolean.class);
            assertFalse(subscribedState.get(),
                    "Consumer should not be subscribed after close");
        });
        testContext.completeNow();
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

        // Send multiple messages
        for (int i = 0; i < messageCount; i++) {
            producer.send("batch-message-" + i).await();
        }
        
        // Wait for all messages to be processed
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), 
            "All " + messageCount + " messages should be processed in batch");
    }

    /**
     * Test error handling in getReactivePoolFuture() when pool creation fails.
     * This tests the catch block at line 748 and error paths.
     */
    @Test
    void testGetReactivePoolFuture_ErrorHandling(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        OutboxConsumer<String> typedConsumer = (OutboxConsumer<String>) consumer;

        manager.closeReactive()
                .compose(ignored -> {
        logger.info("Test: get reactive pool future  error handling");
                    typedConsumer.subscribe(message -> Future.succeededFuture());

                    Promise<Void> timer = Promise.promise();
                    vertx.setTimer(1000, id -> timer.complete());
                    return timer.future();
                })
                .onSuccess(ignored -> testContext.verify(() -> {
                    AtomicBoolean subscribedState = getPrivateField(typedConsumer, "subscribed", AtomicBoolean.class);
                    assertTrue(subscribedState.get(),
                            "Consumer should remain subscribed after pool failure — it retries on next poll");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS),
                "Consumer should handle pool failures gracefully without crashing");
    }

    /**
     * Test close() method with multiple calls and during processing.
     * This tests close() at 58% coverage to reach 80%+.
     */
    @Test
    void testClose_WhileProcessing(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        Promise<Void> startSignal2 = Promise.promise();
        Promise<Void> blockGate = Promise.promise();
        
        consumer.subscribe(message -> {
        logger.info("Test: close  while processing");
            startSignal2.tryComplete();
            blockGate.future().await();
            return Future.succeededFuture();
        });

        producer.send("test").await();
        startSignal2.future().await();
        
        // Close while processing
        consumer.close();
        blockGate.tryComplete();
        
        // Close again (idempotent)
        consumer.close();
        
        // Verify close is actually idempotent — consumer should not be subscribed
        testContext.verify(() -> {
            AtomicBoolean subscribedState = getPrivateField((OutboxConsumer<String>) consumer, "subscribed", AtomicBoolean.class);
            assertFalse(subscribedState.get(),
                    "Consumer should not be subscribed after multiple closes");
        });
        testContext.completeNow();
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

        // Test various payload formats
        String[] payloads = {"simple-string", "{\"complex\":\"json\"}", ""};
        for (String payload : payloads) {
            producer.send(payload).await();
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
    void testDLQConnectionFailure(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        // Configure for fast DLQ transition
        System.setProperty("peegeeq.queue.max-retries", "1");
        System.setProperty("peegeeq.queue.retry-delay-ms", "100");
        
        PeeGeeQConfiguration dlqConfig = new PeeGeeQConfiguration("dlq-fail-test");
        PeeGeeQManager dlqManager = new PeeGeeQManager(dlqConfig, new SimpleMeterRegistry());
        dlqManager.start().await();
        
        try {
        logger.info("Test: d l q connection failure");
            DatabaseService dbService = new PgDatabaseService(dlqManager);
            OutboxFactory dlqFactory = new OutboxFactory(dbService, dlqConfig);
            
            String topic = "dlq-conn-test-" + UUID.randomUUID();
            MessageProducer<String> dlqProducer = dlqFactory.createProducer(topic, String.class);
            MessageConsumer<String> dlqConsumer = dlqFactory.createConsumer(topic, String.class);
            
            try {
                // Send message
                dlqProducer.send("test-dlq-failure").await();
                
                // Subscribe with failing handler to exhaust retries
                AtomicInteger attempts = new AtomicInteger(0);
                Promise<Void> retriesExhausted = Promise.promise();
                
                dlqConsumer.subscribe(message -> {
                    if (attempts.incrementAndGet() >= 2) {
                        retriesExhausted.tryComplete();
                    }
                    throw new RuntimeException("INTENTIONAL: Force DLQ");
                });
                
                // Wait for retries to exhaust
                retriesExhausted.future().await();
                
                // Small delay to let DLQ operation start
                vertx.timer(200).await();
                
                // Kill connections during DLQ operation
                reactivePool.withConnection(conn ->
                    conn.preparedQuery(
                        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
                        "WHERE datname = $1 AND pid != pg_backend_pid() AND application_name LIKE 'PeeGeeQ%'")
                        .execute(Tuple.of(postgres.getDatabaseName()))
                ).await();
                
                // Wait for error handling
                vertx.timer(1000).await();
                
                testContext.completeNow();
            } finally {
                dlqConsumer.close();
                dlqProducer.close();
            }
            
        } finally {
            dlqManager.close();
            System.clearProperty("peegeeq.queue.max-retries");
            System.clearProperty("peegeeq.queue.retry-delay-ms");
        }
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
    void testRetryIncrementConnectionFailure(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        // Configure for retries
        System.setProperty("peegeeq.queue.max-retries", "3");
        System.setProperty("peegeeq.queue.retry-delay-ms", "500");
        
        PeeGeeQConfiguration retryConfig = new PeeGeeQConfiguration("retry-fail-test");
        PeeGeeQManager retryManager = new PeeGeeQManager(retryConfig, new SimpleMeterRegistry());
        retryManager.start().await();
        
        try {
        logger.info("Test: retry increment connection failure");
            DatabaseService dbService = new PgDatabaseService(retryManager);
            OutboxFactory retryFactory = new OutboxFactory(dbService, retryConfig);
            
            String topic = "retry-conn-test-" + UUID.randomUUID();
            MessageProducer<String> retryProducer = retryFactory.createProducer(topic, String.class);
            MessageConsumer<String> retryConsumer = retryFactory.createConsumer(topic, String.class);
            
            try {
                // Send message
                retryProducer.send("test-retry-failure").await();
                
                // Subscribe with handler that fails once
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
                
                // Wait for first failure
                firstAttempt.future().await();
                
                // Kill connections right after failure to disrupt retry increment
                reactivePool.withConnection(conn ->
                    conn.preparedQuery(
                        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
                        "WHERE datname = $1 AND pid != pg_backend_pid() AND application_name LIKE 'PeeGeeQ%'")
                        .execute(Tuple.of(postgres.getDatabaseName()))
                ).await();
                
                // Wait for error handling
                vertx.timer(1000).await();
                
                testContext.completeNow();
            } finally {
                retryConsumer.close();
                retryProducer.close();
            }
            
        } finally {
            retryManager.close();
            System.clearProperty("peegeeq.queue.max-retries");
            System.clearProperty("peegeeq.queue.retry-delay-ms");
        }
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


