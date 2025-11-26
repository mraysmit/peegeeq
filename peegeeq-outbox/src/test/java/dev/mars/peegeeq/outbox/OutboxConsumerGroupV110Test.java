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
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
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
@DisplayName("Outbox Consumer Group v1.1.0 Features")
class OutboxConsumerGroupV110Test {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

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
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

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
    void tearDown() throws Exception {
        if (producer != null) {
            producer.close();
        }
        if (factory != null) {
            factory.close();
        }
        if (manager != null) {
            manager.close();
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
        void testStartWithOptions_FromNow() throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            AtomicInteger count = new AtomicInteger(0);
            group.addConsumer("consumer-1", msg -> {
                count.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });

            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_NOW)
                .build();

            group.start(options);

            assertTrue(group.isActive());
            assertEquals(1, group.getActiveConsumerCount());

            producer.send("Message 1").join();
            Thread.sleep(2000);

            assertTrue(count.get() >= 1);
            group.close();
        }

        @Test
        @DisplayName("should start with FROM_BEGINNING position")
        void testStartWithOptions_FromBeginning() throws Exception {
            // Send historical messages
            for (int i = 0; i < 5; i++) {
                producer.send("Historical-" + i).join();
            }
            Thread.sleep(1000);

            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
            group.addConsumer("consumer-1", msg -> {
                receivedMessages.add(msg.getPayload());
                return CompletableFuture.completedFuture(null);
            });

            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .build();

            group.start(options);
            Thread.sleep(5000);

            assertTrue(group.isActive());
            assertTrue(receivedMessages.size() >= 3,
                "Should process historical messages in outbox");
            group.close();
        }

        @Test
        @DisplayName("should start with FROM_TIMESTAMP position")
        void testStartWithOptions_FromTimestamp() throws Exception {
            Instant beforeTimestamp = Instant.now();
            Thread.sleep(1000);

            for (int i = 0; i < 3; i++) {
                producer.send("Before-" + i).join();
            }

            Thread.sleep(1000);
            Instant cutoffTimestamp = Instant.now();
            Thread.sleep(1000);

            for (int i = 0; i < 3; i++) {
                producer.send("After-" + i).join();
            }

            Thread.sleep(1000);

            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
            group.addConsumer("consumer-1", msg -> {
                receivedMessages.add(msg.getPayload());
                return CompletableFuture.completedFuture(null);
            });

            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_TIMESTAMP)
                .startFromTimestamp(cutoffTimestamp)
                .build();

            group.start(options);
            Thread.sleep(5000);

