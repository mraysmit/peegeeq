package dev.mars.peegeeq.db.deadletter;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE unit tests for DeadLetterMessage data model.
 * 
 * <p>Tests the DeadLetterMessage constructor, getters, and object equality
 * without requiring database access.</p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
class DeadLetterMessageTest {

    @Test
    void testConstructorWithAllFields() {
        Instant now = Instant.now();
        Instant earlier = now.minusSeconds(3600);
        Map<String, String> headers = new HashMap<>();
        headers.put("key1", "value1");
        headers.put("key2", "value2");
        
        DeadLetterMessage message = new DeadLetterMessage(
            123L,
            "outbox_messages",
            456L,
            "test-topic",
            "{\"data\":\"test\"}",
            earlier,
            now,
            "Processing failed",
            3,
            headers,
            "corr-123",
            "group-1"
        );
        
        assertEquals(123L, message.getId());
        assertEquals("outbox_messages", message.getOriginalTable());
        assertEquals(456L, message.getOriginalId());
        assertEquals("test-topic", message.getTopic());
        assertEquals("{\"data\":\"test\"}", message.getPayload());
        assertEquals(earlier, message.getOriginalCreatedAt());
        assertEquals(now, message.getFailedAt());
        assertEquals("Processing failed", message.getFailureReason());
        assertEquals(3, message.getRetryCount());
        assertEquals(headers, message.getHeaders());
        assertEquals("corr-123", message.getCorrelationId());
        assertEquals("group-1", message.getMessageGroup());
    }

    @Test
    void testConstructorWithNullHeaders() {
        Instant now = Instant.now();
        
        DeadLetterMessage message = new DeadLetterMessage(
            1L, "outbox_messages", 2L, "topic", "payload",
            now, now, "reason", 0, null, null, null
        );
        
        assertNull(message.getHeaders());
        assertNull(message.getCorrelationId());
        assertNull(message.getMessageGroup());
    }

    @Test
    void testConstructorRequiresOriginalTable() {
        Instant now = Instant.now();
        
        Exception exception = assertThrows(NullPointerException.class, () -> {
            new DeadLetterMessage(
                1L, null, 2L, "topic", "payload",
                now, now, "reason", 0, null, null, null
            );
        });
        
        assertTrue(exception.getMessage().contains("Original table"));
    }

    @Test
    void testConstructorRequiresTopic() {
        Instant now = Instant.now();
        
        Exception exception = assertThrows(NullPointerException.class, () -> {
            new DeadLetterMessage(
                1L, "table", 2L, null, "payload",
                now, now, "reason", 0, null, null, null
            );
        });
        
        assertTrue(exception.getMessage().contains("Topic"));
    }

    @Test
    void testConstructorRequiresPayload() {
        Instant now = Instant.now();
        
        Exception exception = assertThrows(NullPointerException.class, () -> {
            new DeadLetterMessage(
                1L, "table", 2L, "topic", null,
                now, now, "reason", 0, null, null, null
            );
        });
        
        assertTrue(exception.getMessage().contains("Payload"));
    }

    @Test
    void testConstructorRequiresOriginalCreatedAt() {
        Instant now = Instant.now();
        
        Exception exception = assertThrows(NullPointerException.class, () -> {
            new DeadLetterMessage(
                1L, "table", 2L, "topic", "payload",
                null, now, "reason", 0, null, null, null
            );
        });
        
        assertTrue(exception.getMessage().contains("Original created at"));
    }

    @Test
    void testConstructorRequiresFailedAt() {
        Instant now = Instant.now();

        Exception exception = assertThrows(NullPointerException.class, () -> {
            new DeadLetterMessage(
                1L, "table", 2L, "topic", "payload",
                now, null, "reason", 0, null, null, null
            );
        });

        assertTrue(exception.getMessage().contains("Failed at"));
    }

    @Test
    void testConstructorRequiresFailureReason() {
        Instant now = Instant.now();

        Exception exception = assertThrows(NullPointerException.class, () -> {
            new DeadLetterMessage(
                1L, "table", 2L, "topic", "payload",
                now, now, null, 0, null, null, null
            );
        });

        assertTrue(exception.getMessage().contains("Failure reason"));
    }

    @Test
    void testEqualsAndHashCode() {
        Instant now = Instant.now();
        Map<String, String> headers = Map.of("key", "value");

        DeadLetterMessage msg1 = new DeadLetterMessage(
            1L, "table", 2L, "topic", "payload",
            now, now, "reason", 3, headers, "corr", "group"
        );

        DeadLetterMessage msg2 = new DeadLetterMessage(
            1L, "table", 2L, "topic", "payload",
            now, now, "reason", 3, headers, "corr", "group"
        );

        DeadLetterMessage msg3 = new DeadLetterMessage(
            999L, "table", 2L, "topic", "payload",
            now, now, "reason", 3, headers, "corr", "group"
        );

        assertEquals(msg1, msg2);
        assertEquals(msg1.hashCode(), msg2.hashCode());
        assertNotEquals(msg1, msg3);
    }

    @Test
    void testEqualsSameObject() {
        Instant now = Instant.now();
        DeadLetterMessage message = new DeadLetterMessage(
            1L, "table", 2L, "topic", "payload",
            now, now, "reason", 0, null, null, null
        );

        assertEquals(message, message);
    }

    @Test
    void testEqualsNull() {
        Instant now = Instant.now();
        DeadLetterMessage message = new DeadLetterMessage(
            1L, "table", 2L, "topic", "payload",
            now, now, "reason", 0, null, null, null
        );

        assertNotEquals(message, null);
    }

    @Test
    void testEqualsDifferentClass() {
        Instant now = Instant.now();
        DeadLetterMessage message = new DeadLetterMessage(
            1L, "table", 2L, "topic", "payload",
            now, now, "reason", 0, null, null, null
        );

        assertNotEquals(message, "not a message");
    }

    @Test
    void testToString() {
        Instant now = Instant.now();
        DeadLetterMessage message = new DeadLetterMessage(
            123L, "outbox_messages", 456L, "test-topic", "payload",
            now, now, "Processing failed", 3, null, "corr-123", "group-1"
        );

        String str = message.toString();

        assertTrue(str.contains("123"));
        assertTrue(str.contains("outbox_messages"));
        assertTrue(str.contains("456"));
        assertTrue(str.contains("test-topic"));
        assertTrue(str.contains("Processing failed"));
        assertTrue(str.contains("3"));
        assertTrue(str.contains("corr-123"));
        assertTrue(str.contains("group-1"));
    }
}


