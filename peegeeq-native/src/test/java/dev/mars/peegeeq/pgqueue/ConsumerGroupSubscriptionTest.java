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
import io.vertx.core.Promise;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;

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
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL, SchemaComponent.CONSUMER_GROUP_FANOUT);

        // Initialize PeeGeeQ Manager
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
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

    // ========================================================================
    // Tests for start(SubscriptionOptions)
    // ========================================================================

    @Nested
    @DisplayName("start(SubscriptionOptions) method")
    class StartWithOptionsTests {

        @Test
        @DisplayName("should start with FROM_NOW position")
        void testStartWithOptions_FromNow(VertxTestContext testContext) throws Exception {
        logger.info("Test: start with options  from now");
            List<String> historical = List.of("FromNow-Historical-1", "FromNow-Historical-2");
            String live = "FromNow-Live";
            List<String> received = Collections.synchronizedList(new ArrayList<>());
            Promise<Void> liveReceived = Promise.promise();
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);
            group.addConsumer("consumer-1", msg -> {
                received.add(msg.getPayload());
                if (live.equals(msg.getPayload())) {
                    liveReceived.tryComplete();
                }
                return Future.succeededFuture();
            });

            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_NOW)
                .build();

            sendMessages(historical)
                .compose(v -> group.start(options))
                .compose(v -> producer.send(live))
                .compose(v -> liveReceived.future())
                .compose(v -> countAvailablePayloads(historical))
                .map(availableHistorical -> {
                    assertTrue(group.isActive(), "Group should be active after start completes");
                    assertEquals(1, group.getActiveConsumerCount());
                    assertEquals(List.of(live), received,
                        "FROM_NOW must exclude every message present at the start boundary");
                    assertEquals((long) historical.size(), availableHistorical,
                        "Excluded historical messages must remain available, not be claimed and discarded");
                    return (Void) null;
                })
                .eventually(group::close)
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("should start with FROM_BEGINNING position")
        void testStartWithOptions_FromBeginning(VertxTestContext testContext) throws Exception {
        logger.info("Test: start with options  from beginning");
            List<String> expected = List.of(
                "FromBeginning-1", "FromBeginning-2", "FromBeginning-3");
            List<String> received = Collections.synchronizedList(new ArrayList<>());
            Promise<Void> allReceived = Promise.promise();
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);
            group.addConsumer("consumer-1", msg -> {
                received.add(msg.getPayload());
                if (received.containsAll(expected)) {
                    allReceived.tryComplete();
                }
                return Future.succeededFuture();
            });

            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .build();

            sendMessages(expected)
                .compose(v -> group.start(options))
                .compose(v -> allReceived.future())
                .map(v -> {
                    assertEquals(expected.size(), received.size(),
                        "FROM_BEGINNING must deliver each available historical message exactly once");
                    assertEquals(Set.copyOf(expected), Set.copyOf(received));
                    return (Void) null;
                })
                .eventually(group::close)
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("should start with FROM_TIMESTAMP position")
        void testStartWithOptions_FromTimestamp(VertxTestContext testContext) throws Exception {
        logger.info("Test: start with options  from timestamp");
            List<String> before = List.of("Timestamp-Before-1", "Timestamp-Before-2");
            List<String> after = List.of("Timestamp-After-1", "Timestamp-After-2");
            List<String> received = Collections.synchronizedList(new ArrayList<>());
            Promise<Void> allAfterReceived = Promise.promise();
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);
            group.addConsumer("consumer-1", msg -> {
                received.add(msg.getPayload());
                if (received.containsAll(after)) {
                    allAfterReceived.tryComplete();
                }
                return Future.succeededFuture();
            });

            sendMessages(before)
                .compose(v -> currentDatabaseTime())
                .compose(cutoff -> sendMessages(after)
                    .compose(v -> setCreatedAt(before, cutoff.minusSeconds(1)))
                    .compose(v -> setCreatedAt(after, cutoff.plusSeconds(1)))
                    .compose(v -> group.start(SubscriptionOptions.builder()
                        .startFromTimestamp(cutoff)
                        .build())))
                .compose(v -> allAfterReceived.future())
                .compose(v -> countAvailablePayloads(before))
                .map(availableBefore -> {
                    assertEquals(after.size(), received.size(),
                        "FROM_TIMESTAMP must deliver only messages at or after the cutoff");
                    assertEquals(Set.copyOf(after), Set.copyOf(received));
                    assertEquals((long) before.size(), availableBefore,
                        "Messages before the timestamp must remain available");
                    return (Void) null;
                })
                .eventually(group::close)
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("should start with FROM_MESSAGE_ID position")
        void testStartWithOptions_FromMessageId(VertxTestContext testContext) throws Exception {
            List<String> before = List.of("MessageId-Before-1", "MessageId-Before-2");
            List<String> after = List.of("MessageId-After-1", "MessageId-After-2");
            List<String> received = Collections.synchronizedList(new ArrayList<>());
            Promise<Void> allAfterReceived = Promise.promise();
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);
            group.addConsumer("consumer-1", msg -> {
                received.add(msg.getPayload());
                if (received.containsAll(after)) {
                    allAfterReceived.tryComplete();
                }
                return Future.succeededFuture();
            });

            sendMessages(before)
                .compose(v -> maximumMessageId())
                .compose(boundary -> sendMessages(after)
                    .compose(v -> group.start(SubscriptionOptions.builder()
                        .startFromMessageId(boundary + 1)
                        .build())))
                .compose(v -> allAfterReceived.future())
                .compose(v -> countAvailablePayloads(before))
                .map(availableBefore -> {
                    assertEquals(after.size(), received.size(),
                        "FROM_MESSAGE_ID must deliver only messages at or above the ID boundary");
                    assertEquals(Set.copyOf(after), Set.copyOf(received));
                    assertEquals((long) before.size(), availableBefore,
                        "Messages below the ID boundary must remain available");
                    return (Void) null;
                })
                .eventually(group::close)
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
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
                .compose(v1 -> {
                    assertTrue(group.isActive(), "Group should be active after first start");
                    return group.start(options);
                })
                .compose(v2 -> {
                    assertTrue(group.isActive(), "Group should remain active after second start");
                    return group.close();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        }

        @Test
        @DisplayName("should return failed future after close")
        void testStartWithOptions_AfterClose(VertxTestContext testContext) {
        logger.info("Test: start with options  after close");
            // Arrange
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.addConsumer("consumer-1", msg -> Future.succeededFuture());
            SubscriptionOptions options = SubscriptionOptions.defaults();

            group.close()
                .compose(v -> group.start(options).transform(startResult -> {
                    if (startResult.succeeded()) {
                        return Future.failedFuture(
                            new AssertionError("Starting a closed group unexpectedly succeeded"));
                    }
                    assertInstanceOf(IllegalStateException.class, startResult.cause());
                    return Future.succeededFuture();
                }))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        }

        @Test
        @DisplayName("defaults should use FROM_NOW semantics")
        void testStartWithOptions_DefaultsUseFromNow(VertxTestContext testContext) throws Exception {
            List<String> historical = List.of("Defaults-Historical-1", "Defaults-Historical-2");
            String live = "Defaults-Live";
            List<String> received = Collections.synchronizedList(new ArrayList<>());
            Promise<Void> liveReceived = Promise.promise();
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);
            group.addConsumer("consumer-1", msg -> {
                received.add(msg.getPayload());
                if (live.equals(msg.getPayload())) {
                    liveReceived.tryComplete();
                }
                return Future.succeededFuture();
            });

            sendMessages(historical)
                .compose(v -> group.start(SubscriptionOptions.defaults()))
                .compose(v -> producer.send(live))
                .compose(v -> liveReceived.future())
                .compose(v -> countAvailablePayloads(historical))
                .map(availableHistorical -> {
                    assertEquals(StartPosition.FROM_NOW,
                        SubscriptionOptions.defaults().getStartPosition());
                    assertEquals(List.of(live), received);
                    assertEquals((long) historical.size(), availableHistorical);
                    return (Void) null;
                })
                .eventually(group::close)
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
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
                .compose(v -> {
                    assertTrue(group3.isActive());
                    return group3.stopGracefully();
                })
                .onSuccess(v -> testContext.completeNow())
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
            Promise<Void> atLeastTwoReceived = Promise.promise();

            group.setMessageHandler(msg -> {
                int currentCount = count.incrementAndGet();
                receivedMessages.add(msg.getPayload());
                if (currentCount >= 2) {
                    atLeastTwoReceived.tryComplete();
                }
                return Future.succeededFuture();
            });

            group.start()
                .compose(v -> Future.all(
                    producer.send("Message-1"),
                    producer.send("Message-2"),
                    producer.send("Message-3")).mapEmpty())
                .compose(v -> atLeastTwoReceived.future())
                .eventually(group::close)
                .onSuccess(v -> testContext.verify(() -> {
                    // The native queue may deliver any 2-3 of the sent payloads before close completes;
                    // every received payload must be one of the sent payloads (no duplicates, no corruption).
                    assertEquals(count.get(), receivedMessages.size(),
                        "Counter and received-list must agree");
                    List<String> expectedPayloads = List.of("Message-1", "Message-2", "Message-3");
                    assertTrue(expectedPayloads.containsAll(receivedMessages),
                        "Received payloads must be a subset of sent payloads; got: " + receivedMessages);
                    assertEquals(receivedMessages.size(), receivedMessages.stream().distinct().count(),
                        "Received payloads must not contain duplicates; got: " + receivedMessages);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));

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

        }

        @Test
        @DisplayName("should throw IllegalStateException after close")
        void testSetMessageHandler_AfterClose(VertxTestContext testContext) {
        logger.info("Test: set message handler  after close");
            // Arrange
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.close()
                .onSuccess(v -> testContext.verify(() -> {
                    assertThrows(IllegalStateException.class,
                        () -> group.setMessageHandler(msg -> Future.succeededFuture()),
                        "Should throw IllegalStateException when called on closed group");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
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

            group.start()
                .compose(v -> Future.all(
                    producer.send("Test-1"),
                    producer.send("Test-2")))
                .onFailure(testContext::failNow);

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

        }

        @Test
        @DisplayName("should work with start(SubscriptionOptions)")
        void testSetMessageHandler_IntegrationWithStartOptions(VertxTestContext testContext) throws Exception {
        logger.info("Test: set message handler  integration with start options");
            List<String> expected = List.of(
                "Handler-Historical-1", "Handler-Historical-2", "Handler-Historical-3");
            List<String> received = Collections.synchronizedList(new ArrayList<>());
            Promise<Void> allReceived = Promise.promise();
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);
            group.setMessageHandler(msg -> {
                received.add(msg.getPayload());
                if (received.containsAll(expected)) {
                    allReceived.tryComplete();
                }
                return Future.succeededFuture();
            });

            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .build();

            sendMessages(expected)
                .compose(v -> group.start(options))
                .compose(v -> allReceived.future())
                .map(v -> {
                    assertEquals(expected.size(), received.size(),
                        "The convenience handler must preserve FROM_BEGINNING semantics");
                    assertEquals(Set.copyOf(expected), Set.copyOf(received));
                    return (Void) null;
                })
                .eventually(group::close)
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
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

            Future<Void> sends = group.start();
            for (int i = 0; i < 5; i++) {
                String payload = "Message-" + i;
                sends = sends.compose(v -> producer.send(payload));
            }
            sends.onFailure(testContext::failNow);

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

        }

        @Test
        @DisplayName("should be thread-safe - only one caller succeeds")
        void testSetMessageHandler_ThreadSafety(VertxTestContext testContext) throws Exception {
        logger.info("Test: set message handler  thread safety");
            // Arrange
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            ExecutorService executor = Executors.newFixedThreadPool(5);
            java.util.concurrent.CyclicBarrier startBarrier = new java.util.concurrent.CyclicBarrier(5);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger completedCount = new AtomicInteger(0);
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            Promise<Void> workersComplete = Promise.promise();

            workersComplete.future()
                .map(v -> {
                    assertEquals(1, successCount.get(),
                        "Exactly one thread should succeed");
                    assertEquals(4, failures.size(),
                        "Exactly four threads should fail");
                    failures.forEach(failure -> assertInstanceOf(
                        IllegalStateException.class, failure,
                        "Every losing caller should receive IllegalStateException"));
                    assertEquals(Set.of("test-group-default-consumer"), group.getConsumerIds(),
                        "Concurrent registration must create exactly one default consumer");
                    return (Void) null;
                })
                .eventually(group::close)
                .eventually(() -> {
                    executor.shutdown();
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

            // Act: 5 threads try to set handler simultaneously
            for (int i = 0; i < 5; i++) {
                executor.submit(() -> {
                    try {
                        startBarrier.await(10, TimeUnit.SECONDS);
                        group.setMessageHandler(msg -> Future.succeededFuture());
                        successCount.incrementAndGet();
                    } catch (Throwable failure) {
                        failures.add(failure);
                    } finally {
                        if (completedCount.incrementAndGet() == 5) {
                            workersComplete.complete();
                        }
                    }
                });
            }

            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS),
                "Concurrent handler registration timed out");
        }
    }

    private Future<Void> sendMessages(List<String> payloads) {
        Future<Void> sends = Future.succeededFuture();
        for (String payload : payloads) {
            sends = sends.compose(v -> producer.send(payload));
        }
        return sends;
    }

    private Future<Instant> currentDatabaseTime() {
        return manager.getPool()
            .query("SELECT clock_timestamp() AS cutoff")
            .execute()
            .map(rows -> rows.iterator().next()
                .get(OffsetDateTime.class, "cutoff")
                .toInstant());
    }

    private Future<Long> maximumMessageId() {
        return manager.getPool()
            .preparedQuery(
                "SELECT COALESCE(MAX(id), 0) AS max_id FROM queue_messages WHERE topic = $1")
            .execute(Tuple.of("test-topic"))
            .map(rows -> rows.iterator().next().getLong("max_id"));
    }

    private Future<Long> countAvailablePayloads(List<String> payloads) {
        return manager.getPool()
            .preparedQuery("""
                SELECT COUNT(*) AS message_count
                FROM queue_messages
                WHERE topic = $1
                  AND status = 'AVAILABLE'
                  AND payload->>'value' = ANY($2::text[])
                """)
            .execute(Tuple.of("test-topic", (Object) payloads.toArray(String[]::new)))
            .map(rows -> rows.iterator().next().getLong("message_count"));
    }

    private Future<Void> setCreatedAt(List<String> payloads, Instant timestamp) {
        return manager.getPool().withTransaction(connection -> connection
            .preparedQuery("""
                UPDATE queue_messages
                SET created_at = $1
                WHERE topic = $2
                  AND payload->>'value' = ANY($3::text[])
                """)
            .execute(Tuple.of(
                timestamp.atOffset(ZoneOffset.UTC),
                "test-topic",
                (Object) payloads.toArray(String[]::new)))
            .mapEmpty());
    }
}
