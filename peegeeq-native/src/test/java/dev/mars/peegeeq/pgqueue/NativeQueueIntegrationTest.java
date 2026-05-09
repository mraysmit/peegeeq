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
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

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
@ExtendWith(VertxExtension.class)
class NativeQueueIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(NativeQueueIntegrationTest.class);

    @Container
    private static final PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("native_queue_test");
        container.withUsername("test_user");
        container.withPassword("test_pass");
        return container;
    }

    private PeeGeeQManager manager;
    private QueueFactory queueFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;

    @BeforeEach
    void setUp() throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Initialize database schema using centralized schema initializer ()
        logger.info("Initializing database schema for native queue integration tests");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.NATIVE_QUEUE, SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);
        logger.info("Database schema initialized successfully using centralized schema initializer");

        // Configure test properties with smaller connection pools to avoid exhaustion
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .property("peegeeq.database.pool.min-size", "2")
                .property("peegeeq.database.pool.max-size", "5")
                .property("peegeeq.database.pool.connection-timeout-ms", "10000")
                .property("peegeeq.database.pool.idle-timeout-ms", "60000")
                .property("peegeeq.queue.polling-interval", "PT1S")
                .property("peegeeq.queue.visibility-timeout", "PT30S")
                .property("peegeeq.metrics.enabled", "true")
                .property("peegeeq.circuit-breaker.enabled", "true")
                .build();

        // Clear any existing messages BEFORE initializing components
        clearQueueBeforeSetup();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

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
        logger.info("Tearing down: closing resources and manager");
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
                manager.closeReactive().await();
                logger.debug("PeeGeeQ Manager closed successfully");
            } catch (Exception e) {
                logger.warn("Error closing PeeGeeQ Manager: {}", e.getMessage());
            }
            manager = null;
        }
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
            manager.getDatabaseService().getConnectionProvider()
                .getReactivePool("peegeeq-main")
                .compose(pool -> pool.query("DELETE FROM queue_messages").execute())
                .await();
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    @Test
    void testBasicNativeQueueProducerAndConsumer(Vertx vertx, VertxTestContext testContext) throws Exception {
        String testMessage = "Hello, Native Queue!";
        
        // Send a message
        producer.send(testMessage).await();

        // Consume the message
        Checkpoint received = testContext.checkpoint(1);
        AtomicInteger receivedCount = new AtomicInteger(0);
        List<String> receivedMessages = new ArrayList<>();

        consumer.subscribe(message -> {
            receivedMessages.add(message.getPayload());
            receivedCount.incrementAndGet();
            received.flag();
            return Future.succeededFuture();
        });

        // Wait for message to be received
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        assertEquals(1, receivedCount.get());
        assertEquals(testMessage, receivedMessages.get(0));
    }

    @Test
    void testNativeQueueWithHeaders(Vertx vertx, VertxTestContext testContext) throws Exception {
        String testMessage = "Native queue message with headers";
        Map<String, String> headers = Map.of(
            "content-type", "text/plain",
            "priority", "high",
            "source", "native-test"
        );

        // Send message with headers
        producer.send(testMessage, headers).await();

        // Consume and verify headers
        Checkpoint received = testContext.checkpoint(1);
        List<Message<String>> receivedMessages = new ArrayList<>();

        consumer.subscribe(message -> {
            receivedMessages.add(message);
            received.flag();
            return Future.succeededFuture();
        });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        assertEquals(1, receivedMessages.size());

        Message<String> receivedMessage = receivedMessages.get(0);
        assertEquals(testMessage, receivedMessage.getPayload());
        assertEquals("text/plain", receivedMessage.getHeaders().get("content-type"));
        assertEquals("high", receivedMessage.getHeaders().get("priority"));
        assertEquals("native-test", receivedMessage.getHeaders().get("source"));
    }

    @Test
    void testNativeQueueListenNotify(Vertx vertx, VertxTestContext testContext) throws Exception {
        // This test verifies that LISTEN/NOTIFY works for real-time message delivery
        String testMessage = "Real-time notification test";
        
        Checkpoint received = testContext.checkpoint(1);
        List<String> receivedMessages = new ArrayList<>();

        // Set up consumer first to ensure it's listening
        consumer.subscribe(message -> {
            receivedMessages.add(message.getPayload());
            received.flag();
            return Future.succeededFuture();
        });

        // Wait a moment for consumer to start listening using Vert.x timer
        vertx.timer(1000).await();

        // Send message - should trigger immediate notification
        producer.send(testMessage).await();

        // Should receive message quickly due to LISTEN/NOTIFY
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
        assertEquals(testMessage, receivedMessages.get(0));
    }

    @Test
    void testNativeQueueVisibilityTimeout(Vertx vertx) throws Exception {
        String testMessage = "Visibility timeout test";
        AtomicInteger processingAttempts = new AtomicInteger(0);
        Promise<Void> firstAttempt = Promise.promise();
        Promise<Void> secondAttempt = Promise.promise();

        // Send the message
        producer.send(testMessage).await();

        // Set up consumer that will fail to process (simulating a crash)
        consumer.subscribe(message -> {
            int attempt = processingAttempts.incrementAndGet();
            if (attempt == 1) {
                firstAttempt.tryComplete();
                // Simulate processing failure by not completing the future
                return Promise.<Void>promise().future(); // Never completes
            } else {
                secondAttempt.tryComplete();
                return Future.succeededFuture();
            }
        });

        // Wait for first attempt
        firstAttempt.future().await();

        // Wait for visibility timeout to expire and message to become available again
        // This should be longer than the configured visibility timeout
        secondAttempt.future().await();
        
        assertTrue(processingAttempts.get() >= 2);
    }

    @Test
    void testNativeQueueMultipleConsumers(Vertx vertx, VertxTestContext testContext) throws Exception {
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

        Checkpoint allReceived = testContext.checkpoint(messageCount);
        AtomicInteger totalReceived = new AtomicInteger(0);
        List<String> allReceivedMessages = new ArrayList<>();

        // Set up all consumers
        for (MessageConsumer<String> cons : consumers) {
            cons.subscribe(message -> {
                synchronized (allReceivedMessages) {
                    allReceivedMessages.add(message.getPayload());
                }
                totalReceived.incrementAndGet();
                allReceived.flag();
                return Future.succeededFuture();
            });
        }

        // Send multiple messages
        for (int i = 0; i < messageCount; i++) {
            producer.send("Message " + i).await();
        }

        // Wait for all messages to be processed
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
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
    void testNativeQueueMessageLocking(Vertx vertx) throws Exception {
        // This test verifies that messages are properly locked during processing
        String testMessage = "Locking test message";
        AtomicInteger processingCount = new AtomicInteger(0);
        Promise<Void> finishProcessing = Promise.promise();

        // Send the message
        producer.send(testMessage).await();

        // Create two consumers that will try to process the same message
        DatabaseService databaseService2 = new PgDatabaseService(manager);
        PgNativeQueueFactory testQueueFactory = new PgNativeQueueFactory(databaseService2);
        MessageConsumer<String> consumer2 = testQueueFactory.createConsumer("test-native-topic", String.class);

        try {
            // Set up first consumer with slow processing
            consumer.subscribe(message -> {
                processingCount.incrementAndGet();
                try {
                    finishProcessing.future().await();
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }
                return Future.succeededFuture();
            });

            // Set up second consumer
            consumer2.subscribe(message -> {
                processingCount.incrementAndGet();
                return Future.succeededFuture();
            });

            // Wait for one consumer to pick up the message (the other should be blocked by the lock)
            Promise<Void> oneProcessed = Promise.promise();
            long pollTimer = vertx.setPeriodic(100, id -> {
                if (processingCount.get() == 1) {
                    oneProcessed.tryComplete();
                }
            });
            oneProcessed.future().await();
            vertx.cancelTimer(pollTimer);

            // Allow first consumer to finish
            finishProcessing.tryComplete();

            // Wait a bit more to ensure no additional processing
            vertx.timer(2000).await();
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
    void testNativeQueueFailureAndRetry(Vertx vertx) throws Exception {
        String testMessage = "Retry test message";
        AtomicInteger attemptCount = new AtomicInteger(0);
        Promise<Void> success = Promise.promise();

        // Send the message
        producer.send(testMessage).await();

        // Set up consumer that fails first few times
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            if (attempt < 3) {
                // Fail the first 2 attempts
                return Future.failedFuture(
                    new RuntimeException("Simulated processing failure, attempt " + attempt));
            } else {
                // Succeed on the 3rd attempt
                success.tryComplete();
                return Future.succeededFuture();
            }
        });

        // Wait for successful processing
        success.future().await();
        assertTrue(attemptCount.get() >= 3);
    }

    @Test
    void testNativeQueueDeadLetterIntegration(Vertx vertx) throws Exception {
        // Configure a message that will exceed retry limits
        String testMessage = "Dead letter test message";
        AtomicInteger attemptCount = new AtomicInteger(0);

        // Send the message
        producer.send(testMessage).await();

        // Set up consumer that always fails
        consumer.subscribe(message -> {
            attemptCount.incrementAndGet();
            return Future.failedFuture(
                new RuntimeException("Always fails"));
        });

        // Wait for the message to be moved to dead letter queue after retries
        Promise<Void> inDlq = Promise.promise();
        long pollTimer = vertx.setPeriodic(100, id -> {
            manager.getDeadLetterQueueManager().getStatistics()
                .onSuccess(stats -> {
                    if (stats.totalMessages() > 0) {
                        inDlq.tryComplete();
                    }
                });
        });
        inDlq.future().await();
        vertx.cancelTimer(pollTimer);
        assertTrue(attemptCount.get() > 1);
    }

    @Test
    void testNativeQueueMetricsIntegration(Vertx vertx, VertxTestContext testContext) throws Exception {
        String testMessage = "Metrics integration test";

        // Send a message
        producer.send(testMessage).await();

        // Set up consumer
        Checkpoint received = testContext.checkpoint(1);
        logger.debug("TEST: About to subscribe consumer to topic: test-native-topic");
        consumer.subscribe(message -> {
            logger.debug("TEST: Consumer received message: {}", message.getId());
            received.flag();
            return Future.succeededFuture();
        });
        logger.debug("TEST: Consumer subscription completed");

        // Wait for processing
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));

        // Verify metrics were recorded
        var metrics = manager.getMetrics().getSummary();
        assertTrue(metrics.getMessagesSent() > 0);
        assertTrue(metrics.getMessagesReceived() > 0);
        assertTrue(metrics.getMessagesProcessed() > 0);
        assertTrue(metrics.getNativeQueueDepth() >= 0);
    }

    @Test
    void testNativeQueueHealthCheckIntegration(Vertx vertx) throws Exception {
        // Verify system is healthy
        assertTrue(manager.isHealthy());

        // Poll briefly until the native-queue component is present (health checks run asynchronously)
        var hcm = manager.getHealthCheckManager();
        Promise<Void> componentReady = Promise.promise();
        long pollTimer = vertx.setPeriodic(100, id -> {
            if (hcm.getOverallHealth().components().containsKey("native-queue")) {
                componentReady.tryComplete();
            }
        });
        componentReady.future().await();
        vertx.cancelTimer(pollTimer);
        assertTrue(hcm.getOverallHealth().isHealthy());
    }

    @Test
    void testNativeQueueBackpressureIntegration() throws Exception {
        // This test verifies that backpressure is applied to native queue operations
        var backpressureManager = manager.getBackpressureManager();
        
        // Send a message through backpressure manager
        String result = backpressureManager.execute("native-queue-send", () -> {
            producer.send("Backpressure test").await();
            return "success";
        });
        
        assertEquals("success", result);
        
        var metrics = backpressureManager.getMetrics();
        assertTrue(metrics.getSuccessfulOperations() > 0);
    }

    @Test
    void testNativeQueueConcurrentProducers(Vertx vertx, VertxTestContext testContext) throws Exception {
        int producerCount = 3;
        int messagesPerProducer = 5;
        int totalMessages = producerCount * messagesPerProducer;

        Checkpoint allReceived = testContext.checkpoint(totalMessages);
        List<String> receivedMessages = new ArrayList<>();

        // Set up consumer
        consumer.subscribe(message -> {
            synchronized (receivedMessages) {
                receivedMessages.add(message.getPayload());
            }
            allReceived.flag();
            return Future.succeededFuture();
        });

        // Create multiple producers sending concurrently
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            for (int m = 0; m < messagesPerProducer; m++) {
                final int messageId = m;
                String message = "Native-Producer-" + producerId + "-Message-" + messageId;
                producer.send(message).await();
            }
        }

        // Wait for all messages to be received
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        assertEquals(totalMessages, receivedMessages.size());
    }
}


