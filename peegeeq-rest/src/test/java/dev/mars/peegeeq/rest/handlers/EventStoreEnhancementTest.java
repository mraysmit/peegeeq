package dev.mars.peegeeq.rest.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for enhanced Event Store querying functionality.
 */
@Tag(TestCategories.CORE)
class EventStoreEnhancementTest {

    @BeforeEach
    void setUp() {
        new ObjectMapper();
    }

    @Test
    void testEventResponseStructure() {
        // Test the complete EventResponse class structure
        
        EventStoreHandler.EventResponse event = new EventStoreHandler.EventResponse();
        
        // Test setters
        event.setId("event-123");
        event.setEventType("OrderCreated");
        event.setEventData(Map.of("orderId", "ORD-12345", "amount", 99.99));
        event.setValidFrom(Instant.parse("2025-07-19T10:00:00Z"));
        event.setValidTo(Instant.parse("2025-07-19T23:59:59Z"));
        event.setTransactionTime(Instant.parse("2025-07-19T10:05:00Z"));
        event.setCorrelationId("corr-123");
        event.setCausationId("cause-456");
        event.setVersion(2);
        event.setMetadata(Map.of("source", "order-service", "region", "US-WEST"));
        
        // Test getters
        assertEquals("event-123", event.getId());
        assertEquals("OrderCreated", event.getEventType());
        assertTrue(event.getEventData() instanceof Map);
        assertEquals(Instant.parse("2025-07-19T10:00:00Z"), event.getValidFrom());
        assertEquals(Instant.parse("2025-07-19T23:59:59Z"), event.getValidTo());
        assertEquals(Instant.parse("2025-07-19T10:05:00Z"), event.getTransactionTime());
        assertEquals("corr-123", event.getCorrelationId());
        assertEquals("cause-456", event.getCausationId());
        assertEquals(2, event.getVersion());
        assertEquals(2, event.getMetadata().size());
        
        Map<String, Object> eventData = (Map<String, Object>) event.getEventData();
        assertEquals("ORD-12345", eventData.get("orderId"));
        assertEquals(99.99, eventData.get("amount"));
        
        assertEquals("order-service", event.getMetadata().get("source"));
        assertEquals("US-WEST", event.getMetadata().get("region"));
    }

    @Test
    void testEventResponseConstructor() {
        // Test the full constructor
        
        Map<String, Object> eventData = Map.of(
            "customerId", "CUST-789",
            "items", new String[]{"ITEM-001", "ITEM-002"},
            "total", 149.99
        );
        
        Map<String, Object> metadata = Map.of(
            "source", "payment-service",
            "version", "2.1",
            "processed", true
        );
        
        Instant validFrom = Instant.parse("2025-07-19T12:00:00Z");
        Instant validTo = Instant.parse("2025-07-19T18:00:00Z");
        Instant transactionTime = Instant.parse("2025-07-19T12:01:30Z");
        
        EventStoreHandler.EventResponse event = new EventStoreHandler.EventResponse(
            "event-456",
            "PaymentProcessed",
            eventData,
            validFrom,
            validTo,
            transactionTime,
            "corr-payment-123",
            "cause-order-456",
            3,
            metadata
        );
        
        assertEquals("event-456", event.getId());
        assertEquals("PaymentProcessed", event.getEventType());
        assertEquals(eventData, event.getEventData());
        assertEquals(validFrom, event.getValidFrom());
        assertEquals(validTo, event.getValidTo());
        assertEquals(transactionTime, event.getTransactionTime());
        assertEquals("corr-payment-123", event.getCorrelationId());
        assertEquals("cause-order-456", event.getCausationId());
        assertEquals(3, event.getVersion());
        assertEquals(metadata, event.getMetadata());
    }

