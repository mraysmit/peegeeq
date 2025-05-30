package dev.mars.peegeeq.pgqueue;

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