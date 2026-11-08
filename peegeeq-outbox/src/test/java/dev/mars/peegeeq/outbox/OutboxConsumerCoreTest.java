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
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import dev.mars.peegeeq.test.vertx.TestCleanup;

/**
 * Integration tests for OutboxConsumer core functionality.
 * Tests consumer operations with real database using TestContainers.
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
    void setUp(VertxTestContext testContext) {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        testTopic = "consumer-test-" + UUID.randomUUID().toString().substring(0, 8);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA).build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
            .onSuccess(v -> testContext.verify(() -> {
                DatabaseService databaseService = new PgDatabaseService(manager);
                outboxFactory = new OutboxFactory(databaseService, config);
                producer = outboxFactory.createProducer(testTopic, String.class);
                consumer = outboxFactory.createConsumer(testTopic, String.class);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        if (consumer != null) {
            consumer.close();
        }
        if (producer != null) {
            producer.close();
        }
        Future<Void> factoryClose = outboxFactory != null
                ? outboxFactory.close()
                : Future.succeededFuture();
        factoryClose
            .transform(TestCleanup.cleanup(() -> manager != null
                    ? manager.closeReactive()
                    : Future.succeededFuture()))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    void testConsumerCreation() {
        assertNotNull(consumer, "Consumer should be created");
    }

    @Test
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    void testConsumerSubscribe(Vertx vertx, VertxTestContext testContext) {
        logger.info("Test: consumer subscribe");
        // A strict checkpoint: a second delivery fails the test.
        Checkpoint received = testContext.checkpoint();

        consumer.subscribe(message -> {
            testContext.verify(() ->
                assertEquals("Test message for subscribe", message.getPayload(), "Should receive correct message"));
            received.flag();
            return Future.succeededFuture();
        })
            .compose(v -> producer.send("Test message for subscribe"))
            .onFailure(testContext::failNow);
    }

    @Test
    void testConsumerUnsubscribe(Vertx vertx, VertxTestContext testContext) throws Exception {
        AtomicInteger messageCount = new AtomicInteger(0);
        Promise<Void> firstReceived = Promise.promise();

        consumer.subscribe(message -> {
            logger.info("Test: consumer unsubscribe");
            messageCount.incrementAndGet();
            firstReceived.tryComplete();
            return Future.succeededFuture();
        })
            .compose(v -> producer.send("Message 1"))
            .onFailure(testContext::failNow);
        firstReceived.future()
            .onSuccess(v -> testContext.verify(() -> assertEquals(1, messageCount.get(), "Should have received one message")))
            .compose(v -> {
                consumer.unsubscribe();
                return vertx.timer(1000);
            })
            .compose(v -> producer.send("Message 2"))
            .compose(v -> vertx.timer(2000))
            .onSuccess(v -> testContext.verify(() -> {
                assertEquals(1, messageCount.get(), "Should not receive messages after unsubscribe");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
    void testConsumerReceivesMultipleMessages(Vertx vertx, VertxTestContext testContext) {
        int messageCount = 5;
        // A strict checkpoint: a sixth delivery fails the test; a repeated payload fails it too.
        Checkpoint received = testContext.checkpoint(messageCount);
        Set<String> payloads = ConcurrentHashMap.newKeySet();

        consumer.subscribe(message -> {
            testContext.verify(() -> {
                assertTrue(message.getPayload().startsWith("Message "), "Unexpected payload: " + message.getPayload());
                assertTrue(payloads.add(message.getPayload()), "Delivered twice: " + message.getPayload());
            });
            received.flag();
            return Future.succeededFuture();
        })
            .compose(v -> {
                List<Future<Void>> sends = new ArrayList<>();
                for (int i = 0; i < messageCount; i++) {
                    sends.add(producer.send("Message " + i));
                }
                return Future.all(sends).mapEmpty();
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    void testConsumerReceivesMessagesWithHeaders(Vertx vertx, VertxTestContext testContext) {
        // A strict checkpoint: a second delivery fails the test.
        Checkpoint received = testContext.checkpoint();
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");
        headers.put("source", "test");

        consumer.subscribe(message -> {
            testContext.verify(() -> {
                assertNotNull(message.getHeaders(), "Should receive headers");
                assertEquals("application/json", message.getHeaders().get("content-type"), "Header value should match");
                assertEquals("test", message.getHeaders().get("source"), "Header value should match");
            });
            received.flag();
            return Future.succeededFuture();
        })
            .compose(v -> producer.send("Message with headers", headers))
            .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
    void testConsumerHandlerException(Vertx vertx, VertxTestContext testContext) {
        // The first attempt fails and the message is delivered again; the second attempt
        // succeeds. A strict two-pass checkpoint: a third delivery fails the test.
        Checkpoint attempts = testContext.checkpoint(2);
        AtomicInteger attemptCount = new AtomicInteger(0);

        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            attempts.flag();
            if (attempt == 1) {
                return Future.failedFuture(new RuntimeException("Handler error"));
            }
            return Future.succeededFuture();
        })
            .compose(v -> producer.send("Message that causes error"))
            .onFailure(testContext::failNow);
    }

    @Test
    void testConsumerClose(Vertx vertx, VertxTestContext testContext) throws Exception {
        AtomicInteger messageCount = new AtomicInteger(0);
        Promise<Void> firstReceived = Promise.promise();

        consumer.subscribe(message -> {
            logger.info("Test: consumer close");
            messageCount.incrementAndGet();
            firstReceived.tryComplete();
            return Future.succeededFuture();
        })
            .compose(v -> producer.send("Message before close"))
            .onFailure(testContext::failNow);
        firstReceived.future()
            .compose(v -> {
                consumer.close();
                return producer.send("Message after close");
            })
            .compose(v -> vertx.timer(2000))
            .onSuccess(v -> testContext.verify(() -> {
                assertEquals(1, messageCount.get(), "Should only have received one message");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
    }

    @Test
    void testSubscribeOnClosedConsumerThrowsIllegalStateException(Vertx vertx, VertxTestContext testContext) throws Exception {
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
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    void testConsumerGroupNameSetting(Vertx vertx, VertxTestContext testContext) {
        // Close the setUp consumer and replace with a group-named one;
        // tearDown will close the reassigned field, avoiding an explicit close() here.
        consumer.close();
        consumer = outboxFactory.createConsumer(testTopic, String.class);

        if (consumer instanceof OutboxConsumer) {
            logger.info("Test: consumer group name setting");
            ((OutboxConsumer<String>) consumer).setConsumerGroupName("test-group");
        }

        // A strict checkpoint: a second delivery fails the test.
        Checkpoint received = testContext.checkpoint();

        consumer.subscribe(message -> {
            received.flag();
            return Future.succeededFuture();
        })
            .compose(v -> producer.send("Message for consumer group"))
            .onFailure(testContext::failNow);
    }
}
