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

import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Tests for error handling, retry mechanisms, and failure scenarios in the outbox pattern.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
public class OutboxErrorHandlingTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxErrorHandlingTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        // Use unique topic for each test to avoid interference
        testTopic = "error-test-topic-" + UUID.randomUUID().toString().substring(0, 8);

        // Set up database connection
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.max-retries", "3")
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
            .map(v -> {
                // Create factory and components
                DatabaseService databaseService = new PgDatabaseService(manager);
                outboxFactory = new OutboxFactory(databaseService, config);
                producer = outboxFactory.createProducer(testTopic, String.class);
                consumer = outboxFactory.createConsumer(testTopic, String.class);
                return (Void) null;
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Tearing down: closing resources and manager");
        Future<Void> closeFactory = outboxFactory != null
                ? outboxFactory.close()
                : Future.succeededFuture();
        closeFactory
                .eventually(() -> manager != null
                        ? manager.closeReactive()
                        : Future.succeededFuture())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void testMessageProcessingFailureAndRetry(Vertx vertx, VertxTestContext testContext) {
        AtomicInteger attemptCount = new AtomicInteger();

        consumer.subscribe(message -> {
                int attempt = attemptCount.incrementAndGet();
                logger.info("Processing attempt {} for message: {}", attempt, message.getPayload());
                if (attempt < 3) {
                    logger.info("INTENTIONAL FAILURE: Simulating processing failure on attempt {}", attempt);
                    return Future.failedFuture(
                            new RuntimeException("Simulated processing failure, attempt " + attempt));
                }
                logger.info("SUCCESS: Processing succeeded on attempt {}", attempt);
                return Future.succeededFuture();
            })
            .compose(v -> producer.send("Message that will fail initially"))
            .compose(v -> awaitStatus(vertx, "COMPLETED", 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertState(row, attemptCount, 3, "COMPLETED", 2);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testConsumerExceptionHandling(Vertx vertx, VertxTestContext testContext) {
        AtomicInteger exceptionCount = new AtomicInteger();

        consumer.subscribe(message -> {
                int count = exceptionCount.incrementAndGet();
                logger.info("INTENTIONAL FAILURE: Processing attempt {}, throwing exception", count);
                throw new RuntimeException("Intentional exception for testing");
            })
            .compose(v -> producer.send("Message that causes exception"))
            .compose(v -> awaitStatus(vertx, "DEAD_LETTER", 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertState(row, exceptionCount, 4, "DEAD_LETTER", 3);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testProducerWithClosedConnection(VertxTestContext testContext) {
        logger.info("===== RUNNING INTENTIONAL CLOSED CONNECTION TEST =====");
        producer.close();

        producer.send("Message after close")
            .onSuccess(v -> testContext.failNow("Sending with closed producer should fail"))
            .onFailure(cause -> testContext.verify(() -> {
                assertNotNull(cause, "Closed producer failure should expose a cause");
                logger.info("Closed producer correctly rejected the send: {}", cause.getMessage());
                testContext.completeNow();
            }));
    }

    @Test
    void testConsumerUnsubscribe(Vertx vertx, VertxTestContext testContext) {
        AtomicInteger receivedCount = new AtomicInteger();

        consumer.subscribe(message -> {
                receivedCount.incrementAndGet();
                return Future.succeededFuture();
            })
            .compose(v -> producer.send("First message"))
            .compose(v -> awaitStatus(vertx, "COMPLETED", 100))
            .compose(row -> {
                assertState(row, receivedCount, 1, "COMPLETED", 0);
                consumer.unsubscribe();
                return producer.send("Second message after unsubscribe");
            })
            .compose(v -> latestMessage())
            .onSuccess(row -> testContext.verify(() -> {
                assertEquals("PENDING", row.getString("status"));
                assertEquals(1, receivedCount.intValue(),
                        "Should not receive messages after unsubscribe");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testConsumerClose(Vertx vertx, VertxTestContext testContext) {
        AtomicInteger receivedCount = new AtomicInteger();

        consumer.subscribe(message -> {
                receivedCount.incrementAndGet();
                return Future.succeededFuture();
            })
            .compose(v -> producer.send("Message before close"))
            .compose(v -> awaitStatus(vertx, "COMPLETED", 100))
            .compose(row -> {
                assertState(row, receivedCount, 1, "COMPLETED", 0);
                consumer.close();
                return producer.send("Message after close");
            })
            .compose(v -> latestMessage())
            .onSuccess(row -> testContext.verify(() -> {
                assertEquals("PENDING", row.getString("status"));
                assertEquals(1, receivedCount.intValue(),
                        "Should not receive messages after consumer close");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testNullMessageHandling(VertxTestContext testContext) {
        producer.send(null)
            .onSuccess(v -> testContext.failNow("Sending null payload should fail"))
            .onFailure(cause -> testContext.verify(() -> {
                assertInstanceOf(IllegalArgumentException.class, cause);
                testContext.completeNow();
            }));
    }

    @Test
    void testLargeMessageHandling(Vertx vertx, VertxTestContext testContext) {
        String testMessage = "x".repeat(1_048_576);
        AtomicInteger receivedCount = new AtomicInteger();
        AtomicInteger receivedLength = new AtomicInteger();

        consumer.subscribe(message -> {
                receivedCount.incrementAndGet();
                receivedLength.set(message.getPayload().length());
                return Future.succeededFuture();
            })
            .compose(v -> producer.send(testMessage))
            .compose(v -> awaitStatus(vertx, "COMPLETED", 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertState(row, receivedCount, 1, "COMPLETED", 0);
                assertEquals(testMessage.length(), receivedLength.intValue());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    private Future<Row> awaitStatus(Vertx vertx, String expectedStatus, int remainingAttempts) {
        return latestMessage().compose(row -> {
            if (expectedStatus.equals(row.getString("status"))) {
                return Future.succeededFuture(row);
            }
            if (remainingAttempts == 0) {
                return Future.failedFuture(
                        "Message on topic " + testTopic + " did not reach " + expectedStatus);
            }
            return vertx.timer(100)
                    .compose(v -> awaitStatus(vertx, expectedStatus, remainingAttempts - 1));
        });
    }

    private Future<Row> latestMessage() {
        return manager.getDatabaseService().getConnectionProvider()
                .getReactivePool("peegeeq-main")
                .compose(pool -> pool.withConnection(connection -> connection.preparedQuery("""
                        SELECT status, retry_count
                        FROM outbox
                        WHERE topic = $1
                        ORDER BY id DESC
                        LIMIT 1
                        """).execute(Tuple.of(testTopic))))
                .compose(rows -> rows.size() == 1
                        ? Future.succeededFuture(rows.iterator().next())
                        : Future.failedFuture("No message found for topic " + testTopic));
    }

    private void assertState(
            Row row,
            AtomicInteger observedCount,
            int expectedAttempts,
            String expectedStatus,
            int expectedRetryCount) {
        assertEquals(expectedAttempts, observedCount.intValue());
        assertEquals(expectedStatus, row.getString("status"));
        assertEquals(expectedRetryCount, row.getInteger("retry_count"));
    }
}
