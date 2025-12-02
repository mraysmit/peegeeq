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

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage tests for OutboxMessage.
 * Tests all constructors, getters, and utility methods to achieve full coverage.
 */
@Tag(TestCategories.CORE)
public class OutboxMessageCoverageTest {

    @Test
    @DisplayName("should create message with full constructor including correlation ID")
    void testFullConstructorWithCorrelationId() {
        String id = "msg-123";
        String payload = "test payload";
        Instant createdAt = Instant.now();
        Map<String, String> headers = new HashMap<>();
        headers.put("key1", "value1");
        headers.put("key2", "value2");
        String correlationId = "corr-456";
        
        OutboxMessage<String> message = new OutboxMessage<>(id, payload, createdAt, headers, correlationId);
        
        assertEquals(id, message.getId());
        assertEquals(payload, message.getPayload());
        assertEquals(createdAt, message.getCreatedAt());
        assertEquals(correlationId, message.getCorrelationId());
        assertEquals(2, message.getHeaders().size());
        assertEquals("value1", message.getHeaders().get("key1"));
        assertEquals("value2", message.getHeaders().get("key2"));
    }

    @Test
    @DisplayName("should create message with constructor without correlation ID")
    void testConstructorWithoutCorrelationId() {
        String id = "msg-123";
        String payload = "test payload";
        Instant createdAt = Instant.now();
        Map<String, String> headers = new HashMap<>();
        headers.put("header1", "value1");
        
        OutboxMessage<String> message = new OutboxMessage<>(id, payload, createdAt, headers);
        
        assertEquals(id, message.getId());
        assertEquals(payload, message.getPayload());
        assertEquals(createdAt, message.getCreatedAt());
        assertNull(message.getCorrelationId());
        assertEquals(1, message.getHeaders().size());
        assertEquals("value1", message.getHeaders().get("header1"));
    }

    @Test
    @DisplayName("should create message with simple constructor using current timestamp")
    void testSimpleConstructor() {
        String id = "msg-123";
        String payload = "test payload";
        
        Instant before = Instant.now();
        OutboxMessage<String> message = new OutboxMessage<>(id, payload);
        Instant after = Instant.now();
        
        assertEquals(id, message.getId());
        assertEquals(payload, message.getPayload());
        assertNotNull(message.getCreatedAt());
        assertTrue(message.getCreatedAt().isAfter(before) || message.getCreatedAt().equals(before));
        assertTrue(message.getCreatedAt().isBefore(after) || message.getCreatedAt().equals(after));
        assertNull(message.getCorrelationId());
        assertTrue(message.getHeaders().isEmpty());
    }

    @Test
    @DisplayName("should handle null headers by creating empty map")
    void testNullHeaders() {
        String id = "msg-123";
        String payload = "test payload";
        Instant createdAt = Instant.now();
        
        OutboxMessage<String> message = new OutboxMessage<>(id, payload, createdAt, null);
        
        assertNotNull(message.getHeaders());
        assertTrue(message.getHeaders().isEmpty());
    }

    @Test
    @DisplayName("should handle null correlation ID")
    void testNullCorrelationId() {
        String id = "msg-123";
        String payload = "test payload";
        Instant createdAt = Instant.now();
        Map<String, String> headers = new HashMap<>();
        
        OutboxMessage<String> message = new OutboxMessage<>(id, payload, createdAt, headers, null);
        
        assertNull(message.getCorrelationId());
    }

    @Test
    @DisplayName("should throw NullPointerException when ID is null")
    void testNullId() {
        String payload = "test payload";
        Instant createdAt = Instant.now();
        
        assertThrows(NullPointerException.class, () -> {
            new OutboxMessage<>(null, payload, createdAt, null);
        });
    }

    @Test
    @DisplayName("should throw NullPointerException when createdAt is null")
    void testNullCreatedAt() {
        String id = "msg-123";
        String payload = "test payload";
        
        assertThrows(NullPointerException.class, () -> {
            new OutboxMessage<>(id, payload, null, null);
        });
    }

    @Test
    @DisplayName("should allow null payload")
    void testNullPayload() {
        String id = "msg-123";
        Instant createdAt = Instant.now();
        
        OutboxMessage<String> message = new OutboxMessage<>(id, null, createdAt, null);
        
        assertEquals(id, message.getId());
        assertNull(message.getPayload());
    }

