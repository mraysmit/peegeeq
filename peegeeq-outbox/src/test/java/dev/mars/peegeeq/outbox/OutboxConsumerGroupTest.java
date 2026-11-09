package dev.mars.peegeeq.outbox;

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

import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.junit5.Timeout;
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

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests for consumer group functionality in the outbox pattern.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class OutboxConsumerGroupTest {
    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerGroupTest.class);


    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private ConsumerGroup<String> consumerGroup;
    private String testTopic;
    private String testGroupName;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        testTopic = "group-test-topic-" + UUID.randomUUID().toString().substring(0, 8);
        testGroupName = "test-group-" + UUID.randomUUID().toString().substring(0, 8);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA).build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .onSuccess(v -> {
                    PgDatabaseService databaseService = new PgDatabaseService(manager);
                    outboxFactory = new OutboxFactory(databaseService, config);
                    producer = outboxFactory.createProducer(testTopic, String.class);
                    consumerGroup = outboxFactory.createConsumerGroup(testGroupName, testTopic, String.class);
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Tearing down: closing resources and manager");
        Future<Void> closeFactory = outboxFactory != null
                ? outboxFactory.close()
                : Future.succeededFuture();
        closeFactory
                .transform(factoryResult -> {
                    Future<Void> closeManager = manager != null
                            ? manager.closeReactive()
                            : Future.succeededFuture();
                    return closeManager.transform(managerResult -> {
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
    @Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
    void testBasicConsumerGroup(VertxTestContext testContext) {
        logger.info("Test: basic consumer group");
        int messageCount = 6;
        // The set of consumers used is checked once every message has arrived.
        Promise<Void> allReceived = Promise.promise();
        AtomicInteger received = new AtomicInteger(0);
        Set<String> consumerIds = ConcurrentHashMap.newKeySet();

        for (String consumerId : List.of("consumer-1", "consumer-2", "consumer-3")) {
            consumerGroup.addConsumer(consumerId, message -> {
                consumerIds.add(consumerId);
                if (received.incrementAndGet() == messageCount) {
                    allReceived.tryComplete();
                }
                return Future.succeededFuture();
            });
        }

        consumerGroup.start()
                .compose(v -> {
                    List<Future<Void>> sends = new ArrayList<>(messageCount);
                    for (int i = 0; i < messageCount; i++) {
                        sends.add(producer.send("Group test message " + i));
                    }
                    return Future.all(sends).mapEmpty();
                })
                .compose(sent -> allReceived.future())
                .onSuccess(done -> testContext.verify(() -> {
                    assertEquals(messageCount, received.get(), "Every message must be processed once");
                    assertTrue(consumerIds.size() > 1,
                        "Should use multiple consumers for load balancing, used: " + consumerIds);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
    void testConsumerGroupWithFilters(VertxTestContext testContext) {
        logger.info("Test: consumer group with filters");
        int messageCount = 6;
        // A strict checkpoint: exactly six deliveries. Each consumer asserts its region, so with
        // three US and three EU messages each consumer receives exactly its three.
        CountDownLatch messageCheckpoint = testContext.checkpoint().asLatch(messageCount);

        consumerGroup.addConsumer("us-consumer",
            message -> {
                testContext.verify(() -> assertEquals("US", message.getHeaders().get("region"),
                    "us-consumer received " + message.getPayload()));
                messageCheckpoint.countDown();
                return Future.succeededFuture();
            },
            message -> "US".equals(message.getHeaders().get("region"))
        );

        consumerGroup.addConsumer("eu-consumer",
            message -> {
                testContext.verify(() -> assertEquals("EU", message.getHeaders().get("region"),
                    "eu-consumer received " + message.getPayload()));
                messageCheckpoint.countDown();
                return Future.succeededFuture();
            },
            message -> "EU".equals(message.getHeaders().get("region"))
        );

        Map<String, String> usHeaders = Map.of("region", "US");
        Map<String, String> euHeaders = Map.of("region", "EU");

        consumerGroup.start()
                .compose(v -> {
                    List<Future<Void>> sends = new ArrayList<>(messageCount);
                    for (int i = 0; i < 3; i++) {
                        sends.add(producer.send("US message " + i, usHeaders));
                        sends.add(producer.send("EU message " + i, euHeaders));
                    }
                    return Future.all(sends).mapEmpty();
                })
                .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 45, timeUnit = TimeUnit.SECONDS)
    void testConsumerGroupLoadBalancing(VertxTestContext testContext) {
        logger.info("Test: consumer group load balancing");
        int messageCount = 12;
        // The distribution is checked once every message has arrived.
        Promise<Void> allReceived = Promise.promise();
        AtomicInteger received = new AtomicInteger(0);
        Map<String, AtomicInteger> consumerCounts = new ConcurrentHashMap<>();

        for (String consumerId : List.of("consumer-1", "consumer-2", "consumer-3")) {
            consumerCounts.put(consumerId, new AtomicInteger(0));
            consumerGroup.addConsumer(consumerId, message -> {
                consumerCounts.get(consumerId).incrementAndGet();
                if (received.incrementAndGet() == messageCount) {
                    allReceived.tryComplete();
                }
                return Future.succeededFuture();
            });
        }

        consumerGroup.start()
                .compose(v -> {
                    List<Future<Void>> sends = new ArrayList<>(messageCount);
                    for (int i = 0; i < messageCount; i++) {
                        sends.add(producer.send("Load balance test message " + i));
                    }
                    return Future.all(sends).mapEmpty();
                })
                .compose(sent -> allReceived.future())
                .onSuccess(done -> testContext.verify(() -> {
                    consumerCounts.forEach((consumerId, count) ->
                        assertTrue(count.get() > 0,
                            "Consumer " + consumerId + " should process at least one message"));
                    int totalProcessed = consumerCounts.values().stream()
                        .mapToInt(AtomicInteger::get)
                        .sum();
                    assertEquals(messageCount, totalProcessed,
                        "Total processed messages should equal sent messages");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void repeatedCloseCallsShareInProgressSettlement(VertxTestContext testContext) {
        Promise<Void> handlerStarted = Promise.promise();
        Promise<Void> releaseHandler = Promise.promise();

        consumerGroup.addConsumer("close-settlement-consumer", message -> {
            handlerStarted.tryComplete();
            return releaseHandler.future();
        });

        consumerGroup.start()
                .compose(v -> producer.send("close settlement message"))
                .compose(v -> handlerStarted.future())
                .compose(v -> {
                    Future<Void> firstClose = consumerGroup.close();
                    Future<Void> repeatedClose = consumerGroup.close();
                    try {
                        assertSame(firstClose, repeatedClose,
                                "Repeated close calls must share the in-progress settlement Future");
                        assertFalse(repeatedClose.isComplete(),
                                "Close must remain pending while a message handler is in flight");
                    } finally {
                        releaseHandler.tryComplete();
                    }
                    return repeatedClose;
                })
                .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }

    @Test
    @Timeout(value = 20, timeUnit = TimeUnit.SECONDS)
    void testConsumerGroupDynamicScaling(VertxTestContext testContext) {
        logger.info("Test: consumer group dynamic scaling");
        int initialMessageCount = 3;
        int additionalMessageCount = 6;
        int totalMessages = initialMessageCount + additionalMessageCount;
        Promise<Void> initialPhaseDone = Promise.promise();
        Promise<Void> allProcessed = Promise.promise();
        AtomicInteger initialReceived = new AtomicInteger(0);
        AtomicInteger processedCount = new AtomicInteger(0);

        consumerGroup.addConsumer("initial-consumer", message -> {
            if (processedCount.incrementAndGet() == totalMessages) {
                allProcessed.tryComplete();
            }
            if (initialReceived.incrementAndGet() >= initialMessageCount) {
                initialPhaseDone.tryComplete();
            }
            return Future.succeededFuture();
        });

        Set<String> activeConsumers = ConcurrentHashMap.newKeySet();
        consumerGroup.start()
                .compose(v -> {
                    List<Future<Void>> initialSends = new ArrayList<>(initialMessageCount);
                    for (int i = 0; i < initialMessageCount; i++) {
                        initialSends.add(producer.send("Initial message " + i));
                    }
                    return Future.all(initialSends).mapEmpty();
                })
                .compose(v -> initialPhaseDone.future())
                .compose(v -> {
                    for (String consumerId : List.of("additional-consumer-1", "additional-consumer-2")) {
                        consumerGroup.addConsumer(consumerId, message -> {
                            activeConsumers.add(consumerId);
                            if (processedCount.incrementAndGet() == totalMessages) {
                                allProcessed.tryComplete();
                            }
                            return Future.succeededFuture();
                        });
                    }

                    List<Future<Void>> additionalSends = new ArrayList<>(additionalMessageCount);
                    for (int i = 0; i < additionalMessageCount; i++) {
                        additionalSends.add(producer.send("Additional message " + i));
                    }
                    return Future.all(additionalSends).mapEmpty();
                })
                .compose(sent -> allProcessed.future())
                .onSuccess(done -> testContext.verify(() -> {
                    assertEquals(totalMessages, processedCount.get(), "Should process all messages");
                    assertFalse(activeConsumers.isEmpty(), "Should have active additional consumers");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
}
