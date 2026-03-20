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
import dev.mars.peegeeq.test.categories.TestCategories;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Outbox Consumer Group v1.1.0 features.
 * 
 * <p>Tests the new convenience methods added in v1.1.0 for the outbox pattern implementation:</p>
 * <ul>
 *   <li>{@link ConsumerGroup#start(SubscriptionOptions)} - Type-safe subscription options</li>
 *   <li>{@link ConsumerGroup#setMessageHandler(MessageHandler)} - Convenience for single-consumer groups</li>
 * </ul>
 * 
 * <p>This test class mirrors {@code ConsumerGroupV110Test} but validates the outbox-specific
 * implementation behavior.</p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-17
 * @version 1.1.0
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@DisplayName("Outbox Consumer Group v1.1.0 Features")
class OutboxConsumerGroupV110Test {

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:15.13-alpine3.20");
        container.withDatabaseName("testdb");
        container.withUsername("testuser");
        container.withPassword("testpass");
        return container;
    }

    private PeeGeeQManager manager;
    private QueueFactory factory;
    private MessageProducer<String> producer;

    @BeforeEach
    void setUp() throws Exception {
        // Set test properties
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Ensure required schema exists for outbox tests (creates tables in public schema)
        // Use QUEUE_ALL to initialize all queue tables required by PeeGeeQManager health checks
        // Also include CONSUMER_GROUP_FANOUT for subscription management tables (outbox_topic_subscriptions)
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL, SchemaComponent.CONSUMER_GROUP_FANOUT);

        // Initialize PeeGeeQ Manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create factory and producer for outbox
        DatabaseService databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register the outbox factory
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        factory = provider.createFactory("outbox", databaseService);
        producer = factory.createProducer("test-topic", String.class);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        if (producer != null) {
            producer.close();
        }
        if (factory != null) {
            factory.close();
        }
        if (manager != null) {
            manager.closeReactive().onComplete(ar -> testContext.completeNow());
        } else {
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
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
                messageCheckpoint.flag();
                return Future.succeededFuture();
            });

            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_NOW)
                .build();

            group.start(options);

            assertTrue(group.isActive());
            assertEquals(1, group.getActiveConsumerCount());

            producer.send("Message 1").onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
            group.close();
        }

        @Test
        @DisplayName("should start with FROM_BEGINNING position")
        void testStartWithOptions_FromBeginning(Vertx vertx, VertxTestContext testContext) throws Exception {
            // Send historical messages
            for (int i = 0; i < 5; i++) {
                producer.send("Historical-" + i).onFailure(testContext::failNow);
            }
            java.util.concurrent.CountDownLatch histLatch = new java.util.concurrent.CountDownLatch(1);
            vertx.timer(1000).onComplete(ar -> histLatch.countDown());
            histLatch.await(5, TimeUnit.SECONDS);

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
            group.close();
        }

        @Test
        @DisplayName("should start with FROM_TIMESTAMP position")
        void testStartWithOptions_FromTimestamp(Vertx vertx, VertxTestContext testContext) throws Exception {
            Instant beforeTimestamp = Instant.now();
            java.util.concurrent.CountDownLatch t1 = new java.util.concurrent.CountDownLatch(1);
            vertx.timer(100).onComplete(ar -> t1.countDown());
            t1.await(5, TimeUnit.SECONDS);

            for (int i = 0; i < 3; i++) {
                producer.send("Before-" + i).onFailure(testContext::failNow);
            }

            java.util.concurrent.CountDownLatch t2 = new java.util.concurrent.CountDownLatch(1);
            vertx.timer(100).onComplete(ar -> t2.countDown());
            t2.await(5, TimeUnit.SECONDS);
            Instant cutoffTimestamp = Instant.now();
            java.util.concurrent.CountDownLatch t3 = new java.util.concurrent.CountDownLatch(1);
            vertx.timer(100).onComplete(ar -> t3.countDown());
            t3.await(5, TimeUnit.SECONDS);

            for (int i = 0; i < 3; i++) {
                producer.send("After-" + i).onFailure(testContext::failNow);
            }

            java.util.concurrent.CountDownLatch t4 = new java.util.concurrent.CountDownLatch(1);
            vertx.timer(1000).onComplete(ar -> t4.countDown());
            t4.await(5, TimeUnit.SECONDS);

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
            group.close();
        }

        @Test
        @DisplayName("should throw IllegalArgumentException for null options")
        void testStartWithOptions_NullParameter() {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.addConsumer("consumer-1", msg -> Future.succeededFuture());

            assertThrows(IllegalArgumentException.class, () -> group.start(null));
            group.close();
        }

        @Test
        @DisplayName("should throw IllegalStateException if already active")
        void testStartWithOptions_AlreadyActive() throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.addConsumer("consumer-1", msg -> Future.succeededFuture());

            SubscriptionOptions options = SubscriptionOptions.defaults();
            group.start(options);

            assertThrows(IllegalStateException.class, () -> group.start(options));
            group.close();
        }

        @Test
        @DisplayName("should throw IllegalStateException after close")
        void testStartWithOptions_AfterClose() throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.addConsumer("consumer-1", msg -> Future.succeededFuture());
            group.close();

            SubscriptionOptions options = SubscriptionOptions.defaults();

            assertThrows(IllegalStateException.class, () -> group.start(options));
        }

        @Test
        @DisplayName("should delegate to standard start()")
        void testStartWithOptions_DelegatesToStandardStart(VertxTestContext testContext) throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            Checkpoint messageCheckpoint = testContext.checkpoint();
            group.addConsumer("consumer-1", msg -> {
                messageCheckpoint.flag();
                return Future.succeededFuture();
            });

            SubscriptionOptions options = SubscriptionOptions.defaults();
            group.start(options);

            assertTrue(group.isActive());
            assertEquals(1, group.getActiveConsumerCount());

            producer.send("Test").onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
            group.close();
        }

        @Test
        @DisplayName("should work with multiple start positions")
        void testStartWithOptions_MultiplePositions() throws Exception {
            // FROM_NOW
            ConsumerGroup<String> group1 = factory.createConsumerGroup(
                "group-from-now", "test-topic", String.class);
            group1.addConsumer("c1", msg -> Future.succeededFuture());
            group1.start(SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_NOW).build());
            assertTrue(group1.isActive());
            group1.close();

            // FROM_BEGINNING
            ConsumerGroup<String> group2 = factory.createConsumerGroup(
                "group-from-beginning", "test-topic", String.class);
            group2.addConsumer("c2", msg -> Future.succeededFuture());
            group2.start(SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING).build());
            assertTrue(group2.isActive());
            group2.close();

            // Defaults
            ConsumerGroup<String> group3 = factory.createConsumerGroup(
                "group-defaults", "test-topic", String.class);
            group3.addConsumer("c3", msg -> Future.succeededFuture());
            group3.start(SubscriptionOptions.defaults());
            assertTrue(group3.isActive());
            group3.close();
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

            group.close();
        }

        @Test
        @DisplayName("should return ConsumerGroupMember")
        void testSetMessageHandler_ReturnsConsumerGroupMember() {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            ConsumerGroupMember<String> member = group.setMessageHandler(
                msg -> Future.succeededFuture());

            assertNotNull(member);
            assertTrue(member instanceof ConsumerGroupMember);
            assertEquals("test-group", member.getGroupName());
            assertEquals("test-topic", member.getTopic());

            group.close();
        }

        @Test
        @DisplayName("should process messages in outbox pattern")
        void testSetMessageHandler_ProcessesMessages(VertxTestContext testContext) throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            Checkpoint messageCheckpoint = testContext.checkpoint(2);

            group.setMessageHandler(msg -> {
                messageCheckpoint.flag();
                return Future.succeededFuture();
            });

            group.start();

            producer.send("Message-1").onFailure(testContext::failNow);
            producer.send("Message-2").onFailure(testContext::failNow);
            producer.send("Message-3").onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));

            group.close();
        }

        @Test
        @DisplayName("should throw IllegalStateException when called twice")
        void testSetMessageHandler_CalledTwice() {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.setMessageHandler(msg -> Future.succeededFuture());

            assertThrows(IllegalStateException.class,
                () -> group.setMessageHandler(msg -> Future.succeededFuture()));

            group.close();
        }

        @Test
        @DisplayName("should throw NullPointerException with null handler")
        void testSetMessageHandler_NullHandler() {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            assertThrows(NullPointerException.class,
                () -> group.setMessageHandler(null));

            group.close();
        }

        @Test
        @DisplayName("should throw IllegalStateException after close")
        void testSetMessageHandler_AfterClose() throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.close();

            assertThrows(IllegalStateException.class,
                () -> group.setMessageHandler(msg -> Future.succeededFuture()));
        }

        @Test
        @DisplayName("should work with start()")
        void testSetMessageHandler_IntegrationWithStart(VertxTestContext testContext) throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            Checkpoint messageCheckpoint = testContext.checkpoint();
            group.setMessageHandler(msg -> {
                messageCheckpoint.flag();
                return Future.succeededFuture();
            });

            group.start();

            producer.send("Test-1").onFailure(testContext::failNow);
            producer.send("Test-2").onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
            assertTrue(group.isActive());

            group.close();
        }

        @Test
        @DisplayName("should work with start(SubscriptionOptions)")
        void testSetMessageHandler_IntegrationWithStartOptions(Vertx vertx, VertxTestContext testContext) throws Exception {
            // Send historical messages
            for (int i = 0; i < 3; i++) {
                producer.send("Historical-" + i).onFailure(testContext::failNow);
            }
            java.util.concurrent.CountDownLatch histLatch2 = new java.util.concurrent.CountDownLatch(1);
            vertx.timer(1000).onComplete(ar -> histLatch2.countDown());
            histLatch2.await(5, TimeUnit.SECONDS);

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

            group.close();
        }

        @Test
        @DisplayName("should track statistics")
        void testSetMessageHandler_Statistics(VertxTestContext testContext) throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            Checkpoint messageCheckpoint = testContext.checkpoint(5);
            ConsumerGroupMember<String> member = group.setMessageHandler(msg -> {
                messageCheckpoint.flag();
                return Future.succeededFuture();
            });

            group.start();

            for (int i = 0; i < 5; i++) {
                producer.send("Message-" + i).onFailure(testContext::failNow);
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

            group.close();
        }

        @Test
        @DisplayName("should be thread-safe")
        void testSetMessageHandler_ThreadSafety() throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            ExecutorService executor = Executors.newFixedThreadPool(5);
            CountDownLatch startSignal = new CountDownLatch(1);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);
            List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        startSignal.await();
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

            startSignal.countDown();

            for (java.util.concurrent.Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }

            assertEquals(1, successCount.get());
            assertEquals(4, failureCount.get());

            for (Exception e : exceptions) {
                assertTrue(e instanceof IllegalStateException);
            }

            executor.shutdown();
            group.close();
        }
    }
}


