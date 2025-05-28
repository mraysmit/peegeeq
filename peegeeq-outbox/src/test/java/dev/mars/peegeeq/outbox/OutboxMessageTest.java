package dev.mars.peegeeq.outbox;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the OutboxMessage class.
 */
public class OutboxMessageTest {

    @Test
    void testOutboxMessageConstructor() {
        // Arrange
        String id = "msg-123";
        String payload = "test payload";
        Instant now = Instant.now();
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "text/plain");
        
        // Act
        OutboxMessage<String> message = new OutboxMessage<>(id, payload, now, headers);
        
        // Assert
        assertEquals(id, message.getId());
        assertEquals(payload, message.getPayload());
        assertEquals(now, message.getCreatedAt());
        assertEquals(headers, message.getHeaders());
    }
    
    @Test
    void testOutboxMessageSimpleConstructor() {
        // Arrange
        String id = "msg-456";
        String payload = "another payload";
        
        // Act
        OutboxMessage<String> message = new OutboxMessage<>(id, payload);
        
        // Assert
        assertEquals(id, message.getId());
        assertEquals(payload, message.getPayload());
        assertNotNull(message.getCreatedAt());
        assertTrue(message.getHeaders().isEmpty());
    }
    
    @Test
    void testHeadersImmutability() {
        // Arrange
        Map<String, String> headers = new HashMap<>();
        headers.put("key1", "value1");
        OutboxMessage<String> message = new OutboxMessage<>("id", "payload", Instant.now(), headers);
        
        // Act & Assert
        assertThrows(UnsupportedOperationException.class, () -> {
            message.getHeaders().put("key2", "value2");
        });
    }
}