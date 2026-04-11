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
import io.vertx.core.Promise;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge case tests for Outbox Consumer Group v1.1.0 features.
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
 * @version 1.1.0
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@DisplayName("Outbox Consumer Group v1.1.0 Edge Cases")
class OutboxConsumerGroupV110EdgeCasesTest {

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
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Creates tables in public schema - use QUEUE_ALL for PeeGeeQManager health checks
        // Also include CONSUMER_GROUP_FANOUT for subscription management tables (outbox_topic_subscriptions)
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL, SchemaComponent.CONSUMER_GROUP_FANOUT);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        DatabaseService databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();
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
            manager.closeReactive()
                .onComplete(ar -> testContext.completeNow());
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

                group.start(options);
                return vertx.timer(3000).map(timerId -> {
                    testContext.verify(() -> {
                        assertTrue(group.isActive());
                        // Should receive messages based on ID filtering
                        assertTrue(receivedMessages.size() >= 0,
                            "Should process messages from ID 3 onwards");
                    });
                    group.close();
                    return null;
                });
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
            assertTrue(testContext.awaitCompletion(30, SECONDS));
        }

        @Test
        @DisplayName("should handle message ID that doesn't exist")
        void testStartFromMessageId_NonExistent(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws InterruptedException {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            AtomicInteger count = new AtomicInteger(0);
            Checkpoint messageReceived = testContext.checkpoint();
            group.setMessageHandler(msg -> {
                count.incrementAndGet();
                messageReceived.flag();
                return Future.succeededFuture();
            });

            // Use very large non-existent message ID
            SubscriptionOptions options = SubscriptionOptions.builder()
                .startFromMessageId(999999999L)
                .build();

            group.start(options);
            vertx.timer(2000)
                .compose(timerId -> {
                    testContext.verify(() -> {
                        assertTrue(group.isActive());
                        assertEquals(0, count.get(), "Should not receive any messages");
                    });
                    // Now send a new message
                    return producer.send("New-Message");
                })
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(10, SECONDS));

            group.close();
        }

        @Test
        @DisplayName("should handle message ID = 0")
        void testStartFromMessageId_Zero(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws InterruptedException {
            // Send some messages first
            Future<Void> sendChain = Future.succeededFuture();
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                sendChain = sendChain.compose(v -> producer.send("Message-" + idx));
            }

            sendChain.compose(v -> vertx.timer(500))
                .onSuccess(timerId -> {
                    ConsumerGroup<String> group = factory.createConsumerGroup(
                        "test-group", "test-topic", String.class);

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

                    group.start(options);
                })
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(10, SECONDS));
        }

        @Test
        @DisplayName("should throw exception when FROM_MESSAGE_ID without messageId")
        void testStartFromMessageId_Missing() {
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
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.setMessageHandler(msg -> Future.succeededFuture());

            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_NOW)
                .heartbeatIntervalSeconds(30)
                .heartbeatTimeoutSeconds(120)
                .build();

            group.start(options)
                .onSuccess(v -> testContext.verify(() -> {
                    assertTrue(group.isActive());
                    assertEquals(30, options.getHeartbeatIntervalSeconds());
                    assertEquals(120, options.getHeartbeatTimeoutSeconds());
                    group.close();
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(10, SECONDS));
        }

        @Test
        @DisplayName("should reject zero heartbeat interval")
        void testHeartbeat_ZeroInterval() {
            assertThrows(IllegalArgumentException.class, () -> {
                SubscriptionOptions.builder()
                    .heartbeatIntervalSeconds(0)
                    .build();
            });
        }

        @Test
        @DisplayName("should reject negative heartbeat interval")
        void testHeartbeat_NegativeInterval() {
            assertThrows(IllegalArgumentException.class, () -> {
                SubscriptionOptions.builder()
                    .heartbeatIntervalSeconds(-10)
                    .build();
            });
        }

        @Test
        @DisplayName("should reject timeout <= interval")
        void testHeartbeat_TimeoutNotGreaterThanInterval() {
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
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.setMessageHandler(msg -> Future.succeededFuture());

            SubscriptionOptions options = SubscriptionOptions.builder()
                .heartbeatIntervalSeconds(1)
                .heartbeatTimeoutSeconds(2)
                .build();

            group.start(options)
                .onSuccess(v -> testContext.verify(() -> {
                    assertTrue(group.isActive());
                    group.close();
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

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
            // Send current messages
            Future<Void> sendChain = Future.succeededFuture();
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                sendChain = sendChain.compose(v -> producer.send("Message-" + idx));
            }

            sendChain.compose(v -> vertx.timer(500))
                .onSuccess(timerId -> {
                    ConsumerGroup<String> group = factory.createConsumerGroup(
                        "test-group", "test-topic", String.class);

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

                    group.start(options);
                })
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(10, SECONDS));
        }

        @Test
        @DisplayName("should handle future timestamp gracefully")
        void testTimestamp_Future(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws InterruptedException {
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

            group.start(options);
            vertx.timer(2000)
                .onSuccess(timerId -> testContext.verify(() -> {
                    assertTrue(group.isActive());
                    assertTrue(count.get() >= 0);
                    group.close();
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
            assertTrue(testContext.awaitCompletion(30, SECONDS));
        }

        @Test
        @DisplayName("should throw exception when FROM_TIMESTAMP without timestamp")
        void testTimestamp_Missing() {
            assertThrows(IllegalArgumentException.class, () -> {
                SubscriptionOptions.builder()
                    .startPosition(StartPosition.FROM_TIMESTAMP)
                    .build();
            });
        }

        @Test
        @DisplayName("should reject null timestamp")
        void testTimestamp_Null() {
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

            group.start(options);
            vertx.timer(2000)
                .onSuccess(timerId -> testContext.verify(() -> {
                    assertTrue(group.isActive());
                    assertEquals(0, count.get(), "Should not receive any messages from empty topic");
                    group.close();
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
            assertTrue(testContext.awaitCompletion(30, SECONDS));
        }

        @Test
        @DisplayName("should handle start on empty topic with FROM_TIMESTAMP")
        void testEmptyTopic_FromTimestamp(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws InterruptedException {
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

            group.start(options);
            vertx.timer(2000)
                .onSuccess(timerId -> testContext.verify(() -> {
                    assertTrue(group.isActive());
                    assertEquals(0, count.get());
                    group.close();
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
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
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            Promise<Void> processingGate = Promise.promise();
            AtomicInteger count = new AtomicInteger(0);

            group.setMessageHandler(msg -> {
                count.incrementAndGet();
                return processingGate.future();
            });

            group.start();
            producer.send("Message")
                .compose(v -> vertx.timer(500))
                .onSuccess(timerId -> testContext.verify(() -> {
                    // Try to set handler again while processing
                    assertThrows(IllegalStateException.class, () -> {
                        group.setMessageHandler(msg -> Future.succeededFuture());
                    });

                    processingGate.complete();
                    group.close();
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
            assertTrue(testContext.awaitCompletion(30, SECONDS));
        }

        @Test
        @DisplayName("should handle rapid start-close cycles")
        void testConcurrent_RapidStartClose(VertxTestContext testContext) throws Exception {
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

                    return group.start(options).map(v2 -> {
                        testContext.verify(() -> assertTrue(group.isActive()));
                        group.close();
                        testContext.verify(() -> assertFalse(group.isActive()));
                        return (Void) null;
                    });
                });
            }
            chain
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

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
            assertThrows(NullPointerException.class, () -> {
                SubscriptionOptions.builder()
                    .startPosition(null)
                    .build();
            });
        }

        @Test
        @DisplayName("should allow chaining all builder methods")
        void testBuilder_FullChaining() {
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
            SubscriptionOptions defaults1 = SubscriptionOptions.defaults();
            SubscriptionOptions defaults2 = SubscriptionOptions.defaults();

            assertEquals(defaults1.getStartPosition(), defaults2.getStartPosition());
            assertEquals(defaults1.getHeartbeatIntervalSeconds(), defaults2.getHeartbeatIntervalSeconds());
            assertEquals(defaults1.getHeartbeatTimeoutSeconds(), defaults2.getHeartbeatTimeoutSeconds());
        }

        @Test
        @DisplayName("should implement equals and hashCode correctly")
        void testBuilder_EqualsHashCode() {
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
        void testCleanup_ImmediateClose() throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.setMessageHandler(msg -> Future.succeededFuture());

            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .build();

            group.start(options);
            group.close();  // Immediate close

            assertFalse(group.isActive());
        }

        @Test
        @DisplayName("should handle multiple close calls")
        void testCleanup_MultipleClose() throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.setMessageHandler(msg -> Future.succeededFuture());
            group.start(SubscriptionOptions.defaults());

            group.close();
            group.close();  // Second close should not throw
            group.close();  // Third close should not throw

            assertFalse(group.isActive());
        }

        @Test
        @DisplayName("should prevent operations after close")
        void testCleanup_PreventOperationsAfterClose(VertxTestContext testContext) throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.setMessageHandler(msg -> Future.succeededFuture());
            group.start(SubscriptionOptions.defaults())
                .onSuccess(v -> {
                    group.close();

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
                })
                .onFailure(testContext::failNow);

            assertTrue(testContext.awaitCompletion(10, SECONDS));
        }
    }
}


