package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Tests for metrics collection and monitoring in the outbox pattern.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxMetricsTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxMetricsTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        // Use unique topic for each test to avoid interference
        testTopic = "metrics-test-topic-" + UUID.randomUUID().toString().substring(0, 8);
        
        // Set up database connection
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Create and start manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("metrics-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        // Create factory and components using DatabaseService
        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, manager.getObjectMapper());
        producer = outboxFactory.createProducer(testTopic, String.class);
        consumer = outboxFactory.createConsumer(testTopic, String.class);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        if (consumer != null) {
            consumer.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
        }
        
        // Clear system properties
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");

        if (manager != null) {
            manager.closeReactive()
                    .onSuccess(v -> testContext.completeNow())
                    .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    void testMetricsIntegration(Vertx vertx, VertxTestContext testContext) throws Exception {
        String testMessage = "Metrics test message";

        // Get initial metrics
        var initialMetrics = manager.getMetrics().getSummary();
        double initialSent = initialMetrics.getMessagesSent();
        double initialReceived = initialMetrics.getMessagesReceived();
        double initialProcessed = initialMetrics.getMessagesProcessed();

        logger.info("Initial metrics:");
        logger.info("  - Messages sent: {}", initialSent);
        logger.info("  - Messages received: {}", initialReceived);
        logger.info("  - Messages processed: {}", initialProcessed);

        // Set up consumer
        Promise<Void> messageProcessed = Promise.promise();
        consumer.subscribe(message -> {
            logger.info("Processing message for metrics test: {}", message.getPayload());
            messageProcessed.tryComplete();
            return Future.succeededFuture();
        });

        // Send a message, wait for processing, then poll metrics in executeBlocking
        producer.send(testMessage)
            .compose(v -> {
                logger.info("Message sent, waiting for processing...");
                return messageProcessed.future();
            })
            .compose(v -> vertx.executeBlocking(() -> {
                long deadline = System.currentTimeMillis() + 10_000;
                while (System.currentTimeMillis() < deadline) {
                    var m = manager.getMetrics().getSummary();
                    if (m.getMessagesSent() > initialSent
                        && m.getMessagesReceived() > initialReceived
                        && m.getMessagesProcessed() > initialProcessed) {
                        return null;
                    }
                    LockSupport.parkNanos(100_000_000L);
                }
                throw new AssertionError("Metrics were not updated within 10 seconds");
            }))
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                var finalMetrics = manager.getMetrics().getSummary();
                double finalSent = finalMetrics.getMessagesSent();
                double finalReceived = finalMetrics.getMessagesReceived();
                double finalProcessed = finalMetrics.getMessagesProcessed();

                logger.info("Final metrics:");
                logger.info("  - Messages sent: {}", finalSent);
                logger.info("  - Messages received: {}", finalReceived);
                logger.info("  - Messages processed: {}", finalProcessed);

                assertTrue(finalSent > initialSent, 
                    "Messages sent count should increase (was " + initialSent + ", now " + finalSent + ")");
                assertTrue(finalReceived > initialReceived, 
                    "Messages received count should increase (was " + initialReceived + ", now " + finalReceived + ")");
                assertTrue(finalProcessed > initialProcessed, 
                    "Messages processed count should increase (was " + initialProcessed + ", now " + finalProcessed + ")");
                testContext.completeNow();
            })));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testHealthCheckIntegration(Vertx vertx, VertxTestContext testContext) {
        var healthCheckManager = manager.getHealthCheckManager();
        assertNotNull(healthCheckManager, "Health check manager should be available");

        // Health checks may need time to initialise after manager.start().
        // Poll on a worker thread so the event loop is never blocked.
        vertx.executeBlocking(() -> {
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                var status = healthCheckManager.getOverallHealth();
                if (status != null && status.isHealthy()) {
                    return status;
                }
                LockSupport.parkNanos(200_000_000L); // 200 ms
            }
            throw new AssertionError("Health check did not become healthy within 10 seconds");
        })
        .onComplete(testContext.succeeding(healthStatus -> testContext.verify(() -> {
            logger.info("Health check status: {}", healthStatus.status());
            logger.info("Health check components: {}", healthStatus.components());
            assertTrue(healthStatus.isHealthy(),
                "System should be healthy: " + healthStatus.components());
            testContext.completeNow();
        })));

        try {
            assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Health check test interrupted");
        }
    }

    @Test
    void testMultipleMessageMetrics(Vertx vertx, VertxTestContext testContext) throws Exception {
        int messageCount = 5;
        
        // Get initial metrics
        var initialMetrics = manager.getMetrics().getSummary();
        double initialSent = initialMetrics.getMessagesSent();
        double initialReceived = initialMetrics.getMessagesReceived();
        double initialProcessed = initialMetrics.getMessagesProcessed();

        // Set up consumer
        Promise<Void> allProcessed = Promise.promise();
        AtomicInteger processedCount = new AtomicInteger(0);
        consumer.subscribe(message -> {
            logger.info("Processing message: {}", message.getPayload());
            if (processedCount.incrementAndGet() >= messageCount) {
                allProcessed.tryComplete();
            }
            return Future.succeededFuture();
        });

        // Send multiple messages
        Future<Void> sendChain = Future.succeededFuture();
        for (int i = 0; i < messageCount; i++) {
            final int idx = i;
            sendChain = sendChain.compose(v -> producer.send("Metrics test message " + idx));
        }

        // Wait for all messages processed, then poll metrics in executeBlocking
        sendChain
            .compose(v -> allProcessed.future())
            .compose(v -> vertx.executeBlocking(() -> {
                long deadline = System.currentTimeMillis() + 10_000;
                while (System.currentTimeMillis() < deadline) {
                    var m = manager.getMetrics().getSummary();
                    if (m.getMessagesSent() >= initialSent + messageCount
                        && m.getMessagesReceived() >= initialReceived + messageCount
                        && m.getMessagesProcessed() >= initialProcessed + messageCount) {
                        return null;
                    }
                    LockSupport.parkNanos(100_000_000L);
                }
                throw new AssertionError("Metrics were not updated within 10 seconds");
            }))
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                var finalMetrics = manager.getMetrics().getSummary();
                double finalSent = finalMetrics.getMessagesSent();
                double finalReceived = finalMetrics.getMessagesReceived();
                double finalProcessed = finalMetrics.getMessagesProcessed();

                logger.info("Multiple message metrics:");
                logger.info("  - Initial sent: {}, Final sent: {}", initialSent, finalSent);
                logger.info("  - Initial received: {}, Final received: {}", initialReceived, finalReceived);
                logger.info("  - Initial processed: {}, Final processed: {}", initialProcessed, finalProcessed);

                assertTrue(finalSent >= initialSent + messageCount, 
                    "Messages sent should increase by at least " + messageCount);
                assertTrue(finalReceived >= initialReceived + messageCount, 
                    "Messages received should increase by at least " + messageCount);
                assertTrue(finalProcessed >= initialProcessed + messageCount, 
                    "Messages processed should increase by at least " + messageCount);
                testContext.completeNow();
            })));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testErrorMetrics(Vertx vertx, VertxTestContext testContext) throws Exception {
        String testMessage = "Message that will cause error";
        
        // Get initial metrics
        var initialMetrics = manager.getMetrics().getSummary();
        double initialErrors = initialMetrics.getMessagesFailed();

        logger.info("Initial error count: {}", initialErrors);

        // Set up consumer that always fails
        Promise<Void> errorOccurred = Promise.promise();
        consumer.subscribe(message -> {
            logger.info("INTENTIONAL FAILURE: Processing message that will fail");
            errorOccurred.tryComplete();
            throw new RuntimeException("Intentional error for metrics testing");
        });

        // Send message, wait for error, then poll metrics in executeBlocking
        producer.send(testMessage)
            .compose(v -> errorOccurred.future())
            .compose(v -> vertx.executeBlocking(() -> {
                long deadline = System.currentTimeMillis() + 10_000;
                while (System.currentTimeMillis() < deadline) {
                    if (manager.getMetrics().getSummary().getMessagesFailed() > initialErrors) {
                        return null;
                    }
                    LockSupport.parkNanos(100_000_000L);
                }
                throw new AssertionError("Error metrics were not updated within 10 seconds");
            }))
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                var finalMetrics = manager.getMetrics().getSummary();
                double finalErrors = finalMetrics.getMessagesFailed();

                logger.info("Final error count: {}", finalErrors);

                assertTrue(finalErrors > initialErrors, 
                    "Error count should increase (was " + initialErrors + ", now " + finalErrors + ")");
                testContext.completeNow();
            })));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }
}


