package dev.mars.peegeeq.rest.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for QueueHandler message sending functionality.
 */
@Tag(TestCategories.CORE)
class QueueHandlerUnitTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void testMessageRequestSerialization() throws Exception {
        // Test that MessageRequest can be serialized/deserialized correctly
        String jsonString = "{\"payload\":\"test message\",\"priority\":5,\"delaySeconds\":10}";

        QueueHandler.MessageRequest request = objectMapper.readValue(jsonString, QueueHandler.MessageRequest.class);

        assertEquals("test message", request.getPayload());
        assertEquals(Integer.valueOf(5), request.getPriority());
        assertEquals(Long.valueOf(10), request.getDelaySeconds());
    }

    @Test
    void testMessageRequestValidation() {
        // Test the MessageRequest validation logic
        QueueHandler.MessageRequest request = new QueueHandler.MessageRequest();
        
        // Test null payload validation
        try {
            request.validate();
            assert false : "Should have thrown exception for null payload";
        } catch (IllegalArgumentException e) {
            assert e.getMessage().contains("payload is required");
        }
        
        // Test valid request
        request.setPayload("test");
        request.setPriority(5);
        request.setDelaySeconds(10L);
        
        // Should not throw exception
        request.validate();
    }

    @Test
    void testMessageRequestValidation_InvalidPriority() {
        QueueHandler.MessageRequest request = new QueueHandler.MessageRequest();
        request.setPayload("test");
        request.setPriority(15); // Invalid - should be 1-10
        
        try {
            request.validate();
            assert false : "Should have thrown exception for invalid priority";
        } catch (IllegalArgumentException e) {
            assert e.getMessage().contains("Priority must be between 1 and 10");
        }
    }

    @Test
    void testMessageRequestValidation_NegativeDelay() {
        QueueHandler.MessageRequest request = new QueueHandler.MessageRequest();
        request.setPayload("test");
        request.setDelaySeconds(-5L); // Invalid - should be non-negative
        
        try {
            request.validate();
            assert false : "Should have thrown exception for negative delay";
        } catch (IllegalArgumentException e) {
            assert e.getMessage().contains("Delay seconds cannot be negative");
        }
    }

    @Test
    void testMessageRequestGettersAndSetters() {
        // Test that all getters and setters work correctly
        QueueHandler.MessageRequest request = new QueueHandler.MessageRequest();

        request.setPayload("test payload");
        request.setPriority(7);
        request.setDelaySeconds(30L);
        request.setMessageType("TestMessage");

        assertEquals("test payload", request.getPayload());
        assertEquals(Integer.valueOf(7), request.getPriority());
        assertEquals(Long.valueOf(30), request.getDelaySeconds());
        assertEquals("TestMessage", request.getMessageType());
    }
}