    @Test
    @DisplayName("should return unmodifiable headers map")
    void testUnmodifiableHeaders() {
        String id = "msg-123";
        String payload = "test payload";
        Instant createdAt = Instant.now();
        Map<String, String> headers = new HashMap<>();
        headers.put("key1", "value1");
        
        OutboxMessage<String> message = new OutboxMessage<>(id, payload, createdAt, headers);
        Map<String, String> returnedHeaders = message.getHeaders();
        
        assertThrows(UnsupportedOperationException.class, () -> {
            returnedHeaders.put("key2", "value2");
        });
    }

    @Test
    @DisplayName("should create defensive copy of headers")
    void testHeadersDefensiveCopy() {
        String id = "msg-123";
        String payload = "test payload";
        Instant createdAt = Instant.now();
        Map<String, String> headers = new HashMap<>();
        headers.put("key1", "value1");
        
        OutboxMessage<String> message = new OutboxMessage<>(id, payload, createdAt, headers);
        
        // Modify original headers
        headers.put("key2", "value2");
        
        // Message headers should not be affected
        assertEquals(1, message.getHeaders().size());
        assertNull(message.getHeaders().get("key2"));
    }

    @Test
    @DisplayName("should generate toString with all fields")
    void testToStringWithAllFields() {
        String id = "msg-123";
        String payload = "test payload";
        Instant createdAt = Instant.parse("2025-12-02T10:15:30.00Z");
        Map<String, String> headers = new HashMap<>();
        headers.put("key1", "value1");
        String correlationId = "corr-456";
        
        OutboxMessage<String> message = new OutboxMessage<>(id, payload, createdAt, headers, correlationId);
        String toString = message.toString();
        
        assertTrue(toString.contains("OutboxMessage{"));
        assertTrue(toString.contains("id='msg-123'"));
        assertTrue(toString.contains("payload=test payload"));
        assertTrue(toString.contains("createdAt=2025-12-02T10:15:30Z"));
        assertTrue(toString.contains("correlationId='corr-456'"));
        assertTrue(toString.contains("headers="));
        assertTrue(toString.contains("key1"));
    }

    @Test
    @DisplayName("should generate toString with null correlation ID")
    void testToStringWithNullCorrelationId() {
        String id = "msg-123";
        String payload = "test payload";
        Instant createdAt = Instant.now();
        
        OutboxMessage<String> message = new OutboxMessage<>(id, payload, createdAt, null, null);
        String toString = message.toString();
        
        assertTrue(toString.contains("OutboxMessage{"));
        assertTrue(toString.contains("id='msg-123'"));
        assertTrue(toString.contains("correlationId='null'"));
    }

    @Test
    @DisplayName("should handle complex object payloads")
    void testComplexPayload() {
        String id = "msg-123";
        TestPayload payload = new TestPayload("test", 42);
        Instant createdAt = Instant.now();
        
        OutboxMessage<TestPayload> message = new OutboxMessage<>(id, payload, createdAt, null);
        
        assertEquals(id, message.getId());
        assertEquals(payload, message.getPayload());
        assertEquals("test", message.getPayload().name);
        assertEquals(42, message.getPayload().value);
    }

    @Test
    @DisplayName("should handle multiple headers")
    void testMultipleHeaders() {
        String id = "msg-123";
        String payload = "test payload";
        Instant createdAt = Instant.now();
        Map<String, String> headers = new HashMap<>();
        headers.put("header1", "value1");
        headers.put("header2", "value2");
        headers.put("header3", "value3");
        
        OutboxMessage<String> message = new OutboxMessage<>(id, payload, createdAt, headers);
        
        assertEquals(3, message.getHeaders().size());
        assertEquals("value1", message.getHeaders().get("header1"));
        assertEquals("value2", message.getHeaders().get("header2"));
        assertEquals("value3", message.getHeaders().get("header3"));
    }

    // Helper class for testing complex payloads
    private static class TestPayload {
        final String name;
        final int value;

        TestPayload(String name, int value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public String toString() {
            return "TestPayload{name='" + name + "', value=" + value + "}";
        }
    }
}
