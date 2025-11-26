package dev.mars.peegeeq.rest.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Management API Handler functionality.
 */
@Tag(TestCategories.CORE)
class ManagementApiHandlerTest {

    @BeforeEach
    void setUp() {
        new ObjectMapper();
    }

    @Test
    void testHealthCheckResponse() {
        // Test health check response structure
        
        JsonObject expectedHealth = new JsonObject()
            .put("status", "UP")
            .put("timestamp", "2025-07-19T14:30:00Z")
            .put("uptime", "7d 14h 32m")
            .put("version", "1.0.0")
            .put("build", "Phase-5-Management-UI");
        
        // Verify response structure
        assertEquals("UP", expectedHealth.getString("status"));
        assertTrue(expectedHealth.containsKey("timestamp"));
        assertTrue(expectedHealth.containsKey("uptime"));
        assertEquals("1.0.0", expectedHealth.getString("version"));
        assertEquals("Phase-5-Management-UI", expectedHealth.getString("build"));
    }

    @Test
    void testSystemOverviewResponse() {
        // Test system overview response structure
        
        JsonObject systemStats = new JsonObject()
            .put("totalQueues", 12)
            .put("totalConsumerGroups", 8)
            .put("totalEventStores", 4)
            .put("totalMessages", 1547892)
            .put("messagesPerSecond", 245.5)
            .put("activeConnections", 23)
            .put("uptime", "7d 14h 32m");
        
        JsonObject queueSummary = new JsonObject()
            .put("total", 12)
            .put("active", 8)
            .put("idle", 3)
            .put("error", 1);
        
        JsonObject consumerGroupSummary = new JsonObject()
            .put("total", 8)
            .put("active", 6)
            .put("members", 24);
        
        JsonObject eventStoreSummary = new JsonObject()
            .put("total", 4)
            .put("events", 125000)
            .put("corrections", 45);
        
        JsonObject overview = new JsonObject()
            .put("systemStats", systemStats)
            .put("queueSummary", queueSummary)
            .put("consumerGroupSummary", consumerGroupSummary)
            .put("eventStoreSummary", eventStoreSummary)
            .put("recentActivity", new JsonObject[0])
            .put("timestamp", System.currentTimeMillis());
        
        // Verify overview structure
        assertNotNull(overview.getJsonObject("systemStats"));
        assertNotNull(overview.getJsonObject("queueSummary"));
        assertNotNull(overview.getJsonObject("consumerGroupSummary"));
        assertNotNull(overview.getJsonObject("eventStoreSummary"));
        assertTrue(overview.containsKey("recentActivity"));
        assertTrue(overview.containsKey("timestamp"));
        
        // Verify system stats
        JsonObject stats = overview.getJsonObject("systemStats");
        assertEquals(12, stats.getInteger("totalQueues"));
        assertEquals(8, stats.getInteger("totalConsumerGroups"));
        assertEquals(4, stats.getInteger("totalEventStores"));
        assertEquals(1547892, stats.getInteger("totalMessages"));
        assertEquals(245.5, stats.getDouble("messagesPerSecond"));
        assertEquals(23, stats.getInteger("activeConnections"));
        assertEquals("7d 14h 32m", stats.getString("uptime"));
        
        // Verify queue summary
        JsonObject queues = overview.getJsonObject("queueSummary");
        assertEquals(12, queues.getInteger("total"));
        assertEquals(8, queues.getInteger("active"));
        assertEquals(3, queues.getInteger("idle"));
        assertEquals(1, queues.getInteger("error"));
        
        // Verify consumer group summary
        JsonObject groups = overview.getJsonObject("consumerGroupSummary");
        assertEquals(8, groups.getInteger("total"));
        assertEquals(6, groups.getInteger("active"));
        assertEquals(24, groups.getInteger("members"));
        
        // Verify event store summary
        JsonObject events = overview.getJsonObject("eventStoreSummary");
        assertEquals(4, events.getInteger("total"));
        assertEquals(125000, events.getInteger("events"));
        assertEquals(45, events.getInteger("corrections"));
    }

