package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;

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
import dev.mars.peegeeq.api.deadletter.DeadLetterMessageInfo;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Test suite for verifying dead letter queue integration with direct exception handling.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxDeadLetterQueueTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxDeadLetterQueueTest.class);

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory factory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;

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
        manager.start()
                .compose(v -> {
                    PgDatabaseService databaseService = new PgDatabaseService(manager);
                    PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
                    OutboxFactoryRegistrar.registerWith(provider);
                    factory = provider.createFactory("outbox", databaseService);
                    producer = factory.createProducer("test-dlq-integration", String.class);
                    consumer = factory.createConsumer("test-dlq-integration", String.class);
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Tearing down: closing resources and manager");
        Future<Void> factoryClose = factory != null ? factory.close() : Future.succeededFuture();
        factoryClose.transform(factoryResult -> {
                    Future<Void> managerClose = manager != null
                            ? manager.closeReactive()
                            : Future.succeededFuture();
                    return managerClose.transform(managerResult -> {
                        if (factoryResult.failed()) {
                            return Future.failedFuture(factoryResult.cause());
                        }
                        if (managerResult.failed()) {
                            return Future.failedFuture(managerResult.cause());
                        }
                        return Future.succeededFuture();
                    });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void testDirectExceptionMovesToDeadLetterQueue(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Testing Direct Exception Moves to Dead Letter Queue ===");
        
        String testMessage = "Message that should go to DLQ";
        AtomicInteger attemptCount = new AtomicInteger(0);
        Promise<Void> retriesComplete = Promise.promise();
        // Factory created via registrar without PeeGeeQConfiguration, so max-retries defaults to 3
        // initial attempt + 3 retries = 4 total handler invocations
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: DLQ test attempt {} for message: {}", 
                attempt, message.getPayload());
            if (attempt == 4) {
                retriesComplete.complete();
            }
            throw new RuntimeException("INTENTIONAL FAILURE: Should go to DLQ, attempt " + attempt);
        })
                .compose(v -> producer.send(testMessage))
                .compose(v -> retriesComplete.future())
                .compose(v -> vertx.timer(2000))
                .compose(v -> manager.getDeadLetterQueueManager()
                        .getDeadLetterMessages("test-dlq-integration", 10, 0))
                .map(dlqMessages -> {
                    assertEquals(4, attemptCount.get(),
                            "Should have made exactly 4 processing attempts (1 initial + 3 retries, max-retries=3 default)");
                    assertFalse(dlqMessages.isEmpty(), "Should have at least one message in dead letter queue");

                    DeadLetterMessageInfo dlqMessage = dlqMessages.get(0);
                    assertEquals("test-dlq-integration", dlqMessage.topic(), "DLQ message should have correct topic");
                    assertTrue(dlqMessage.failureReason().contains("Should go to DLQ"),
                            "DLQ message should contain failure reason");
                    assertEquals(3, dlqMessage.retryCount(),
                            "DLQ message should show retry_count=3 (max-retries=3 default, DLQ triggered when retry_count >= max-retries)");
                    return (Void) null;
                })
                .onSuccess(v -> {
                    logger.info("Direct exception DLQ integration test completed successfully");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @Test
    void testDLQErrorInformationPreservation(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Testing DLQ Error Information Preservation ===");
        
        String testMessage = "Message with detailed error info";
        String customErrorMessage = "Custom business validation failed: Invalid order amount";
        AtomicInteger attemptCount = new AtomicInteger(0);
        Promise<Void> retriesComplete = Promise.promise();
        // Factory created via registrar without PeeGeeQConfiguration, so max-retries defaults to 3
        // initial attempt + 3 retries = 4 total handler invocations
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Error info test attempt {} for message: {}", 
                attempt, message.getPayload());
            if (attempt == 4) {
                retriesComplete.complete();
            }
            throw new IllegalArgumentException(customErrorMessage + " (attempt " + attempt + ")");
        })
                .compose(v -> producer.send(testMessage))
                .compose(v -> retriesComplete.future())
                .compose(v -> vertx.timer(2000))
                .compose(v -> manager.getDeadLetterQueueManager()
                        .getDeadLetterMessages("test-dlq-integration", 10, 0))
                .map(dlqMessages -> {
                    assertEquals(4, attemptCount.get(), "Should complete all retry attempts");
                    assertFalse(dlqMessages.isEmpty(), "Should have message in DLQ");

                    DeadLetterMessageInfo dlqMessage = dlqMessages.get(0);
                    assertTrue(dlqMessage.failureReason().contains(customErrorMessage),
                            "DLQ should preserve custom error message");
                    assertTrue(dlqMessage.failureReason().contains("IllegalArgumentException"),
                            "DLQ should include exception type information");
                    return (Void) null;
                })
                .onSuccess(v -> {
                    logger.info("DLQ error information preservation test completed successfully");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }
}
