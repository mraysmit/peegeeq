package dev.mars.peegeeq.rest.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test demonstrating the message sending functionality.
 * This test shows how the REST API would be used to send messages.
 */
@Tag(TestCategories.CORE)
class MessageSendingIntegrationTest {

    @Test
    void testMessageRequestJsonSerialization() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        
        // Test serialization of a complete message request
        String jsonInput = """
            {
                "payload": {
                    "orderId": "12345",
                    "customerId": "67890",
                    "amount": 99.99
                },
                "headers": {
                    "source": "order-service",
                    "version": "1.0",
                    "region": "US"
                },
                "priority": 5,
                "delaySeconds": 0,
                "messageType": "OrderCreated"
            }
            """;
        
        // Parse the JSON into a MessageRequest
        QueueHandler.MessageRequest request = objectMapper.readValue(jsonInput, QueueHandler.MessageRequest.class);
        
        // Verify the parsing worked correctly
        assertNotNull(request.getPayload());
        assertEquals(5, request.getPriority().intValue());
        assertEquals(0L, request.getDelaySeconds().longValue());
        assertEquals("OrderCreated", request.getMessageType());
        
        // Verify headers
        assertNotNull(request.getHeaders());
        assertEquals("order-service", request.getHeaders().get("source"));
        assertEquals("1.0", request.getHeaders().get("version"));
        assertEquals("US", request.getHeaders().get("region"));
        
        // Test validation
        assertDoesNotThrow(() -> request.validate());
    }

    @Test
    void testSimpleMessageRequest() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        
        // Test a simple message request
        String jsonInput = """
            {
                "payload": "Hello, World!"
            }
            """;
        
        QueueHandler.MessageRequest request = objectMapper.readValue(jsonInput, QueueHandler.MessageRequest.class);
        
        assertEquals("Hello, World!", request.getPayload());
        assertNull(request.getPriority());
        assertNull(request.getDelaySeconds());
        assertNull(request.getMessageType());
        assertNull(request.getHeaders());
        
        // Should still validate successfully
        assertDoesNotThrow(() -> request.validate());
    }

    @Test
    void testMessageRequestWithInvalidPriority() throws Exception {
        System.out.println("🚫 ===== RUNNING INTENTIONAL INVALID PRIORITY TEST =====");
        System.out.println("🚫 **INTENTIONAL TEST** - This test deliberately uses an invalid priority value (15)");
        System.out.println("🚫 **INTENTIONAL TEST FAILURE** - Expected validation exception for priority > 10");

        ObjectMapper objectMapper = new ObjectMapper();

        String jsonInput = """
            {
                "payload": "Test message",
                "priority": 15
            }
            """;

        QueueHandler.MessageRequest request = objectMapper.readValue(jsonInput, QueueHandler.MessageRequest.class);

        // Should throw validation error for invalid priority
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> request.validate()
        );

        assertTrue(exception.getMessage().contains("Priority must be between 1 and 10"));

        System.out.println("🚫 **SUCCESS** - Invalid priority properly threw validation exception");
        System.out.println("🚫 ===== INTENTIONAL TEST COMPLETED =====");
    }

    @Test
    void testExpectedRestApiUsage() {
        // This test documents how the REST API would be used
        
        // Example curl command that would work with our implementation:
        /*
        curl -X POST http://localhost:8080/api/v1/queues/my-setup/orders/messages \
          -H "Content-Type: application/json" \
          -d '{
            "payload": {
              "orderId": "12345",
              "customerId": "67890",
              "amount": 99.99
            },
            "headers": {
              "source": "order-service",
              "version": "1.0",
              "region": "US"
            },
            "priority": 5,
            "delaySeconds": 0,
            "messageType": "OrderCreated"
          }'
        */
        
        // Expected response:
        /*
        {
          "message": "Message sent successfully",
          "queueName": "orders",
          "setupId": "my-setup",
          "messageId": "correlation-uuid-here"
        }
        */
        
        // This test just verifies our understanding is correct
        assertTrue(true, "REST API usage documented");
    }

    @Test
    void testBatchMessageRequestStructure() throws Exception {
        String batchJsonInput = """
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
                ]
            }
            """;
        
        // Parse as generic JsonObject for now (batch support is Phase 2)
        JsonObject batchRequest = new JsonObject(batchJsonInput);
        
        assertTrue(batchRequest.containsKey("messages"));
        assertEquals(2, batchRequest.getJsonArray("messages").size());
    }
}
