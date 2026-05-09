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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.core.Future;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Integration tests for OutboxConsumer core functionality.
 * Tests consumer operations with real database using TestContainers.
 * 
 * Focuses on testing uncovered consumer methods to increase coverage from 75% to 90%+:
 * - Consumer lifecycle (subscribe, unsubscribe, close)
 * - Message receiving and handling
 * - Consumer group tracking
 * - Error handling during consumption
 * - Configuration-based behavior
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxConsumerCoreTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerCoreTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;

    @BeforeEach
    void setUp() throws Exception {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        testTopic = "consumer-test-" + UUID.randomUUID().toString().substring(0, 8);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres).build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, String.class);
        consumer = outboxFactory.createConsumer(testTopic, String.class);
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
        if (manager != null) {
            manager.closeReactive().await();
        }

    }

    @Test
    void testConsumerCreation() {
        assertNotNull(consumer, "Consumer should be created");
    }

    @Test
    void testConsumerSubscribe(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: consumer creation");
        Checkpoint latch = testContext.checkpoint();
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        consumer.subscribe(message -> {
            receivedMessage.set(message.getPayload());
            latch.flag();
            return Future.succeededFuture();
        });

        String testMessage = "Test message for subscribe";
        producer.send(testMessage).await();

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should receive message within timeout");
        assertEquals(testMessage, receivedMessage.get(), "Should receive correct message");
    }

    @Test
    void testConsumerUnsubscribe(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        io.vertx.core.Promise<Void> firstReceivedPromise = io.vertx.core.Promise.promise();
        AtomicInteger messageCount = new AtomicInteger(0);

        consumer.subscribe(message -> {
        logger.info("Test: consumer unsubscribe");
            messageCount.incrementAndGet();
            firstReceivedPromise.tryComplete();
            return Future.succeededFuture();
        });

        producer.send("Message 1").await();
        firstReceivedPromise.future().await();
        assertEquals(1, messageCount.get(), "Should have received one message");

        consumer.unsubscribe();

        vertx.timer(1000).await();

        producer.send("Message 2").await();
        vertx.timer(2000).await();

        assertEquals(1, messageCount.get(), "Should not receive messages after unsubscribe");
        testContext.completeNow();
    }

    @Test
    void testConsumerReceivesMultipleMessages(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        int messageCount = 5;
        Checkpoint latch = testContext.checkpoint(messageCount);
        AtomicInteger receivedCount = new AtomicInteger(0);

        consumer.subscribe(message -> {
        logger.info("Test: consumer receives multiple messages");
            receivedCount.incrementAndGet();
            latch.flag();
            return Future.succeededFuture();
        });

        for (int i = 0; i < messageCount; i++) {
            producer.send("Message " + i).await();
        }

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Should receive all messages within timeout");
        assertEquals(messageCount, receivedCount.get(), "Should receive all messages");
    }

    @Test
    void testConsumerReceivesMessagesWithHeaders(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint latch = testContext.checkpoint();
        AtomicReference<Map<String, String>> receivedHeaders = new AtomicReference<>();

        consumer.subscribe(message -> {
        logger.info("Test: consumer receives messages with headers");
            receivedHeaders.set(message.getHeaders());
            latch.flag();
            return Future.succeededFuture();
        });

        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");
        headers.put("source", "test");

        producer.send("Message with headers", headers).await();

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should receive message within timeout");
        assertNotNull(receivedHeaders.get(), "Should receive headers");
        assertTrue(receivedHeaders.get().containsKey("content-type"), "Should contain content-type header");
        assertEquals("application/json", receivedHeaders.get().get("content-type"), "Header value should match");
    }

    @Test
    void testConsumerHandlerException(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint latch = testContext.checkpoint();
        AtomicInteger attemptCount = new AtomicInteger(0);

        consumer.subscribe(message -> {
        logger.info("Test: consumer handler exception");
            int attempt = attemptCount.incrementAndGet();
            latch.flag();

            if (attempt == 1) {
                return Future.failedFuture(new RuntimeException("Handler error"));
            }
            return Future.succeededFuture();
        });

        producer.send("Message that causes error").await();

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should attempt to process message");
        assertTrue(attemptCount.get() >= 1, "Should have at least one processing attempt");
    }

    @Test
    void testConsumerClose(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        io.vertx.core.Promise<Void> firstReceivedPromise = io.vertx.core.Promise.promise();
        AtomicInteger messageCount = new AtomicInteger(0);

        consumer.subscribe(message -> {
        logger.info("Test: consumer close");
            messageCount.incrementAndGet();
            firstReceivedPromise.tryComplete();
            return Future.succeededFuture();
        });

        producer.send("Message before close").await();
        firstReceivedPromise.future().await();

        consumer.close();

        producer.send("Message after close").await();

        vertx.timer(2000).await();

        assertEquals(1, messageCount.get(), "Should only have received one message");
        testContext.completeNow();
    }

    @Test
    void testSubscribeOnClosedConsumerThrowsIllegalStateException(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: subscribe on closed consumer");
        consumer.close();

        testContext.verify(() -> {
            Future<Void> result = consumer.subscribe(message -> Future.succeededFuture());
            assertTrue(result.failed(), "Subscribing to a closed consumer should return a failed future");
            assertInstanceOf(IllegalStateException.class, result.cause(),
                    "Subscribing to a closed consumer should fail with IllegalStateException");
        });
        testContext.completeNow();
    }

    @Test
    void testConsumerGroupNameSetting(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        MessageConsumer<String> groupConsumer = outboxFactory.createConsumer(testTopic, String.class);

        if (groupConsumer instanceof OutboxConsumer) {
        logger.info("Test: consumer group name setting");
            ((OutboxConsumer<String>) groupConsumer).setConsumerGroupName("test-group");
        }

        Checkpoint latch = testContext.checkpoint();

        groupConsumer.subscribe(message -> {
            latch.flag();
            return Future.succeededFuture();
        });

        producer.send("Message for consumer group").await();

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Consumer with group should receive message");

        groupConsumer.close();
    }
}


