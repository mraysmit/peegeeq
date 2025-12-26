package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

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
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Tests for metrics collection and monitoring in the outbox pattern.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
public class OutboxMetricsTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

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
        manager.start();

        // Create factory and components using DatabaseService
        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, manager.getObjectMapper());
        producer = outboxFactory.createProducer(testTopic, String.class);
        consumer = outboxFactory.createConsumer(testTopic, String.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
        }
        if (manager != null) {
            manager.close();
        }
        
        // Clear system properties
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
    }

    @Test
    void testMetricsIntegration() throws Exception {
        String testMessage = "Metrics test message";

        // Get initial metrics
        var initialMetrics = manager.getMetrics().getSummary();
        double initialSent = initialMetrics.getMessagesSent();
        double initialReceived = initialMetrics.getMessagesReceived();
        double initialProcessed = initialMetrics.getMessagesProcessed();

        System.out.println("Initial metrics:");
        System.out.println("  - Messages sent: " + initialSent);
        System.out.println("  - Messages received: " + initialReceived);
        System.out.println("  - Messages processed: " + initialProcessed);

        // Set up consumer
        CountDownLatch latch = new CountDownLatch(1);
        consumer.subscribe(message -> {
            System.out.println("Processing message for metrics test: " + message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send a message
        producer.send(testMessage).get(5, TimeUnit.SECONDS);
        System.out.println("Message sent, waiting for processing...");

        // Wait for processing
        assertTrue(latch.await(10, TimeUnit.SECONDS), 
            "Message should be processed within timeout");

        // Allow some time for metrics to be updated
        Thread.sleep(2000);

        // Verify metrics were recorded
        var finalMetrics = manager.getMetrics().getSummary();
        double finalSent = finalMetrics.getMessagesSent();
        double finalReceived = finalMetrics.getMessagesReceived();
        double finalProcessed = finalMetrics.getMessagesProcessed();

        System.out.println("Final metrics:");
        System.out.println("  - Messages sent: " + finalSent);
        System.out.println("  - Messages received: " + finalReceived);
        System.out.println("  - Messages processed: " + finalProcessed);

        // Verify metrics increased
        assertTrue(finalSent > initialSent, 
            "Messages sent count should increase (was " + initialSent + ", now " + finalSent + ")");
        assertTrue(finalReceived > initialReceived, 
            "Messages received count should increase (was " + initialReceived + ", now " + finalReceived + ")");
        assertTrue(finalProcessed > initialProcessed, 
            "Messages processed count should increase (was " + initialProcessed + ", now " + finalProcessed + ")");
    }

    @Test
    void testHealthCheckIntegration() throws Exception {
        // Verify health check manager exists
        var healthCheckManager = manager.getHealthCheckManager();
        assertNotNull(healthCheckManager, "Health check manager should be available");

        // Get health status
        var healthStatus = healthCheckManager.getOverallHealth();
        assertNotNull(healthStatus, "Health status should be available");

        System.out.println("Health check status: " + healthStatus.status());
        System.out.println("Health check components: " + healthStatus.components());

        // Health should be UP for a properly functioning system
        assertTrue(healthStatus.isHealthy(),
            "System should be healthy: " + healthStatus.components());
    }

    @Test
    void testCircuitBreakerIntegration() throws Exception {
        // Verify circuit breaker manager exists
        var cbManager = manager.getCircuitBreakerManager();
        assertNotNull(cbManager, "Circuit breaker manager should be available");

        // Initially, no circuit breakers should exist (lazy initialization)
        var initialCircuitBreakerNames = cbManager.getCircuitBreakerNames();
        assertNotNull(initialCircuitBreakerNames, "Circuit breaker names should be available");
        assertTrue(initialCircuitBreakerNames.isEmpty(),
            "Circuit breakers should be empty initially (lazy initialization)");

        // Test that we can manually create circuit breakers using the manager
        // This demonstrates that the circuit breaker functionality is working
        String testResult = cbManager.executeDatabaseOperation("test-select", () -> "test-query-result");
        assertEquals("test-query-result", testResult, "Circuit breaker should execute operation successfully");

        // Now we should have a circuit breaker for the database operation
        var circuitBreakerNames = cbManager.getCircuitBreakerNames();
        System.out.println("Available circuit breakers after manual operation: " + circuitBreakerNames);

        // Should have the circuit breaker we just created
        assertFalse(circuitBreakerNames.isEmpty(),
            "Should have circuit breakers after manual database operation");
        assertTrue(circuitBreakerNames.contains("database-test-select"),
            "Should contain the database-test-select circuit breaker");

        // Test circuit breaker metrics
        var metrics = cbManager.getMetrics("database-test-select");
        assertNotNull(metrics, "Circuit breaker metrics should be available");
        assertEquals("CLOSED", metrics.getState(), "Circuit breaker should be in CLOSED state");
        assertEquals(1, metrics.getSuccessfulCalls(), "Should have 1 successful call");
        assertEquals(0, metrics.getFailedCalls(), "Should have 0 failed calls");
    }

    @Test
    void testMultipleMessageMetrics() throws Exception {
        int messageCount = 5;
        
        // Get initial metrics
        var initialMetrics = manager.getMetrics().getSummary();
        double initialSent = initialMetrics.getMessagesSent();
        double initialReceived = initialMetrics.getMessagesReceived();
        double initialProcessed = initialMetrics.getMessagesProcessed();

        // Set up consumer
        CountDownLatch latch = new CountDownLatch(messageCount);
        consumer.subscribe(message -> {
            System.out.println("Processing message: " + message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send multiple messages
        for (int i = 0; i < messageCount; i++) {
            producer.send("Metrics test message " + i).get(5, TimeUnit.SECONDS);
        }

        // Wait for all messages to be processed
        assertTrue(latch.await(15, TimeUnit.SECONDS), 
            "All messages should be processed within timeout");

        // Allow time for metrics to be updated
        Thread.sleep(2000);

        // Verify metrics
        var finalMetrics = manager.getMetrics().getSummary();
        double finalSent = finalMetrics.getMessagesSent();
        double finalReceived = finalMetrics.getMessagesReceived();
        double finalProcessed = finalMetrics.getMessagesProcessed();

        System.out.println("Multiple message metrics:");
        System.out.println("  - Initial sent: " + initialSent + ", Final sent: " + finalSent);
        System.out.println("  - Initial received: " + initialReceived + ", Final received: " + finalReceived);
        System.out.println("  - Initial processed: " + initialProcessed + ", Final processed: " + finalProcessed);

        // Verify metrics increased by at least the number of messages sent
        assertTrue(finalSent >= initialSent + messageCount, 
            "Messages sent should increase by at least " + messageCount);
        assertTrue(finalReceived >= initialReceived + messageCount, 
            "Messages received should increase by at least " + messageCount);
        assertTrue(finalProcessed >= initialProcessed + messageCount, 
            "Messages processed should increase by at least " + messageCount);
    }

    @Test
    void testErrorMetrics() throws Exception {
        String testMessage = "Message that will cause error";
        
        // Get initial metrics
        var initialMetrics = manager.getMetrics().getSummary();
        double initialErrors = initialMetrics.getMessagesFailed();

        System.out.println("Initial error count: " + initialErrors);

        // Set up consumer that always fails
        CountDownLatch errorLatch = new CountDownLatch(1);
        consumer.subscribe(message -> {
            System.out.println("INTENTIONAL FAILURE: Processing message that will fail");
            errorLatch.countDown();
            throw new RuntimeException("Intentional error for metrics testing");
        });

        // Send message
        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Wait for error to occur
        assertTrue(errorLatch.await(10, TimeUnit.SECONDS), 
            "Error should occur within timeout");

        // Allow time for error metrics to be updated
        Thread.sleep(3000);

        // Verify error metrics increased
        var finalMetrics = manager.getMetrics().getSummary();
        double finalErrors = finalMetrics.getMessagesFailed();

        System.out.println("Final error count: " + finalErrors);

        assertTrue(finalErrors > initialErrors, 
            "Error count should increase (was " + initialErrors + ", now " + finalErrors + ")");
    }
}
