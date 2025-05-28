package dev.mars.peegeeq.pg;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the PgNativeMessage class.
 */
public class PgNativeMessageTest {

    @Test
    void testPgNativeMessageConstructor() {
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
        assertEquals(headers, message.getHeaders());
    }
    
    @Test
    void testPgNativeMessageSimpleConstructor() {
        // Arrange
        String id = "msg-456";
        String payload = "another payload";
        
        // Act
        PgNativeMessage<String> message = new PgNativeMessage<>(id, payload);
        
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
        PgNativeMessage<String> message = new PgNativeMessage<>("id", "payload", Instant.now(), headers);
        
        // Act & Assert
        assertThrows(UnsupportedOperationException.class, () -> {
            message.getHeaders().put("key2", "value2");
        });
    }
}