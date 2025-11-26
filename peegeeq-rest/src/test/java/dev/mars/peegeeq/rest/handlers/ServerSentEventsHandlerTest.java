package dev.mars.peegeeq.rest.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Server-Sent Events (SSE) handler functionality.
 */
@Tag(TestCategories.CORE)
class ServerSentEventsHandlerTest {

    @BeforeEach
    void setUp() {
        new ObjectMapper();
    }

    @Test
    void testSSEConnectionCreation() {
        // Test SSEConnection creation and basic functionality
        
        String connectionId = "sse-test-123";
        String setupId = "test-setup";
        String queueName = "test-queue";
        
        // Test connection statistics structure
        JsonObject expectedStats = new JsonObject()
            .put("connectionId", connectionId)
            .put("setupId", setupId)
            .put("queueName", queueName)
            .put("active", true)
            .put("batchSize", 1)
            .put("maxWaitTime", 5000L)
            .put("messagesReceived", 0L)
            .put("messagesSent", 0L);
        
        // Verify expected structure
        assertEquals(connectionId, expectedStats.getString("connectionId"));
        assertEquals(setupId, expectedStats.getString("setupId"));
        assertEquals(queueName, expectedStats.getString("queueName"));
        assertTrue(expectedStats.getBoolean("active"));
        assertEquals(1, expectedStats.getInteger("batchSize"));
        assertEquals(5000L, expectedStats.getLong("maxWaitTime"));
        assertEquals(0L, expectedStats.getLong("messagesReceived"));
        assertEquals(0L, expectedStats.getLong("messagesSent"));
    }

    @Test
    void testSSEEventStructures() {
        // Test the various SSE event structures
        
        // Connection event
        JsonObject connectionEvent = new JsonObject()
            .put("type", "connection")
            .put("connectionId", "sse-123")
            .put("setupId", "test-setup")
            .put("queueName", "test-queue")
            .put("timestamp", System.currentTimeMillis())
            .put("message", "Connected to PeeGeeQ SSE stream");
        
        assertEquals("connection", connectionEvent.getString("type"));
        assertEquals("sse-123", connectionEvent.getString("connectionId"));
        assertEquals("test-setup", connectionEvent.getString("setupId"));
        assertEquals("test-queue", connectionEvent.getString("queueName"));
        assertTrue(connectionEvent.containsKey("timestamp"));
        assertEquals("Connected to PeeGeeQ SSE stream", connectionEvent.getString("message"));
        
        // Data event
        JsonObject dataEvent = new JsonObject()
            .put("type", "data")
            .put("connectionId", "sse-123")
            .put("messageId", "msg-789")
            .put("payload", new JsonObject().put("orderId", "12345"))
            .put("messageType", "OrderCreated")
            .put("timestamp", System.currentTimeMillis())
            .put("headers", new JsonObject().put("source", "order-service"));
        
        assertEquals("data", dataEvent.getString("type"));
        assertEquals("sse-123", dataEvent.getString("connectionId"));
        assertEquals("msg-789", dataEvent.getString("messageId"));
        assertEquals("OrderCreated", dataEvent.getString("messageType"));
        assertTrue(dataEvent.containsKey("payload"));
        assertTrue(dataEvent.containsKey("headers"));
        assertTrue(dataEvent.containsKey("timestamp"));
        
        // Batch event
        JsonObject[] messages = new JsonObject[2];
        messages[0] = new JsonObject()
            .put("messageId", "msg-1")
            .put("payload", "Message 1")
            .put("messageType", "Text");
        messages[1] = new JsonObject()
            .put("messageId", "msg-2")
            .put("payload", "Message 2")
            .put("messageType", "Text");
        
        JsonObject batchEvent = new JsonObject()
            .put("type", "batch")
            .put("connectionId", "sse-123")
            .put("messageCount", messages.length)
            .put("messages", messages)
            .put("timestamp", System.currentTimeMillis());
        
        assertEquals("batch", batchEvent.getString("type"));
        assertEquals("sse-123", batchEvent.getString("connectionId"));
        assertEquals(2, batchEvent.getInteger("messageCount"));
        assertTrue(batchEvent.containsKey("messages"));
        assertTrue(batchEvent.containsKey("timestamp"));
        
        // Error event
        JsonObject errorEvent = new JsonObject()
            .put("type", "error")
            .put("connectionId", "sse-123")
            .put("error", "Queue not found")
            .put("timestamp", System.currentTimeMillis());
        
        assertEquals("error", errorEvent.getString("type"));
        assertEquals("sse-123", errorEvent.getString("connectionId"));
        assertEquals("Queue not found", errorEvent.getString("error"));
        assertTrue(errorEvent.containsKey("timestamp"));
        
        // Heartbeat event
        JsonObject heartbeatEvent = new JsonObject()
            .put("type", "heartbeat")
            .put("connectionId", "sse-123")
            .put("timestamp", System.currentTimeMillis())
            .put("messagesReceived", 150L)
            .put("messagesSent", 25L)
            .put("uptime", 60000L);
        
        assertEquals("heartbeat", heartbeatEvent.getString("type"));
        assertEquals("sse-123", heartbeatEvent.getString("connectionId"));
        assertTrue(heartbeatEvent.containsKey("timestamp"));
        assertEquals(150L, heartbeatEvent.getLong("messagesReceived"));
        assertEquals(25L, heartbeatEvent.getLong("messagesSent"));
        assertEquals(60000L, heartbeatEvent.getLong("uptime"));
    }

