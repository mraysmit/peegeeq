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

import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.*;
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
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for Outbox Consumer Group subscription and handler features.
 * 
 * <p>Tests the subscription and handler convenience methods for the outbox pattern implementation:</p>
 * <ul>
 *   <li>{@link ConsumerGroup#start(SubscriptionOptions)} - Type-safe subscription options</li>
 *   <li>{@link ConsumerGroup#setMessageHandler(MessageHandler)} - Convenience for single-consumer groups</li>
 * </ul>
 * 
 * <p>This test class mirrors {@code ConsumerGroupSubscriptionTest} but validates the outbox-specific
 * implementation behavior.</p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-17
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@DisplayName("Outbox Consumer Group Subscription Features")
class OutboxConsumerGroupSubscriptionTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerGroupSubscriptionTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory factory;
    private MessageProducer<String> producer;

    @BeforeEach
    void setUp() throws Exception {
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .property("peegeeq.queue.polling-interval", "PT0.5S")
                .build();

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL, SchemaComponent.CONSUMER_GROUP_FANOUT);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        DatabaseService databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        factory = provider.createFactory("outbox", databaseService);
        producer = factory.createProducer("test-topic", String.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.info("Tearing down: closing resources and clearing system properties");
        if (producer != null) {
            producer.close();
        }
        if (factory != null) {
            factory.close();
        }
        if (manager != null) {
            manager.closeReactive().await();
        }
    }

    // ========================================================================
    // Tests for start(SubscriptionOptions) - Outbox Implementation
    // ========================================================================

    @Nested
    @DisplayName("start(SubscriptionOptions) method - Outbox")
    class StartWithOptionsTests {

        @Test
        @DisplayName("should start with FROM_NOW position")
        void testStartWithOptions_FromNow(VertxTestContext testContext) throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            Checkpoint messageCheckpoint = testContext.checkpoint();
            group.addConsumer("consumer-1", msg -> {
        logger.info("Test: start with options  from now");
                messageCheckpoint.flag();
                return Future.succeededFuture();
            });

            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_NOW)
                .build();

            group.start(options)
                .compose(v -> {
                    testContext.verify(() -> {
                        assertTrue(group.isActive());
                        assertEquals(1, group.getActiveConsumerCount());
                    });
                    return producer.send("Message 1");
                })
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));
        }

        @Test
        @DisplayName("should start with FROM_BEGINNING position")
        void testStartWithOptions_FromBeginning(Vertx vertx, VertxTestContext testContext) throws Exception {
            for (int i = 0; i < 5; i++) {
        logger.info("Test: start with options  from beginning");
                producer.send("Historical-" + i).await();
            }
            vertx.timer(1000).await();

            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            Checkpoint messageCheckpoint = testContext.checkpoint(3);
            group.addConsumer("consumer-1", msg -> {
                messageCheckpoint.flag();
                return Future.succeededFuture();
            });

            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .build();

            group.start(options);

            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
            assertTrue(group.isActive());
            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));
        }

        @Test
        @DisplayName("should start with FROM_TIMESTAMP position")
        void testStartWithOptions_FromTimestamp(Vertx vertx, VertxTestContext testContext) throws Exception {
            vertx.timer(100).await();

            for (int i = 0; i < 3; i++) {
        logger.info("Test: start with options  from timestamp");
                producer.send("Before-" + i).await();
            }

            vertx.timer(100).await();
            Instant cutoffTimestamp = Instant.now();
            vertx.timer(100).await();

            for (int i = 0; i < 3; i++) {
                producer.send("After-" + i).await();
            }

            vertx.timer(1000).await();

            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            Checkpoint messageCheckpoint = testContext.checkpoint();
            group.addConsumer("consumer-1", msg -> {
                messageCheckpoint.flag();
                return Future.succeededFuture();
            });

            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_TIMESTAMP)
                .startFromTimestamp(cutoffTimestamp)
                .build();

            group.start(options);

            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
            assertTrue(group.isActive());
            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));
        }

        @Test
        @DisplayName("should throw IllegalArgumentException for null options")
        void testStartWithOptions_NullParameter() {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.addConsumer("consumer-1", msg -> Future.succeededFuture());

            assertThrows(IllegalArgumentException.class, () -> group.start(null));
            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));
        }

        @Test
        @DisplayName("should fail with IllegalStateException if already active")
        void testStartWithOptions_AlreadyActive(VertxTestContext testContext) throws Exception {
        logger.info("Test: start with options  null parameter");
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.addConsumer("consumer-1", msg -> Future.succeededFuture());

            SubscriptionOptions options = SubscriptionOptions.defaults();
            group.start(options)
                .compose(v -> group.start(options))
                .onSuccess(v -> testContext.failNow("Should have failed with IllegalStateException"))
                .onFailure(e -> testContext.verify(() -> {
                    assertInstanceOf(IllegalStateException.class, e);
                    group.close().onFailure(testContext::failNow);
                    testContext.completeNow();
                }));

            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("should fail with IllegalStateException after close")
        void testStartWithOptions_AfterClose(VertxTestContext testContext) throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.addConsumer("consumer-1", msg -> Future.succeededFuture());
            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));

            SubscriptionOptions options = SubscriptionOptions.defaults();
            group.start(options)
                .onSuccess(v -> testContext.failNow("Should have failed with IllegalStateException"))
                .onFailure(e -> testContext.verify(() -> {
        logger.info("Test: start with options  after close");
                    assertInstanceOf(IllegalStateException.class, e);
                    testContext.completeNow();
                }));

            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("should delegate to standard start()")
        void testStartWithOptions_DelegatesToStandardStart(VertxTestContext testContext) throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            Checkpoint messageCheckpoint = testContext.checkpoint();
            group.addConsumer("consumer-1", msg -> {
        logger.info("Test: start with options  delegates to standard start");
                messageCheckpoint.flag();
                return Future.succeededFuture();
            });

            SubscriptionOptions options = SubscriptionOptions.defaults();
            group.start(options)
                .compose(v -> {
                    testContext.verify(() -> {
                        assertTrue(group.isActive());
                        assertEquals(1, group.getActiveConsumerCount());
                    });
                    return producer.send("Test");
                })
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));
        }

        @Test
        @DisplayName("should work with multiple start positions")
        void testStartWithOptions_MultiplePositions(VertxTestContext testContext) throws Exception {
            // FROM_NOW
            ConsumerGroup<String> group1 = factory.createConsumerGroup(
                "group-from-now", "test-topic", String.class);
            group1.addConsumer("c1", msg -> Future.succeededFuture());
            group1.start(SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_NOW).build())
                .compose(v -> {
        logger.info("Test: start with options  multiple positions");
                    testContext.verify(() -> assertTrue(group1.isActive()));
                    group1.close().onFailure(e -> logger.warn("group1.close() failed", e));
                    // FROM_BEGINNING
                    ConsumerGroup<String> group2 = factory.createConsumerGroup(
                        "group-from-beginning", "test-topic", String.class);
                    group2.addConsumer("c2", msg -> Future.succeededFuture());
                    return group2.start(SubscriptionOptions.builder()
                        .startPosition(StartPosition.FROM_BEGINNING).build())
                        .map(v2 -> group2);
                })
                .compose(group2 -> {
                    testContext.verify(() -> assertTrue(group2.isActive()));
                    group2.close().onFailure(e -> logger.warn("group2.close() failed", e));
                    // Defaults
                    ConsumerGroup<String> group3 = factory.createConsumerGroup(
                        "group-defaults", "test-topic", String.class);
                    group3.addConsumer("c3", msg -> Future.succeededFuture());
                    return group3.start(SubscriptionOptions.defaults())
                        .map(v3 -> group3);
                })
                .onComplete(testContext.succeeding(group3 -> testContext.verify(() -> {
                    assertTrue(group3.isActive());
                    group3.close().onFailure(e -> logger.warn("group3.close() failed", e));
                    testContext.completeNow();
                })));

            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
        }
    }

    // ========================================================================
    // Tests for setMessageHandler() - Outbox Implementation
    // ========================================================================

    @Nested
    @DisplayName("setMessageHandler() method - Outbox")
    class SetMessageHandlerTests {

        @Test
        @DisplayName("should create default consumer with correct ID")
        void testSetMessageHandler_CreatesDefaultConsumer() {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            ConsumerGroupMember<String> member = group.setMessageHandler(
                msg -> Future.succeededFuture());

            assertNotNull(member);
            assertEquals("test-group-default-consumer", member.getConsumerId());
            assertEquals(1, group.getConsumerIds().size());
            assertTrue(group.getConsumerIds().contains("test-group-default-consumer"));

            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));
        }

        @Test
        @DisplayName("should return ConsumerGroupMember")
        void testSetMessageHandler_ReturnsConsumerGroupMember() {
        logger.info("Test: set message handler  creates default consumer");
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            ConsumerGroupMember<String> member = group.setMessageHandler(
                msg -> Future.succeededFuture());

            assertNotNull(member);
            assertInstanceOf(ConsumerGroupMember.class, member);
            assertEquals("test-group", member.getGroupName());
            assertEquals("test-topic", member.getTopic());

            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));
        }

        @Test
        @DisplayName("should process messages in outbox pattern")
        void testSetMessageHandler_ProcessesMessages(VertxTestContext testContext) throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            Checkpoint messageCheckpoint = testContext.checkpoint(2);

            group.setMessageHandler(msg -> {
        logger.info("Test: set message handler  processes messages");
                messageCheckpoint.flag();
                return Future.succeededFuture();
            });

            group.start();

            producer.send("Message-1").await();
            producer.send("Message-2").await();
            producer.send("Message-3").await();

            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));

            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));
        }

        @Test
        @DisplayName("should throw IllegalStateException when called twice")
        void testSetMessageHandler_CalledTwice() {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.setMessageHandler(msg -> Future.succeededFuture());

            assertThrows(IllegalStateException.class,
                () -> group.setMessageHandler(msg -> Future.succeededFuture()));

            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));
        }

        @Test
        @DisplayName("should throw NullPointerException with null handler")
        void testSetMessageHandler_NullHandler() {
        logger.info("Test: set message handler  called twice");
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            assertThrows(NullPointerException.class,
                () -> group.setMessageHandler(null));

            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));
        }

        @Test
        @DisplayName("should throw IllegalStateException after close")
        void testSetMessageHandler_AfterClose() throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));

            assertThrows(IllegalStateException.class,
                () -> group.setMessageHandler(msg -> Future.succeededFuture()));
        }

        @Test
        @DisplayName("should work with start()")
        void testSetMessageHandler_IntegrationWithStart(VertxTestContext testContext) throws Exception {
        logger.info("Test: set message handler  after close");
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            Checkpoint messageCheckpoint = testContext.checkpoint();
            group.setMessageHandler(msg -> {
                messageCheckpoint.flag();
                return Future.succeededFuture();
            });

            group.start();

            producer.send("Test-1").await();
            producer.send("Test-2").await();

            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
            assertTrue(group.isActive());

            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));
        }

        @Test
        @DisplayName("should work with start(SubscriptionOptions)")
        void testSetMessageHandler_IntegrationWithStartOptions(Vertx vertx, VertxTestContext testContext) throws Exception {
            for (int i = 0; i < 3; i++) {
        logger.info("Test: set message handler  integration with start options");
                producer.send("Historical-" + i).await();
            }
            vertx.timer(1000).await();

            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            Checkpoint messageCheckpoint = testContext.checkpoint(2);
            group.setMessageHandler(msg -> {
                messageCheckpoint.flag();
                return Future.succeededFuture();
            });

            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .build();

            group.start(options);

            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
            assertTrue(group.isActive());

            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));
        }

        @Test
        @DisplayName("should track statistics")
        void testSetMessageHandler_Statistics(VertxTestContext testContext) throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            Checkpoint messageCheckpoint = testContext.checkpoint(5);
            ConsumerGroupMember<String> member = group.setMessageHandler(msg -> {
        logger.info("Test: set message handler  statistics");
                messageCheckpoint.flag();
                return Future.succeededFuture();
            });

            group.start();

            for (int i = 0; i < 5; i++) {
                producer.send("Message-" + i).await();
            }

            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));

            ConsumerGroupStats groupStats = group.getStats();
            assertNotNull(groupStats);
            assertEquals("test-group", groupStats.getGroupName());
            assertEquals(1, groupStats.getActiveConsumerCount());

            ConsumerMemberStats memberStats = member.getStats();
            assertNotNull(memberStats);
            assertEquals("test-group-default-consumer", memberStats.getConsumerId());
            assertTrue(memberStats.isActive());

            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));
        }

        @Test
        @DisplayName("should be thread-safe")
        void testSetMessageHandler_ThreadSafety() throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            ExecutorService executor = Executors.newFixedThreadPool(5);
            CyclicBarrier startBarrier = new CyclicBarrier(6);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);
            List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
        logger.info("Test: set message handler  thread safety");
                futures.add(executor.submit(() -> {
                    try {
                        startBarrier.await();
                        group.setMessageHandler(msg -> Future.succeededFuture());
                        successCount.incrementAndGet();
                    } catch (IllegalStateException e) {
                        failureCount.incrementAndGet();
                        exceptions.add(e);
                    } catch (Exception e) {
                        exceptions.add(e);
                    }
                }));
            }

            startBarrier.await();

            for (java.util.concurrent.Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }

            assertEquals(1, successCount.get());
            assertEquals(4, failureCount.get());

            for (Exception e : exceptions) {
                assertInstanceOf(IllegalStateException.class, e);
            }

            executor.shutdown();
            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));
        }
    }
}


