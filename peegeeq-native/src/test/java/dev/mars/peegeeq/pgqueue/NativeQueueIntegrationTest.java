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


import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.deadletter.DeadLetterStatsInfo;
import dev.mars.peegeeq.api.health.OverallHealthInfo;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the native PostgreSQL queue implementation.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class NativeQueueIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(NativeQueueIntegrationTest.class);

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("native_queue_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    private PeeGeeQManager manager;
    private QueueFactory queueFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;

    @BeforeEach
    void setUp() {
        // Initialize database schema using centralized schema initializer (CRITICAL FIX)
        logger.info("Initializing database schema for native queue integration tests");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.NATIVE_QUEUE, SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);
        logger.info("Database schema initialized successfully using centralized schema initializer");

        // Configure test properties with smaller connection pools to avoid exhaustion
        Properties testProps = new Properties();
        testProps.setProperty("peegeeq.database.host", postgres.getHost());
        testProps.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        testProps.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        testProps.setProperty("peegeeq.database.username", postgres.getUsername());
        testProps.setProperty("peegeeq.database.password", postgres.getPassword());
        testProps.setProperty("peegeeq.database.ssl.enabled", "false");

        // Use smaller connection pools for testing to avoid PostgreSQL connection limits
        testProps.setProperty("peegeeq.database.pool.min-size", "2");
        testProps.setProperty("peegeeq.database.pool.max-size", "5");
        testProps.setProperty("peegeeq.database.pool.connection-timeout-ms", "10000");
        testProps.setProperty("peegeeq.database.pool.idle-timeout-ms", "60000");

        testProps.setProperty("peegeeq.queue.polling-interval", "PT1S");
        testProps.setProperty("peegeeq.queue.visibility-timeout", "PT30S");
        testProps.setProperty("peegeeq.metrics.enabled", "true");
        testProps.setProperty("peegeeq.circuit-breaker.enabled", "true");

        // Set system properties
        testProps.forEach((key, value) -> System.setProperty(key.toString(), value.toString()));

        // Clear any existing messages BEFORE initializing components
        clearQueueBeforeSetup();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Initialize native queue components - following provider pattern like working examples
        DatabaseService databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register native factory implementation
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        queueFactory = provider.createFactory("native", databaseService);
        producer = queueFactory.createProducer("test-native-topic", String.class);
        consumer = queueFactory.createConsumer("test-native-topic", String.class);

        // Clear any existing messages AFTER all components are initialized as well
        clearQueue();
    }

    @AfterEach
    void tearDown() {
        // Close resources in reverse order of creation for proper cleanup
        if (consumer != null) {
            try {
                consumer.close();
                logger.debug("Consumer closed successfully");
            } catch (Exception e) {
                logger.warn("Error closing consumer: {}", e.getMessage());
            }
            consumer = null;
        }

        if (producer != null) {
            try {
                producer.close();
                logger.debug("Producer closed successfully");
            } catch (Exception e) {
                logger.warn("Error closing producer: {}", e.getMessage());
            }
            producer = null;
        }

        if (queueFactory != null) {
            try {
                queueFactory.close();
                logger.debug("Queue factory closed successfully");
            } catch (Exception e) {
                logger.warn("Error closing queue factory: {}", e.getMessage());
            }
            queueFactory = null;
        }

        // Clear any remaining messages from the queue
        clearQueue();

        if (manager != null) {
            try {
                manager.close();
                logger.debug("PeeGeeQ Manager closed successfully");
            } catch (Exception e) {
                logger.warn("Error closing PeeGeeQ Manager: {}", e.getMessage());
            }
            manager = null;
        }

        // Clear system properties to avoid interference between tests
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        System.clearProperty("peegeeq.database.pool.min-size");
        System.clearProperty("peegeeq.database.pool.max-size");

        // Clean up system properties
        System.getProperties().entrySet().removeIf(entry ->
            entry.getKey().toString().startsWith("peegeeq."));
    }

    private void clearQueueBeforeSetup() {
        // Initialize schema using JDBC before starting PeeGeeQManager
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             java.sql.Statement stmt = conn.createStatement()) {

            // Create queue_messages table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS queue_messages (
                    id BIGSERIAL PRIMARY KEY,
                    topic VARCHAR(255) NOT NULL,
                    payload JSONB NOT NULL,
                    visible_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                    lock_id BIGINT,
                    lock_until TIMESTAMP WITH TIME ZONE,
                    retry_count INT DEFAULT 0,
                    max_retries INT DEFAULT 3,
                    status VARCHAR(50) DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'LOCKED', 'PROCESSED', 'FAILED', 'DEAD_LETTER')),
                    headers JSONB DEFAULT '{}',
                    correlation_id VARCHAR(255),
                    message_group VARCHAR(255),
                    priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10)
                )
                """);

            // Create outbox table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS outbox (
                    id BIGSERIAL PRIMARY KEY,
                    topic VARCHAR(255) NOT NULL,
                    payload JSONB NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                    processed_at TIMESTAMP WITH TIME ZONE,
                    processing_started_at TIMESTAMP WITH TIME ZONE,
                    status VARCHAR(50) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD_LETTER')),
                    retry_count INT DEFAULT 0,
                    max_retries INT DEFAULT 3,
                    next_retry_at TIMESTAMP WITH TIME ZONE,
                    version INT DEFAULT 0,
                    headers JSONB DEFAULT '{}',
                    error_message TEXT,
                    correlation_id VARCHAR(255),
                    message_group VARCHAR(255),
                    priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10)
                )
                """);

            // Create dead_letter_queue table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS dead_letter_queue (
                    id BIGSERIAL PRIMARY KEY,
                    original_table VARCHAR(50) NOT NULL,
                    original_id BIGINT NOT NULL,
                    topic VARCHAR(255) NOT NULL,
                    payload JSONB NOT NULL,
                    original_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    failed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                    failure_reason TEXT NOT NULL,
                    retry_count INT NOT NULL,
                    headers JSONB DEFAULT '{}',
                    correlation_id VARCHAR(255),
                    message_group VARCHAR(255)
                )
                """);

            // Clear existing data ensuring FK dependencies are handled
            stmt.execute("TRUNCATE TABLE message_processing, queue_messages, outbox, dead_letter_queue CASCADE");

        } catch (Exception e) {
            logger.error("Failed to initialize schema", e);
            throw new RuntimeException("Schema initialization failed", e);
        }
    }

    private void clearQueue() {
        try {
            // Clear ALL messages from ALL topics to ensure test isolation
            // Use reactive pool instead of JDBC DataSource
            io.vertx.sqlclient.Pool pool = manager.getDatabaseService().getConnectionProvider()
                .getReactivePool("peegeeq-main").toCompletionStage().toCompletableFuture().get();
            pool.query("DELETE FROM queue_messages")
                .execute()
                .toCompletionStage().toCompletableFuture().get();
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    @Test
    void testBasicNativeQueueProducerAndConsumer() throws Exception {
        String testMessage = "Hello, Native Queue!";
        
        // Send a message
        CompletableFuture<Void> sendFuture = producer.send(testMessage);
        sendFuture.get(5, TimeUnit.SECONDS);

        // Consume the message
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger receivedCount = new AtomicInteger(0);
        List<String> receivedMessages = new ArrayList<>();

        consumer.subscribe(message -> {
            receivedMessages.add(message.getPayload());
            receivedCount.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for message to be received
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(1, receivedCount.get());
        assertEquals(testMessage, receivedMessages.get(0));
    }

    @Test
    void testNativeQueueWithHeaders() throws Exception {
        String testMessage = "Native queue message with headers";
        Map<String, String> headers = Map.of(
            "content-type", "text/plain",
            "priority", "high",
            "source", "native-test"
        );

        // Send message with headers
        CompletableFuture<Void> sendFuture = producer.send(testMessage, headers);
        sendFuture.get(5, TimeUnit.SECONDS);

        // Consume and verify headers
        CountDownLatch latch = new CountDownLatch(1);
        List<Message<String>> receivedMessages = new ArrayList<>();

        consumer.subscribe(message -> {
            receivedMessages.add(message);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(1, receivedMessages.size());

        Message<String> receivedMessage = receivedMessages.get(0);
        assertEquals(testMessage, receivedMessage.getPayload());
        assertEquals("text/plain", receivedMessage.getHeaders().get("content-type"));
        assertEquals("high", receivedMessage.getHeaders().get("priority"));
        assertEquals("native-test", receivedMessage.getHeaders().get("source"));
    }

    @Test
    void testNativeQueueListenNotify() throws Exception {
        // This test verifies that LISTEN/NOTIFY works for real-time message delivery
        String testMessage = "Real-time notification test";
        
        CountDownLatch latch = new CountDownLatch(1);
        List<String> receivedMessages = new ArrayList<>();

        // Set up consumer first to ensure it's listening
        consumer.subscribe(message -> {
            receivedMessages.add(message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Wait a moment for consumer to start listening
        Thread.sleep(1000);

        // Send message - should trigger immediate notification
        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Should receive message quickly due to LISTEN/NOTIFY
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(testMessage, receivedMessages.get(0));
    }

    @Test
    void testNativeQueueVisibilityTimeout() throws Exception {
        String testMessage = "Visibility timeout test";
        AtomicInteger processingAttempts = new AtomicInteger(0);
        CountDownLatch firstAttemptLatch = new CountDownLatch(1);
        CountDownLatch secondAttemptLatch = new CountDownLatch(1);

        // Send the message
        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Set up consumer that will fail to process (simulating a crash)
        consumer.subscribe(message -> {
            int attempt = processingAttempts.incrementAndGet();
            if (attempt == 1) {
                firstAttemptLatch.countDown();
                // Simulate processing failure by not completing the future
                return new CompletableFuture<>(); // Never completes
            } else {
                secondAttemptLatch.countDown();
                return CompletableFuture.completedFuture(null);
            }
        });

        // Wait for first attempt
        assertTrue(firstAttemptLatch.await(10, TimeUnit.SECONDS));

        // Wait for visibility timeout to expire and message to become available again
        // This should be longer than the configured visibility timeout
        assertTrue(secondAttemptLatch.await(45, TimeUnit.SECONDS));
        
        assertTrue(processingAttempts.get() >= 2);
    }

    @Test
    void testNativeQueueMultipleConsumers() throws Exception {
        int messageCount = 10;
        int consumerCount = 3;
        
        // Create additional consumers
        List<MessageConsumer<String>> consumers = new ArrayList<>();
        List<PgNativeQueueFactory> additionalFactories = new ArrayList<>();
        consumers.add(consumer); // Add the existing consumer

        for (int i = 1; i < consumerCount; i++) {
            DatabaseService additionalDatabaseService = new PgDatabaseService(manager);
            PgNativeQueueFactory additionalFactory = new PgNativeQueueFactory(additionalDatabaseService);
            additionalFactories.add(additionalFactory);
            consumers.add(additionalFactory.createConsumer("test-native-topic", String.class));
        }

        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicInteger totalReceived = new AtomicInteger(0);
        List<String> allReceivedMessages = new ArrayList<>();

        // Set up all consumers
        for (MessageConsumer<String> cons : consumers) {
            cons.subscribe(message -> {
                synchronized (allReceivedMessages) {
                    allReceivedMessages.add(message.getPayload());
                }
                totalReceived.incrementAndGet();
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });
        }

        // Send multiple messages
        for (int i = 0; i < messageCount; i++) {
            producer.send("Message " + i).get(1, TimeUnit.SECONDS);
        }

        // Wait for all messages to be processed
        assertTrue(latch.await(30, TimeUnit.SECONDS));
        assertEquals(messageCount, totalReceived.get());
        assertEquals(messageCount, allReceivedMessages.size());

        // Clean up additional consumers
        for (int i = 1; i < consumers.size(); i++) {
            consumers.get(i).close();
        }

        // Clean up additional factories
        for (PgNativeQueueFactory factory : additionalFactories) {
            try {
                factory.close();
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    @Test
    void testNativeQueueMessageLocking() throws Exception {
        // This test verifies that messages are properly locked during processing
        String testMessage = "Locking test message";
        AtomicInteger processingCount = new AtomicInteger(0);
        CountDownLatch startProcessingLatch = new CountDownLatch(2);
        CountDownLatch finishProcessingLatch = new CountDownLatch(1);

        // Send the message
        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Create two consumers that will try to process the same message
        DatabaseService databaseService2 = new PgDatabaseService(manager);
        PgNativeQueueFactory testQueueFactory = new PgNativeQueueFactory(databaseService2);
        MessageConsumer<String> consumer2 = testQueueFactory.createConsumer("test-native-topic", String.class);

        try {
            // Set up first consumer with slow processing
            consumer.subscribe(message -> {
                processingCount.incrementAndGet();
                startProcessingLatch.countDown();
                try {
                    finishProcessingLatch.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return CompletableFuture.completedFuture(null);
            });

            // Set up second consumer
            consumer2.subscribe(message -> {
                processingCount.incrementAndGet();
                startProcessingLatch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Wait for consumers to start and process the message
            // Need to wait longer than the polling interval (5 seconds) for HYBRID mode
            Thread.sleep(7000);

            // Only one consumer should have picked up the message
            // (The other should be blocked by the lock)
            assertEquals(1, processingCount.get());

            // Allow first consumer to finish
            finishProcessingLatch.countDown();

            // Wait a bit more to ensure no additional processing
            Thread.sleep(2000);
            assertEquals(1, processingCount.get());

        } finally {
            consumer2.close();
            try {
                testQueueFactory.close();
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    @Test
    void testNativeQueueFailureAndRetry() throws Exception {
        String testMessage = "Retry test message";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch successLatch = new CountDownLatch(1);

        // Send the message
        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Set up consumer that fails first few times
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            if (attempt < 3) {
                // Fail the first 2 attempts
                return CompletableFuture.failedFuture(
                    new RuntimeException("Simulated processing failure, attempt " + attempt));
            } else {
                // Succeed on the 3rd attempt
                successLatch.countDown();
                return CompletableFuture.completedFuture(null);
            }
        });

        // Wait for successful processing
        assertTrue(successLatch.await(60, TimeUnit.SECONDS));
        assertTrue(attemptCount.get() >= 3);
    }

    @Test
    void testNativeQueueDeadLetterIntegration() throws Exception {
        // Configure a message that will exceed retry limits
        String testMessage = "Dead letter test message";
        AtomicInteger attemptCount = new AtomicInteger(0);

        // Send the message
        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Set up consumer that always fails
        consumer.subscribe(message -> {
            attemptCount.incrementAndGet();
            return CompletableFuture.failedFuture(
                new RuntimeException("Always fails"));
        });

        // Wait for multiple retry attempts
        Thread.sleep(30000); // Wait long enough for retries to exhaust

        // Verify the message was moved to dead letter queue
        DeadLetterStatsInfo dlqStats = manager.getDeadLetterQueueManager().getStatistics();
        assertTrue(dlqStats.totalMessages() > 0);
        assertTrue(attemptCount.get() > 1);
    }

    @Test
    void testNativeQueueMetricsIntegration() throws Exception {
        String testMessage = "Metrics integration test";

        // Send a message
        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Set up consumer
        CountDownLatch latch = new CountDownLatch(1);
        logger.debug("TEST: About to subscribe consumer to topic: test-native-topic");
        consumer.subscribe(message -> {
            logger.debug("TEST: Consumer received message: {}", message.getId());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        logger.debug("TEST: Consumer subscription completed");

        // Wait for processing
        assertTrue(latch.await(10, TimeUnit.SECONDS));

        // Verify metrics were recorded
        var metrics = manager.getMetrics().getSummary();
        assertTrue(metrics.getMessagesSent() > 0);
        assertTrue(metrics.getMessagesReceived() > 0);
        assertTrue(metrics.getMessagesProcessed() > 0);
        assertTrue(metrics.getNativeQueueDepth() >= 0);
    }

    @Test
    void testNativeQueueHealthCheckIntegration() throws Exception {
        // Verify system is healthy
        assertTrue(manager.isHealthy());

        // Poll briefly until the native-queue component is present (health checks run asynchronously)
        var hcm = manager.getHealthCheckManager();
        boolean seenNative = false;
        for (int i = 0; i < 30; i++) { // up to ~3s
            OverallHealthInfo hs = hcm.getOverallHealth();
            if (hs.components().containsKey("native-queue")) {
                assertTrue(hs.isHealthy());
                seenNative = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(seenNative);
    }

    @Test
    void testNativeQueueBackpressureIntegration() throws Exception {
        // This test verifies that backpressure is applied to native queue operations
        var backpressureManager = manager.getBackpressureManager();
        
        // Send a message through backpressure manager
        String result = backpressureManager.execute("native-queue-send", () -> {
            producer.send("Backpressure test").get(5, TimeUnit.SECONDS);
            return "success";
        });
        
        assertEquals("success", result);
        
        var metrics = backpressureManager.getMetrics();
        assertTrue(metrics.getSuccessfulOperations() > 0);
    }

    @Test
    void testNativeQueueConcurrentProducers() throws Exception {
        int producerCount = 3;
        int messagesPerProducer = 5;
        int totalMessages = producerCount * messagesPerProducer;

        CountDownLatch latch = new CountDownLatch(totalMessages);
        List<String> receivedMessages = new ArrayList<>();

        // Set up consumer
        consumer.subscribe(message -> {
            synchronized (receivedMessages) {
                receivedMessages.add(message.getPayload());
            }
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Create multiple producers sending concurrently
        List<CompletableFuture<Void>> allSends = new ArrayList<>();
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            for (int m = 0; m < messagesPerProducer; m++) {
                final int messageId = m;
                String message = "Native-Producer-" + producerId + "-Message-" + messageId;
                allSends.add(producer.send(message));
            }
        }

        // Wait for all sends to complete
        CompletableFuture.allOf(allSends.toArray(new CompletableFuture[0]))
            .get(15, TimeUnit.SECONDS);

        // Wait for all messages to be received
        assertTrue(latch.await(30, TimeUnit.SECONDS));
        assertEquals(totalMessages, receivedMessages.size());
    }
}