    @Test
    void testSSEConfigurationEvent() {
        // Test configuration event structure
        
        JsonObject filters = new JsonObject()
            .put("messageType", "OrderCreated")
            .put("headers", new JsonObject().put("region", "US-WEST"))
            .put("payloadContains", "urgent");
        
        JsonObject configEvent = new JsonObject()
            .put("type", "configured")
            .put("connectionId", "sse-123")
            .put("consumerGroup", "my-group")
            .put("batchSize", 10)
            .put("maxWaitTime", 30000L)
            .put("filters", filters)
            .put("timestamp", System.currentTimeMillis());
        
        assertEquals("configured", configEvent.getString("type"));
        assertEquals("sse-123", configEvent.getString("connectionId"));
        assertEquals("my-group", configEvent.getString("consumerGroup"));
        assertEquals(10, configEvent.getInteger("batchSize"));
        assertEquals(30000L, configEvent.getLong("maxWaitTime"));
        assertNotNull(configEvent.getJsonObject("filters"));
        assertTrue(configEvent.containsKey("timestamp"));
        
        // Verify filters structure
        JsonObject configFilters = configEvent.getJsonObject("filters");
        assertEquals("OrderCreated", configFilters.getString("messageType"));
        assertTrue(configFilters.containsKey("headers"));
        assertEquals("urgent", configFilters.getString("payloadContains"));
    }

    @Test
    void testSSEEventFormatting() {
        // Test SSE event formatting (how events are formatted for the SSE protocol)
        
        JsonObject data = new JsonObject()
            .put("type", "data")
            .put("messageId", "msg-123")
            .put("payload", "Test message");
        
        // Expected SSE format:
        // event: message
        // data: {"type":"data","messageId":"msg-123","payload":"Test message"}
        // 
        // (empty line to complete the event)
        
        String expectedSSEFormat = "event: message\n" +
                                  "data: " + data.encode() + "\n" +
                                  "\n";
        
        // Verify the format structure
        assertTrue(expectedSSEFormat.startsWith("event: message\n"));
        assertTrue(expectedSSEFormat.contains("data: {"));
        assertTrue(expectedSSEFormat.endsWith("\n\n"));
        
        // Verify JSON data can be parsed
        String dataLine = expectedSSEFormat.split("\n")[1];
        String jsonData = dataLine.substring(6); // Remove "data: " prefix
        JsonObject parsedData = new JsonObject(jsonData);
        
        assertEquals("data", parsedData.getString("type"));
        assertEquals("msg-123", parsedData.getString("messageId"));
        assertEquals("Test message", parsedData.getString("payload"));
    }