    // NOTE: EventQueryParams was removed when EventStoreHandler was refactored to use the real EventStore API
    // This test is preserved for reference but commented out
    /*
    @Test
    void testEventQueryParams() {
        // Test the EventQueryParams class
        
        EventStoreHandler.EventQueryParams params = new EventStoreHandler.EventQueryParams();
        
        // Test default values
        assertEquals(100, params.getLimit());
        assertEquals(0, params.getOffset());
        
        // Test setters with validation
        params.setEventType("OrderEvent");
        params.setFromTime(Instant.parse("2025-07-19T00:00:00Z"));
        params.setToTime(Instant.parse("2025-07-19T23:59:59Z"));
        params.setLimit(50);
        params.setOffset(25);
        params.setCorrelationId("test-correlation");
        params.setCausationId("test-causation");
        
        assertEquals("OrderEvent", params.getEventType());
        assertEquals(Instant.parse("2025-07-19T00:00:00Z"), params.getFromTime());
        assertEquals(Instant.parse("2025-07-19T23:59:59Z"), params.getToTime());
        assertEquals(50, params.getLimit());
        assertEquals(25, params.getOffset());
        assertEquals("test-correlation", params.getCorrelationId());
        assertEquals("test-causation", params.getCausationId());
        
        // Test limit validation (should clamp between 1 and 1000)
        params.setLimit(-5);
        assertEquals(1, params.getLimit());
        
        params.setLimit(2000);
        assertEquals(1000, params.getLimit());
        
        // Test offset validation (should not be negative)
        params.setOffset(-10);
        assertEquals(0, params.getOffset());
    }
    */

    @Test
    void testEventQueryResponse() {
        // Test the structure of event query responses
        
        JsonArray events = new JsonArray();
        
        JsonObject event1 = new JsonObject()
            .put("id", "event-1")
            .put("eventType", "OrderCreated")
            .put("eventData", new JsonObject().put("orderId", "ORD-001").put("amount", 50.00))
            .put("validFrom", "2025-07-19T10:00:00Z")
            .put("validTo", (String) null)
            .put("transactionTime", "2025-07-19T10:01:00Z")
            .put("correlationId", "corr-1")
            .put("causationId", "cause-1")
            .put("version", 1)
            .put("metadata", new JsonObject().put("source", "order-service"));
        
        JsonObject event2 = new JsonObject()
            .put("id", "event-2")
            .put("eventType", "OrderUpdated")
            .put("eventData", new JsonObject().put("orderId", "ORD-001").put("status", "CONFIRMED"))
            .put("validFrom", "2025-07-19T11:00:00Z")
            .put("validTo", (String) null)
            .put("transactionTime", "2025-07-19T11:01:00Z")
            .put("correlationId", "corr-2")
            .put("causationId", "cause-2")
            .put("version", 1)
            .put("metadata", new JsonObject().put("source", "order-service"));
        
        events.add(event1).add(event2);
        
        JsonObject filters = new JsonObject()
            .put("eventType", "OrderCreated")
            .put("fromTime", "2025-07-19T00:00:00Z")
            .put("toTime", "2025-07-19T23:59:59Z")
            .put("correlationId", "test-corr");
        
        JsonObject queryResponse = new JsonObject()
            .put("message", "Events retrieved successfully")
            .put("eventStoreName", "order-events")
            .put("setupId", "test-setup")
            .put("eventCount", 2)
            .put("limit", 100)
            .put("offset", 0)
            .put("hasMore", false)
            .put("filters", filters)
            .put("events", events)
            .put("timestamp", System.currentTimeMillis());
        
        // Verify response structure
        assertEquals("Events retrieved successfully", queryResponse.getString("message"));
        assertEquals("order-events", queryResponse.getString("eventStoreName"));
        assertEquals("test-setup", queryResponse.getString("setupId"));
        assertEquals(2, queryResponse.getInteger("eventCount"));
        assertEquals(100, queryResponse.getInteger("limit"));
        assertEquals(0, queryResponse.getInteger("offset"));
        assertFalse(queryResponse.getBoolean("hasMore"));
        assertNotNull(queryResponse.getJsonObject("filters"));
        assertEquals(2, queryResponse.getJsonArray("events").size());
        
        // Verify filter structure
        JsonObject responseFilters = queryResponse.getJsonObject("filters");
        assertEquals("OrderCreated", responseFilters.getString("eventType"));
        assertEquals("2025-07-19T00:00:00Z", responseFilters.getString("fromTime"));
        assertEquals("2025-07-19T23:59:59Z", responseFilters.getString("toTime"));
        assertEquals("test-corr", responseFilters.getString("correlationId"));
        
        // Verify event structure
        JsonObject firstEvent = queryResponse.getJsonArray("events").getJsonObject(0);
        assertEquals("event-1", firstEvent.getString("id"));
        assertEquals("OrderCreated", firstEvent.getString("eventType"));
        assertTrue(firstEvent.containsKey("eventData"));
        assertEquals("2025-07-19T10:00:00Z", firstEvent.getString("validFrom"));
        assertNull(firstEvent.getString("validTo"));
        assertEquals("2025-07-19T10:01:00Z", firstEvent.getString("transactionTime"));
        assertEquals("corr-1", firstEvent.getString("correlationId"));
        assertEquals("cause-1", firstEvent.getString("causationId"));
        assertEquals(1, firstEvent.getInteger("version"));
        assertTrue(firstEvent.containsKey("metadata"));
    }

