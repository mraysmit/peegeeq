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
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for OutboxConsumer focusing on lifecycle methods and state management.
 * Uses TestContainers for real database connectivity following standardized patterns.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class OutboxConsumerIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerIntegrationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName(PostgreSQLTestConstants.DEFAULT_DATABASE_NAME)
            .withUsername(PostgreSQLTestConstants.DEFAULT_USERNAME)
            .withPassword(PostgreSQLTestConstants.DEFAULT_PASSWORD)
            .withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE)
            .withReuse(false);

    private OutboxConsumer<String> consumer;
    private ObjectMapper objectMapper;
    private PgClientFactory clientFactory;
    private PeeGeeQManager manager;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize schema using centralized schema initializer - use QUEUE_ALL for PeeGeeQManager health checks
        logger.info("Initializing database schema for OutboxConsumer integration tests");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer");

        // Configure system properties for TestContainer
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.database.schema", "public");

        // Initialize PeeGeeQ manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Get client factory from manager
        clientFactory = manager.getClientFactory();
        objectMapper = new ObjectMapper();

        logger.info("OutboxConsumer integration test setup completed");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (consumer != null) {
            try {
                consumer.close();
            } catch (Exception e) {
                logger.warn("Error closing consumer: {}", e.getMessage());
            }
        }
        if (manager != null) {
            try {
                manager.stop();
            } catch (Exception e) {
                logger.warn("Error stopping manager: {}", e.getMessage());
            }
        }
        logger.info("OutboxConsumer integration test teardown completed");
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
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");

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
        assertThrows(IllegalStateException.class, () -> consumer.subscribe(handler));

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