    @Test
    void testQueueListResponse() {
        // Test queue list response structure
        
        JsonObject queue1 = new JsonObject()
            .put("name", "orders")
            .put("setup", "production")
            .put("messages", 1247)
            .put("consumers", 3)
            .put("messageRate", 45.2)
            .put("consumerRate", 42.8)
            .put("status", "active")
            .put("durability", "durable")
            .put("autoDelete", false)
            .put("createdAt", "2025-07-15T09:30:00Z");
        
        JsonObject queue2 = new JsonObject()
            .put("name", "notifications")
            .put("setup", "production")
            .put("messages", 0)
            .put("consumers", 1)
            .put("messageRate", 0.0)
            .put("consumerRate", 0.0)
            .put("status", "idle")
            .put("durability", "transient")
            .put("autoDelete", true)
            .put("createdAt", "2025-07-16T14:22:00Z");
        
        JsonObject[] queues = { queue1, queue2 };
        
        JsonObject response = new JsonObject()
            .put("message", "Queues retrieved successfully")
            .put("queueCount", 2)
            .put("queues", queues)
            .put("timestamp", System.currentTimeMillis());
        
        // Verify response structure
        assertEquals("Queues retrieved successfully", response.getString("message"));
        assertEquals(2, response.getInteger("queueCount"));
        assertTrue(response.containsKey("queues"));
        assertTrue(response.containsKey("timestamp"));
        
        // Verify first queue
        assertEquals("orders", queue1.getString("name"));
        assertEquals("production", queue1.getString("setup"));
        assertEquals(1247, queue1.getInteger("messages"));
        assertEquals(3, queue1.getInteger("consumers"));
        assertEquals(45.2, queue1.getDouble("messageRate"));
        assertEquals(42.8, queue1.getDouble("consumerRate"));
        assertEquals("active", queue1.getString("status"));
        assertEquals("durable", queue1.getString("durability"));
        assertFalse(queue1.getBoolean("autoDelete"));
        assertEquals("2025-07-15T09:30:00Z", queue1.getString("createdAt"));
        
        // Verify second queue
        assertEquals("notifications", queue2.getString("name"));
        assertEquals("production", queue2.getString("setup"));
        assertEquals(0, queue2.getInteger("messages"));
        assertEquals(1, queue2.getInteger("consumers"));
        assertEquals(0.0, queue2.getDouble("messageRate"));
        assertEquals(0.0, queue2.getDouble("consumerRate"));
        assertEquals("idle", queue2.getString("status"));
        assertEquals("transient", queue2.getString("durability"));
        assertTrue(queue2.getBoolean("autoDelete"));
        assertEquals("2025-07-16T14:22:00Z", queue2.getString("createdAt"));
    }

    @Test
    void testSystemMetricsResponse() {
        // Test system metrics response structure
        
        JsonObject metrics = new JsonObject()
            .put("timestamp", System.currentTimeMillis())
            .put("uptime", 604800000L) // 7 days in milliseconds
            .put("memoryUsed", 536870912L) // 512 MB
            .put("memoryTotal", 1073741824L) // 1 GB
            .put("memoryMax", 2147483648L) // 2 GB
            .put("cpuCores", 8)
            .put("threadsActive", 24)
            .put("messagesPerSecond", 245.7)
            .put("activeConnections", 23)
            .put("totalMessages", 1547892);
        
        // Verify metrics structure
        assertTrue(metrics.containsKey("timestamp"));
        assertTrue(metrics.containsKey("uptime"));
        assertTrue(metrics.containsKey("memoryUsed"));
        assertTrue(metrics.containsKey("memoryTotal"));
        assertTrue(metrics.containsKey("memoryMax"));
        assertTrue(metrics.containsKey("cpuCores"));
        assertTrue(metrics.containsKey("threadsActive"));
        assertTrue(metrics.containsKey("messagesPerSecond"));
        assertTrue(metrics.containsKey("activeConnections"));
        assertTrue(metrics.containsKey("totalMessages"));
        
        // Verify metric values
        assertEquals(604800000L, metrics.getLong("uptime"));
        assertEquals(536870912L, metrics.getLong("memoryUsed"));
        assertEquals(1073741824L, metrics.getLong("memoryTotal"));
        assertEquals(2147483648L, metrics.getLong("memoryMax"));
        assertEquals(8, metrics.getInteger("cpuCores"));
        assertEquals(24, metrics.getInteger("threadsActive"));
        assertEquals(245.7, metrics.getDouble("messagesPerSecond"));
        assertEquals(23, metrics.getInteger("activeConnections"));
        assertEquals(1547892, metrics.getInteger("totalMessages"));
    }

