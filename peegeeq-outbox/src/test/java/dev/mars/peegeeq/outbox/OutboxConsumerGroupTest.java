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
import io.vertx.junit5.Checkpoint;
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
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    void setUp() throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        testTopic = "group-test-topic-" + UUID.randomUUID().toString().substring(0, 8);
        testGroupName = "test-group-" + UUID.randomUUID().toString().substring(0, 8);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres).build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        PgDatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, String.class);
        consumerGroup = outboxFactory.createConsumerGroup(testGroupName, testTopic, String.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.info("Tearing down: closing resources and manager");
        if (consumerGroup != null) {
            consumerGroup.close().onFailure(e -> logger.warn("consumerGroup.close() failed in tearDown", e));
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
    void testBasicConsumerGroup(VertxTestContext testContext) throws Exception {
        logger.info("Test: basic consumer group");
        int messageCount = 6;
        Checkpoint messageCheckpoint = testContext.checkpoint(messageCount);
        Set<String> consumerIds = ConcurrentHashMap.newKeySet();

        consumerGroup.addConsumer("consumer-1", message -> {
            consumerIds.add("consumer-1");
            messageCheckpoint.flag();
            return Future.succeededFuture();
        });

        consumerGroup.addConsumer("consumer-2", message -> {
            consumerIds.add("consumer-2");
            messageCheckpoint.flag();
            return Future.succeededFuture();
        });

        consumerGroup.addConsumer("consumer-3", message -> {
            consumerIds.add("consumer-3");
            messageCheckpoint.flag();
            return Future.succeededFuture();
        });

        consumerGroup.start();

        for (int i = 0; i < messageCount; i++) {
            producer.send("Group test message " + i).await();
        }

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
            "All messages should be processed by consumer group within timeout");

        assertTrue(consumerIds.size() > 1,
            "Should use multiple consumers for load balancing, used: " + consumerIds);
    }

    @Test
    void testConsumerGroupWithFilters(VertxTestContext testContext) throws Exception {
        logger.info("Test: consumer group with filters");
        int messageCount = 6;
        Checkpoint messageCheckpoint = testContext.checkpoint(messageCount);
        AtomicInteger usProcessedCount = new AtomicInteger(0);
        AtomicInteger euProcessedCount = new AtomicInteger(0);

        consumerGroup.addConsumer("us-consumer",
            message -> {
                usProcessedCount.incrementAndGet();
                messageCheckpoint.flag();
                return Future.succeededFuture();
            },
            message -> "US".equals(message.getHeaders().get("region"))
        );

        consumerGroup.addConsumer("eu-consumer",
            message -> {
                euProcessedCount.incrementAndGet();
                messageCheckpoint.flag();
                return Future.succeededFuture();
            },
            message -> "EU".equals(message.getHeaders().get("region"))
        );

        consumerGroup.start();

        Map<String, String> usHeaders = Map.of("region", "US");
        Map<String, String> euHeaders = Map.of("region", "EU");

        for (int i = 0; i < 3; i++) {
            producer.send("US message " + i, usHeaders).await();
            producer.send("EU message " + i, euHeaders).await();
        }

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
            "All filtered messages should be processed within timeout");

        assertEquals(3, usProcessedCount.get(), "Should process 3 US messages");
        assertEquals(3, euProcessedCount.get(), "Should process 3 EU messages");
    }

    @Test
    void testConsumerGroupLoadBalancing(VertxTestContext testContext) throws Exception {
        logger.info("Test: consumer group load balancing");
        int messageCount = 12;
        Checkpoint messageCheckpoint = testContext.checkpoint(messageCount);
        Map<String, AtomicInteger> consumerCounts = new ConcurrentHashMap<>();

        consumerCounts.put("consumer-1", new AtomicInteger(0));
        consumerCounts.put("consumer-2", new AtomicInteger(0));
        consumerCounts.put("consumer-3", new AtomicInteger(0));

        consumerGroup.addConsumer("consumer-1", message -> {
            consumerCounts.get("consumer-1").incrementAndGet();
            messageCheckpoint.flag();
            return Future.succeededFuture();
        });

        consumerGroup.addConsumer("consumer-2", message -> {
            consumerCounts.get("consumer-2").incrementAndGet();
            messageCheckpoint.flag();
            return Future.succeededFuture();
        });

        consumerGroup.addConsumer("consumer-3", message -> {
            consumerCounts.get("consumer-3").incrementAndGet();
            messageCheckpoint.flag();
            return Future.succeededFuture();
        });

        consumerGroup.start();

        for (int i = 0; i < messageCount; i++) {
            producer.send("Load balance test message " + i).await();
        }

        assertTrue(testContext.awaitCompletion(45, TimeUnit.SECONDS),
            "All messages should be processed within timeout");

        consumerCounts.forEach((consumerId, count) ->
            assertTrue(count.get() > 0,
                "Consumer " + consumerId + " should process at least one message"));

        int totalProcessed = consumerCounts.values().stream()
            .mapToInt(AtomicInteger::get)
            .sum();
        assertEquals(messageCount, totalProcessed,
            "Total processed messages should equal sent messages");
    }

    @Test
    void testConsumerGroupDynamicScaling(VertxTestContext testContext) throws Exception {
        logger.info("Test: consumer group dynamic scaling");
        int initialMessageCount = 3;
        int additionalMessageCount = 6;
        Promise<Void> initialPhaseDone = Promise.promise();
        AtomicInteger initialReceived = new AtomicInteger(0);
        Checkpoint totalCheckpoint = testContext.checkpoint(initialMessageCount + additionalMessageCount);
        AtomicInteger processedCount = new AtomicInteger(0);

        consumerGroup.addConsumer("initial-consumer", message -> {
            processedCount.incrementAndGet();
            totalCheckpoint.flag();
            if (initialReceived.incrementAndGet() >= initialMessageCount) {
                initialPhaseDone.tryComplete();
            }
            return Future.succeededFuture();
        });

        consumerGroup.start();

        for (int i = 0; i < initialMessageCount; i++) {
            producer.send("Initial message " + i).await();
        }

        initialPhaseDone.future().await();

        Set<String> activeConsumers = ConcurrentHashMap.newKeySet();

        consumerGroup.addConsumer("additional-consumer-1", message -> {
            activeConsumers.add("additional-consumer-1");
            processedCount.incrementAndGet();
            totalCheckpoint.flag();
            return Future.succeededFuture();
        });

        consumerGroup.addConsumer("additional-consumer-2", message -> {
            activeConsumers.add("additional-consumer-2");
            processedCount.incrementAndGet();
            totalCheckpoint.flag();
            return Future.succeededFuture();
        });

        for (int i = 0; i < additionalMessageCount; i++) {
            producer.send("Additional message " + i).await();
        }

        assertTrue(testContext.awaitCompletion(20, TimeUnit.SECONDS),
            "All messages should be processed");

        assertEquals(initialMessageCount + additionalMessageCount, processedCount.get(),
            "Should process all messages");
        assertFalse(activeConsumers.isEmpty(),
            "Should have active additional consumers");
    }
}


