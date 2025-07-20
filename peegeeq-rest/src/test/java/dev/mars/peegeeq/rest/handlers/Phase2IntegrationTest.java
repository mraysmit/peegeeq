package dev.mars.peegeeq.rest.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test demonstrating Phase 2 advanced features working together.
 */
class Phase2IntegrationTest {

    @Test
    void testCompletePhase2Workflow() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        
        // Test 1: Enhanced single message with type detection
        testEnhancedSingleMessage(objectMapper);
        
        // Test 2: Batch message processing
        testBatchMessageProcessing(objectMapper);
        
        // Test 3: Advanced validation features
        testAdvancedValidation(objectMapper);
    }
    
    private void testEnhancedSingleMessage(ObjectMapper objectMapper) throws Exception {
        // Create a complex message with all Phase 2 features
        String complexMessageJson = """
            {
                "payload": {
                    "eventType": "OrderCreated",
                    "orderId": "ORD-12345",
                    "customerId": "CUST-67890",
                    "amount": 299.99,
                    "currency": "USD",
                    "items": [
                        {"sku": "ITEM-001", "quantity": 2, "price": 149.99},
                        {"sku": "ITEM-002", "quantity": 1, "price": 0.01}
                    ]
                },
                "headers": {
                    "source": "order-service",
                    "version": "2.1",
                    "region": "US-WEST",
                    "correlationId": "CORR-ABC123",
                    "traceId": "TRACE-XYZ789"
                },
                "priority": 7,
                "delaySeconds": 0,
                "messageType": "OrderCreatedEvent"
            }
            """;
        
        QueueHandler.MessageRequest request = objectMapper.readValue(complexMessageJson, QueueHandler.MessageRequest.class);
        
        // Test enhanced validation
        assertDoesNotThrow(() -> request.validate());
        
        // Test type detection (should use explicit messageType)
        assertEquals("OrderCreatedEvent", request.detectMessageType());
        
        // Test headers validation
        assertNotNull(request.getHeaders());
        assertEquals(5, request.getHeaders().size());
        assertEquals("order-service", request.getHeaders().get("source"));
        assertEquals("CORR-ABC123", request.getHeaders().get("correlationId"));
        
        // Test priority and delay
        assertEquals(Integer.valueOf(7), request.getPriority());
        assertEquals(Long.valueOf(0), request.getDelaySeconds());
        
        System.out.println("✅ Enhanced single message test passed");
    }
    
    private void testBatchMessageProcessing(ObjectMapper objectMapper) throws Exception {
        // Create a batch with different message types
        String batchJson = """
            {
                "messages": [
                    {
                        "payload": {
                            "eventType": "OrderCreated",
                            "orderId": "ORD-001"
                        },
                        "priority": 8,
                        "messageType": "OrderEvent"
                    },
                    {
                        "payload": {
                            "commandType": "ProcessPayment",
                            "paymentId": "PAY-001"
                        },
                        "priority": 9,
                        "delaySeconds": 5
                    },
                    {
                        "payload": "Simple notification message",
                        "priority": 3,
                        "headers": {
                            "category": "notification",
                            "urgency": "low"
                        }
                    },
                    {
                        "payload": {
                            "userId": "USER-123",
                            "action": "login"
                        },
                        "priority": 5
                    }
                ],
                "failOnError": false,
                "maxBatchSize": 10
            }
            """;
        
        QueueHandler.BatchMessageRequest batchRequest = objectMapper.readValue(batchJson, QueueHandler.BatchMessageRequest.class);
        
        // Test batch validation
        assertDoesNotThrow(() -> batchRequest.validate());
        
        // Test batch properties
        assertEquals(4, batchRequest.getMessages().size());
        assertFalse(batchRequest.isFailOnError());
        assertEquals(10, batchRequest.getMaxBatchSize());
        
        // Test individual message type detection
        assertEquals("OrderEvent", batchRequest.getMessages().get(0).detectMessageType()); // Explicit type
        assertEquals("Command", batchRequest.getMessages().get(1).detectMessageType()); // Detected from commandType
        assertEquals("Text", batchRequest.getMessages().get(2).detectMessageType()); // String payload
        assertEquals("User", batchRequest.getMessages().get(3).detectMessageType()); // Detected from userId
        
        // Test individual message validation
        for (QueueHandler.MessageRequest msg : batchRequest.getMessages()) {
            assertDoesNotThrow(() -> msg.validate());
        }
        
        System.out.println("✅ Batch message processing test passed");
    }
    
    private void testAdvancedValidation(ObjectMapper objectMapper) throws Exception {
        // Test 1: Header validation
        QueueHandler.MessageRequest msgWithBadHeaders = new QueueHandler.MessageRequest();
        msgWithBadHeaders.setPayload("test");
        
        Map<String, String> badHeaders = new HashMap<>();
        badHeaders.put("", "empty-key"); // Invalid empty key
        msgWithBadHeaders.setHeaders(badHeaders);
        
        IllegalArgumentException headerException = assertThrows(
            IllegalArgumentException.class,
            () -> msgWithBadHeaders.validate()
        );
        assertTrue(headerException.getMessage().contains("Header keys cannot be null or empty"));
        
        // Test 2: Batch size validation
        QueueHandler.BatchMessageRequest oversizedBatch = new QueueHandler.BatchMessageRequest();
        oversizedBatch.setMaxBatchSize(2);
        
        QueueHandler.MessageRequest msg1 = new QueueHandler.MessageRequest();
        msg1.setPayload("Message 1");
        QueueHandler.MessageRequest msg2 = new QueueHandler.MessageRequest();
        msg2.setPayload("Message 2");
        QueueHandler.MessageRequest msg3 = new QueueHandler.MessageRequest();
        msg3.setPayload("Message 3");
        
        oversizedBatch.setMessages(Arrays.asList(msg1, msg2, msg3));
        
        IllegalArgumentException sizeException = assertThrows(
            IllegalArgumentException.class,
            () -> oversizedBatch.validate()
        );
        assertTrue(sizeException.getMessage().contains("Batch size exceeds maximum allowed: 2"));
        
        // Test 3: Nested message validation in batch
        QueueHandler.BatchMessageRequest batchWithInvalidMsg = new QueueHandler.BatchMessageRequest();
        
        QueueHandler.MessageRequest validMsg = new QueueHandler.MessageRequest();
        validMsg.setPayload("Valid");
        
        QueueHandler.MessageRequest invalidMsg = new QueueHandler.MessageRequest();
        invalidMsg.setPayload("Invalid");
        invalidMsg.setPriority(15); // Invalid priority > 10
        
        batchWithInvalidMsg.setMessages(Arrays.asList(validMsg, invalidMsg));
        
        IllegalArgumentException nestedException = assertThrows(
            IllegalArgumentException.class,
            () -> batchWithInvalidMsg.validate()
        );
        assertTrue(nestedException.getMessage().contains("Message at index 1 is invalid"));
        assertTrue(nestedException.getMessage().contains("Priority must be between 1 and 10"));
        
        System.out.println("✅ Advanced validation test passed");
    }
    
    @Test
    void testMessageTypeDetectionEdgeCases() {
        // Test various edge cases for message type detection
        
        // Test 1: Null payload
        QueueHandler.MessageRequest nullPayloadMsg = new QueueHandler.MessageRequest();
        nullPayloadMsg.setPayload(null);
        assertEquals("Unknown", nullPayloadMsg.detectMessageType());
        
        // Test 2: Empty map payload
        QueueHandler.MessageRequest emptyMapMsg = new QueueHandler.MessageRequest();
        emptyMapMsg.setPayload(new HashMap<>());
        assertEquals("Object", emptyMapMsg.detectMessageType());
        
        // Test 3: Numeric payload
        QueueHandler.MessageRequest numericMsg = new QueueHandler.MessageRequest();
        numericMsg.setPayload(42);
        assertEquals("Numeric", numericMsg.detectMessageType());
        
        // Test 4: Array payload
        QueueHandler.MessageRequest arrayMsg = new QueueHandler.MessageRequest();
        arrayMsg.setPayload(Arrays.asList("item1", "item2"));
        assertEquals("Array", arrayMsg.detectMessageType());
        
        // Test 5: Explicit type overrides detection
        QueueHandler.MessageRequest explicitTypeMsg = new QueueHandler.MessageRequest();
        Map<String, Object> payloadWithEventType = new HashMap<>();
        payloadWithEventType.put("eventType", "SomeEvent");
        explicitTypeMsg.setPayload(payloadWithEventType);
        explicitTypeMsg.setMessageType("ExplicitType");
        assertEquals("ExplicitType", explicitTypeMsg.detectMessageType()); // Should use explicit, not detected
        
        System.out.println("✅ Message type detection edge cases test passed");
    }
    
    @Test
    void testPhase2ApiDocumentation() {
        // This test documents the complete Phase 2 API usage
        
        System.out.println("📚 Phase 2 API Documentation:");
        System.out.println();
        
        System.out.println("🔹 Enhanced Single Message API:");
        System.out.println("POST /api/v1/queues/{setupId}/{queueName}/messages");
        System.out.println("- Automatic message type detection");
        System.out.println("- Enhanced header validation");
        System.out.println("- Metadata in response (timestamp, type, etc.)");
        System.out.println();
        
        System.out.println("🔹 Batch Message API:");
        System.out.println("POST /api/v1/queues/{setupId}/{queueName}/messages/batch");
        System.out.println("- Send multiple messages in one request");
        System.out.println("- Configurable error handling (fail-fast or continue)");
        System.out.println("- Batch size limits and validation");
        System.out.println("- Multi-status responses (207 if some failed)");
        System.out.println();
        
        System.out.println("🔹 Enhanced Features:");
        System.out.println("- Smart message type detection from payload");
        System.out.println("- Comprehensive header validation");
        System.out.println("- Payload size tracking");
        System.out.println("- Automatic timestamp addition");
        System.out.println("- Detailed error messages with context");
        
        assertTrue(true, "Phase 2 API documentation complete");
    }
}
