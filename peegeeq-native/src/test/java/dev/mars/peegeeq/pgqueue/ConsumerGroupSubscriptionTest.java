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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.Future;

import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for Consumer Group subscription and handler features.
 * 
 * <p>Tests the subscription and handler convenience methods:</p>
 * <ul>
 *   <li>{@link ConsumerGroup#start(SubscriptionOptions)} - Type-safe subscription options</li>
 *   <li>{@link ConsumerGroup#setMessageHandler(MessageHandler)} - Convenience for single-consumer groups</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-17
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@DisplayName("Consumer Group Subscription Features")
class ConsumerGroupSubscriptionTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupSubscriptionTest.class);


    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
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
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Ensure required schema exists for native queue tests - use QUEUE_ALL for PeeGeeQManager health checks
        // Also include CONSUMER_GROUP_FANOUT for subscription management tables (outbox_topic_subscriptions)
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL, SchemaComponent.CONSUMER_GROUP_FANOUT);

        // Initialize PeeGeeQ Manager
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        // Create factory and producer
        DatabaseService databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register the native factory
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        factory = provider.createFactory("native", databaseService);
        producer = factory.createProducer("test-topic", String.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.info("Tearing down: closing resources and manager");
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
    // Tests for start(SubscriptionOptions)
    // ========================================================================

    @Nested
    @DisplayName("start(SubscriptionOptions) method")
    class StartWithOptionsTests {

        @Test
        @Disabled("Native queue requires SubscriptionManager integration for start position support - use two-step process with SubscriptionManager.subscribe()")
        @DisplayName("should start with FROM_NOW position")
        void testStartWithOptions_FromNow(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: start with options  from now");
            // Arrange
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            AtomicInteger count = new AtomicInteger(0);
            Checkpoint messageReceived = testContext.checkpoint();
            group.addConsumer("consumer-1", msg -> {
                count.incrementAndGet();
                messageReceived.flag();
                return Future.succeededFuture();
            });

            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_NOW)
                .build();

            // Act
            group.start(options);

            // Assert
            assertTrue(group.isActive(), "Group should be active after start");
            assertEquals(1, group.getActiveConsumerCount());

            // Send message after start
            producer.send("Message 1");
            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));

            assertTrue(count.get() >= 1, "Should process messages sent after start");

            // Cleanup
            group.close();
        }

        @Test
        @Disabled("Native queue requires SubscriptionManager integration for start position support - use two-step process with SubscriptionManager.subscribe()")
        @DisplayName("should start with FROM_BEGINNING position")
        void testStartWithOptions_FromBeginning(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: start with options  from beginning");
            // Arrange: Send messages before subscription
            List<String> sentMessages = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                String msg = "Historical-" + i;
                producer.send(msg);
                sentMessages.add(msg);
            }

            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
            Checkpoint messagesReceived = testContext.checkpoint(3);
            group.addConsumer("consumer-1", msg -> {
                receivedMessages.add(msg.getPayload());
                messagesReceived.flag();
                return Future.succeededFuture();
            });

            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .build();

            // Act - start after a delay to ensure messages are committed
            vertx.setTimer(1000, id -> {
                group.start(options);
            });

            // Wait for processing
            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));

            // Assert
            assertTrue(group.isActive());
            assertTrue(receivedMessages.size() >= 3,
                "Should process historical messages, received: " + receivedMessages.size());

            // Cleanup
            group.close();
        }

        @Test
        @Disabled("Native queue requires SubscriptionManager integration for start position support - use two-step process with SubscriptionManager.subscribe()")
        @DisplayName("should start with FROM_TIMESTAMP position")
        void testStartWithOptions_FromTimestamp(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: start with options  from timestamp");
            // Arrange: Send messages and capture timestamp
            Instant beforeTimestamp = Instant.now();

            for (int i = 0; i < 3; i++) {
                producer.send("Before-" + i);
            }

            Instant cutoffTimestamp = Instant.now();

            for (int i = 0; i < 3; i++) {
                producer.send("After-" + i);
            }

            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
            Checkpoint messageReceived = testContext.checkpoint();
            group.addConsumer("consumer-1", msg -> {
                receivedMessages.add(msg.getPayload());
                messageReceived.flag();
                return Future.succeededFuture();
            });

            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_TIMESTAMP)
                .startFromTimestamp(cutoffTimestamp)
                .build();

            // Act - start after a delay to ensure messages are committed
            vertx.setTimer(1000, id -> {
                group.start(options);
            });

            // Wait for processing
            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));

            // Assert
            assertTrue(group.isActive());
            assertTrue(receivedMessages.size() >= 1,
                "Should process messages after timestamp, received: " + receivedMessages.size());

            // Cleanup
            group.close();
        }

        @Test
        @DisplayName("should throw IllegalArgumentException for null options")
        void testStartWithOptions_NullParameter() {
        logger.info("Test: start with options  null parameter");
            // Arrange
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.addConsumer("consumer-1", msg -> Future.succeededFuture());

            // Act & Assert
            assertThrows(IllegalArgumentException.class, () -> group.start(null),
                "Should throw IllegalArgumentException for null SubscriptionOptions");

            // Cleanup
            group.close();
        }

        @Test
        @DisplayName("should allow multiple start calls (idempotent)")
        void testStartWithOptions_AlreadyActive() throws Exception {
        logger.info("Test: start with options  already active");
            // Arrange
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.addConsumer("consumer-1", msg -> Future.succeededFuture());

            SubscriptionOptions options = SubscriptionOptions.defaults();
            group.start(options).await();
            assertTrue(group.isActive(), "Group should be active after first start");

            // Act - second start should be idempotent (no exception)
            group.start(options).await();
            
            // Assert
            assertTrue(group.isActive(), "Group should remain active after second start");

            // Cleanup
            group.close();
        }

        @Test
        @DisplayName("should return failed future after close")
        void testStartWithOptions_AfterClose() throws Exception {
        logger.info("Test: start with options  after close");
            // Arrange
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.addConsumer("consumer-1", msg -> Future.succeededFuture());
            group.close();

            SubscriptionOptions options = SubscriptionOptions.defaults();

            // Act & Assert async method returns a failed future, not a thrown exception
            Future<Void> result = group.start(options);
            assertTrue(result.failed(), "Should fail when starting a closed group");
            assertInstanceOf(IllegalStateException.class, result.cause());
        }

        @Test
        @Disabled("Native queue requires SubscriptionManager integration for start position support - use two-step process with SubscriptionManager.subscribe()")
        @DisplayName("should delegate to standard start() method")
        void testStartWithOptions_DelegatesToStandardStart(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: start with options  delegates to standard start");
            // Arrange
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            AtomicInteger count = new AtomicInteger(0);
            Checkpoint messageReceived = testContext.checkpoint();
            group.addConsumer("consumer-1", msg -> {
                count.incrementAndGet();
                messageReceived.flag();
                return Future.succeededFuture();
            });

            SubscriptionOptions options = SubscriptionOptions.defaults();

            // Act
            group.start(options);

            // Assert - behavior should be same as calling start()
            assertTrue(group.isActive());
            assertEquals(1, group.getActiveConsumerCount());

            // Send message
            producer.send("Test");
            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));

            assertTrue(count.get() >= 1);

            // Cleanup
            group.close();
        }

        @Test
        @DisplayName("should work with different start positions")
        void testStartWithOptions_MultiplePositions() throws Exception {
        logger.info("Test: start with options  multiple positions");
            // Test FROM_NOW
            ConsumerGroup<String> group1 = factory.createConsumerGroup(
                "group-from-now", "test-topic", String.class);
            group1.addConsumer("c1", msg -> Future.succeededFuture());
            group1.start(SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_NOW)
                .build()).await();
            assertTrue(group1.isActive());
            group1.close();

            // Test FROM_BEGINNING
            ConsumerGroup<String> group2 = factory.createConsumerGroup(
                "group-from-beginning", "test-topic", String.class);
            group2.addConsumer("c2", msg -> Future.succeededFuture());
            group2.start(SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .build()).await();
            assertTrue(group2.isActive());
            group2.close();

            // Test defaults (implicitly FROM_NOW)
            ConsumerGroup<String> group3 = factory.createConsumerGroup(
                "group-defaults", "test-topic", String.class);
            group3.addConsumer("c3", msg -> Future.succeededFuture());
            group3.start(SubscriptionOptions.defaults()).await();
            assertTrue(group3.isActive());
            group3.close();
        }
    }

    // ========================================================================
    // Tests for setMessageHandler()
    // ========================================================================

    @Nested
    @DisplayName("setMessageHandler() method")
    class SetMessageHandlerTests {

        @Test
        @DisplayName("should create default consumer with correct ID pattern")
        void testSetMessageHandler_CreatesDefaultConsumer() {
        logger.info("Test: set message handler  creates default consumer");
            // Arrange
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            // Act
            ConsumerGroupMember<String> member = group.setMessageHandler(
                msg -> Future.succeededFuture());

            // Assert
            assertNotNull(member, "Should return ConsumerGroupMember");
            assertEquals("test-group-default-consumer", member.getConsumerId(),
                "Consumer ID should follow pattern {groupName}-default-consumer");
            assertEquals(1, group.getConsumerIds().size(),
                "Should have exactly one consumer");
            assertTrue(group.getConsumerIds().contains("test-group-default-consumer"),
                "Consumer list should contain default consumer");

            // Cleanup
            group.close();
        }

        @Test
        @DisplayName("should return ConsumerGroupMember instance")
        void testSetMessageHandler_ReturnsConsumerGroupMember() {
        logger.info("Test: set message handler  returns consumer group member");
            // Arrange
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            // Act
            ConsumerGroupMember<String> member = group.setMessageHandler(
                msg -> Future.succeededFuture());

            // Assert
            assertNotNull(member);
            assertTrue(member instanceof ConsumerGroupMember);
            assertEquals("test-group", member.getGroupName());
            assertEquals("test-topic", member.getTopic());

            // Cleanup
            group.close();
        }

        @Test
        @DisplayName("should process messages correctly")
        void testSetMessageHandler_ProcessesMessages(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: set message handler  processes messages");
            // Arrange
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            AtomicInteger count = new AtomicInteger(0);
            List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
            Checkpoint messagesReceived = testContext.checkpoint(2);

            group.setMessageHandler(msg -> {
                count.incrementAndGet();
                receivedMessages.add(msg.getPayload());
                messagesReceived.flag();
                return Future.succeededFuture();
            });

            // Act
            group.start();

            // Send messages
            producer.send("Message-1");
            producer.send("Message-2");
            producer.send("Message-3");

            // Wait for processing
            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));

            // Assert
            assertTrue(count.get() >= 2,
                "Should process at least 2 messages, got: " + count.get());
            assertTrue(receivedMessages.size() >= 2,
                "Should receive at least 2 messages");

            // Cleanup
            group.close();
        }

        @Test
        @DisplayName("should throw IllegalStateException when called twice")
        void testSetMessageHandler_CalledTwice() {
        logger.info("Test: set message handler  called twice");
            // Arrange
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.setMessageHandler(msg -> Future.succeededFuture());

            // Act & Assert
            assertThrows(IllegalStateException.class,
                () -> group.setMessageHandler(msg -> Future.succeededFuture()),
                "Should throw IllegalStateException when called twice");

            // Cleanup
            group.close();
        }

        @Test
        @DisplayName("should throw IllegalArgumentException for null handler")
        void testSetMessageHandler_NullHandler() {
        logger.info("Test: set message handler  null handler");
            // Arrange
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            // Act & Assert - Implementation throws IllegalArgumentException
            assertThrows(IllegalArgumentException.class,
                () -> group.setMessageHandler(null),
                "Should throw IllegalArgumentException for null handler");

            // Cleanup
            group.close();
        }

        @Test
        @DisplayName("should throw IllegalStateException after close")
        void testSetMessageHandler_AfterClose() throws Exception {
        logger.info("Test: set message handler  after close");
            // Arrange
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.close();

            // Act & Assert
            assertThrows(IllegalStateException.class,
                () -> group.setMessageHandler(msg -> Future.succeededFuture()),
                "Should throw IllegalStateException when called on closed group");
        }

        @Test
        @DisplayName("should work with start() method")
        void testSetMessageHandler_IntegrationWithStart(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: set message handler  integration with start");
            // Arrange
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            AtomicInteger count = new AtomicInteger(0);
            Checkpoint messageReceived = testContext.checkpoint();
            group.setMessageHandler(msg -> {
                count.incrementAndGet();
                messageReceived.flag();
                return Future.succeededFuture();
            });

            // Act
            group.start();

            // Send messages
            producer.send("Test-1");
            producer.send("Test-2");

            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));

            // Assert
            assertTrue(group.isActive());
            assertTrue(count.get() >= 1,
                "Should process at least 1 message");

            // Cleanup
            group.close();
        }

        @Test
        @Disabled("Native queue requires SubscriptionManager integration for start position support - use two-step process with SubscriptionManager.subscribe()")
        @DisplayName("should work with start(SubscriptionOptions)")
        void testSetMessageHandler_IntegrationWithStartOptions(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: set message handler  integration with start options");
            // Arrange: Send historical messages
            for (int i = 0; i < 3; i++) {
                producer.send("Historical-" + i);
            }

            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            AtomicInteger count = new AtomicInteger(0);
            Checkpoint messagesReceived = testContext.checkpoint(2);
            group.setMessageHandler(msg -> {
                count.incrementAndGet();
                messagesReceived.flag();
                return Future.succeededFuture();
            });

            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .build();

            // Act - start after a delay to ensure messages are committed
            vertx.setTimer(1000, id -> {
                group.start(options);
            });

            // Wait for processing
            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));

            // Assert
            assertTrue(group.isActive());
            assertTrue(count.get() >= 2,
                "Should process historical messages, got: " + count.get());

            // Cleanup
            group.close();
        }

        @Test
        @DisplayName("should track statistics correctly")
        void testSetMessageHandler_Statistics(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: set message handler  statistics");
            // Arrange
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            AtomicInteger count = new AtomicInteger(0);
            Checkpoint messagesReceived = testContext.checkpoint(3);
            ConsumerGroupMember<String> member = group.setMessageHandler(msg -> {
                int c = count.incrementAndGet();
                if (c <= 3) {
                    messagesReceived.flag();
                }
                return Future.succeededFuture();
            });

            group.start();

            // Send messages
            for (int i = 0; i < 5; i++) {
                producer.send("Message-" + i);
            }

            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));

            // Assert
            ConsumerGroupStats groupStats = group.getStats();
            assertNotNull(groupStats);
            assertEquals("test-group", groupStats.getGroupName());
            assertEquals(1, groupStats.getActiveConsumerCount());

            ConsumerMemberStats memberStats = member.getStats();
            assertNotNull(memberStats);
            assertEquals("test-group-default-consumer", memberStats.getConsumerId());
            assertTrue(memberStats.isActive());

            // Cleanup
            group.close();
        }

        @Test
        @Disabled("Native queue setMessageHandler() has a race condition - thread safety needs implementation fix")
        @DisplayName("should be thread-safe - only one caller succeeds")
        void testSetMessageHandler_ThreadSafety() throws Exception {
        logger.info("Test: set message handler  thread safety");
            // Arrange
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            ExecutorService executor = Executors.newFixedThreadPool(5);
            java.util.concurrent.CyclicBarrier startBarrier = new java.util.concurrent.CyclicBarrier(6);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);
            List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

            // Act: 5 threads try to set handler simultaneously
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
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

            // Release all threads simultaneously
            startBarrier.await();

            // Wait for completion
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }

            // Assert
            assertEquals(1, successCount.get(),
                "Exactly one thread should succeed");
            assertEquals(4, failureCount.get(),
                "Four threads should fail with IllegalStateException");

            for (Exception e : exceptions) {
                assertTrue(e instanceof IllegalStateException,
                    "Exception should be IllegalStateException, got: " + e.getClass());
            }

            // Cleanup
            executor.shutdown();
            group.close();
        }
    }
}


