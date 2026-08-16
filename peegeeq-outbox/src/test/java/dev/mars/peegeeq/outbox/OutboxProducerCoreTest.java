package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Integration tests for OutboxProducer.
 * Tests producer functionality with real database using TestContainers.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxProducerCoreTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxProducerCoreTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private String testTopic;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        logger.info("=== OutboxProducerCoreTest SETUP STARTED ===");

        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        // Use unique topic for each test to avoid interference
        testTopic = "test-topic-" + UUID.randomUUID().toString().substring(0, 8);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.5S")
                .build();
        // Create and start manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .compose(v -> {
                    DatabaseService databaseService = new PgDatabaseService(manager);
                    outboxFactory = new OutboxFactory(databaseService, config);
                    producer = outboxFactory.createProducer(testTopic, String.class);
                    logger.info("=== OutboxProducerCoreTest SETUP COMPLETED ===");
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext tearDownContext) {
        logger.info("=== OutboxProducerCoreTest TEARDOWN STARTED ===");

        Future<Void> factoryClose = outboxFactory != null
                ? outboxFactory.close()
                : Future.succeededFuture();
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
                .onSuccess(v -> {
                    logger.info("=== OutboxProducerCoreTest TEARDOWN COMPLETED ===");
                    tearDownContext.completeNow();
                })
                .onFailure(tearDownContext::failNow);
    }

    private void verifyMessageCount(Future<Void> sendFuture, long expectedCount,
                                    VertxTestContext testContext, String completionMessage) {
        assertNotNull(sendFuture, "Send should return a future");
        sendFuture
                .compose(v -> outboxFactory.countMessages(testTopic))
                .map(count -> {
                    assertEquals(expectedCount, count, "Persisted outbox message count");
                    return (Void) null;
                })
                .onSuccess(v -> {
                    logger.info(completionMessage);
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @Test
    void testProducerCreation() {
        logger.info("=== TEST: testProducerCreation STARTED ===");

        assertNotNull(producer, "Producer should be created");

        logger.info("=== TEST: testProducerCreation COMPLETED ===");
    }

    @Test
    void testSendBasicMessage(VertxTestContext testContext) {
        logger.info("Test: producer creation");
        logger.info("=== TEST: testSendBasicMessage STARTED ===");

        String testMessage = "Hello, OutboxProducer Test!";

        Future<Void> sendFuture = producer.send(testMessage);
        verifyMessageCount(sendFuture, 1, testContext,
                "=== TEST: testSendBasicMessage COMPLETED ===");
    }

    @Test
    void testSendMessageWithHeaders(VertxTestContext testContext) {
        logger.info("=== TEST: testSendMessageWithHeaders STARTED ===");

        String testMessage = "Message with headers";
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "text/plain");
        headers.put("source", "producer-test");

        Future<Void> sendFuture = producer.send(testMessage, headers);
        verifyMessageCount(sendFuture, 1, testContext,
                "=== TEST: testSendMessageWithHeaders COMPLETED ===");
    }

    @Test
    void testSendMessageWithCorrelationId(VertxTestContext testContext) {
        logger.info("Test: send message with headers");
        logger.info("=== TEST: testSendMessageWithCorrelationId STARTED ===");

        String testMessage = "Message with correlation ID";
        Map<String, String> headers = new HashMap<>();
        headers.put("test-header", "test-value");
        String correlationId = UUID.randomUUID().toString();

        Future<Void> sendFuture = producer.send(testMessage, headers, correlationId);
        verifyMessageCount(sendFuture, 1, testContext,
                "=== TEST: testSendMessageWithCorrelationId COMPLETED ===");
    }

    @Test
    void testSendMessageWithAllParameters(VertxTestContext testContext) {
        logger.info("=== TEST: testSendMessageWithAllParameters STARTED ===");

        String testMessage = "Message with all parameters";
        Map<String, String> headers = new HashMap<>();
        headers.put("test-header", "test-value");
        String correlationId = UUID.randomUUID().toString();
        String messageGroup = "test-group";

        Future<Void> sendFuture = producer.send(testMessage, headers, correlationId, messageGroup);
        verifyMessageCount(sendFuture, 1, testContext,
                "=== TEST: testSendMessageWithAllParameters COMPLETED ===");
    }

    @Test
    void testSendMultipleMessages(VertxTestContext testContext) {
        logger.info("Test: send message with all parameters");
        logger.info("=== TEST: testSendMultipleMessages STARTED ===");

        int messageCount = 10;
        Future<Void> sendChain = Future.succeededFuture();
        for (int i = 0; i < messageCount; i++) {
            String message = "Message " + i;
            sendChain = sendChain.compose(v -> producer.send(message));
        }

        verifyMessageCount(sendChain, messageCount, testContext,
                "=== TEST: testSendMultipleMessages COMPLETED ===");
    }

    @Test
    void testProducerClose(VertxTestContext testContext) {
        logger.info("=== TEST: testProducerClose STARTED ===");

        producer.send("test message")
                .compose(v -> outboxFactory.countMessages(testTopic))
                .compose(count -> {
                    assertEquals(1L, count, "Message should be persisted before close");
                    producer.close();
                    return producer.send("must fail after close")
                            .transform(sendAfterClose -> {
                                assertTrue(sendAfterClose.failed(), "Send after close should fail");
                                return Future.succeededFuture();
                            });
                })
                .onSuccess(v -> {
                    logger.info("=== TEST: testProducerClose COMPLETED ===");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }
}
