package dev.mars.peegeeq.outbox;

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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Debug test to understand why retry mechanism is not working.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class RetryDebugTest {

    private static final Logger logger = LoggerFactory.getLogger(RetryDebugTest.class);

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private OutboxFactory outboxFactory;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.max-retries", "3")
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps), new SimpleMeterRegistry());
        manager.start().map(v -> {
            DatabaseService databaseService = new PgDatabaseService(manager);
            outboxFactory = new OutboxFactory(databaseService, manager.getConfiguration());

            logger.info("Creating producer and consumer...");
            producer = outboxFactory.createProducer("debug-retry", String.class);
            logger.info("Producer created: {}", producer.getClass().getSimpleName());

            consumer = outboxFactory.createConsumer("debug-retry", String.class);
            logger.info("Consumer created: {}", consumer.getClass().getName());
            logger.info("Consumer created: {}", consumer.getClass().getSimpleName());
            return (Void) null;
        })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
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

    /**
     * Checks database state using reactive pool for verification queries.
     */
    private Future<Row> awaitDeadLetterState(Vertx vertx, int remainingAttempts) {
        return manager.getDatabaseService().getConnectionProvider()
            .getReactivePool("peegeeq-main")
            .compose(pool -> pool.withConnection(conn -> conn.preparedQuery("""
                    SELECT status, retry_count, max_retries
                    FROM outbox
                    WHERE topic = 'debug-retry'
                    ORDER BY created_at DESC
                    LIMIT 1
                    """).execute()))
            .compose(rows -> {
                if (rows.size() == 1) {
                    Row row = rows.iterator().next();
                    if ("DEAD_LETTER".equals(row.getString("status"))) {
                        return Future.succeededFuture(row);
                    }
                }
                if (remainingAttempts <= 0) {
                    return Future.failedFuture("Message did not reach DEAD_LETTER state");
                }
                return vertx.timer(100)
                        .compose(v -> awaitDeadLetterState(vertx, remainingAttempts - 1));
            });
    }

    @Test
    void debugRetryMechanism(Vertx vertx, VertxTestContext testContext) throws Exception {
        int maxRetries = 3;
        int expectedAttempts = maxRetries + 1;
        String testMessage = "Debug retry message";
        AtomicInteger attemptCount = new AtomicInteger(0);
        Promise<Void> exhaustedAttempts = Promise.promise();

        logger.info("Sending message: {}", testMessage);

        producer.send(testMessage)
            .compose(v -> {
                logger.info("Message sent successfully: {}", testMessage);
                return consumer.subscribe(message -> {
                    int attempt = attemptCount.incrementAndGet();
                    logger.info("ATTEMPT {}: Processing message: {}", attempt, message.getPayload());
                    if (attempt == expectedAttempts) {
                        exhaustedAttempts.tryComplete();
                    }
                    return Future.failedFuture(
                            new RuntimeException("INTENTIONAL FAILURE: Debug retry, attempt " + attempt));
                });
            })
            .compose(v -> exhaustedAttempts.future())
            .compose(v -> awaitDeadLetterState(vertx, 50))
            .onSuccess(row -> testContext.verify(() -> {
                assertEquals(expectedAttempts, attemptCount.intValue(),
                        "The initial delivery plus each configured retry must run exactly once");
                assertEquals("DEAD_LETTER", row.getString("status"));
                assertEquals(maxRetries, row.getInteger("retry_count"));
                assertEquals(maxRetries, row.getInteger("max_retries"));
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
}