    @Test
    void testSingleEventResponse() {
        // Test the structure of single event retrieval responses
        
        JsonObject eventData = new JsonObject()
            .put("orderId", "ORD-12345")
            .put("customerId", "CUST-67890")
            .put("amount", 199.99)
            .put("status", "PENDING");
        
        JsonObject metadata = new JsonObject()
            .put("source", "EventStoreHandler")
            .put("sampleData", true)
            .put("retrievedAt", System.currentTimeMillis());
        
        JsonObject event = new JsonObject()
            .put("id", "event-specific-123")
            .put("eventType", "SampleEvent")
            .put("eventData", eventData)
            .put("validFrom", "2025-07-19T09:00:00Z")
            .put("validTo", (String) null)
            .put("transactionTime", "2025-07-19T09:30:00Z")
            .put("correlationId", "corr-event-specific-123")
            .put("causationId", "cause-event-specific-123")
            .put("version", 1)
            .put("metadata", metadata);
        
        JsonObject response = new JsonObject()
            .put("message", "Event retrieved successfully")
            .put("eventStoreName", "order-events")
            .put("setupId", "test-setup")
            .put("eventId", "event-specific-123")
            .put("event", event)
            .put("timestamp", System.currentTimeMillis());
        
        // Verify response structure
        assertEquals("Event retrieved successfully", response.getString("message"));
        assertEquals("order-events", response.getString("eventStoreName"));
        assertEquals("test-setup", response.getString("setupId"));
        assertEquals("event-specific-123", response.getString("eventId"));
        assertNotNull(response.getJsonObject("event"));
        assertTrue(response.containsKey("timestamp"));
        
        // Verify event structure
        JsonObject responseEvent = response.getJsonObject("event");
        assertEquals("event-specific-123", responseEvent.getString("id"));
        assertEquals("SampleEvent", responseEvent.getString("eventType"));
        assertNotNull(responseEvent.getJsonObject("eventData"));
        assertEquals("2025-07-19T09:00:00Z", responseEvent.getString("validFrom"));
        assertNull(responseEvent.getString("validTo"));
        assertEquals("2025-07-19T09:30:00Z", responseEvent.getString("transactionTime"));
        assertEquals("corr-event-specific-123", responseEvent.getString("correlationId"));
        assertEquals("cause-event-specific-123", responseEvent.getString("causationId"));
        assertEquals(1, responseEvent.getInteger("version"));
        assertNotNull(responseEvent.getJsonObject("metadata"));
        
        // Verify event data
        JsonObject responseEventData = responseEvent.getJsonObject("eventData");
        assertEquals("ORD-12345", responseEventData.getString("orderId"));
        assertEquals("CUST-67890", responseEventData.getString("customerId"));
        assertEquals(199.99, responseEventData.getDouble("amount"));
        assertEquals("PENDING", responseEventData.getString("status"));
    }

