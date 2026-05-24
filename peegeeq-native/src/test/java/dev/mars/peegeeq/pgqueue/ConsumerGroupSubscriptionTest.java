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
import io.vertx.sqlclient.Tuple;

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
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory factory;
    private MessageProducer<String> producer;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
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
        manager.start()
            .compose(v -> manager.getPool().preparedQuery("DELETE FROM queue_messages WHERE topic = $1").execute(Tuple.of("test-topic")))
            .onSuccess(v -> {
                DatabaseService databaseService = new PgDatabaseService(manager);
                QueueFactoryProvider provider = new PgQueueFactoryProvider();
                PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                factory = provider.createFactory("native", databaseService);
                producer = factory.createProducer("test-topic", String.class);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        logger.info("Tearing down: closing resources and manager");
        if (producer != null) {
            try { producer.close(); } catch (Exception e) { logger.warn("Error closing producer", e); }
        }
        if (factory != null) {
            try { factory.close(); } catch (Exception e) { logger.warn("Error closing factory", e); }
        }
        if (manager != null) {
            manager.closeReactive()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(e -> { logger.warn("manager close failed: {}", e.getMessage()); testContext.completeNow(); });
        } else {
            testContext.completeNow();
        }
        testContext.awaitCompletion(30, TimeUnit.SECONDS);
    }

    // ========================================================================
    // Tests for start(SubscriptionOptions)
    // ========================================================================

    @Nested
    @DisplayName("start(SubscriptionOptions) method")
    class StartWithOptionsTests {

        // TODO(consumer-group-start-position): Re-enable once native ConsumerGroup.start(SubscriptionOptions)
        // honors StartPosition.FROM_NOW. Today the native impl ignores startPosition and behaves as
        // "from now" only by accident (no historical replay path exists). Fix path:
        //   1. Route ConsumerGroup.start(options) through SubscriptionManager.subscribe(topic, options)
        //      so StartPosition is applied at the subscription layer (the bitemporal/outbox path already does this).
        //   2. Then this test's expectation ("only messages sent after start are received") becomes meaningful
        //      instead of trivially true.
        // Owner: native-queue. Tracking: see git history of this file commit a6b9cc8d for prior context.
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
            producer.send("Message 1").onFailure(testContext::failNow);
            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));

            assertTrue(count.get() >= 1, "Should process messages sent after start");

            // Cleanup
            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));
        }

        // TODO(consumer-group-start-position): Re-enable once native ConsumerGroup.start(SubscriptionOptions)
        // honors StartPosition.FROM_BEGINNING. Currently the native queue does not replay historical rows;
        // SubscriptionManager.subscribe() is the only path that performs the LSN/sequence rewind. Fix path:
        //   1. Implement historical replay in PgNativeConsumerGroup.start(options) by delegating to
        //      SubscriptionManager.subscribe() when options.startPosition != FROM_NOW.
        //   2. Ensure replay reads through outbox_topic_subscriptions cursor (already provisioned by
        //      SchemaComponent.CONSUMER_GROUP_FANOUT in setUp).
        @Test
        @Disabled("Native queue requires SubscriptionManager integration for start position support - use two-step process with SubscriptionManager.subscribe()")
        @DisplayName("should start with FROM_BEGINNING position")
        void testStartWithOptions_FromBeginning(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: start with options  from beginning");
            // Arrange: Send messages before subscription
            List<String> sentMessages = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                String msg = "Historical-" + i;
                producer.send(msg).onFailure(testContext::failNow);
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
            vertx.timer(1000)
                .onSuccess(id -> group.start(options))
                .onFailure(testContext::failNow);

            // Wait for processing
            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));

            // Assert
            assertTrue(group.isActive());
            assertTrue(receivedMessages.size() >= 3,
                "Should process historical messages, received: " + receivedMessages.size());

            // Cleanup
            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));
        }

        // TODO(consumer-group-start-position): Re-enable once native ConsumerGroup.start(SubscriptionOptions)
        // honors StartPosition.FROM_TIMESTAMP + startFromTimestamp. Same root cause as FROM_BEGINNING:
        // the native path lacks a timestamp-cursor query. Fix path:
        //   1. Add timestamp predicate to the native replay query (filter on queue_messages.created_at >= ?).
        //   2. Route through SubscriptionManager.subscribe(topic, options) so the cursor is shared with
        //      bitemporal/outbox implementations.
        @Test
        @Disabled("Native queue requires SubscriptionManager integration for start position support - use two-step process with SubscriptionManager.subscribe()")
        @DisplayName("should start with FROM_TIMESTAMP position")
        void testStartWithOptions_FromTimestamp(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: start with options  from timestamp");
            // Arrange: Send messages and capture timestamp
            Instant beforeTimestamp = Instant.now();

            for (int i = 0; i < 3; i++) {
                producer.send("Before-" + i).onFailure(testContext::failNow);
            }

            Instant cutoffTimestamp = Instant.now();

            for (int i = 0; i < 3; i++) {
                producer.send("After-" + i).onFailure(testContext::failNow);
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
            vertx.timer(1000)
                .onSuccess(id -> group.start(options))
                .onFailure(testContext::failNow);

            // Wait for processing
            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));

            // Assert
            assertTrue(group.isActive());
            assertTrue(receivedMessages.size() >= 1,
                "Should process messages after timestamp, received: " + receivedMessages.size());

            // Cleanup
            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));
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
            group.close().onFailure(e -> fail("close() failed: " + e.getMessage()));
        }

        @Test
        @DisplayName("should allow multiple start calls (idempotent)")
        void testStartWithOptions_AlreadyActive(VertxTestContext testContext) {
        logger.info("Test: start with options  already active");
            // Arrange
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.addConsumer("consumer-1", msg -> Future.succeededFuture());

            SubscriptionOptions options = SubscriptionOptions.defaults();
            group.start(options)
                .onSuccess(v1 -> testContext.verify(() -> {
                    assertTrue(group.isActive(), "Group should be active after first start");
                    // Act - second start should be idempotent (no exception)
                    group.start(options)
                        .onSuccess(v2 -> testContext.verify(() -> {
                            assertTrue(group.isActive(), "Group should remain active after second start");
                            group.close().onFailure(testContext::failNow);
                            testContext.completeNow();
                        }))
                        .onFailure(testContext::failNow);
                }))
                .onFailure(testContext::failNow);
        }

        @Test
        @DisplayName("should return failed future after close")
        void testStartWithOptions_AfterClose() throws Exception {
        logger.info("Test: start with options  after close");
            // Arrange
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.addConsumer("consumer-1", msg -> Future.succeededFuture());
            group.close().onFailure(e -> fail("close() failed: " + e.getMessage()));

            SubscriptionOptions options = SubscriptionOptions.defaults();

            // Act & Assert async method returns a failed future, not a thrown exception
            Future<Void> result = group.start(options);
            assertTrue(result.failed(), "Should fail when starting a closed group");
            assertInstanceOf(IllegalStateException.class, result.cause());
        }

        // TODO(consumer-group-start-position): Re-enable once start(SubscriptionOptions) is implemented
        // properly. Today this passes only by accident: default options == FROM_NOW, and the native impl
        // is FROM_NOW-only. Once start(options) is real (see prior TODOs in this nested class), this test
        // verifies the delegation contract ("start(defaults()) behaves identically to start()").
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
            producer.send("Test").onFailure(testContext::failNow);
            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));

            assertTrue(count.get() >= 1);

            // Cleanup
            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));
        }

        @Test
        @DisplayName("should work with different start positions")
        void testStartWithOptions_MultiplePositions(VertxTestContext testContext) {
        logger.info("Test: start with options  multiple positions");
            // Test FROM_NOW
            ConsumerGroup<String> group1 = factory.createConsumerGroup(
                "group-from-now", "test-topic", String.class);
            group1.addConsumer("c1", msg -> Future.succeededFuture());

            ConsumerGroup<String> group2 = factory.createConsumerGroup(
                "group-from-beginning", "test-topic", String.class);
            group2.addConsumer("c2", msg -> Future.succeededFuture());

            ConsumerGroup<String> group3 = factory.createConsumerGroup(
                "group-defaults", "test-topic", String.class);
            group3.addConsumer("c3", msg -> Future.succeededFuture());

            group1.start(SubscriptionOptions.builder()
                    .startPosition(StartPosition.FROM_NOW)
                    .build())
                .compose(v -> {
                    assertTrue(group1.isActive());
                    return group1.stopGracefully().compose(ignored -> group2.start(SubscriptionOptions.builder()
                        .startPosition(StartPosition.FROM_BEGINNING)
                        .build()));
                })
                .compose(v -> {
                    assertTrue(group2.isActive());
                    return group2.stopGracefully().compose(ignored -> group3.start(SubscriptionOptions.defaults()));
                })
                .onSuccess(v -> testContext.verify(() -> {
                    assertTrue(group3.isActive());
                    group3.stopGracefully().onFailure(testContext::failNow);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
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
            group.close().onFailure(e -> fail("close() failed: " + e.getMessage()));
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
            group.close().onFailure(e -> fail("close() failed: " + e.getMessage()));
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
            producer.send("Message-1").onFailure(testContext::failNow);
            producer.send("Message-2").onFailure(testContext::failNow);
            producer.send("Message-3").onFailure(testContext::failNow);

            // Wait for processing
            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));

            // Assert: checkpoint(2) gates timing; verify content correctness of what arrived.
            // The native queue may deliver any 2-3 of the sent payloads within the checkpoint window;
            // every received payload must be one of the sent payloads (no duplicates, no corruption).
            assertEquals(count.get(), receivedMessages.size(),
                "Counter and received-list must agree");
            List<String> expectedPayloads = List.of("Message-1", "Message-2", "Message-3");
            assertTrue(expectedPayloads.containsAll(receivedMessages),
                "Received payloads must be a subset of sent payloads; got: " + receivedMessages);
            assertEquals(receivedMessages.size(), receivedMessages.stream().distinct().count(),
                "Received payloads must not contain duplicates; got: " + receivedMessages);

            // Cleanup
            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));
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
            group.close().onFailure(e -> fail("close() failed: " + e.getMessage()));
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
            group.close().onFailure(e -> fail("close() failed: " + e.getMessage()));
        }

        @Test
        @DisplayName("should throw IllegalStateException after close")
        void testSetMessageHandler_AfterClose() throws Exception {
        logger.info("Test: set message handler  after close");
            // Arrange
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.close().onFailure(e -> fail("close() failed: " + e.getMessage()));

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
            List<String> receivedPayloads = Collections.synchronizedList(new ArrayList<>());
            Checkpoint messageReceived = testContext.checkpoint();
            group.setMessageHandler(msg -> {
                count.incrementAndGet();
                receivedPayloads.add(msg.getPayload());
                messageReceived.flag();
                return Future.succeededFuture();
            });

            // Act
            group.start();

            // Send messages
            producer.send("Test-1").onFailure(testContext::failNow);
            producer.send("Test-2").onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));

            // Assert: checkpoint() gates on first arrival; verify content of what arrived.
            assertTrue(group.isActive());
            assertEquals(count.get(), receivedPayloads.size(),
                "Counter and received-list must agree");
            List<String> expectedPayloads = List.of("Test-1", "Test-2");
            assertTrue(expectedPayloads.containsAll(receivedPayloads),
                "Received payloads must be a subset of sent payloads; got: " + receivedPayloads);
            assertEquals(receivedPayloads.size(), receivedPayloads.stream().distinct().count(),
                "Received payloads must not contain duplicates; got: " + receivedPayloads);

            // Cleanup
            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));
        }

        // TODO(consumer-group-start-position): Re-enable once setMessageHandler() + start(options) chain
        // supports historical replay. Depends on the same fix as the StartWithOptionsTests nested class:
        // native ConsumerGroup needs SubscriptionManager.subscribe() integration to honor FROM_BEGINNING.
        @Test
        @Disabled("Native queue requires SubscriptionManager integration for start position support - use two-step process with SubscriptionManager.subscribe()")
        @DisplayName("should work with start(SubscriptionOptions)")
        void testSetMessageHandler_IntegrationWithStartOptions(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: set message handler  integration with start options");
            // Arrange: Send historical messages
            for (int i = 0; i < 3; i++) {
                producer.send("Historical-" + i).onFailure(testContext::failNow);
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
            vertx.timer(1000)
                .onSuccess(id -> group.start(options))
                .onFailure(testContext::failNow);

            // Wait for processing
            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));

            // Assert
            assertTrue(group.isActive());
            assertTrue(count.get() >= 2,
                "Should process historical messages, got: " + count.get());

            // Cleanup
            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));
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
                producer.send("Message-" + i).onFailure(testContext::failNow);
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
            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));
        }

        // TODO(setMessageHandler-thread-safety): Real production race condition, not a test bug.
        // PgNativeConsumerGroup.setMessageHandler() reads-then-writes the handler field without
        // CAS / synchronized, so concurrent callers can both pass the "already set?" check and both
        // install a handler. Fix path:
        //   1. In PgNativeConsumerGroup, replace the handler field with an AtomicReference<MessageHandler<T>>
        //      (or guard the set with a single synchronized block).
        //   2. Use compareAndSet(null, handler) -> on false throw IllegalStateException.
        //   3. Mirror the same fix in any other ConsumerGroup impl exposing setMessageHandler().
        // Re-enable this test after the fix; it already encodes the correct contract (1 success, 4 ISE).
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
                        startBarrier.await(10, TimeUnit.SECONDS);
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
            startBarrier.await(10, TimeUnit.SECONDS);

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
            group.close().onFailure(e -> logger.warn("close() failed in cleanup", e));
        }
    }
}


