package dev.mars.peegeeq.rest.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.rest.dto.BatchMessageRequest;
import dev.mars.peegeeq.rest.dto.MessageRequest;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for message type detection and batch request validation.
 *
 * Tests the logic for:
 * - Detecting message types (Event, Command, Order, Text)
 * - Validating message headers
 * - Validating batch message requests
 */
@Tag(TestCategories.CORE)
class MessageTypeDetectionAndValidationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void testMessageTypeDetection_EventType() throws Exception {
        String jsonInput = """
            {
                "payload": {
                    "eventType": "OrderCreated",
                    "orderId": "12345"
                }
            }
            """;
        
        MessageRequest request = objectMapper.readValue(jsonInput, MessageRequest.class);
        assertEquals("Event", request.detectMessageType());
    }

    @Test
    void testMessageTypeDetection_CommandType() throws Exception {
        String jsonInput = """
            {
                "payload": {
                    "commandType": "CreateOrder",
                    "customerId": "67890"
                }
            }
            """;
        
        MessageRequest request = objectMapper.readValue(jsonInput, MessageRequest.class);
        assertEquals("Command", request.detectMessageType());
    }

    @Test
    void testMessageTypeDetection_OrderType() throws Exception {
        String jsonInput = """
            {
                "payload": {
                    "orderId": "12345",
                    "amount": 99.99
                }
            }
            """;
        
        MessageRequest request = objectMapper.readValue(jsonInput, MessageRequest.class);
        assertEquals("Order", request.detectMessageType());
    }

    @Test
    void testMessageTypeDetection_TextType() throws Exception {
        String jsonInput = """
            {
                "payload": "Simple text message"
            }
            """;
        
        MessageRequest request = objectMapper.readValue(jsonInput, MessageRequest.class);
        assertEquals("Text", request.detectMessageType());
    }

    @Test
    void testMessageTypeDetection_ExplicitType() throws Exception {
        String jsonInput = """
            {
                "payload": "Some data",
                "messageType": "CustomType"
            }
            """;
        
        MessageRequest request = objectMapper.readValue(jsonInput, MessageRequest.class);
        assertEquals("CustomType", request.detectMessageType());
    }

    @Test
    void testEnhancedValidation_InvalidHeaders() throws Exception {
        MessageRequest request = new MessageRequest();
        request.setPayload("test");
        
        Map<String, String> invalidHeaders = new HashMap<>();
        invalidHeaders.put("", "value"); // Empty key
        request.setHeaders(invalidHeaders);
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class, 
            () -> request.validate()
        );
        
        assertTrue(exception.getMessage().contains("Header keys cannot be null or empty"));
    }

    @Test
    void testEnhancedValidation_NullHeaderValue() throws Exception {
        MessageRequest request = new MessageRequest();
        request.setPayload("test");
        
        Map<String, String> invalidHeaders = new HashMap<>();
        invalidHeaders.put("key", null); // Null value
        request.setHeaders(invalidHeaders);
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class, 
            () -> request.validate()
        );
        
        assertTrue(exception.getMessage().contains("Header values cannot be null"));
    }

    @Test
    void testBatchMessageRequest_ValidBatch() throws Exception {
        String jsonInput = """
            {
                "messages": [
                    {
                        "payload": "Message 1",
                        "priority": 5
                    },
                    {
                        "payload": "Message 2",
                        "priority": 3,
                        "delaySeconds": 10
                    }
                ],
                "failOnError": true,
                "maxBatchSize": 50
            }
            """;
        
        BatchMessageRequest batchRequest = objectMapper.readValue(jsonInput, BatchMessageRequest.class);
        
        assertEquals(2, batchRequest.getMessages().size());
        assertTrue(batchRequest.isFailOnError());
        assertEquals(50, batchRequest.getMaxBatchSize());
        
        // Should validate successfully
        assertDoesNotThrow(() -> batchRequest.validate());
    }

    @Test
    void testBatchMessageRequest_EmptyBatch() throws Exception {
        BatchMessageRequest batchRequest = new BatchMessageRequest();
        batchRequest.setMessages(Arrays.asList()); // Empty list
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class, 
            () -> batchRequest.validate()
        );
        
        assertTrue(exception.getMessage().contains("Batch must contain at least one message"));
    }

    @Test
    void testBatchMessageRequest_ExceedsMaxSize() throws Exception {
        BatchMessageRequest batchRequest = new BatchMessageRequest();
        batchRequest.setMaxBatchSize(2);
        
        // Create 3 messages (exceeds max of 2)
        MessageRequest msg1 = new MessageRequest();
        msg1.setPayload("Message 1");
        MessageRequest msg2 = new MessageRequest();
        msg2.setPayload("Message 2");
        MessageRequest msg3 = new MessageRequest();
        msg3.setPayload("Message 3");
        
        batchRequest.setMessages(Arrays.asList(msg1, msg2, msg3));
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class, 
            () -> batchRequest.validate()
        );
        
        assertTrue(exception.getMessage().contains("Batch size exceeds maximum allowed: 2"));
    }

    @Test
    void testBatchMessageRequest_InvalidMessageInBatch() throws Exception {
        BatchMessageRequest batchRequest = new BatchMessageRequest();
        
        MessageRequest validMsg = new MessageRequest();
        validMsg.setPayload("Valid message");
        
        MessageRequest invalidMsg = new MessageRequest();
        invalidMsg.setPayload(null); // Invalid - null payload
        
        batchRequest.setMessages(Arrays.asList(validMsg, invalidMsg));
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class, 
            () -> batchRequest.validate()
        );
        
        assertTrue(exception.getMessage().contains("Message at index 1 is invalid"));
        assertTrue(exception.getMessage().contains("payload is required"));
    }

    @Test
    void testBatchRequestJsonStructure() throws Exception {
        // Test the expected JSON structure for batch requests
        String expectedBatchJson = """
            {
                "messages": [
                    {
                        "payload": {
                            "orderId": "12345",
                            "customerId": "67890",
                            "amount": 99.99
                        },
                        "headers": {
                            "source": "order-service",
                            "version": "1.0"
                        },
                        "priority": 5,
                        "messageType": "OrderCreated"
                    },
                    {
                        "payload": {
                            "orderId": "12346",
                            "customerId": "67891",
                            "amount": 149.99
                        },
                        "headers": {
                            "source": "order-service",
                            "version": "1.0"
                        },
                        "priority": 3,
                        "delaySeconds": 30,
                        "messageType": "OrderCreated"
                    }
                ],
                "failOnError": false,
                "maxBatchSize": 100
            }
            """;
        
        BatchMessageRequest batchRequest = objectMapper.readValue(expectedBatchJson, BatchMessageRequest.class);
        
        assertEquals(2, batchRequest.getMessages().size());
        assertFalse(batchRequest.isFailOnError());
        assertEquals(100, batchRequest.getMaxBatchSize());
        
        // Verify first message
        MessageRequest firstMsg = batchRequest.getMessages().get(0);
        assertEquals("OrderCreated", firstMsg.getMessageType());
        assertEquals(Integer.valueOf(5), firstMsg.getPriority());
        assertNotNull(firstMsg.getHeaders());
        assertEquals("order-service", firstMsg.getHeaders().get("source"));
        
        // Verify second message
        MessageRequest secondMsg = batchRequest.getMessages().get(1);
        assertEquals("OrderCreated", secondMsg.getMessageType());
        assertEquals(Integer.valueOf(3), secondMsg.getPriority());
        assertEquals(Long.valueOf(30), secondMsg.getDelaySeconds());
        
        // Should validate successfully
        assertDoesNotThrow(() -> batchRequest.validate());
    }

    @Test
    void testExpectedBatchApiUsage() {
        // This test documents how the batch API would be used
        
        // Example curl command for batch sending:
        /*
        curl -X POST http://localhost:8080/api/v1/queues/my-setup/orders/messages/batch \
          -H "Content-Type: application/json" \
          -d '{
            "messages": [
              {
                "payload": {"orderId": "12345", "amount": 99.99},
                "priority": 5,
                "messageType": "OrderCreated"
              },
              {
                "payload": {"orderId": "12346", "amount": 149.99},
                "priority": 3,
                "delaySeconds": 30,
                "messageType": "OrderCreated"
              }
            ],
            "failOnError": false,
            "maxBatchSize": 100
          }'
        */
        
        // Expected response:
        /*
        {
          "message": "Batch messages processed",
          "queueName": "orders",
          "setupId": "my-setup",
          "totalMessages": 2,
          "successfulMessages": 2,
          "failedMessages": 0,
          "messageIds": ["uuid1", "uuid2"]
        }
        */
        
        assertTrue(true, "Batch API usage documented");
    }
}
