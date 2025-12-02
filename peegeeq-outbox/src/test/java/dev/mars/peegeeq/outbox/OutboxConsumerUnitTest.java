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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.categories.TestCategories;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OutboxConsumer focusing on lifecycle methods and state management.
 * These tests don't require database infrastructure and focus on code coverage of simpler methods.
 */
@Tag(TestCategories.CORE)
class OutboxConsumerUnitTest {

    private OutboxConsumer<String> consumer;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Vertx vertx = Vertx.vertx();

    @AfterEach
    void tearDown() throws Exception {
        if (consumer != null) {
            try {
                consumer.close();
            } catch (Exception e) {
                // Ignore exceptions during cleanup
            }
        }
        if (vertx != null) {
            vertx.close();
        }
    }

    @Test
    void testConstructorWithClientFactory_NoConfiguration() {
        // Given
        PgClientFactory clientFactory = new PgClientFactory(vertx);

        // When
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);

        // Then
        assertNotNull(consumer);
    }

    @Test
    void testConstructorWithClientFactory_WithConfiguration() {
        // Given
        PgClientFactory clientFactory = new PgClientFactory(vertx);
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");

        // When
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null, config);

        // Then
        assertNotNull(consumer);
    }

    @Test
    void testSetConsumerGroupName() {
        // Given
        PgClientFactory clientFactory = new PgClientFactory(vertx);
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);

        // When
        consumer.setConsumerGroupName("test-group");

        // Then - no exception thrown
    }

    @Test
    void testSetConsumerGroupName_NullValue() {
        // Given
        PgClientFactory clientFactory = new PgClientFactory(vertx);
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);

        // When/Then - should not throw exception with null
        assertDoesNotThrow(() -> consumer.setConsumerGroupName(null));
    }

    @Test
    void testSetConsumerGroupName_EmptyString() {
        // Given
        PgClientFactory clientFactory = new PgClientFactory(vertx);
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);

        // When/Then - should not throw exception with empty string
        assertDoesNotThrow(() -> consumer.setConsumerGroupName(""));
    }

    @Test
    void testUnsubscribe_WhenNotSubscribed() {
        // Given
        PgClientFactory clientFactory = new PgClientFactory(vertx);
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);

        // When - unsubscribe without ever subscribing
        consumer.unsubscribe();

        // Then - should complete without error
    }

    @Test
    void testClose_WhenNotSubscribed() throws Exception {
        // Given
        PgClientFactory clientFactory = new PgClientFactory(vertx);
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);

        // When
        consumer.close();

        // Then - should close successfully
        consumer = null; // Prevent double-close in tearDown
    }

    @Test
    void testClose_MultipleInvocations() throws Exception {
        // Given
        PgClientFactory clientFactory = new PgClientFactory(vertx);
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
        PgClientFactory clientFactory = new PgClientFactory(vertx);
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
        // Given
        PgClientFactory clientFactory = new PgClientFactory(vertx);

        // When - no configuration provided, should use defaults
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);

        // Then
        assertNotNull(consumer);
    }

    @Test
    void testConstructor_ClientFactoryPath() {
        // Given
        PgClientFactory clientFactory = new PgClientFactory(vertx);

        // When
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null, null);

        // Then
        assertNotNull(consumer);
    }



    @Test
    void testSubscribe_Success() {
        // Given
        PgClientFactory clientFactory = new PgClientFactory(vertx);
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
        PgClientFactory clientFactory = new PgClientFactory(vertx);
        consumer = new OutboxConsumer<>(clientFactory, objectMapper, "test-topic", String.class, null);

        // When/Then - should throw exception with null handler
        assertThrows(Exception.class, () -> consumer.subscribe(null));
    }

    @Test
    void testUnsubscribe_AfterSubscribe() {
        // Given
        PgClientFactory clientFactory = new PgClientFactory(vertx);
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
        PgClientFactory clientFactory = new PgClientFactory(vertx);
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
        PgClientFactory clientFactory = new PgClientFactory(vertx);
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
        PgClientFactory clientFactory = new PgClientFactory(vertx);
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
        PgClientFactory clientFactory = new PgClientFactory(vertx);
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
