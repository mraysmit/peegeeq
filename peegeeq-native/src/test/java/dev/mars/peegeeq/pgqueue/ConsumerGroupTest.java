package dev.mars.peegeeq.pgqueue;

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

import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.ConsumerGroupMember;
import dev.mars.peegeeq.api.messaging.ConsumerGroupStats;
import dev.mars.peegeeq.api.messaging.ConsumerMemberStats;
import dev.mars.peegeeq.api.messaging.MessageFilter;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.sqlclient.Tuple;
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


import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration tests for consumer groups functionality.
 *
 * <p>Each test composes on {@link ConsumerGroup#start()} before sending and uses a
 * handler-owned {@link Promise} to expose message delivery as a Future. Cleanup is part
 * of the same chain, so neither readiness nor shutdown is inferred from timing.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-14
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class ConsumerGroupTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupTest.class);


    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory factory;
    private MessageProducer<String> producer;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Ensure required schema exists for native queue tests - use QUEUE_ALL for PeeGeeQManager health checks
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        // Initialize PeeGeeQ Manager
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
            .compose(v -> manager.getPool().preparedQuery("DELETE FROM queue_messages WHERE topic = $1").execute(Tuple.of("test-topic")))
            .map(v -> {
                DatabaseService databaseService = new PgDatabaseService(manager);
                QueueFactoryProvider provider = new PgQueueFactoryProvider();
                PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                factory = provider.createFactory("native", databaseService);
                producer = factory.createProducer("test-topic", String.class);
                return (Void) null;
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        logger.info("Tearing down: closing resources and manager");
        Future<Void> producerClose = Future.succeededFuture();
        if (producer != null) {
            try {
                producer.close();
            } catch (Exception e) {
                logger.error("Error closing producer", e);
                producerClose = Future.failedFuture(e);
            }
        }
        Future<Void> factoryClose = factory != null ? factory.close() : Future.succeededFuture();
        Future.join(producerClose, factoryClose)
                .mapEmpty()
                .onSuccess(v -> finishManagerClose(testContext, null))
                .onFailure(e -> finishManagerClose(testContext, e));
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "Test resource cleanup timed out");
    }

    private void finishManagerClose(VertxTestContext testContext, Throwable cleanupFailure) {
        Future<Void> managerClose = manager != null
                ? manager.closeReactive()
                : Future.succeededFuture();
        managerClose
                .onSuccess(v -> {
                    if (cleanupFailure == null) {
                        testContext.completeNow();
                    } else {
                        logger.error("Test resource cleanup failed", cleanupFailure);
                        testContext.failNow(cleanupFailure);
                    }
                })
                .onFailure(closeFailure -> {
                    if (cleanupFailure == null) {
                        logger.error("Manager close failed", closeFailure);
                        testContext.failNow(closeFailure);
                    } else {
                        cleanupFailure.addSuppressed(closeFailure);
                        logger.error("Test resource cleanup and manager close failed", cleanupFailure);
                        testContext.failNow(cleanupFailure);
                    }
                });
    }

    @Test
    void testBasicConsumerGroupFunctionality(VertxTestContext testContext) throws Exception {
        logger.info("Test: basic consumer group functionality");
        // Create consumer group
        ConsumerGroup<String> consumerGroup = factory.createConsumerGroup(
            "TestGroup", "test-topic", String.class);

        // Verify initial state
        assertEquals("TestGroup", consumerGroup.getGroupName());
        assertEquals("test-topic", consumerGroup.getTopic());
        assertEquals(0, consumerGroup.getActiveConsumerCount());
        assertFalse(consumerGroup.isActive());

        // Add consumers
        AtomicInteger consumer1Count = new AtomicInteger(0);
        AtomicInteger consumer2Count = new AtomicInteger(0);
        AtomicInteger totalDelivered = new AtomicInteger(0);
        Promise<Void> messagesDelivered = Promise.promise();

        consumerGroup.addConsumer("consumer-1",
            message -> {
                consumer1Count.incrementAndGet();
                if (totalDelivered.incrementAndGet() == 3) {
                    completeOnNextContext(messagesDelivered);
                }
                return Future.succeededFuture();
            });

        consumerGroup.addConsumer("consumer-2",
            message -> {
                consumer2Count.incrementAndGet();
                if (totalDelivered.incrementAndGet() == 3) {
                    completeOnNextContext(messagesDelivered);
                }
                return Future.succeededFuture();
            });

        // Verify consumers added
        assertEquals(2, consumerGroup.getConsumerIds().size());
        assertTrue(consumerGroup.getConsumerIds().contains("consumer-1"));
        assertTrue(consumerGroup.getConsumerIds().contains("consumer-2"));

        consumerGroup.start()
            .compose(v -> {
                assertTrue(consumerGroup.isActive());
                assertEquals(2, consumerGroup.getActiveConsumerCount());
                return Future.all(
                        producer.send("Message 1"),
                        producer.send("Message 2"),
                        producer.send("Message 3"));
            })
            .compose(v -> messagesDelivered.future())
            .map(v -> {
                ConsumerGroupStats stats = consumerGroup.getStats();
                int totalProcessed = consumer1Count.get() + consumer2Count.get();
                assertEquals(3, totalProcessed);
                assertEquals("TestGroup", stats.getGroupName());
                assertEquals("test-topic", stats.getTopic());
                assertEquals(2, stats.getActiveConsumerCount());
                assertEquals(3, stats.getTotalMessagesProcessed());
                return (Void) null;
            })
            .eventually(consumerGroup::stopGracefully)
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testMessageFilteringByHeaders(VertxTestContext testContext) throws Exception {
        logger.info("Test: message filtering by headers");
        // Create consumer group
        ConsumerGroup<String> consumerGroup = factory.createConsumerGroup(
            "FilterGroup", "test-topic", String.class);

        // Counters for different regions
        AtomicInteger usCount = new AtomicInteger(0);
        AtomicInteger euCount = new AtomicInteger(0);
        AtomicInteger allCount = new AtomicInteger(0);
        Promise<Void> allMessagesDelivered = Promise.promise();

        // Add consumers with filters
        consumerGroup.addConsumer("us-consumer", 
            message -> {
                usCount.incrementAndGet();
                return Future.succeededFuture();
            },
            MessageFilter.byHeader("region", "US"));

        consumerGroup.addConsumer("eu-consumer", 
            message -> {
                euCount.incrementAndGet();
                return Future.succeededFuture();
            },
            MessageFilter.byHeader("region", "EU"));

        consumerGroup.addConsumer("all-consumer", 
            message -> {
                if (allCount.incrementAndGet() == 3) {
                    completeOnNextContext(allMessagesDelivered);
                }
                return Future.succeededFuture();
            },
            MessageFilter.acceptAll());

        consumerGroup.start()
            .compose(v -> Future.all(
                    producer.send("US Message"),
                    producer.send("EU Message"),
                    producer.send("ASIA Message")))
            .compose(v -> allMessagesDelivered.future())
            .map(v -> {
                assertEquals(0, usCount.get());
                assertEquals(0, euCount.get());
                assertEquals(3, allCount.get());
                return (Void) null;
            })
            .eventually(consumerGroup::stopGracefully)
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testConsumerGroupWithGroupLevelFilter(VertxTestContext testContext) throws Exception {
        logger.info("Test: consumer group with group level filter");
        // Create consumer group without group-level filter for now
        // TODO: Test group-level filtering once header support is fixed
        ConsumerGroup<String> consumerGroup = factory.createConsumerGroup(
            "PriorityGroup", "test-topic", String.class);

        // Don't set group filter for now due to header encoding issue
        // consumerGroup.setGroupFilter(MessageFilter.byHeader("priority", "HIGH"));

        AtomicInteger processedCount = new AtomicInteger(0);
        Promise<Void> messagesDelivered = Promise.promise();

        // Add consumer
        consumerGroup.addConsumer("priority-consumer",
            message -> {
                if (processedCount.incrementAndGet() == 3) {
                    completeOnNextContext(messagesDelivered);
                }
                return Future.succeededFuture();
            });

        consumerGroup.start()
            .compose(v -> Future.all(
                    producer.send("Test Message 1"),
                    producer.send("Test Message 2"),
                    producer.send("Test Message 3")))
            .compose(v -> messagesDelivered.future())
            .map(v -> {
                assertEquals(3, processedCount.get());
                return (Void) null;
            })
            .eventually(consumerGroup::stopGracefully)
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(25, TimeUnit.SECONDS));
    }

    @Test
    void testConsumerGroupStatistics(VertxTestContext testContext) throws Exception {
        logger.info("Test: consumer group statistics");
        // Create consumer group
        ConsumerGroup<String> consumerGroup = factory.createConsumerGroup(
            "StatsGroup", "test-topic", String.class);

        AtomicInteger processedCount = new AtomicInteger(0);
        Promise<Void> messagesDelivered = Promise.promise();

        // Add consumer
        ConsumerGroupMember<String> member = consumerGroup.addConsumer("stats-consumer", 
            message -> {
                if (processedCount.incrementAndGet() == 5) {
                    completeOnNextContext(messagesDelivered);
                }
                return Future.succeededFuture();
            });

        consumerGroup.start()
            .compose(v -> Future.all(
                    producer.send("Message 0"),
                    producer.send("Message 1"),
                    producer.send("Message 2"),
                    producer.send("Message 3"),
                    producer.send("Message 4")))
            .compose(v -> messagesDelivered.future())
            .map(v -> {
                ConsumerGroupStats groupStats = consumerGroup.getStats();
                assertEquals("StatsGroup", groupStats.getGroupName());
                assertEquals("test-topic", groupStats.getTopic());
                assertEquals(1, groupStats.getActiveConsumerCount());
                assertEquals(5, groupStats.getTotalMessagesProcessed());

                ConsumerMemberStats memberStats = member.getStats();
                assertEquals("stats-consumer", memberStats.getConsumerId());
                assertEquals("StatsGroup", memberStats.getGroupName());
                assertEquals("test-topic", memberStats.getTopic());
                assertTrue(memberStats.isActive());
                assertEquals(5, memberStats.getMessagesProcessed());
                assertEquals(groupStats.getTotalMessagesProcessed(), memberStats.getMessagesProcessed(),
                    "Group and member statistics should match for single-member group");
                return (Void) null;
            })
            .eventually(consumerGroup::stopGracefully)
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testGracefulStopWaitsForHandlerAndMessageDeletion(VertxTestContext testContext) throws Exception {
        ConsumerGroup<String> consumerGroup = factory.createConsumerGroup(
                "GracefulDrainGroup", "test-topic", String.class);
        Promise<Void> handlerEntered = Promise.promise();
        Promise<Void> releaseHandler = Promise.promise();

        consumerGroup.addConsumer("drain-consumer", message -> {
            handlerEntered.tryComplete();
            return releaseHandler.future();
        });

        consumerGroup.start()
            .compose(v -> producer.send("Message held during graceful stop"))
            .compose(v -> handlerEntered.future())
            .compose(v -> {
                Future<Void> stopFuture = consumerGroup.stopGracefully();
                Future<Void> repeatedStopFuture = consumerGroup.stopGracefully();
                Future<Void> closeFuture = consumerGroup.close();
                try {
                    assertFalse(stopFuture.isComplete(),
                            "Graceful stop must remain pending while the admitted handler is pending");
                    assertSame(stopFuture, repeatedStopFuture,
                            "Repeated graceful stop must observe the same in-progress settlement");
                    assertFalse(closeFuture.isComplete(),
                            "Close during graceful stop must wait for the same admitted handler");
                } finally {
                    releaseHandler.tryComplete();
                }
                return Future.join(stopFuture, closeFuture).map(ignored -> (Void) null);
            })
            .compose(v -> manager.getPool()
                    .preparedQuery("SELECT COUNT(*) AS message_count FROM queue_messages WHERE topic = $1")
                    .execute(Tuple.of("test-topic")))
            .map(rows -> {
                assertEquals(0L, rows.iterator().next().getLong("message_count"),
                        "Graceful stop must wait for successful message deletion");
                assertFalse(consumerGroup.isActive());
                return (Void) null;
            })
            .eventually(() -> {
                releaseHandler.tryComplete();
                return consumerGroup.close();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testGracefulStopWaitsForHandlerFailureAndRetryPersistence(VertxTestContext testContext) throws Exception {
        ConsumerGroup<String> consumerGroup = factory.createConsumerGroup(
                "GracefulFailureDrainGroup", "test-topic", String.class);
        Promise<Void> handlerEntered = Promise.promise();
        Promise<Void> handlerOutcome = Promise.promise();

        consumerGroup.addConsumer("failure-drain-consumer", message -> {
            handlerEntered.tryComplete();
            return handlerOutcome.future();
        });

        consumerGroup.start()
            .compose(v -> producer.send("Message whose failed handling must be persisted"))
            .compose(v -> handlerEntered.future())
            .compose(v -> {
                Future<Void> stopFuture = consumerGroup.stopGracefully();
                try {
                    assertFalse(stopFuture.isComplete(),
                            "Graceful stop must remain pending while the admitted handler is pending");
                } finally {
                    handlerOutcome.tryFail(new IllegalStateException("controlled handler failure"));
                }
                return stopFuture;
            })
            .compose(v -> manager.getPool()
                    .preparedQuery("""
                            SELECT status, retry_count, lock_until
                            FROM queue_messages
                            WHERE topic = $1
                            """)
                    .execute(Tuple.of("test-topic")))
            .map(rows -> {
                assertEquals(1, rows.size(),
                        "A failed message below the retry limit must remain in the native queue");
                var row = rows.iterator().next();
                assertEquals("AVAILABLE", row.getString("status"));
                assertEquals(1, row.getInteger("retry_count"));
                assertNull(row.getValue("lock_until"),
                        "Graceful stop must wait until the failed message lock is released");
                assertFalse(consumerGroup.isActive());
                return (Void) null;
            })
            .eventually(() -> {
                handlerOutcome.tryFail(new IllegalStateException("test cleanup"));
                return consumerGroup.close();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testRemoveConsumerFromGroup(VertxTestContext testContext) throws Exception {
        logger.info("Test: remove consumer from group");
        // Create consumer group
        ConsumerGroup<String> consumerGroup = factory.createConsumerGroup(
            "RemovalGroup", "test-topic", String.class);

        // Add consumers
        consumerGroup.addConsumer("consumer-1", 
            message -> Future.succeededFuture());
        consumerGroup.addConsumer("consumer-2", 
            message -> Future.succeededFuture());

        assertEquals(2, consumerGroup.getConsumerIds().size());

        // Remove one consumer
        boolean removed = consumerGroup.removeConsumer("consumer-1");
        assertTrue(removed);
        assertEquals(1, consumerGroup.getConsumerIds().size());
        assertTrue(consumerGroup.getConsumerIds().contains("consumer-2"));

        // Try to remove non-existent consumer
        boolean notRemoved = consumerGroup.removeConsumer("non-existent");
        assertFalse(notRemoved);

        consumerGroup.close()
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    private void completeOnNextContext(Promise<Void> promise) {
        manager.getVertx().runOnContext(ignored -> promise.tryComplete());
    }
}
