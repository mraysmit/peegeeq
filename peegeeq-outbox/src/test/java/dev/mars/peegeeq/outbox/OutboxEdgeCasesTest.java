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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Row;
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

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Test suite for edge cases and error conditions in outbox exception handling.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
public class OutboxEdgeCasesTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxEdgeCasesTest.class);

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

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.max-retries", "2")
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps), new SimpleMeterRegistry());
        manager.start()
            .map(v -> {
                PgDatabaseService databaseService = new PgDatabaseService(manager);
                PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
                OutboxFactoryRegistrar.registerWith(provider);
                factory = provider.createFactory("outbox", databaseService);
                topic = "test-edge-cases-" + UUID.randomUUID().toString().substring(0, 8);
                producer = factory.createProducer(topic, String.class);
                consumer = factory.createConsumer(topic, String.class);
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
    void testNullFutureReturn(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Null Future Return ===");
        
        String testMessage = "Message that returns null future";
        AtomicInteger attemptCount = new AtomicInteger(0);

        // Set up consumer that returns null Future
        consumer.subscribe(message -> {
                int attempt = attemptCount.incrementAndGet();
                logger.info("INTENTIONAL FAILURE: Processing attempt {} returning null Future", attempt);

                // Return null - should be converted to a handler failure
                return null;
            })
            .compose(v -> producer.send(testMessage))
            .compose(v -> awaitDeadLetterState(vertx, 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertTerminalFailure(row, attemptCount, "null Future");
                logger.info("Null Future return test completed successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testExceptionDuringMessageAccess(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing Exception During Message Access ===");
        
        String testMessage = "Message for access exception test";
        AtomicInteger attemptCount = new AtomicInteger(0);

        // Set up consumer that throws exception after accessing message properties
        consumer.subscribe(message -> {
                int attempt = attemptCount.incrementAndGet();
                logger.info("INTENTIONAL FAILURE: Processing attempt {} with message access exception", attempt);

                String payload = message.getPayload();
                if (payload != null) {
                    throw new IllegalStateException(
                            "INTENTIONAL FAILURE: Exception during message access, attempt " + attempt);
                }

                return Future.succeededFuture();
            })
            .compose(v -> producer.send(testMessage))
            .compose(v -> awaitDeadLetterState(vertx, 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertTerminalFailure(row, attemptCount, "message access exception");
                logger.info("Exception during message access test completed successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testInterruptedCauseHandling(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing InterruptedException Cause Handling ===");
        
        String testMessage = "Message with interrupted failure cause";
        AtomicInteger attemptCount = new AtomicInteger(0);

        consumer.subscribe(message -> {
                int attempt = attemptCount.incrementAndGet();
                logger.info("INTENTIONAL FAILURE: Processing attempt {} with interrupted cause", attempt);

                // Preserve the InterruptedException as diagnostic cause without mutating
                // the shared Vert.x event-loop thread's interrupt flag.
                throw new RuntimeException(
                        "INTENTIONAL FAILURE: Interrupted cause, attempt " + attempt,
                        new InterruptedException("Simulated interruption"));
            })
            .compose(v -> producer.send(testMessage))
            .compose(v -> awaitDeadLetterState(vertx, 100))
            .onSuccess(row -> testContext.verify(() -> {
                assertTerminalFailure(row, attemptCount, "interrupted cause");
                logger.info("InterruptedException cause handling test completed successfully");
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

