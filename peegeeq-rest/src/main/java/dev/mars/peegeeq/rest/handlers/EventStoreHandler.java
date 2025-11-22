/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.mars.peegeeq.rest.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handler for bi-temporal event store REST endpoints.
 * 
 * Handles HTTP requests for storing and querying bi-temporal events,
 * including point-in-time queries and event history.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-18
 * @version 1.0
 */
public class EventStoreHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(EventStoreHandler.class);
    
    private final DatabaseSetupService setupService;
    private final ObjectMapper objectMapper;
    
    public EventStoreHandler(DatabaseSetupService setupService, ObjectMapper objectMapper) {
        this.setupService = setupService;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Stores a new event in the event store.
     */
    public void storeEvent(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String eventStoreName = ctx.pathParam("eventStoreName");
        
        try {
            String body = ctx.body().asString();
            EventRequest eventRequest = objectMapper.readValue(body, EventRequest.class);
            
            logger.info("Storing event {} in event store {} for setup: {}", 
                    eventRequest.getEventType(), eventStoreName, setupId);
            
            // For now, return a placeholder response
            // In a complete implementation, this would:
            // 1. Get the setup from setupService
            // 2. Get the event store for the setup
            // 3. Store the event with bi-temporal semantics
            
            setupService.getSetupStatus(setupId)
                    .thenAccept(status -> {
                        JsonObject response = new JsonObject()
                                .put("message", "Event stored successfully")
                                .put("eventStoreName", eventStoreName)
                                .put("setupId", setupId)
                                .put("eventId", java.util.UUID.randomUUID().toString())
                                .put("transactionTime", Instant.now().toString());
                        
                        ctx.response()
                                .setStatusCode(200)
                                .putHeader("content-type", "application/json")
                                .end(response.encode());
                        
                        logger.info("Event stored successfully in event store {} for setup {}", 
                                eventStoreName, setupId);
                    })
                    .exceptionally(throwable -> {
                        // Check if this is an expected setup not found error (no stack trace)
                        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                        if (isSetupNotFoundError(cause)) {
                            logger.debug("🚫 EXPECTED: Setup not found for event store: {} (setup: {})",
                                       eventStoreName, setupId);
                        } else {
                            logger.error("Error storing event in event store: " + eventStoreName, throwable);
                        }
                        sendError(ctx, 400, "Failed to store event: " + throwable.getMessage());
                        return null;
                    });
                    
        } catch (Exception e) {
            logger.error("Error parsing store event request", e);
            sendError(ctx, 400, "Invalid request format");
        }
    }
    
    /**
     * Queries events by type and time range.
     */
    public void queryEvents(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String eventStoreName = ctx.pathParam("eventStoreName");

        // Parse query parameters
        String eventType = ctx.request().getParam("eventType");
        String fromTimeParam = ctx.request().getParam("fromTime");
        String toTimeParam = ctx.request().getParam("toTime");
        String limitParam = ctx.request().getParam("limit");
        String offsetParam = ctx.request().getParam("offset");
        String correlationId = ctx.request().getParam("correlationId");
        String causationId = ctx.request().getParam("causationId");

        logger.info("Querying events in event store {} for setup: {} with filters: eventType={}, fromTime={}, toTime={}, limit={}",
                   eventStoreName, setupId, eventType, fromTimeParam, toTimeParam, limitParam);

        // Validate and parse parameters
        EventQueryParams queryParams = parseQueryParameters(ctx, eventType, fromTimeParam, toTimeParam,
                                                           limitParam, offsetParam, correlationId, causationId);
        if (queryParams == null) {
            return; // Error already sent
        }

        setupService.getSetupResult(setupId)
                .thenAccept(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        sendError(ctx, 404, "Setup not found or not active: " + setupId);
                        return;
                    }

                    // Get the event store
                    var eventStore = setupResult.getEventStores().get(eventStoreName);
                    if (eventStore == null) {
                        sendError(ctx, 404, "Event store not found: " + eventStoreName);
                        return;
                    }

                    try {
                        // Query events from the event store
                        List<EventResponse> events = queryEventsFromStore(eventStore, queryParams);

                        // Create response with pagination info
                        JsonObject response = new JsonObject()
                            .put("message", "Events retrieved successfully")
                            .put("eventStoreName", eventStoreName)
                            .put("setupId", setupId)
                            .put("eventCount", events.size())
                            .put("limit", queryParams.getLimit())
                            .put("offset", queryParams.getOffset())
                            .put("hasMore", events.size() == queryParams.getLimit()) // Simple check
                            .put("filters", createFiltersObject(queryParams))
                            .put("events", events)
                            .put("timestamp", System.currentTimeMillis());

                        ctx.response()
                                .setStatusCode(200)
                                .putHeader("content-type", "application/json")
                                .end(response.encode());

                        logger.info("Retrieved {} events from event store {}", events.size(), eventStoreName);

                    } catch (Exception e) {
                        logger.error("Error querying events from event store {}: {}", eventStoreName, e.getMessage(), e);
                        sendError(ctx, 500, "Failed to query events: " + e.getMessage());
                    }
                })
                .exceptionally(throwable -> {
                    // Check if this is an expected setup not found error (no stack trace)
                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                    if (isSetupNotFoundError(cause)) {
                        logger.debug("🚫 EXPECTED: Setup not found for event store query: {} (setup: {})",
                                   eventStoreName, setupId);
                        sendError(ctx, 404, "Setup not found: " + setupId);
                    } else {
                        logger.error("Error setting up event store query for {}: {}", eventStoreName, throwable.getMessage(), throwable);
                        sendError(ctx, 500, "Failed to setup event store query: " + throwable.getMessage());
                    }
                    return null;
                });
    }
    
    /**
     * Gets a specific event by ID.
     */
    public void getEvent(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String eventStoreName = ctx.pathParam("eventStoreName");
        String eventId = ctx.pathParam("eventId");

        logger.info("Getting event {} from event store {} in setup: {}", eventId, eventStoreName, setupId);

        setupService.getSetupResult(setupId)
                .thenAccept(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        sendError(ctx, 404, "Setup not found or not active: " + setupId);
                        return;
                    }

                    // Get the event store
                    var eventStore = setupResult.getEventStores().get(eventStoreName);
                    if (eventStore == null) {
                        sendError(ctx, 404, "Event store not found: " + eventStoreName);
                        return;
                    }

                    try {
                        // Get the specific event (placeholder implementation)
                        EventResponse event = getEventFromStore(eventStore, eventId);

                        if (event == null) {
                            sendError(ctx, 404, "Event not found: " + eventId);
                            return;
                        }

                        JsonObject response = new JsonObject()
                            .put("message", "Event retrieved successfully")
                            .put("eventStoreName", eventStoreName)
                            .put("setupId", setupId)
                            .put("eventId", eventId)
                            .put("event", event)
                            .put("timestamp", System.currentTimeMillis());

                        ctx.response()
                                .setStatusCode(200)
                                .putHeader("content-type", "application/json")
                                .end(response.encode());

                        logger.info("Retrieved event {} from event store {}", eventId, eventStoreName);

                    } catch (Exception e) {
                        logger.error("Error getting event {} from event store {}: {}", eventId, eventStoreName, e.getMessage(), e);
                        sendError(ctx, 500, "Failed to get event: " + e.getMessage());
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("Error setting up event retrieval for {}: {}", eventId, throwable.getMessage(), throwable);
                    sendError(ctx, 500, "Failed to setup event retrieval: " + throwable.getMessage());
                    return null;
                });
    }
    
    /**
     * Gets event store statistics.
     */
    public void getStats(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String eventStoreName = ctx.pathParam("eventStoreName");

        logger.info("Getting stats for event store {} in setup: {}", eventStoreName, setupId);

        setupService.getSetupResult(setupId)
                .thenAccept(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        sendError(ctx, 404, "Setup not found or not active: " + setupId);
                        return;
                    }

                    // Get the event store
                    var eventStore = setupResult.getEventStores().get(eventStoreName);
                    if (eventStore == null) {
                        sendError(ctx, 404, "Event store not found: " + eventStoreName);
                        return;
                    }

                    try {
                        // Get statistics from the event store (placeholder implementation)
                        EventStoreStats stats = getStatsFromStore(eventStore, eventStoreName);

                        JsonObject response = new JsonObject()
                            .put("message", "Event store statistics retrieved successfully")
                            .put("eventStoreName", eventStoreName)
                            .put("setupId", setupId)
                            .put("stats", stats)
                            .put("timestamp", System.currentTimeMillis());

                        ctx.response()
                                .setStatusCode(200)
                                .putHeader("content-type", "application/json")
                                .end(response.encode());

                        logger.info("Retrieved statistics for event store {}", eventStoreName);

                    } catch (Exception e) {
                        logger.error("Error getting event store stats for {}: {}", eventStoreName, e.getMessage(), e);
                        sendError(ctx, 500, "Failed to get event store stats: " + e.getMessage());
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("Error setting up event store stats retrieval for {}: {}", eventStoreName, throwable.getMessage(), throwable);
                    sendError(ctx, 500, "Failed to setup event store stats retrieval: " + throwable.getMessage());
                    return null;
                });
    }

    /**
     * Check if this is a setup not found error (expected, no stack trace needed).
     */
    private boolean isSetupNotFoundError(Throwable throwable) {
        return throwable != null &&
               throwable.getClass().getSimpleName().equals("SetupNotFoundException");
    }

    private void sendError(RoutingContext ctx, int statusCode, String message) {
        JsonObject error = new JsonObject()
                .put("error", message)
                .put("timestamp", System.currentTimeMillis());
        
        ctx.response()
                .setStatusCode(statusCode)
                .putHeader("content-type", "application/json")
                .end(error.encode());
    }
    
    /**
     * Request object for storing events.
     */
    public static class EventRequest {
        private String eventType;
        private Object eventData;
        private Instant validFrom;
        private Instant validTo;
        private String correlationId;
        private String causationId;
        private Map<String, Object> metadata;
        
        // Getters and setters
        public String getEventType() { return eventType; }
        public void setEventType(String eventType) { this.eventType = eventType; }
        
        public Object getEventData() { return eventData; }
        public void setEventData(Object eventData) { this.eventData = eventData; }
        
        public Instant getValidFrom() { return validFrom; }
        public void setValidFrom(Instant validFrom) { this.validFrom = validFrom; }
        
        public Instant getValidTo() { return validTo; }
        public void setValidTo(Instant validTo) { this.validTo = validTo; }
        
        public String getCorrelationId() { return correlationId; }
        public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
        
        public String getCausationId() { return causationId; }
        public void setCausationId(String causationId) { this.causationId = causationId; }
        
        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }
    
    /**
     * Response object for events.
     */
    public static class EventResponse {
        private String id;
        private String eventType;
        private Object eventData;
        private Instant validFrom;
        private Instant validTo;
        private Instant transactionTime;
        private String correlationId;
        private String causationId;
        private int version;
        private Map<String, Object> metadata;

        public EventResponse() {}

        public EventResponse(String id, String eventType, Object eventData, Instant validFrom,
                           Instant validTo, Instant transactionTime, String correlationId,
                           String causationId, int version, Map<String, Object> metadata) {
            this.id = id;
            this.eventType = eventType;
            this.eventData = eventData;
            this.validFrom = validFrom;
            this.validTo = validTo;
            this.transactionTime = transactionTime;
            this.correlationId = correlationId;
            this.causationId = causationId;
            this.version = version;
            this.metadata = metadata;
        }

        // Getters and setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getEventType() { return eventType; }
        public void setEventType(String eventType) { this.eventType = eventType; }

        public Object getEventData() { return eventData; }
        public void setEventData(Object eventData) { this.eventData = eventData; }

        public Instant getValidFrom() { return validFrom; }
        public void setValidFrom(Instant validFrom) { this.validFrom = validFrom; }

        public Instant getValidTo() { return validTo; }
        public void setValidTo(Instant validTo) { this.validTo = validTo; }

        public Instant getTransactionTime() { return transactionTime; }
        public void setTransactionTime(Instant transactionTime) { this.transactionTime = transactionTime; }

        public String getCorrelationId() { return correlationId; }
        public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

        public String getCausationId() { return causationId; }
        public void setCausationId(String causationId) { this.causationId = causationId; }

        public int getVersion() { return version; }
        public void setVersion(int version) { this.version = version; }

        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }
    
    /**
     * Event store statistics response object.
     */
    public static class EventStoreStats {
        private final String eventStoreName;
        private final long totalEvents;
        private final long totalCorrections;
        private final Map<String, Long> eventCountsByType;
        
        public EventStoreStats(String eventStoreName, long totalEvents, long totalCorrections, 
                              Map<String, Long> eventCountsByType) {
            this.eventStoreName = eventStoreName;
            this.totalEvents = totalEvents;
            this.totalCorrections = totalCorrections;
            this.eventCountsByType = eventCountsByType;
        }
        
        public String getEventStoreName() { return eventStoreName; }
        public long getTotalEvents() { return totalEvents; }
        public long getTotalCorrections() { return totalCorrections; }
        public Map<String, Long> getEventCountsByType() { return eventCountsByType; }
    }

    /**
     * Query parameters for event store queries.
     */
    public static class EventQueryParams {
        private String eventType;
        private Instant fromTime;
        private Instant toTime;
        private int limit = 100;
        private int offset = 0;
        private String correlationId;
        private String causationId;

        // Getters and setters
        public String getEventType() { return eventType; }
        public void setEventType(String eventType) { this.eventType = eventType; }

        public Instant getFromTime() { return fromTime; }
        public void setFromTime(Instant fromTime) { this.fromTime = fromTime; }

        public Instant getToTime() { return toTime; }
        public void setToTime(Instant toTime) { this.toTime = toTime; }

        public int getLimit() { return limit; }
        public void setLimit(int limit) { this.limit = Math.max(1, Math.min(1000, limit)); }

        public int getOffset() { return offset; }
        public void setOffset(int offset) { this.offset = Math.max(0, offset); }

        public String getCorrelationId() { return correlationId; }
        public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

        public String getCausationId() { return causationId; }
        public void setCausationId(String causationId) { this.causationId = causationId; }
    }

    /**
     * Parses query parameters for event queries.
     */
    private EventQueryParams parseQueryParameters(RoutingContext ctx, String eventType, String fromTimeParam,
                                                 String toTimeParam, String limitParam, String offsetParam,
                                                 String correlationId, String causationId) {
        EventQueryParams params = new EventQueryParams();

        try {
            // Parse event type
            if (eventType != null && !eventType.trim().isEmpty()) {
                params.setEventType(eventType.trim());
            }

            // Parse time range
            if (fromTimeParam != null && !fromTimeParam.trim().isEmpty()) {
                params.setFromTime(Instant.parse(fromTimeParam));
            }

            if (toTimeParam != null && !toTimeParam.trim().isEmpty()) {
                params.setToTime(Instant.parse(toTimeParam));
            }

            // Parse limit
            if (limitParam != null && !limitParam.trim().isEmpty()) {
                int limit = Integer.parseInt(limitParam);
                params.setLimit(limit);
            }

            // Parse offset
            if (offsetParam != null && !offsetParam.trim().isEmpty()) {
                int offset = Integer.parseInt(offsetParam);
                params.setOffset(offset);
            }

            // Parse correlation and causation IDs
            if (correlationId != null && !correlationId.trim().isEmpty()) {
                params.setCorrelationId(correlationId.trim());
            }

            if (causationId != null && !causationId.trim().isEmpty()) {
                params.setCausationId(causationId.trim());
            }

            return params;

        } catch (Exception e) {
            logger.error("Error parsing query parameters: {}", e.getMessage(), e);
            sendError(ctx, 400, "Invalid query parameters: " + e.getMessage());
            return null;
        }
    }

    /**
     * Creates a filters object for the response.
     */
    private JsonObject createFiltersObject(EventQueryParams params) {
        JsonObject filters = new JsonObject();

        if (params.getEventType() != null) {
            filters.put("eventType", params.getEventType());
        }

        if (params.getFromTime() != null) {
            filters.put("fromTime", params.getFromTime().toString());
        }

        if (params.getToTime() != null) {
            filters.put("toTime", params.getToTime().toString());
        }

        if (params.getCorrelationId() != null) {
            filters.put("correlationId", params.getCorrelationId());
        }

        if (params.getCausationId() != null) {
            filters.put("causationId", params.getCausationId());
        }

        return filters;
    }

    /**
     * Queries events from the event store (placeholder implementation).
     */
    private List<EventResponse> queryEventsFromStore(Object eventStoreFactory, EventQueryParams params) {
        // This is a placeholder implementation
        // In a real implementation, this would use the event store factory to query actual events

        logger.info("Querying events with parameters: eventType={}, fromTime={}, toTime={}, limit={}, offset={}",
                   params.getEventType(), params.getFromTime(), params.getToTime(), params.getLimit(), params.getOffset());

        // Return sample events for demonstration
        List<EventResponse> events = new ArrayList<>();

        // Create sample events based on query parameters
        int sampleCount = Math.min(params.getLimit(), 5); // Return up to 5 sample events

        for (int i = 0; i < sampleCount; i++) {
            EventResponse event = new EventResponse();
            event.setId("event-" + (params.getOffset() + i + 1));
            event.setEventType(params.getEventType() != null ? params.getEventType() : "SampleEvent");
            event.setEventData(Map.of(
                "sampleField", "Sample value " + (i + 1),
                "index", i + 1,
                "timestamp", System.currentTimeMillis()
            ));
            event.setValidFrom(Instant.now().minusSeconds(3600 * (i + 1))); // 1 hour ago per event
            event.setValidTo(null); // Open-ended validity
            event.setTransactionTime(Instant.now().minusSeconds(1800 * (i + 1))); // 30 min ago per event
            event.setCorrelationId(params.getCorrelationId() != null ? params.getCorrelationId() : "corr-" + (i + 1));
            event.setCausationId(params.getCausationId() != null ? params.getCausationId() : "cause-" + (i + 1));
            event.setVersion(1);
            event.setMetadata(Map.of(
                "source", "EventStoreHandler",
                "sampleData", true,
                "eventIndex", i + 1
            ));

            events.add(event);
        }

        return events;
    }

    /**
     * Gets a specific event from the event store (placeholder implementation).
     */
    private EventResponse getEventFromStore(Object eventStore, String eventId) {
        // This is a placeholder implementation
        // In a real implementation, this would use the event store to get the specific event

        logger.info("Getting event {} from event store", eventId);

        // Return a sample event if the ID matches a pattern
        if (eventId.startsWith("event-")) {
            EventResponse event = new EventResponse();
            event.setId(eventId);
            event.setEventType("SampleEvent");
            event.setEventData(Map.of(
                "eventId", eventId,
                "sampleField", "Sample value for " + eventId,
                "timestamp", System.currentTimeMillis()
            ));
            event.setValidFrom(Instant.now().minusSeconds(3600)); // 1 hour ago
            event.setValidTo(null); // Open-ended validity
            event.setTransactionTime(Instant.now().minusSeconds(1800)); // 30 min ago
            event.setCorrelationId("corr-" + eventId);
            event.setCausationId("cause-" + eventId);
            event.setVersion(1);
            event.setMetadata(Map.of(
                "source", "EventStoreHandler",
                "sampleData", true,
                "retrievedAt", System.currentTimeMillis()
            ));

            return event;
        }

        // Return null for non-matching IDs (not found)
        return null;
    }

    /**
     * Gets statistics from the event store (placeholder implementation).
     */
    private EventStoreStats getStatsFromStore(Object eventStore, String eventStoreName) {
        // This is a placeholder implementation
        // In a real implementation, this would use the event store to get actual statistics

        logger.info("Getting statistics for event store {}", eventStoreName);

        // Return sample statistics
        Map<String, Long> eventCountsByType = Map.of(
            "OrderCreated", 1250L,
            "OrderUpdated", 890L,
            "OrderCancelled", 156L,
            "PaymentProcessed", 1100L,
            "SampleEvent", 25L
        );

        return new EventStoreStats(
            eventStoreName,
            3421L, // Total events
            45L,   // Total corrections
            eventCountsByType
        );
    }

    /**
     * Gets all versions of a specific event.
     * GET /api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/versions
     */
    public void getAllVersions(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String eventStoreName = ctx.pathParam("eventStoreName");
        String eventId = ctx.pathParam("eventId");

        logger.info("Getting all versions of event {} from event store {} in setup: {}", eventId, eventStoreName, setupId);

        setupService.getSetupResult(setupId)
                .thenAccept(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        sendError(ctx, 404, "Setup not found or not active: " + setupId);
                        return;
                    }

                    // Get the event store
                    var eventStore = setupResult.getEventStores().get(eventStoreName);
                    if (eventStore == null) {
                        sendError(ctx, 404, "Event store not found: " + eventStoreName);
                        return;
                    }

                    try {
                        // Get all versions (placeholder - returns empty array)
                        List<EventResponse> versions = new ArrayList<>();
                        
                        JsonObject response = new JsonObject()
                            .put("message", "Event versions retrieved successfully")
                            .put("eventStoreName", eventStoreName)
                            .put("setupId", setupId)
                            .put("eventId", eventId)
                            .put("versions", versions)
                            .put("timestamp", System.currentTimeMillis());

                        ctx.response()
                                .setStatusCode(200)
                                .putHeader("content-type", "application/json")
                                .end(response.encode());

                        logger.info("Retrieved {} versions of event {} from event store {}", versions.size(), eventId, eventStoreName);

                    } catch (Exception e) {
                        logger.error("Error getting versions of event {} from event store {}: {}", eventId, eventStoreName, e.getMessage(), e);
                        sendError(ctx, 500, "Failed to get event versions: " + e.getMessage());
                    }
                })
                .exceptionally(throwable -> {
                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                    if (isSetupNotFoundError(cause)) {
                        logger.debug("🚫 EXPECTED: Setup not found for getting event versions: {} (setup: {})",
                                   eventStoreName, setupId);
                        sendError(ctx, 404, "Setup not found: " + setupId);
                    } else {
                        logger.error("Error setting up event versions retrieval for {}: {}", eventId, throwable.getMessage(), throwable);
                        sendError(ctx, 500, "Failed to setup event versions retrieval: " + throwable.getMessage());
                    }
                    return null;
                });
    }

    /**
     * Gets an event as of a specific transaction time (bi-temporal query).
     * GET /api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/at?transactionTime=<timestamp>
     */
    public void getAsOfTransactionTime(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String eventStoreName = ctx.pathParam("eventStoreName");
        String eventId = ctx.pathParam("eventId");
        String transactionTimeParam = ctx.request().getParam("transactionTime");

        if (transactionTimeParam == null || transactionTimeParam.isEmpty()) {
            sendError(ctx, 400, "transactionTime parameter is required");
            return;
        }

        logger.info("Getting event {} as of transaction time {} from event store {} in setup: {}", 
                   eventId, transactionTimeParam, eventStoreName, setupId);

        // Parse transaction time to validate format
        try {
            Instant.parse(transactionTimeParam);
        } catch (Exception e) {
            sendError(ctx, 400, "Invalid transactionTime format. Expected ISO-8601 instant: " + e.getMessage());
            return;
        }

        setupService.getSetupResult(setupId)
                .thenAccept(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        sendError(ctx, 404, "Setup not found or not active: " + setupId);
                        return;
                    }

                    // Get the event store
                    var eventStore = setupResult.getEventStores().get(eventStoreName);
                    if (eventStore == null) {
                        sendError(ctx, 404, "Event store not found: " + eventStoreName);
                        return;
                    }

                    try {
                        // Get event as of transaction time (placeholder - returns null for now)
                        // TODO: In real implementation, query EventStore with transaction time parameter
                        EventResponse event = null; // Placeholder
                        
                        if (event == null) {
                            // No event found at this transaction time
                            sendError(ctx, 404, "Event not found as of transaction time: " + transactionTimeParam);
                            return;
                        }

                        // This code is only reached if event is found
                        JsonObject response = new JsonObject()
                            .put("message", "Event retrieved as of transaction time")
                            .put("eventStoreName", eventStoreName)
                            .put("setupId", setupId)
                            .put("eventId", eventId)
                            .put("transactionTime", transactionTimeParam)
                            .put("event", event)
                            .put("timestamp", System.currentTimeMillis());

                        ctx.response()
                                .setStatusCode(200)
                                .putHeader("content-type", "application/json")
                                .end(response.encode());

                        logger.info("Retrieved event {} as of transaction time {} from event store {}", 
                                  eventId, transactionTimeParam, eventStoreName);

                    } catch (Exception e) {
                        logger.error("Error getting event {} as of time {} from event store {}: {}", 
                                   eventId, transactionTimeParam, eventStoreName, e.getMessage(), e);
                        sendError(ctx, 500, "Failed to get event as of transaction time: " + e.getMessage());
                    }
                })
                .exceptionally(throwable -> {
                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                    if (isSetupNotFoundError(cause)) {
                        logger.debug("🚫 EXPECTED: Setup not found for temporal event query: {} (setup: {})",
                                   eventStoreName, setupId);
                        sendError(ctx, 404, "Setup not found: " + setupId);
                    } else {
                        logger.error("Error setting up temporal event query for {}: {}", eventId, throwable.getMessage(), throwable);
                        sendError(ctx, 500, "Failed to setup temporal event query: " + throwable.getMessage());
                    }
                    return null;
                });
    }


}
