package dev.mars.peegeeq.api.messaging;

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

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Core tests for Message interface and implementations.
 * Fast unit tests that validate message creation and basic functionality.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-26
 * @version 1.0
 */
@Tag(TestCategories.CORE)
public class MessageTest {

    @Test
    void testSimpleMessageCreation() {
        // Test creation of a simple message implementation
        String messageId = "msg-001";
        String payload = "Hello, World!";
        Instant createdAt = Instant.now();
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "text/plain");
        headers.put("source", "test");

        SimpleMessage<String> message = new SimpleMessage<>(messageId, payload, createdAt, headers);

        assertEquals(messageId, message.getId(), "Message ID should match");
        assertEquals(payload, message.getPayload(), "Message payload should match");
        assertEquals(createdAt, message.getCreatedAt(), "Message creation time should match");
        assertEquals(headers, message.getHeaders(), "Message headers should match");
    }

    @Test
    void testMessageWithNullValues() {
        // Test message creation with null values
        assertThrows(IllegalArgumentException.class, () -> {
            new SimpleMessage<>(null, "payload", Instant.now(), new HashMap<>());
        }, "Message creation should fail with null ID");

        assertThrows(IllegalArgumentException.class, () -> {
            new SimpleMessage<>("msg-002", "payload", null, new HashMap<>());
        }, "Message creation should fail with null creation time");
    }

    @Test
    void testMessageWithEmptyHeaders() {
        // Test message creation with empty headers
        String messageId = "msg-003";
        String payload = "Test payload";
        Instant createdAt = Instant.now();

        SimpleMessage<String> message = new SimpleMessage<>(messageId, payload, createdAt, null);

        assertEquals(messageId, message.getId(), "Message ID should match");
        assertEquals(payload, message.getPayload(), "Message payload should match");
        assertNotNull(message.getHeaders(), "Headers should not be null");
        assertTrue(message.getHeaders().isEmpty(), "Headers should be empty when null provided");
    }

    @Test
    void testMessageEquality() {
        // Test message equality
        String messageId = "msg-004";
        String payload = "Equality test";
        Instant createdAt = Instant.now();
        Map<String, String> headers = new HashMap<>();
        headers.put("test", "value");

        SimpleMessage<String> message1 = new SimpleMessage<>(messageId, payload, createdAt, headers);
        SimpleMessage<String> message2 = new SimpleMessage<>(messageId, payload, createdAt, headers);
        SimpleMessage<String> message3 = new SimpleMessage<>("msg-005", payload, createdAt, headers);

        assertEquals(message1, message2, "Messages with same data should be equal");
        assertEquals(message1.hashCode(), message2.hashCode(), "Messages with same data should have same hash code");
        assertNotEquals(message1, message3, "Messages with different IDs should not be equal");
    }

    @Test
    void testMessageToString() {
        // Test message string representation
        String messageId = "msg-006";
        String payload = "ToString test";
        Instant createdAt = Instant.now();
        Map<String, String> headers = new HashMap<>();
        headers.put("format", "test");

        SimpleMessage<String> message = new SimpleMessage<>(messageId, payload, createdAt, headers);
        String messageString = message.toString();

        assertNotNull(messageString, "toString should not return null");
        assertTrue(messageString.contains(messageId), "toString should contain message ID");
        assertTrue(messageString.contains(payload), "toString should contain payload");
    }

    @Test
    void testMessageHeaderOperations() {
        // Test message header operations
        String messageId = "msg-007";
        String payload = "Header test";
        Instant createdAt = Instant.now();
        Map<String, String> headers = new HashMap<>();
        headers.put("initial", "value");

        SimpleMessage<String> message = new SimpleMessage<>(messageId, payload, createdAt, headers);

        assertTrue(message.hasHeader("initial"), "Message should have initial header");
        assertFalse(message.hasHeader("missing"), "Message should not have missing header");
        assertEquals("value", message.getHeader("initial"), "Header value should match");
        assertNull(message.getHeader("missing"), "Missing header should return null");
    }

    @Test
    void testMessageWithComplexPayload() {
        // Test message with complex payload type
        String messageId = "msg-008";
        Map<String, Object> complexPayload = new HashMap<>();
        complexPayload.put("userId", 12345);
        complexPayload.put("action", "login");
        complexPayload.put("timestamp", Instant.now());

        Instant createdAt = Instant.now();
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");

        SimpleMessage<Map<String, Object>> message = new SimpleMessage<>(messageId, complexPayload, createdAt, headers);

        assertEquals(messageId, message.getId(), "Message ID should match");
        assertEquals(complexPayload, message.getPayload(), "Complex payload should match");
        assertEquals("application/json", message.getHeader("content-type"), "Content type header should match");
    }

    @Test
    void testMessageImmutability() {
        // Test that message headers are immutable
        String messageId = "msg-009";
        String payload = "Immutability test";
        Instant createdAt = Instant.now();
        Map<String, String> originalHeaders = new HashMap<>();
        originalHeaders.put("original", "value");

        SimpleMessage<String> message = new SimpleMessage<>(messageId, payload, createdAt, originalHeaders);

        // Modify original headers map
        originalHeaders.put("modified", "new-value");

        // Message headers should not be affected
        assertFalse(message.hasHeader("modified"), "Message headers should not be affected by external modifications");
        assertEquals(1, message.getHeaders().size(), "Message should have only original header");
    }

    /**
     * Simple implementation of Message interface for testing purposes.
     */
    private static class SimpleMessage<T> implements Message<T> {
        private final String id;
        private final T payload;
        private final Instant createdAt;
        private final Map<String, String> headers;

        public SimpleMessage(String id, T payload, Instant createdAt, Map<String, String> headers) {
            if (id == null) {
                throw new IllegalArgumentException("Message ID cannot be null");
            }
            if (createdAt == null) {
                throw new IllegalArgumentException("Message creation time cannot be null");
            }

            this.id = id;
            this.payload = payload;
            this.createdAt = createdAt;
            this.headers = headers == null ? new HashMap<>() : new HashMap<>(headers);
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public T getPayload() {
            return payload;
        }

        @Override
        public Instant getCreatedAt() {
            return createdAt;
        }

        @Override
        public Map<String, String> getHeaders() {
            return new HashMap<>(headers);
        }

        public boolean hasHeader(String key) {
            return headers.containsKey(key);
        }

        public String getHeader(String key) {
            return headers.get(key);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            SimpleMessage<?> that = (SimpleMessage<?>) obj;
            return id.equals(that.id) &&
                   payload.equals(that.payload) &&
                   createdAt.equals(that.createdAt) &&
                   headers.equals(that.headers);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return "SimpleMessage{id='" + id + "', payload=" + payload + ", createdAt=" + createdAt + "}";
        }
    }
}