            assertTrue(group.isActive());
            assertTrue(receivedMessages.size() >= 1,
                "Should process messages after timestamp in outbox");
            group.close();
        }

        @Test
        @DisplayName("should throw IllegalArgumentException for null options")
        void testStartWithOptions_NullParameter() {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.addConsumer("consumer-1", msg -> CompletableFuture.completedFuture(null));

            assertThrows(IllegalArgumentException.class, () -> group.start(null));
            group.close();
        }

        @Test
        @DisplayName("should throw IllegalStateException if already active")
        void testStartWithOptions_AlreadyActive() throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.addConsumer("consumer-1", msg -> CompletableFuture.completedFuture(null));

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

            group.addConsumer("consumer-1", msg -> CompletableFuture.completedFuture(null));
            group.close();

            SubscriptionOptions options = SubscriptionOptions.defaults();

            assertThrows(IllegalStateException.class, () -> group.start(options));
        }

        @Test
        @DisplayName("should delegate to standard start()")
        void testStartWithOptions_DelegatesToStandardStart() throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            AtomicInteger count = new AtomicInteger(0);
            group.addConsumer("consumer-1", msg -> {
                count.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });

            SubscriptionOptions options = SubscriptionOptions.defaults();
            group.start(options);

            assertTrue(group.isActive());
            assertEquals(1, group.getActiveConsumerCount());

            producer.send("Test").join();
            Thread.sleep(2000);

            assertTrue(count.get() >= 1);
            group.close();
        }

        @Test
        @DisplayName("should work with multiple start positions")
        void testStartWithOptions_MultiplePositions() throws Exception {
            // FROM_NOW
            ConsumerGroup<String> group1 = factory.createConsumerGroup(
                "group-from-now", "test-topic", String.class);
            group1.addConsumer("c1", msg -> CompletableFuture.completedFuture(null));
            group1.start(SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_NOW).build());
            assertTrue(group1.isActive());
            group1.close();

            // FROM_BEGINNING
            ConsumerGroup<String> group2 = factory.createConsumerGroup(
                "group-from-beginning", "test-topic", String.class);
            group2.addConsumer("c2", msg -> CompletableFuture.completedFuture(null));
            group2.start(SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING).build());
            assertTrue(group2.isActive());
            group2.close();

            // Defaults
            ConsumerGroup<String> group3 = factory.createConsumerGroup(
                "group-defaults", "test-topic", String.class);
            group3.addConsumer("c3", msg -> CompletableFuture.completedFuture(null));
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
                msg -> CompletableFuture.completedFuture(null));

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
                msg -> CompletableFuture.completedFuture(null));

            assertNotNull(member);
            assertTrue(member instanceof ConsumerGroupMember);
            assertEquals("test-group", member.getGroupName());
            assertEquals("test-topic", member.getTopic());

            group.close();
        }

        @Test
        @DisplayName("should process messages in outbox pattern")
        void testSetMessageHandler_ProcessesMessages() throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            AtomicInteger count = new AtomicInteger(0);
            List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());

            group.setMessageHandler(msg -> {
                count.incrementAndGet();
                receivedMessages.add(msg.getPayload());
                return CompletableFuture.completedFuture(null);
            });

            group.start();

            producer.send("Message-1").join();
            producer.send("Message-2").join();
            producer.send("Message-3").join();

            Thread.sleep(5000);

            assertTrue(count.get() >= 2);
            assertTrue(receivedMessages.size() >= 2);

            group.close();
        }

        @Test
        @DisplayName("should throw IllegalStateException when called twice")
        void testSetMessageHandler_CalledTwice() {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            group.setMessageHandler(msg -> CompletableFuture.completedFuture(null));

            assertThrows(IllegalStateException.class,
                () -> group.setMessageHandler(msg -> CompletableFuture.completedFuture(null)));

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
                () -> group.setMessageHandler(msg -> CompletableFuture.completedFuture(null)));
        }

        @Test
        @DisplayName("should work with start()")
        void testSetMessageHandler_IntegrationWithStart() throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            AtomicInteger count = new AtomicInteger(0);
            group.setMessageHandler(msg -> {
                count.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });

            group.start();

            producer.send("Test-1").join();
            producer.send("Test-2").join();

            Thread.sleep(3000);

            assertTrue(group.isActive());
            assertTrue(count.get() >= 1);

            group.close();
        }

        @Test
        @DisplayName("should work with start(SubscriptionOptions)")
        void testSetMessageHandler_IntegrationWithStartOptions() throws Exception {
            // Send historical messages
            for (int i = 0; i < 3; i++) {
                producer.send("Historical-" + i).join();
            }
            Thread.sleep(1000);

            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            AtomicInteger count = new AtomicInteger(0);
            group.setMessageHandler(msg -> {
                count.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });

            SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .build();

            group.start(options);
            Thread.sleep(5000);

            assertTrue(group.isActive());
            assertTrue(count.get() >= 2);

            group.close();
        }

        @Test
        @DisplayName("should track statistics")
        void testSetMessageHandler_Statistics() throws Exception {
            ConsumerGroup<String> group = factory.createConsumerGroup(
                "test-group", "test-topic", String.class);

            AtomicInteger count = new AtomicInteger(0);
            ConsumerGroupMember<String> member = group.setMessageHandler(msg -> {
                count.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });

            group.start();

            for (int i = 0; i < 5; i++) {
                producer.send("Message-" + i).join();
            }

            Thread.sleep(5000);

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
            CountDownLatch startLatch = new CountDownLatch(1);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);
            List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        startLatch.await();
                        group.setMessageHandler(msg -> CompletableFuture.completedFuture(null));
                        successCount.incrementAndGet();
                    } catch (IllegalStateException e) {
                        failureCount.incrementAndGet();
                        exceptions.add(e);
                    } catch (Exception e) {
                        exceptions.add(e);
                    }
                }));
            }

            startLatch.countDown();

            for (Future<?> future : futures) {
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