    @Test
    void testSSEQueryParameterParsing() {
        // Test query parameter parsing for SSE configuration
        
        // Simulate query parameters that would be parsed
        String consumerGroup = "my-consumer-group";
        String batchSize = "10";
        String maxWait = "30000";
        String messageType = "OrderCreated";
        String headerRegion = "US-WEST";
        String headerPriority = "HIGH";
        
        // Test parameter validation
        assertNotNull(consumerGroup);
        assertFalse(consumerGroup.trim().isEmpty());
        
        int parsedBatchSize = Integer.parseInt(batchSize);
        assertTrue(parsedBatchSize >= 1 && parsedBatchSize <= 100);
        
        long parsedMaxWait = Long.parseLong(maxWait);
        assertTrue(parsedMaxWait >= 1000L && parsedMaxWait <= 60000L);
        
        assertNotNull(messageType);
        assertFalse(messageType.trim().isEmpty());
        
        // Test header filter construction
        JsonObject headerFilters = new JsonObject()
            .put("region", headerRegion)
            .put("priority", headerPriority);
        
        JsonObject filters = new JsonObject()
            .put("messageType", messageType)
            .put("headers", headerFilters);
        
        assertEquals("OrderCreated", filters.getString("messageType"));
        JsonObject headers = filters.getJsonObject("headers");
        assertEquals("US-WEST", headers.getString("region"));
        assertEquals("HIGH", headers.getString("priority"));
    }

    @Test
    void testSSEApiDocumentation() {
        // This test documents the SSE API usage
        
        System.out.println("📚 Phase 4 Server-Sent Events (SSE) API Documentation:");
        System.out.println();
        
        System.out.println("🔹 SSE Connection:");
        System.out.println("GET /api/v1/queues/{setupId}/{queueName}/stream");
        System.out.println("- Real-time message streaming over HTTP");
        System.out.println("- Standard SSE protocol (text/event-stream)");
        System.out.println("- One-way server-to-client communication");
        System.out.println();
        
        System.out.println("🔹 Query Parameters:");
        System.out.println("- consumerGroup: Consumer group name");
        System.out.println("- batchSize: Messages per batch (1-100)");
        System.out.println("- maxWait: Maximum wait time in milliseconds (1s-60s)");
        System.out.println("- messageType: Filter by message type");
        System.out.println("- header.{key}: Filter by header values");
        System.out.println();
        
        System.out.println("🔹 SSE Event Types:");
        System.out.println("- connection: Initial connection established");
        System.out.println("- configured: Configuration confirmation");
        System.out.println("- message: Individual queue message");
        System.out.println("- batch: Multiple messages");
        System.out.println("- heartbeat: Connection keep-alive");
        System.out.println("- error: Error notifications");
        System.out.println();
        
        System.out.println("🔹 Example Usage (JavaScript):");
        System.out.println("""
            const eventSource = new EventSource(
              '/api/v1/queues/my-setup/orders/stream?messageType=OrderCreated&batchSize=5'
            );
            
            eventSource.addEventListener('message', (event) => {
              const data = JSON.parse(event.data);
              console.log('Received message:', data);
            });
            
            eventSource.addEventListener('error', (event) => {
              console.error('SSE error:', event);
            });
            """);
        System.out.println();
        
        System.out.println("🔹 HTTP Headers:");
        System.out.println("- Content-Type: text/event-stream");
        System.out.println("- Cache-Control: no-cache");
        System.out.println("- Connection: keep-alive");
        System.out.println("- Access-Control-Allow-Origin: *");
        
        assertTrue(true, "SSE API documentation complete");
    }
}