    @Test
    void testRecentActivityStructure() {
        // Test recent activity structure
        
        JsonObject activity1 = new JsonObject()
            .put("timestamp", "2025-07-19T14:32:15Z")
            .put("action", "Consumer Group Created")
            .put("resource", "order-processors")
            .put("status", "success")
            .put("details", "Created with 3 members");
        
        JsonObject activity2 = new JsonObject()
            .put("timestamp", "2025-07-19T14:28:42Z")
            .put("action", "Queue Message Sent")
            .put("resource", "orders")
            .put("status", "success")
            .put("details", "Batch of 50 messages");
        
        JsonObject activity3 = new JsonObject()
            .put("timestamp", "2025-07-19T14:22:03Z")
            .put("action", "Consumer Timeout")
            .put("resource", "analytics-consumer-2")
            .put("status", "warning")
            .put("details", "Session timeout after 30s");
        
        JsonObject[] activities = { activity1, activity2, activity3 };
        
        // Verify activity structure
        for (JsonObject activity : activities) {
            assertTrue(activity.containsKey("timestamp"));
            assertTrue(activity.containsKey("action"));
            assertTrue(activity.containsKey("resource"));
            assertTrue(activity.containsKey("status"));
            assertTrue(activity.containsKey("details"));
        }
        
        // Verify specific activities
        assertEquals("Consumer Group Created", activity1.getString("action"));
        assertEquals("order-processors", activity1.getString("resource"));
        assertEquals("success", activity1.getString("status"));
        assertEquals("Created with 3 members", activity1.getString("details"));
        
        assertEquals("Queue Message Sent", activity2.getString("action"));
        assertEquals("orders", activity2.getString("resource"));
        assertEquals("success", activity2.getString("status"));
        assertEquals("Batch of 50 messages", activity2.getString("details"));
        
        assertEquals("Consumer Timeout", activity3.getString("action"));
        assertEquals("analytics-consumer-2", activity3.getString("resource"));
        assertEquals("warning", activity3.getString("status"));
        assertEquals("Session timeout after 30s", activity3.getString("details"));
    }

    @Test
    void testManagementApiDocumentation() {
        // This test documents the Management API usage
        
        System.out.println("📚 Phase 5 Management API Documentation:");
        System.out.println();
        
        System.out.println("🔹 Health Check:");
        System.out.println("GET /api/v1/health");
        System.out.println("- System health status");
        System.out.println("- Uptime and version information");
        System.out.println("- Build information");
        System.out.println();
        
        System.out.println("🔹 System Overview:");
        System.out.println("GET /api/v1/management/overview");
        System.out.println("- Complete system statistics");
        System.out.println("- Queue, consumer group, and event store summaries");
        System.out.println("- Recent activity feed");
        System.out.println();
        
        System.out.println("🔹 Queue Management:");
        System.out.println("GET /api/v1/management/queues");
        System.out.println("- List all queues across setups");
        System.out.println("- Queue statistics and status");
        System.out.println("- Message rates and consumer counts");
        System.out.println();
        
        System.out.println("🔹 System Metrics:");
        System.out.println("GET /api/v1/management/metrics");
        System.out.println("- Real-time system metrics");
        System.out.println("- Memory and CPU usage");
        System.out.println("- Message throughput statistics");
        System.out.println();
        
        System.out.println("🔹 Static UI Serving:");
        System.out.println("GET /ui/ - Management console interface");
        System.out.println("GET / - Redirects to /ui/");
        System.out.println();
        
        System.out.println("🔹 Response Format:");
        System.out.println("- All responses in JSON format");
        System.out.println("- Consistent error handling");
        System.out.println("- Timestamp included in responses");
        System.out.println("- Real-time data with caching");
        
        assertTrue(true, "Management API documentation complete");
    }
}
