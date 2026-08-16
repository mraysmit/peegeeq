package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

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
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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


import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Test suite for Future-based exception handling in outbox consumers.
 * 
 * Tests verify that exceptions returned in failed Futures are properly
 * processed through retry and dead letter queue mechanisms.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxFutureExceptionTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxFutureExceptionTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory factory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String topic;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        // Configure system properties for test container
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.max-retries", "2")
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        // Initialize PeeGeeQ
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps), new SimpleMeterRegistry());
        manager.start()
                .map(v -> {
                    PgDatabaseService databaseService = new PgDatabaseService(manager);
                    PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
                    OutboxFactoryRegistrar.registerWith(provider);

                    factory = provider.createFactory("outbox", databaseService);
                    topic = "test-future-exceptions-" + UUID.randomUUID().toString().substring(0, 8);
                    producer = factory.createProducer(topic, String.class);
                    consumer = factory.createConsumer(topic, String.class);
                    return (Void) null;
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext tearDownContext) {
        logger.info("Tearing down: closing resources and manager");
        Future<Void> closeFactory = factory != null
                ? factory.close()
                : Future.succeededFuture();
        closeFactory
                .eventually(() -> manager != null
                        ? manager.closeReactive()
                        : Future.succeededFuture())
                .onSuccess(v -> tearDownContext.completeNow())
                .onFailure(tearDownContext::failNow);
    }

    @Test
    void testFailedFutureHandling(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Failed Future Handling ===");
        
        String testMessage = "Message that returns failed future";
        AtomicInteger attemptCount = new AtomicInteger(0);

        // Set up consumer that returns failed Future
        consumer.subscribe(message -> {
                int attempt = attemptCount.incrementAndGet();
                logger.info("INTENTIONAL FAILURE: Processing attempt {} for failed future", attempt);

                // Return failed Future - should be handled correctly
                return Future.failedFuture(
                    new RuntimeException("INTENTIONAL FAILURE: Failed future from handler, attempt " + attempt)
                );
            })
            .compose(v -> producer.send(testMessage))
            .compose(v -> awaitDeadLetterState(vertx, 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertTerminalFailure(row, attemptCount, "failed future");
                logger.info("Failed future handling test completed successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testAsyncFailureHandling(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Async Failure Handling ===");
        
        String testMessage = "Message that fails asynchronously";
        AtomicInteger attemptCount = new AtomicInteger(0);

        // Set up consumer that fails asynchronously
        consumer.subscribe(message -> {
                int attempt = attemptCount.incrementAndGet();
                logger.info("INTENTIONAL FAILURE: Processing attempt {} for async failure", attempt);

                // Return future that fails asynchronously after a delay
                return vertx.timer(50)
                        .compose(id -> Future.failedFuture(
                                new RuntimeException("INTENTIONAL FAILURE: Async failure, attempt " + attempt)));
            })
            .compose(v -> producer.send(testMessage))
            .compose(v -> awaitDeadLetterState(vertx, 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertTerminalFailure(row, attemptCount, "async failure");
                logger.info("Async failure handling test completed successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testTimeoutExceptionHandling(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Timeout Exception Handling ===");
        
        String testMessage = "Message that times out";
        AtomicInteger attemptCount = new AtomicInteger(0);

        // Set up consumer that times out
        consumer.subscribe(message -> {
                int attempt = attemptCount.incrementAndGet();
                logger.info("INTENTIONAL FAILURE: Processing attempt {} for timeout", attempt);

                // Return future that fails after the simulated timeout
                return vertx.timer(100)
                        .compose(timerId -> Future.failedFuture(
                                new RuntimeException("INTENTIONAL FAILURE: Timeout exception, attempt " + attempt)));
            })
            .compose(v -> producer.send(testMessage))
            .compose(v -> awaitDeadLetterState(vertx, 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertTerminalFailure(row, attemptCount, "timeout");
                logger.info("Timeout exception handling test completed successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testNullFutureHandling(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Null Future Handling ===");
        
        String testMessage = "Message that returns null future";
        AtomicInteger attemptCount = new AtomicInteger(0);

        // Set up consumer that returns null (should cause NPE)
        consumer.subscribe(message -> {
                int attempt = attemptCount.incrementAndGet();
                logger.info("INTENTIONAL FAILURE: Processing attempt {} returning null", attempt);

                // Return null - should be converted to a handler failure
                return null;
            })
            .compose(v -> producer.send(testMessage))
            .compose(v -> awaitDeadLetterState(vertx, 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertTerminalFailure(row, attemptCount, "null Future");
                logger.info("Null Future handling test completed successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    private Future<Row> awaitDeadLetterState(Vertx vertx, int remainingAttempts) {
        return manager.getDatabaseService().getConnectionProvider()
                .getReactivePool("peegeeq-main")
                .compose(pool -> pool.withConnection(connection -> connection.preparedQuery("""
                        SELECT status, retry_count
                        FROM outbox
                        WHERE topic = $1
                        ORDER BY created_at DESC
                        LIMIT 1
                        """).execute(Tuple.of(topic))))
                .compose(rows -> {
                    if (rows.size() == 1) {
                        Row row = rows.iterator().next();
                        if ("DEAD_LETTER".equals(row.getString("status"))) {
                            return Future.succeededFuture(row);
                        }
                    }
                    if (remainingAttempts == 0) {
                        return Future.failedFuture(
                                "Message on topic " + topic + " did not reach DEAD_LETTER");
                    }
                    return vertx.timer(100)
                            .compose(v -> awaitDeadLetterState(vertx, remainingAttempts - 1));
                });
    }

    private void assertTerminalFailure(Row row, AtomicInteger attemptCount, String scenario) {
        assertEquals(3, attemptCount.intValue(),
                "Should make exactly 3 processing attempts for " + scenario);
        assertEquals("DEAD_LETTER", row.getString("status"),
                "Message should reach DEAD_LETTER for " + scenario);
        assertEquals(2, row.getInteger("retry_count"),
                "Message should persist retry_count=2 for " + scenario);
    }
}

