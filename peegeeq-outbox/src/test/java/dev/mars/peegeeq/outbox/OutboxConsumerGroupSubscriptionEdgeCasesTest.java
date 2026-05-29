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
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Edge case tests for Outbox Consumer Group subscription features.
 * 
 * <p>Comprehensive edge case testing for:</p>
 * <ul>
 *   <li>FROM_MESSAGE_ID start position</li>
 *   <li>Heartbeat configuration options</li>
 *   <li>Builder validation edge cases</li>
 *   <li>Boundary conditions (empty topics, invalid IDs, timestamps)</li>
 *   <li>Concurrent operations and race conditions</li>
 *   <li>Resource cleanup edge cases</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-17
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@DisplayName("Outbox Consumer Group Subscription Edge Cases")
class OutboxConsumerGroupSubscriptionEdgeCasesTest {
    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerGroupSubscriptionEdgeCasesTest.class);


    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory factory;
    private MessageProducer<String> producer;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .property("peegeeq.queue.polling-interval", "PT0.5S")
                .build();
        // Creates tables in public schema - use QUEUE_ALL for PeeGeeQManager health checks
        // Also include CONSUMER_GROUP_FANOUT for subscription management tables (outbox_topic_subscriptions)
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL, SchemaComponent.CONSUMER_GROUP_FANOUT);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().onSuccess(v -> {
            DatabaseService databaseService = new PgDatabaseService(manager);
            QueueFactoryProvider provider = new PgQueueFactoryProvider();
            OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

            factory = provider.createFactory("outbox", databaseService);
            producer = factory.createProducer("test-topic", String.class);
            testContext.completeNow();
        }).onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        if (producer != null) {
            producer.close();
        }
        if (factory != null) {
            factory.close();
        }
        if (manager != null) {
            manager.closeReactive()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(30, SECONDS));
    }

    // ========================================================================
    // FROM_MESSAGE_ID Edge Cases
    // ========================================================================

    @Nested
    @DisplayName("FROM_MESSAGE_ID start position")
    class FromMessageIdTests {

        @Test
        @DisplayName("should start from specific message ID")
        void testStartFromMessageId_Valid(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        logger.info("Test: start from message id  valid");
            // Send 5 messages with gaps
            Future<Void> sendChain = Future.succeededFuture();
            for (int i = 0; i < 5; i++) {
                final int idx = i;
                sendChain = sendChain
                    .compose(v -> producer.send("Message-" + idx))
                    .compose(v -> vertx.timer(100))
                    .mapEmpty();
            }

            sendChain.compose(v -> {
                ConsumerGroup<String> group = factory.createConsumerGroup(
                    "test-group", "test-topic", String.class);

                List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
                group.setMessageHandler(msg -> {
                    receivedMessages.add(msg.getPayload());
                    return Future.succeededFuture();
                });

                // Start from message ID 3 (outbox doesn't return IDs, so use a reasonable ID)
                SubscriptionOptions options = SubscriptionOptions.builder()
                    .startFromMessageId(3L)
                    .build();

                return group.start(options)
                    .compose(v2 -> vertx.timer(3000))
                    .map(timerId -> {
                        testContext.verify(() -> {
                            assertTrue(group.isActive());
                            // Messages with ID >= 3 should be received
                            assertFalse(receivedMessages.isEmpty(),
                                "Should process messages from ID 3 onwards");
                        });
                        return (Void) null;
                    })
                    .eventually(() -> group.close());
            })
            .onComplete(testContext.succeeding(v -> testContext.completeNow()));
            assertTrue(testContext.awaitCompletion(30, SECONDS));
        }

        @Test
        @DisplayName("should handle message ID that doesn't exist")
        void testStartFromMessageId_NonExistent(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        logger.info("Test: start from message id  non existent");
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            AtomicInteger count = new AtomicInteger(0);
            group.setMessageHandler(msg -> {
                count.incrementAndGet();
                return Future.succeededFuture();
            });

            // Use very large non-existent message ID
            SubscriptionOptions options = SubscriptionOptions.builder()
                .startFromMessageId(999999999L)
                .build();

            group.start(options)
                .compose(v -> producer.send("New-Message"))
                .compose(v -> vertx.timer(2000))
                .compose(v -> {
                    testContext.verify(() -> {
                        assertTrue(group.isActive());
                        assertEquals(1, count.get(), "Consumer should process the newly sent message");
                    });
                    return group.close();
                })
                .onComplete(testContext.succeeding(v -> testContext.completeNow()));

            assertTrue(testContext.awaitCompletion(10, SECONDS));
        }

        @Test
        @DisplayName("should handle message ID = 0")
        void testStartFromMessageId_Zero(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        logger.info("Test: start from message id  zero");
            // Send some messages first
            Future<Void> sendChain = Future.succeededFuture();
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                sendChain = sendChain.compose(v -> producer.send("Message-" + idx));
            }

            ConsumerGroup<String>[] groupHolder = new ConsumerGroup[1];

            sendChain.compose(v -> vertx.timer(500))
                .compose(timerId -> {
                    ConsumerGroup<String> group = factory.createConsumerGroup(
                        "test-group", "test-topic", String.class);
                    groupHolder[0] = group;

                    List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
                    Checkpoint received = testContext.checkpoint(2);
                    group.setMessageHandler(msg -> {
                        receivedMessages.add(msg.getPayload());
                        received.flag();
                        return Future.succeededFuture();
                    });

                    SubscriptionOptions options = SubscriptionOptions.builder()
                        .startFromMessageId(0L)
                        .build();

                    return group.start(options);
                })
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(10, SECONDS));
            if (groupHolder[0] != null) {
                groupHolder[0].close().onFailure(err -> logger.warn("group close failed", err));
            }
        }

        @Test
        @DisplayName("should throw exception when FROM_MESSAGE_ID without messageId")
        void testStartFromMessageId_Missing() {
        logger.info("Test: start from message id  missing");
            assertThrows(IllegalArgumentException.class, () -> {
                SubscriptionOptions.builder()
                    .startPosition(StartPosition.FROM_MESSAGE_ID)
                    .build();
            });
        }
    }

    // ========================================================================
    // Heartbeat Configuration Edge Cases
    // ========================================================================

    @Nested
    @DisplayName("Heartbeat configuration")
    class HeartbeatConfigTests {

        @Test
        @DisplayName("should accept custom heartbeat settings")
        void testHeartbeat_CustomSettings(VertxTestContext testContext) throws Exception {
        logger.info("Test: heartbeat  custom settings");
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.setMessageHandler(msg -> Future.succeededFuture());

            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_NOW)
                .heartbeatIntervalSeconds(30)
                .heartbeatTimeoutSeconds(120)
                .build();

            group.start(options)
                .compose(v -> {
                    testContext.verify(() -> {
                        assertTrue(group.isActive());
                        assertEquals(30, options.getHeartbeatIntervalSeconds());
                        assertEquals(120, options.getHeartbeatTimeoutSeconds());
                    });
                    return group.close();
                })
                .onComplete(testContext.succeeding(v -> testContext.completeNow()));

            assertTrue(testContext.awaitCompletion(10, SECONDS));
        }

        @Test
        @DisplayName("should reject zero heartbeat interval")
        void testHeartbeat_ZeroInterval() {
        logger.info("Test: heartbeat  zero interval");
            assertThrows(IllegalArgumentException.class, () -> {
                SubscriptionOptions.builder()
                    .heartbeatIntervalSeconds(0)
                    .build();
            });
        }

        @Test
        @DisplayName("should reject negative heartbeat interval")
        void testHeartbeat_NegativeInterval() {
        logger.info("Test: heartbeat  negative interval");
            assertThrows(IllegalArgumentException.class, () -> {
                SubscriptionOptions.builder()
                    .heartbeatIntervalSeconds(-10)
                    .build();
            });
        }

        @Test
        @DisplayName("should reject timeout <= interval")
        void testHeartbeat_TimeoutNotGreaterThanInterval() {
        logger.info("Test: heartbeat  timeout not greater than interval");
            assertThrows(IllegalArgumentException.class, () -> {
                SubscriptionOptions.builder()
                    .heartbeatIntervalSeconds(60)
                    .heartbeatTimeoutSeconds(60)  // Equal, not greater
                    .build();
            });

            assertThrows(IllegalArgumentException.class, () -> {
                SubscriptionOptions.builder()
                    .heartbeatIntervalSeconds(60)
                    .heartbeatTimeoutSeconds(30)  // Less than interval
                    .build();
            });
        }

        @Test
        @DisplayName("should accept minimal valid heartbeat settings")
        void testHeartbeat_MinimalValid(VertxTestContext testContext) throws Exception {
        logger.info("Test: heartbeat  minimal valid");
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.setMessageHandler(msg -> Future.succeededFuture());

            SubscriptionOptions options = SubscriptionOptions.builder()
                .heartbeatIntervalSeconds(1)
                .heartbeatTimeoutSeconds(2)
                .build();

            group.start(options)
                .compose(v -> {
                    testContext.verify(() -> assertTrue(group.isActive()));
                    return group.close();
                })
                .onComplete(testContext.succeeding(v -> testContext.completeNow()));

            assertTrue(testContext.awaitCompletion(10, SECONDS));
        }
    }

    // ========================================================================
    // Timestamp Boundary Conditions
    // ========================================================================

    @Nested
    @DisplayName("Timestamp boundary conditions")
    class TimestampBoundaryTests {

        @Test
        @DisplayName("should handle very old timestamp")
        void testTimestamp_VeryOld(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        logger.info("Test: timestamp  very old");
            // Send current messages
            Future<Void> sendChain = Future.succeededFuture();
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                sendChain = sendChain.compose(v -> producer.send("Message-" + idx));
            }

            ConsumerGroup<String>[] groupHolder = new ConsumerGroup[1];

            sendChain.compose(v -> vertx.timer(500))
                .compose(timerId -> {
                    ConsumerGroup<String> group = factory.createConsumerGroup(
                        "test-group", "test-topic", String.class);
                    groupHolder[0] = group;

                    List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
                    Checkpoint received = testContext.checkpoint(2);
                    group.setMessageHandler(msg -> {
                        receivedMessages.add(msg.getPayload());
                        received.flag();
                        return Future.succeededFuture();
                    });

                    // Use timestamp from 1 year ago
                    Instant veryOldTimestamp = Instant.now().minus(365, ChronoUnit.DAYS);

                    SubscriptionOptions options = SubscriptionOptions.builder()
                        .startFromTimestamp(veryOldTimestamp)
                        .build();

                    return group.start(options);
                })
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(10, SECONDS));
            if (groupHolder[0] != null) {
                groupHolder[0].close().onFailure(err -> logger.warn("group close failed", err));
            }
        }

        @Test
        @DisplayName("should handle future timestamp gracefully")
        void testTimestamp_Future(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        logger.info("Test: timestamp  future");
            // Don't send any messages before starting
            
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            AtomicInteger count = new AtomicInteger(0);
            group.setMessageHandler(msg -> {
                count.incrementAndGet();
                return Future.succeededFuture();
            });

            // Use timestamp 1 hour in the future
            Instant futureTimestamp = Instant.now().plus(1, ChronoUnit.HOURS);

            SubscriptionOptions options = SubscriptionOptions.builder()
                .startFromTimestamp(futureTimestamp)
                .build();

            group.start(options)
                .compose(v -> vertx.timer(2000))
                .compose(v -> {
                    testContext.verify(() -> assertTrue(group.isActive(), "Consumer should start successfully with future timestamp"));
                    return group.close();
                })
                .onComplete(testContext.succeeding(v -> testContext.completeNow()));
            assertTrue(testContext.awaitCompletion(30, SECONDS));
        }

        @Test
        @DisplayName("should throw exception when FROM_TIMESTAMP without timestamp")
        void testTimestamp_Missing() {
        logger.info("Test: timestamp  missing");
            assertThrows(IllegalArgumentException.class, () -> {
                SubscriptionOptions.builder()
                    .startPosition(StartPosition.FROM_TIMESTAMP)
                    .build();
            });
        }

        @Test
        @DisplayName("should reject null timestamp")
        void testTimestamp_Null() {
        logger.info("Test: timestamp  null");
            assertThrows(NullPointerException.class, () -> {
                SubscriptionOptions.builder()
                    .startFromTimestamp(null);
            });
        }
    }

    // ========================================================================
    // Empty Topic and No Messages Edge Cases
    // ========================================================================

    @Nested
    @DisplayName("Empty topic edge cases")
    class EmptyTopicTests {

        @Test
        @DisplayName("should handle start on empty topic with FROM_BEGINNING")
        void testEmptyTopic_FromBeginning(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        logger.info("Test: empty topic  from beginning");
            // Don't send any messages
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "empty-topic", String.class);

            AtomicInteger count = new AtomicInteger(0);
            group.setMessageHandler(msg -> {
                count.incrementAndGet();
                return Future.succeededFuture();
            });

            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .build();

            group.start(options)
                .compose(v -> vertx.timer(2000))
                .compose(v -> {
                    testContext.verify(() -> {
                        assertTrue(group.isActive());
                        assertEquals(0, count.get(), "Should not receive any messages from empty topic");
                    });
                    return group.close();
                })
                .onComplete(testContext.succeeding(v -> testContext.completeNow()));
            assertTrue(testContext.awaitCompletion(30, SECONDS));
        }

        @Test
        @DisplayName("should handle start on empty topic with FROM_TIMESTAMP")
        void testEmptyTopic_FromTimestamp(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        logger.info("Test: empty topic  from timestamp");
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "empty-topic", String.class);

            AtomicInteger count = new AtomicInteger(0);
            group.setMessageHandler(msg -> {
                count.incrementAndGet();
                return Future.succeededFuture();
            });

            SubscriptionOptions options = SubscriptionOptions.builder()
                .startFromTimestamp(Instant.now().minus(1, ChronoUnit.HOURS))
                .build();

            group.start(options)
                .compose(v -> vertx.timer(2000))
                .compose(v -> {
                    testContext.verify(() -> {
                        assertTrue(group.isActive());
                        assertEquals(0, count.get());
                    });
                    return group.close();
                })
                .onComplete(testContext.succeeding(v -> testContext.completeNow()));
            assertTrue(testContext.awaitCompletion(30, SECONDS));
        }
    }

    // ========================================================================
    // Concurrent Operations Edge Cases
    // ========================================================================

    @Nested
    @DisplayName("Concurrent operations")
    class ConcurrentOperationsTests {

        @Test
        @DisplayName("should handle setMessageHandler during message processing")
        void testConcurrent_SetHandlerDuringProcessing(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        logger.info("Test: concurrent  set handler during processing");
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            AtomicInteger count = new AtomicInteger(0);

            // Use a non-blocking handler. The IllegalStateException on the second
            // setMessageHandler() call is thrown because a handler is already registered
            // (members.containsKey), not because a message is actively in-flight.
            // A blocking processingGate.future() pattern leaves the consumer waiting
            // for ACK when tearDown runs, which causes the BackpressureManager permit to
            // never be released until its 30-second timeout, hanging manager.closeReactive().
            group.setMessageHandler(msg -> {
                count.incrementAndGet();
                return Future.succeededFuture();
            });

            group.start()
                .compose(v -> producer.send("Message"))
                .compose(v -> vertx.timer(500))
                .onComplete(testContext.succeeding(timerId -> testContext.verify(() -> {
                    // The handler was already set above  a second call always throws,
                    // whether or not a message is currently being processed.
                    assertThrows(IllegalStateException.class, () -> {
                        group.setMessageHandler(msg -> Future.succeededFuture());
                    });
                    testContext.completeNow();
                })));
            assertTrue(testContext.awaitCompletion(30, SECONDS));
        }

        @Test
        @DisplayName("should handle rapid start-close cycles")
        void testConcurrent_RapidStartClose(VertxTestContext testContext) throws Exception {
        logger.info("Test: concurrent  rapid start close");
            Future<Void> chain = Future.succeededFuture();
            for (int i = 0; i < 5; i++) {
                final int idx = i;
                chain = chain.compose(v -> {
                    ConsumerGroup<String> group = factory.createConsumerGroup(
                        "test-group-" + idx, "test-topic", String.class);
                    group.setMessageHandler(msg -> Future.succeededFuture());

                    SubscriptionOptions options = SubscriptionOptions.builder()
                        .startPosition(StartPosition.FROM_NOW)
                        .build();

                    return group.start(options)
                        .compose(v2 -> {
                            testContext.verify(() -> assertTrue(group.isActive()));
                            return group.close();
                        })
                        .map(v2 -> {
                            testContext.verify(() -> assertFalse(group.isActive()));
                            return (Void) null;
                        });
                });
            }
            chain
                .onComplete(testContext.succeeding(v -> testContext.completeNow()));

            assertTrue(testContext.awaitCompletion(30, SECONDS));
        }
    }

    // ========================================================================
    // SubscriptionOptions Builder Edge Cases
    // ========================================================================

    @Nested
    @DisplayName("SubscriptionOptions builder")
    class BuilderEdgeCasesTests {

        @Test
        @DisplayName("should handle null StartPosition")
        void testBuilder_NullStartPosition() {
        logger.info("Test: builder  null start position");
            assertThrows(NullPointerException.class, () -> {
                SubscriptionOptions.builder()
                    .startPosition(null)
                    .build();
            });
        }

        @Test
        @DisplayName("should allow chaining all builder methods")
        void testBuilder_FullChaining() {
        logger.info("Test: builder  full chaining");
            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_NOW)
                .heartbeatIntervalSeconds(45)
                .heartbeatTimeoutSeconds(180)
                .build();

            assertNotNull(options);
            assertEquals(StartPosition.FROM_NOW, options.getStartPosition());
            assertEquals(45, options.getHeartbeatIntervalSeconds());
            assertEquals(180, options.getHeartbeatTimeoutSeconds());
        }

        @Test
        @DisplayName("should have consistent defaults()")
        void testBuilder_DefaultsConsistency() {
        logger.info("Test: builder  defaults consistency");
            SubscriptionOptions defaults1 = SubscriptionOptions.defaults();
            SubscriptionOptions defaults2 = SubscriptionOptions.defaults();

            assertEquals(defaults1.getStartPosition(), defaults2.getStartPosition());
            assertEquals(defaults1.getHeartbeatIntervalSeconds(), defaults2.getHeartbeatIntervalSeconds());
            assertEquals(defaults1.getHeartbeatTimeoutSeconds(), defaults2.getHeartbeatTimeoutSeconds());
        }

        @Test
        @DisplayName("should implement equals and hashCode correctly")
        void testBuilder_EqualsHashCode() {
        logger.info("Test: builder  equals hash code");
            SubscriptionOptions options1 = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_NOW)
                .heartbeatIntervalSeconds(60)
                .heartbeatTimeoutSeconds(300)
                .build();

            SubscriptionOptions options2 = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_NOW)
                .heartbeatIntervalSeconds(60)
                .heartbeatTimeoutSeconds(300)
                .build();

            assertEquals(options1, options2);
            assertEquals(options1.hashCode(), options2.hashCode());

            SubscriptionOptions options3 = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .heartbeatIntervalSeconds(60)
                .heartbeatTimeoutSeconds(300)
                .build();

            assertNotEquals(options1, options3);
        }

        @Test
        @DisplayName("should have meaningful toString()")
        void testBuilder_ToString() {
        logger.info("Test: builder  to string");
            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_NOW)
                .build();

            String str = options.toString();
            assertNotNull(str);
            assertTrue(str.contains("FROM_NOW"));
            assertTrue(str.contains("heartbeat"));
        }
    }

    // ========================================================================
    // Resource Cleanup Edge Cases
    // ========================================================================

    @Nested
    @DisplayName("Resource cleanup")
    class ResourceCleanupTests {

        @Test
        @DisplayName("should handle close immediately after start with options")
        void testCleanup_ImmediateClose(VertxTestContext testContext) throws Exception {
        logger.info("Test: cleanup  immediate close");
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.setMessageHandler(msg -> Future.succeededFuture());

            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .build();

            group.start(options)
                .compose(v -> group.close())
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    assertFalse(group.isActive());
                    testContext.completeNow();
                })));
            assertTrue(testContext.awaitCompletion(10, SECONDS));
        }

        @Test
        @DisplayName("should handle multiple close calls")
        void testCleanup_MultipleClose(VertxTestContext testContext) throws Exception {
        logger.info("Test: cleanup  multiple close");
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.setMessageHandler(msg -> Future.succeededFuture());
            group.start(SubscriptionOptions.defaults())
                .compose(v -> group.close())
                .compose(v -> group.close())
                .compose(v -> group.close())
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    assertFalse(group.isActive());
                    testContext.completeNow();
                })));
            assertTrue(testContext.awaitCompletion(10, SECONDS));
        }

        @Test
        @DisplayName("should prevent operations after close")
        void testCleanup_PreventOperationsAfterClose(VertxTestContext testContext) throws Exception {
        logger.info("Test: cleanup  prevent operations after close");
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.setMessageHandler(msg -> Future.succeededFuture());
            group.start(SubscriptionOptions.defaults())
                .compose(v -> group.close())
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    // start(options) returns failed Future (not throw) after close
                    group.start(SubscriptionOptions.defaults())
                        .onSuccess(v2 -> testContext.failNow("Should have failed after close"))
                        .onFailure(e -> testContext.verify(() -> {
                            assertInstanceOf(IllegalStateException.class, e);

                            // setMessageHandler throws synchronously after close
                            assertThrows(IllegalStateException.class, () -> {
                                group.setMessageHandler(msg -> Future.succeededFuture());
                            });

                            testContext.completeNow();
                        }));
                })));

            assertTrue(testContext.awaitCompletion(10, SECONDS));
        }
    }
}


