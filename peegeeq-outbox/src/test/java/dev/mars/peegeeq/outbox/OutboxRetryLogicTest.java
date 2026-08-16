package dev.mars.peegeeq.outbox;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Test suite for verifying retry logic behavior with direct exceptions.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxRetryLogicTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxRetryLogicTest.class);

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory factory;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.max-retries", "3")
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps), new SimpleMeterRegistry());
        manager.start()
                .map(v -> {
                    PgDatabaseService databaseService = new PgDatabaseService(manager);
                    PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
                    OutboxFactoryRegistrar.registerWith(provider);

                    factory = provider.createFactory("outbox", databaseService);
                    return (Void) null;
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Tearing down: closing resources and manager");
        Future<Void> closeFactory = factory != null
                ? factory.close()
                : Future.succeededFuture();
        closeFactory
                .eventually(() -> manager != null
                        ? manager.closeReactive()
                        : Future.succeededFuture())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void testRetryCountIncrementsCorrectly(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Retry Count Increments ===");

        String topicName = newTopic("retry-count-test");
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class);

        String testMessage = "Message for retry count test";
        AtomicInteger attemptCount = new AtomicInteger(0);

        consumer.subscribe(message -> {
                int attempt = attemptCount.incrementAndGet();
                logger.info("INTENTIONAL FAILURE: Retry attempt {} for message: {}",
                    attempt, message.getPayload());

                throw new RuntimeException("INTENTIONAL FAILURE: Always fail for retry test, attempt " + attempt);
            })
            .compose(v -> producer.send(testMessage))
            .compose(v -> awaitTerminalState(vertx, topicName, "DEAD_LETTER", 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertRetryState(row, attemptCount, 4, "DEAD_LETTER", 3, "retry count");
                logger.info("Retry count increment test completed successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testMaxRetriesThresholdRespected(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Max Retries Threshold ===");

        String topicName = newTopic("max-retries-test");
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
        OutboxConsumerConfig consumerConfig = OutboxConsumerConfig.builder()
                .maxRetries(1)
                .pollingInterval(Duration.ofMillis(100))
                .build();
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, consumerConfig);

        String testMessage = "Message for max retries test";
        AtomicInteger attemptCount = new AtomicInteger(0);

        consumer.subscribe(message -> {
                int attempt = attemptCount.incrementAndGet();
                logger.info("INTENTIONAL FAILURE: Max retries attempt {} for message: {}",
                    attempt, message.getPayload());

                throw new RuntimeException("INTENTIONAL FAILURE: Testing max retries, attempt " + attempt);
            })
            .compose(v -> producer.send(testMessage))
            .compose(v -> awaitTerminalState(vertx, topicName, "DEAD_LETTER", 100))
            .onSuccess(row -> testContext.verify(() -> {
                    assertRetryState(row, attemptCount, 2, "DEAD_LETTER", 1, "max retries threshold");
                    logger.info("Max retries threshold test completed successfully");
                    testContext.completeNow();
                }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testEventualSuccessAfterRetries(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Eventual Success After Retries ===");

        String topicName = newTopic("eventual-success-test");
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class);

        String testMessage = "Message that eventually succeeds";
        AtomicInteger attemptCount = new AtomicInteger(0);

        consumer.subscribe(message -> {
                int attempt = attemptCount.incrementAndGet();

                if (attempt < 3) {
                    logger.info("INTENTIONAL FAILURE: Failing attempt {} for eventual success test", attempt);
                    throw new RuntimeException("INTENTIONAL FAILURE: Failing on purpose, attempt " + attempt);
                } else {
                    logger.info("SUCCESS: Succeeding on attempt {} for eventual success test", attempt);
                    return Future.succeededFuture();
                }
            })
            .compose(v -> producer.send(testMessage))
            .compose(v -> awaitTerminalState(vertx, topicName, "COMPLETED", 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertRetryState(row, attemptCount, 3, "COMPLETED", 2, "eventual success");
                logger.info("Eventual success after retries test completed successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testDifferentExceptionTypesRetryBehavior(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Different Exception Types Retry Behavior ===");

        testExceptionTypeRetry(vertx, "IllegalArgumentException",
            () -> new IllegalArgumentException("INTENTIONAL FAILURE: Invalid argument"))
            .compose(v -> testExceptionTypeRetry(vertx, "IllegalStateException",
                () -> new IllegalStateException("INTENTIONAL FAILURE: Invalid state")))
            .compose(v -> testExceptionTypeRetry(vertx, "NullPointerException",
                () -> new NullPointerException("INTENTIONAL FAILURE: Null pointer")))
            .onSuccess(v -> {
                logger.info("Different exception types retry test completed successfully");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    private Future<Void> testExceptionTypeRetry(
            Vertx vertx,
            String exceptionType,
            ExceptionFactory exceptionFactory) {
        logger.info("Testing retry behavior for: {}", exceptionType);

        String topicName = newTopic("exception-test-" + exceptionType.toLowerCase());
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class);

        String testMessage = "Message for " + exceptionType + " test";
        AtomicInteger attemptCount = new AtomicInteger(0);

        Future<Void> scenario = consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: {} attempt {} for message: {}",
                exceptionType, attempt, message.getPayload());

            throw exceptionFactory.create();
        })
            .compose(v -> producer.send(testMessage))
            .compose(v -> awaitTerminalState(vertx, topicName, "DEAD_LETTER", 100))
            .map(row -> {
                assertRetryState(row, attemptCount, 4, "DEAD_LETTER", 3, exceptionType);
                return (Void) null;
            });

        return scenario.eventually(() -> {
                consumer.close();
                producer.close();
                return Future.succeededFuture();
            });
    }

    private Future<Row> awaitTerminalState(
            Vertx vertx,
            String topicName,
            String expectedStatus,
            int remainingAttempts) {
        return manager.getDatabaseService().getConnectionProvider()
                .getReactivePool("peegeeq-main")
                .compose(pool -> pool.withConnection(connection -> connection.preparedQuery("""
                        SELECT status, retry_count
                        FROM outbox
                        WHERE topic = $1
                        ORDER BY created_at DESC
                        LIMIT 1
                        """).execute(Tuple.of(topicName))))
                .compose(rows -> {
                    if (rows.size() == 1) {
                        Row row = rows.iterator().next();
                        if (expectedStatus.equals(row.getString("status"))) {
                            return Future.succeededFuture(row);
                        }
                    }
                    if (remainingAttempts == 0) {
                        return Future.failedFuture(
                                "Message on topic " + topicName + " did not reach " + expectedStatus);
                    }
                    return vertx.timer(100)
                            .compose(v -> awaitTerminalState(
                                    vertx,
                                    topicName,
                                    expectedStatus,
                                    remainingAttempts - 1));
                });
    }

    private void assertRetryState(
            Row row,
            AtomicInteger attemptCount,
            int expectedAttempts,
            String expectedStatus,
            int expectedRetryCount,
            String scenario) {
        assertEquals(expectedAttempts, attemptCount.intValue(),
                "Unexpected attempt count for " + scenario);
        assertEquals(expectedStatus, row.getString("status"),
                "Unexpected terminal status for " + scenario);
        assertEquals(expectedRetryCount, row.getInteger("retry_count"),
                "Unexpected persisted retry count for " + scenario);
    }

    private String newTopic(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @FunctionalInterface
    private interface ExceptionFactory {
        RuntimeException create();
    }
}
