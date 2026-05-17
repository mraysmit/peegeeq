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

import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for OutboxConsumer focusing on lifecycle methods and state management.
 * Uses TestContainers for real database connectivity following standardized patterns.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class OutboxConsumerIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerIntegrationTest.class);

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private OutboxConsumer<String> consumer;
    private ObjectMapper objectMapper;
    private PgClientFactory clientFactory;
    private PeeGeeQManager manager;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Initialize schema using centralized schema initializer - use QUEUE_ALL for PeeGeeQManager health checks
        logger.info("Initializing database schema for OutboxConsumer integration tests");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer");

        // Configure system properties for TestContainer
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .property("peegeeq.queue.polling-interval", "PT0.5S")
                .build();

        // Initialize PeeGeeQ manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().onSuccess(v -> {
            // Get client factory from manager
            clientFactory = manager.getClientFactory();
            objectMapper = new ObjectMapper();
            logger.info("OutboxConsumer integration test setup completed");
            testContext.completeNow();
        }).onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        if (consumer != null) {
            try {
                consumer.close();
            } catch (Exception e) {
                logger.warn("Error closing consumer: {}", e.getMessage());
            }
        }
        if (manager != null) {
            manager.closeReactive()
                .onSuccess(v -> {
                    logger.info("OutboxConsumer integration test teardown completed");
                    testContext.completeNow();
                })
                .onFailure(e -> {
                    logger.warn("Error stopping manager: {}", e.getMessage());
                    testContext.completeNow();
                });
        } else {
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    void testConstructorWithClientFactory_NoConfiguration() {
        // When
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);

        // Then
        assertNotNull(consumer);
    }

    @Test
    void testConstructorWithClientFactory_WithConfiguration() {
        // Given
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test", new Properties());

        // When
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null, config);

        // Then
        assertNotNull(consumer);
    }

    @Test
    void testSetConsumerGroupName() {
        // Given
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);

        // When
        consumer.setConsumerGroupName("test-group");

        // Then - no exception thrown
    }

    @Test
    void testSetConsumerGroupName_NullValue() {
        // Given
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);

        // When/Then - should not throw exception with null
        assertDoesNotThrow(() -> consumer.setConsumerGroupName(null));
    }

    @Test
    void testSetConsumerGroupName_EmptyString() {
        // Given
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);

        // When/Then - should not throw exception with empty string
        assertDoesNotThrow(() -> consumer.setConsumerGroupName(""));
    }

    @Test
    void testUnsubscribe_WhenNotSubscribed() {
        // Given
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);

        // When - unsubscribe without ever subscribing
        consumer.unsubscribe();

        // Then - should complete without error
    }

    @Test
    void testClose_WhenNotSubscribed() throws Exception {
        // Given
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);

        // When
        consumer.close();

        // Then - should close successfully
        consumer = null; // Prevent double-close in tearDown
    }

    @Test
    void testClose_MultipleInvocations() throws Exception {
        // Given
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);

        // When - close multiple times
        consumer.close();
        consumer.close();

        // Then - should handle multiple closes gracefully
        consumer = null;
    }

    @Test
    void testSubscribe_ThrowsWhenClosed() throws Exception {
        // Given
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);
        MessageHandler<String> handler = message -> {
            // no-op handler
            return null;
        };
        consumer.close();

        // When/Then
        Future<Void> result = consumer.subscribe(handler);
        assertTrue(result.failed(), "subscribe on closed consumer should return a failed future");
        assertInstanceOf(IllegalStateException.class, result.cause(),
                "subscribe on closed consumer should fail with IllegalStateException");

        consumer = null;
    }

    @Test
    void testConstructor_WithDefaultPollingInterval() {
        // When - no configuration provided, should use defaults
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);

        // Then
        assertNotNull(consumer);
    }

    @Test
    void testConstructor_ClientFactoryPath() {
        // When
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null, null);

        // Then
        assertNotNull(consumer);
    }

    @Test
    void testSubscribe_Success() {
        // Given
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);
        MessageHandler<String> handler = message -> {
            return null;
        };

        // When/Then - should not throw
        assertDoesNotThrow(() -> consumer.subscribe(handler));
    }

    @Test
    void testSubscribe_WithNullHandler() {
        // Given
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);

        // When/Then - should throw exception with null handler
        assertThrows(Exception.class, () -> consumer.subscribe(null));
    }

    @Test
    void testUnsubscribe_AfterSubscribe() {
        // Given
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);
        MessageHandler<String> handler = message -> {
            return null;
        };
        consumer.subscribe(handler);

        // When
        consumer.unsubscribe();

        // Then - should complete without error
    }

    @Test
    void testClose_AfterSubscribe() throws Exception {
        // Given
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);
        MessageHandler<String> handler = message -> {
            return null;
        };
        consumer.subscribe(handler);

        // When
        consumer.close();

        // Then - should close successfully
        consumer = null;
    }

    @Test
    void testMultipleSubscribe_Attempts() {
        // Given
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);
        MessageHandler<String> handler1 = message -> {
            return null;
        };
        MessageHandler<String> handler2 = message -> {
            return null;
        };

        // When - subscribe twice
        consumer.subscribe(handler1);
        consumer.subscribe(handler2);

        // Then - should complete without exception (replaces handler)
    }

    @Test
    void testUnsubscribe_MultipleInvocations() {
        // Given
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);
        MessageHandler<String> handler = message -> {
            return null;
        };
        consumer.subscribe(handler);

        // When - unsubscribe multiple times
        consumer.unsubscribe();
        consumer.unsubscribe();

        // Then - should handle multiple unsubscribes gracefully (idempotent)
    }

    @Test
    void testSubscribe_AfterUnsubscribe() {
        // Given
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);
        MessageHandler<String> handler1 = message -> {
            return null;
        };
        MessageHandler<String> handler2 = message -> {
            return null;
        };

        consumer.subscribe(handler1);
        consumer.unsubscribe();

        // When - subscribe again after unsubscribe
        // Then - should allow re-subscription
        assertDoesNotThrow(() -> consumer.subscribe(handler2));
    }
}



