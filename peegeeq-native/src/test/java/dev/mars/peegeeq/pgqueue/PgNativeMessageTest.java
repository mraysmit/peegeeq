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


import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the PgNativeMessage class.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class PgNativeMessageTest {

    @Test
    void testConstructorWithAllParameters() {
        // Arrange
        String id = "msg-123";
        String payload = "test payload";
        Instant now = Instant.now();
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "text/plain");
        
        // Act
        PgNativeMessage<String> message = new PgNativeMessage<>(id, payload, now, headers);
        
        // Assert
        assertEquals(id, message.getId());
        assertEquals(payload, message.getPayload());
        assertEquals(now, message.getCreatedAt());
        assertEquals(headers.size(), message.getHeaders().size());
        assertEquals(headers.get("content-type"), message.getHeaders().get("content-type"));
    }
    
    @Test
    void testConstructorWithIdAndPayload() {
        // Arrange
        String id = "msg-456";
        String payload = "another payload";
        
        // Act
        PgNativeMessage<String> message = new PgNativeMessage<>(id, payload);
        
        // Assert
        assertEquals(id, message.getId());
        assertEquals(payload, message.getPayload());
        assertNotNull(message.getCreatedAt());
        assertNotNull(message.getHeaders());
        assertTrue(message.getHeaders().isEmpty());
    }
    
    @Test
    void testNullIdThrowsException() {
        // Arrange
        String payload = "test payload";
        Instant now = Instant.now();
        
        // Act & Assert
        assertThrows(NullPointerException.class, () -> {
            new PgNativeMessage<>(null, payload, now, null);
        });
    }
    
    @Test
    void testNullCreatedAtThrowsException() {
        // Arrange
        String id = "msg-789";
        String payload = "test payload";
        
        // Act & Assert
        assertThrows(NullPointerException.class, () -> {
            new PgNativeMessage<>(id, payload, null, null);
        });
    }
    
    @Test
    void testHeadersAreImmutable() {
        // Arrange
        String id = "msg-123";
        String payload = "test payload";
        Instant now = Instant.now();
        Map<String, String> headers = new HashMap<>();
        headers.put("key1", "value1");
        
        // Act
        PgNativeMessage<String> message = new PgNativeMessage<>(id, payload, now, headers);
        
        // Assert - verify we can't modify the headers
        assertThrows(UnsupportedOperationException.class, () -> {
            message.getHeaders().put("key2", "value2");
        });
    }
    
    @Test
    void testToString() {
        // Arrange
        String id = "msg-123";
        String payload = "test payload";
        Instant now = Instant.now();
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "text/plain");
        
        // Act
        PgNativeMessage<String> message = new PgNativeMessage<>(id, payload, now, headers);
        String toString = message.toString();
        
        // Assert
        assertTrue(toString.contains(id));
        assertTrue(toString.contains(payload));
        assertTrue(toString.contains(headers.toString()));
    }
}