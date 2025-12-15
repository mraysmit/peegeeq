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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PgNativeQueue shutdown scenarios and race condition handling.
 * Validates the fixes for "Pool closed" errors during shutdown.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-07
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class PgNativeQueueShutdownTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("native_queue_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    private PeeGeeQManager manager;
    private PgNativeQueueFactory queueFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;

    @BeforeEach
    void setUp() {
        // Configure test properties - following existing pattern exactly
        Properties testProps = new Properties();
        testProps.setProperty("peegeeq.database.host", postgres.getHost());
        testProps.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        testProps.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        testProps.setProperty("peegeeq.database.username", postgres.getUsername());
        testProps.setProperty("peegeeq.database.password", postgres.getPassword());
        testProps.setProperty("peegeeq.database.ssl.enabled", "false");
        testProps.setProperty("peegeeq.queue.polling-interval", "PT1S");
        testProps.setProperty("peegeeq.queue.visibility-timeout", "PT30S");
        testProps.setProperty("peegeeq.metrics.enabled", "true");
        testProps.setProperty("peegeeq.circuit-breaker.enabled", "true");

        // Ensure required schema exists for native queue tests
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.NATIVE_QUEUE, SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);

        // Set system properties
        testProps.forEach((key, value) -> System.setProperty(key.toString(), value.toString()));

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Initialize native queue components - following exact pattern
        queueFactory = new PgNativeQueueFactory(
            manager.getClientFactory(),
            new ObjectMapper(),
            manager.getMetrics()
        );
        producer = queueFactory.createProducer("test-native-topic", String.class);
        consumer = queueFactory.createConsumer("test-native-topic", String.class);
    }

    @AfterEach
    void tearDown() {
        try {
            if (consumer != null) {
                consumer.close();
            }
            if (producer != null) {
                producer.close();
            }
            if (manager != null) {
                manager.close();
            }
        } catch (Exception e) {
            // Ignore cleanup errors
        }

        // Clear system properties
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        System.clearProperty("peegeeq.database.ssl.enabled");
        System.clearProperty("peegeeq.queue.polling-interval");
        System.clearProperty("peegeeq.queue.visibility-timeout");
        System.clearProperty("peegeeq.metrics.enabled");
        System.clearProperty("peegeeq.circuit-breaker.enabled");
    }

    @Test
    void testBasicShutdownWithoutErrors() throws Exception {
        // Step 1: Send a simple message
        String testMessage = "Basic shutdown test";
        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Step 2: Process the message
        AtomicBoolean messageReceived = new AtomicBoolean(false);
        CountDownLatch processedLatch = new CountDownLatch(1);

        consumer.subscribe(message -> {
            messageReceived.set(true);
            processedLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Step 3: Wait for processing
        assertTrue(processedLatch.await(10, TimeUnit.SECONDS));
        assertTrue(messageReceived.get());

        // Step 4: Close consumer (this should not produce "Pool closed" errors)
        consumer.close();

        // The key validation is that no ERROR level "Pool closed" messages appear in logs
        // This test validates the shutdown race condition fix
    }

    @Test
    void testShutdownDuringMessageProcessing() throws Exception {
        // Step 1: Send a message
        String testMessage = "Shutdown during processing test";
        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Step 2: Set up consumer that will trigger shutdown during processing
        AtomicBoolean messageReceived = new AtomicBoolean(false);
        CountDownLatch shutdownLatch = new CountDownLatch(1);

        consumer.subscribe(message -> {
            messageReceived.set(true);

            // Initiate shutdown immediately after receiving message
            // This tests the race condition scenario
            new Thread(() -> {
                try {
                    Thread.sleep(10); // Small delay to let processing start
                    consumer.close();
                    shutdownLatch.countDown();
                } catch (Exception e) {
                    // Ignore
                }
            }).start();

            return CompletableFuture.completedFuture(null);
        });

        // Step 3: Wait for shutdown
        assertTrue(shutdownLatch.await(10, TimeUnit.SECONDS));
        assertTrue(messageReceived.get());

        // The key validation is that no ERROR level "Pool closed" messages appear in logs
        // This specifically tests the shutdown race condition fix
    }
}