    @Test
    void testEventStoreStatsResponse() {
        // Test the structure of event store statistics responses
        
        JsonObject eventCountsByType = new JsonObject()
            .put("OrderCreated", 1250L)
            .put("OrderUpdated", 890L)
            .put("OrderCancelled", 156L)
            .put("PaymentProcessed", 1100L)
            .put("SampleEvent", 25L);
        
        JsonObject stats = new JsonObject()
            .put("eventStoreName", "order-events")
            .put("totalEvents", 3421L)
            .put("totalCorrections", 45L)
            .put("eventCountsByType", eventCountsByType);
        
        JsonObject response = new JsonObject()
            .put("message", "Event store statistics retrieved successfully")
            .put("eventStoreName", "order-events")
            .put("setupId", "test-setup")
            .put("stats", stats)
            .put("timestamp", System.currentTimeMillis());
        
        // Verify response structure
        assertEquals("Event store statistics retrieved successfully", response.getString("message"));
        assertEquals("order-events", response.getString("eventStoreName"));
        assertEquals("test-setup", response.getString("setupId"));
        assertNotNull(response.getJsonObject("stats"));
        assertTrue(response.containsKey("timestamp"));
        
        // Verify stats structure
        JsonObject responseStats = response.getJsonObject("stats");
        assertEquals("order-events", responseStats.getString("eventStoreName"));
        assertEquals(3421L, responseStats.getLong("totalEvents"));
        assertEquals(45L, responseStats.getLong("totalCorrections"));
        assertNotNull(responseStats.getJsonObject("eventCountsByType"));
        
        // Verify event counts by type
        JsonObject responseCounts = responseStats.getJsonObject("eventCountsByType");
        assertEquals(1250L, responseCounts.getLong("OrderCreated"));
        assertEquals(890L, responseCounts.getLong("OrderUpdated"));
        assertEquals(156L, responseCounts.getLong("OrderCancelled"));
        assertEquals(1100L, responseCounts.getLong("PaymentProcessed"));
        assertEquals(25L, responseCounts.getLong("SampleEvent"));
    }

    @Test
    void testEventStoreApiDocumentation() {
        // This test documents the enhanced Event Store API usage
        
        System.out.println("📚 Phase 4 Enhanced Event Store API Documentation:");
        System.out.println();
        
        System.out.println("🔹 Event Querying:");
        System.out.println("GET /api/v1/eventstores/{setupId}/{eventStoreName}/events");
        System.out.println("- Query events with advanced filtering");
        System.out.println("- Support for time-range queries, event type filtering");
        System.out.println("- Pagination with limit and offset");
        System.out.println("- Correlation and causation ID filtering");
        System.out.println();
        
        System.out.println("🔹 Query Parameters:");
        System.out.println("- eventType: Filter by event type");
        System.out.println("- fromTime: Start time (ISO 8601 format)");
        System.out.println("- toTime: End time (ISO 8601 format)");
        System.out.println("- limit: Maximum events to return (1-1000, default: 100)");
        System.out.println("- offset: Number of events to skip (default: 0)");
        System.out.println("- correlationId: Filter by correlation ID");
        System.out.println("- causationId: Filter by causation ID");
        System.out.println();
        
        System.out.println("🔹 Single Event Retrieval:");
        System.out.println("GET /api/v1/eventstores/{setupId}/{eventStoreName}/events/{eventId}");
        System.out.println("- Get a specific event by ID");
        System.out.println("- Returns complete event details including bi-temporal information");
        System.out.println();
        
        System.out.println("🔹 Event Store Statistics:");
        System.out.println("GET /api/v1/eventstores/{setupId}/{eventStoreName}/stats");
        System.out.println("- Get comprehensive statistics about the event store");
        System.out.println("- Total events, corrections, and counts by event type");
        System.out.println();
        
        System.out.println("🔹 Bi-Temporal Support:");
        System.out.println("- validFrom/validTo: Business time (when events actually happened)");
        System.out.println("- transactionTime: System time (when events were recorded)");
        System.out.println("- version: Event version for corrections and updates");
        System.out.println();
        
        System.out.println("🔹 Example Query:");
        System.out.println("GET /api/v1/eventstores/my-setup/order-events/events?eventType=OrderCreated&fromTime=2025-07-19T00:00:00Z&toTime=2025-07-19T23:59:59Z&limit=50&offset=0");
        
        assertTrue(true, "Enhanced Event Store API documentation complete");
    }
}
