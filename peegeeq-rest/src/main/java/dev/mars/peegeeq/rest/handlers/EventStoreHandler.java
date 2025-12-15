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
import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.TemporalRange;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
    private final Vertx vertx;

    // SSE connection management
    private final Map<String, EventStoreSSEConnection> activeConnections = new ConcurrentHashMap<>();
    private final AtomicLong connectionIdCounter = new AtomicLong(0);

    public EventStoreHandler(DatabaseSetupService setupService, ObjectMapper objectMapper) {
        this(setupService, objectMapper, null);
    }

    public EventStoreHandler(DatabaseSetupService setupService, ObjectMapper objectMapper, Vertx vertx) {
        this.setupService = setupService;
        this.objectMapper = objectMapper;
        this.vertx = vertx;
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
            
            // Validate required fields
            if (eventRequest.getEventType() == null || eventRequest.getEventType().isEmpty()) {
                sendError(ctx, 400, "eventType is required");
                return;
            }
            if (eventRequest.getEventData() == null) {
                sendError(ctx, 400, "eventData is required");
                return;
            }
            
            logger.info("Storing event {} in event store {} for setup: {}", 
                    eventRequest.getEventType(), eventStoreName, setupId);
            
            setupService.getSetupResult(setupId)
                    .thenAccept(setupResult -> {
                        if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                            sendError(ctx, 404, "Setup not found or not active: " + setupId);
                            return;
                        }

                        // Get the event store
                        EventStore<Object> eventStore = (EventStore<Object>) setupResult.getEventStores().get(eventStoreName);
                        if (eventStore == null) {
                            sendError(ctx, 500, "Event store not found: " + eventStoreName);
                            return;
                        }

                        // Prepare valid time (default to now if not provided)
                        Instant validTime = eventRequest.getValidFrom() != null 
                            ? eventRequest.getValidFrom() 
                            : Instant.now();

                        // Convert metadata to headers
                        Map<String, String> headers = eventRequest.getMetadata() != null
                            ? eventRequest.getMetadata().entrySet().stream()
                                .collect(java.util.stream.Collectors.toMap(
                                    Map.Entry::getKey,
                                    e -> e.getValue() != null ? e.getValue().toString() : ""
                                ))
                            : Map.of();

                        // Store the event in the event store
                        eventStore.append(
                            eventRequest.getEventType(),
                            eventRequest.getEventData(),
                            validTime,
                            headers,
                            eventRequest.getCorrelationId(),
                            eventRequest.getCausationId() // using causationId as aggregateId
                        )
                        .thenAccept(storedEvent -> {
                            JsonObject response = new JsonObject()
                                    .put("message", "Event stored successfully")
                                    .put("eventStoreName", eventStoreName)
                                    .put("setupId", setupId)
                                    .put("eventId", storedEvent.getEventId())
                                    .put("version", storedEvent.getVersion())
                                    .put("transactionTime", storedEvent.getTransactionTime().toString());

                            ctx.response()
                                    .setStatusCode(201)
                                    .putHeader("content-type", "application/json")
                                    .end(response.encode());

                            logger.info("Event stored successfully in event store {} for setup {} with ID {} (version {})",
                                    eventStoreName, setupId, storedEvent.getEventId(), storedEvent.getVersion());
                        })
                        .exceptionally(storeEx -> {
                            logger.error("Error storing event in event store {}: {}", eventStoreName, storeEx.getMessage(), storeEx);
                            sendError(ctx, 500, "Failed to store event: " + storeEx.getMessage());
                            return null;
                        });
                    })
                    .exceptionally(throwable -> {
                        // Check if this is an expected setup not found error (no stack trace)
                        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                        if (isSetupNotFoundError(cause)) {
                            logger.debug("🚫 EXPECTED: Setup not found for event store: {} (setup: {})",
                                       eventStoreName, setupId);
                            sendError(ctx, 404, "Setup not found: " + setupId);
                        } else {
                            logger.error("Error setting up event store for storing: " + eventStoreName, throwable);
                            sendError(ctx, 500, "Failed to setup event store: " + throwable.getMessage());
                        }
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
                    EventStore<?> eventStore = setupResult.getEventStores().get(eventStoreName);
                    if (eventStore == null) {
                        sendError(ctx, 500, "Event store not found: " + eventStoreName);
                        return;
                    }

                    try {
                        // Build EventQuery from query parameters
                        EventQuery eventQuery = buildEventQuery(queryParams);

                        // Query events from the event store using the real implementation
                        eventStore.query(eventQuery)
                            .thenAccept(events -> {
                                List<EventResponse> eventResponses = events.stream()
                                    .map(this::convertToEventResponse)
                                    .toList();

                                // Create response with pagination info
                                JsonObject response = new JsonObject()
                                    .put("message", "Events retrieved successfully")
                                    .put("eventStoreName", eventStoreName)
                                    .put("setupId", setupId)
                                    .put("eventCount", eventResponses.size())
                                    .put("limit", queryParams.getLimit())
                                    .put("offset", queryParams.getOffset())
                                    .put("hasMore", eventResponses.size() == queryParams.getLimit())
                                    .put("filters", createFiltersObject(queryParams))
                                    .put("events", eventResponses)
                                    .put("timestamp", System.currentTimeMillis());

                                ctx.response()
                                        .setStatusCode(200)
                                        .putHeader("content-type", "application/json")
                                        .end(response.encode());

                                logger.info("Retrieved {} events from event store {}", eventResponses.size(), eventStoreName);
                            })
                            .exceptionally(ex -> {
                                logger.error("Error querying events from event store {}: {}", eventStoreName, ex.getMessage(), ex);
                                sendError(ctx, 500, "Failed to query events: " + ex.getMessage());
                                return null;
                            });

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
                        sendError(ctx, 500, "Setup not found: " + setupId);
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
                        sendError(ctx, 500, "Event store not found: " + eventStoreName);
                        return;
                    }

                    try {
                        // Get the specific event using the real implementation
                        eventStore.getById(eventId)
                            .thenAccept(event -> {
                                if (event == null) {
                                    sendError(ctx, 404, "Event not found: " + eventId);
                                    return;
                                }

                                EventResponse eventResponse = convertToEventResponse(event);

                                JsonObject response = new JsonObject()
                                    .put("message", "Event retrieved successfully")
                                    .put("eventStoreName", eventStoreName)
                                    .put("setupId", setupId)
                                    .put("eventId", eventId)
                                    .put("event", eventResponse)
                                    .put("timestamp", System.currentTimeMillis());

                                ctx.response()
                                        .setStatusCode(200)
                                        .putHeader("content-type", "application/json")
                                        .end(response.encode());

                                logger.info("Retrieved event {} from event store {}", eventId, eventStoreName);
                            })
                            .exceptionally(ex -> {
                                logger.error("Error getting event {} from event store {}: {}", eventId, eventStoreName, ex.getMessage(), ex);
                                sendError(ctx, 500, "Failed to get event: " + ex.getMessage());
                                return null;
                            });

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
                        sendError(ctx, 500, "Event store not found: " + eventStoreName);
                        return;
                    }

                    try {
                        // Get statistics from the event store using the real implementation
                        eventStore.getStats()
                            .thenAccept(stats -> {
                                // Convert EventStore.EventStoreStats to our REST EventStoreStats
                                EventStoreStats restStats = new EventStoreStats(
                                    eventStoreName,
                                    stats.getTotalEvents(),
                                    stats.getTotalCorrections(),
                                    stats.getEventCountsByType()
                                );

                                JsonObject response = new JsonObject()
                                    .put("message", "Event store statistics retrieved successfully")
                                    .put("eventStoreName", eventStoreName)
                                    .put("setupId", setupId)
                                    .put("stats", restStats)
                                    .put("timestamp", System.currentTimeMillis());

                                ctx.response()
                                        .setStatusCode(200)
                                        .putHeader("content-type", "application/json")
                                        .end(response.encode());

                                logger.info("Retrieved statistics for event store {}", eventStoreName);
                            })
                            .exceptionally(ex -> {
                                logger.error("Error getting event store stats for {}: {}", eventStoreName, ex.getMessage(), ex);
                                sendError(ctx, 500, "Failed to get event store stats: " + ex.getMessage());
                                return null;
                            });

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
     * Request object for appending corrections to events.
     * Used for bi-temporal corrections that preserve the audit trail.
     */
    public static class CorrectionRequest {
        private String eventType;
        private Object eventData;
        private Instant validFrom;
        private String correctionReason;
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

        public String getCorrectionReason() { return correctionReason; }
        public void setCorrectionReason(String correctionReason) { this.correctionReason = correctionReason; }

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
     * Builds an EventQuery from REST query parameters.
     */
    private EventQuery buildEventQuery(EventQueryParams params) {
        EventQuery.Builder builder = EventQuery.builder();

        if (params.getEventType() != null) {
            builder.eventType(params.getEventType());
        }

        // Build valid time range from fromTime and toTime
        if (params.getFromTime() != null || params.getToTime() != null) {
            TemporalRange validTimeRange = new TemporalRange(
                params.getFromTime(),
                params.getToTime(),
                true, // startInclusive
                true  // endInclusive
            );
            builder.validTimeRange(validTimeRange);
        }

        if (params.getCorrelationId() != null) {
            builder.correlationId(params.getCorrelationId());
        }

        // Note: causationId maps to aggregateId in the EventQuery
        if (params.getCausationId() != null) {
            builder.aggregateId(params.getCausationId());
        }

        builder.limit(params.getLimit());
        builder.offset(params.getOffset());

        return builder.build();
    }

    // ==================== REMOVED PLACEHOLDER METHODS ====================
    // The following placeholder methods have been removed and replaced with
    // real implementations that call the actual EventStore service methods:
    // - queryEventsFromStore() -> now uses eventStore.query(EventQuery)
    // - getEventFromStore() -> now uses eventStore.getById(eventId)
    // - getStatsFromStore() -> now uses eventStore.getStats()

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
                        sendError(ctx, 500, "Event store not found: " + eventStoreName);
                        return;
                    }

                    try {
                        // Get all versions from the event store
                        eventStore.getAllVersions(eventId)
                            .thenAccept(events -> {
                                List<EventResponse> versions = events.stream()
                                    .map(this::convertToEventResponse)
                                    .toList();
                                
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
                            })
                            .exceptionally(ex -> {
                                logger.error("Error getting versions of event {} from event store {}: {}", eventId, eventStoreName, ex.getMessage(), ex);
                                sendError(ctx, 500, "Failed to get event versions: " + ex.getMessage());
                                return null;
                            });

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
                        sendError(ctx, 500, "Event store not found: " + eventStoreName);
                        return;
                    }

                    try {
                        // Get event as of transaction time from the event store
                        Instant transactionTime = Instant.parse(transactionTimeParam);
                        
                        eventStore.getAsOfTransactionTime(eventId, transactionTime)
                            .thenAccept(event -> {
                                if (event == null) {
                                    // No event found at this transaction time
                                    sendError(ctx, 404, "Event not found as of transaction time: " + transactionTimeParam);
                                    return;
                                }

                                EventResponse eventResponse = convertToEventResponse(event);
                                
                                JsonObject response = new JsonObject()
                                    .put("message", "Event retrieved as of transaction time")
                                    .put("eventStoreName", eventStoreName)
                                    .put("setupId", setupId)
                                    .put("eventId", eventId)
                                    .put("transactionTime", transactionTimeParam)
                                    .put("event", eventResponse)
                                    .put("timestamp", System.currentTimeMillis());

                                ctx.response()
                                        .setStatusCode(200)
                                        .putHeader("content-type", "application/json")
                                        .end(response.encode());

                                logger.info("Retrieved event {} as of transaction time {} from event store {}", 
                                          eventId, transactionTimeParam, eventStoreName);
                            })
                            .exceptionally(ex -> {
                                logger.error("Error getting event {} as of time {} from event store {}: {}", 
                                           eventId, transactionTimeParam, eventStoreName, ex.getMessage(), ex);
                                sendError(ctx, 500, "Failed to get event as of transaction time: " + ex.getMessage());
                                return null;
                            });

                    } catch (Exception e) {
                        logger.error("Error parsing transaction time: {}", e.getMessage());
                        sendError(ctx, 400, "Invalid transaction time format: " + e.getMessage());
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

    /**
     * Appends a correction to an existing event.
     * This is a core bi-temporal feature that allows correcting historical events
     * while preserving the complete audit trail.
     *
     * POST /api/v1/eventstores/:setupId/:eventStoreName/events/:eventId/corrections
     *
     * Request body:
     * {
     *   "eventType": "OrderUpdated",           // Optional: defaults to original event type
     *   "eventData": { ... },                  // Required: the corrected data
     *   "validFrom": "2025-07-01T10:00:00Z",   // Optional: corrected valid time (defaults to now)
     *   "correctionReason": "Price was incorrect", // Required: reason for the correction
     *   "correlationId": "corr-123",           // Optional: for distributed tracing
     *   "causationId": "order-456",            // Optional: aggregate ID
     *   "metadata": { ... }                    // Optional: additional headers
     * }
     */
    public void appendCorrection(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String eventStoreName = ctx.pathParam("eventStoreName");
        String originalEventId = ctx.pathParam("eventId");

        try {
            String body = ctx.body().asString();
            CorrectionRequest correctionRequest = objectMapper.readValue(body, CorrectionRequest.class);

            // Validate required fields
            if (correctionRequest.getEventData() == null) {
                sendError(ctx, 400, "eventData is required");
                return;
            }
            if (correctionRequest.getCorrectionReason() == null || correctionRequest.getCorrectionReason().isEmpty()) {
                sendError(ctx, 400, "correctionReason is required");
                return;
            }

            logger.info("Appending correction to event {} in event store {} for setup: {} (reason: {})",
                    originalEventId, eventStoreName, setupId, correctionRequest.getCorrectionReason());

            setupService.getSetupResult(setupId)
                    .thenAccept(setupResult -> {
                        if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                            sendError(ctx, 404, "Setup not found or not active: " + setupId);
                            return;
                        }

                        // Get the event store
                        @SuppressWarnings("unchecked")
                        EventStore<Object> eventStore = (EventStore<Object>) setupResult.getEventStores().get(eventStoreName);
                        if (eventStore == null) {
                            sendError(ctx, 500, "Event store not found: " + eventStoreName);
                            return;
                        }

                        // First, get the original event to determine the event type if not provided
                        eventStore.getById(originalEventId)
                            .thenCompose(originalEvent -> {
                                if (originalEvent == null) {
                                    throw new RuntimeException("Original event not found: " + originalEventId);
                                }

                                // Use provided event type or fall back to original
                                String eventType = correctionRequest.getEventType() != null
                                    ? correctionRequest.getEventType()
                                    : originalEvent.getEventType();

                                // Prepare valid time (default to now if not provided)
                                Instant validTime = correctionRequest.getValidFrom() != null
                                    ? correctionRequest.getValidFrom()
                                    : Instant.now();

                                // Convert metadata to headers
                                Map<String, String> headers = correctionRequest.getMetadata() != null
                                    ? correctionRequest.getMetadata().entrySet().stream()
                                        .collect(java.util.stream.Collectors.toMap(
                                            Map.Entry::getKey,
                                            e -> e.getValue() != null ? e.getValue().toString() : ""
                                        ))
                                    : Map.of();

                                // Append the correction
                                if (correctionRequest.getCorrelationId() != null || correctionRequest.getCausationId() != null) {
                                    // Use full metadata version
                                    return eventStore.appendCorrection(
                                        originalEventId,
                                        eventType,
                                        correctionRequest.getEventData(),
                                        validTime,
                                        headers,
                                        correctionRequest.getCorrelationId(),
                                        correctionRequest.getCausationId(),
                                        correctionRequest.getCorrectionReason()
                                    );
                                } else {
                                    // Use simple version
                                    return eventStore.appendCorrection(
                                        originalEventId,
                                        eventType,
                                        correctionRequest.getEventData(),
                                        validTime,
                                        correctionRequest.getCorrectionReason()
                                    );
                                }
                            })
                            .thenAccept(correctionEvent -> {
                                JsonObject response = new JsonObject()
                                        .put("message", "Correction appended successfully")
                                        .put("eventStoreName", eventStoreName)
                                        .put("setupId", setupId)
                                        .put("originalEventId", originalEventId)
                                        .put("correctionEventId", correctionEvent.getEventId())
                                        .put("version", correctionEvent.getVersion())
                                        .put("transactionTime", correctionEvent.getTransactionTime().toString())
                                        .put("correctionReason", correctionRequest.getCorrectionReason());

                                ctx.response()
                                        .setStatusCode(201)
                                        .putHeader("content-type", "application/json")
                                        .end(response.encode());

                                logger.info("Correction appended successfully to event {} in event store {} for setup {} with new ID {} (version {})",
                                        originalEventId, eventStoreName, setupId, correctionEvent.getEventId(), correctionEvent.getVersion());
                            })
                            .exceptionally(ex -> {
                                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                                String errorMessage = cause.getMessage();

                                if (errorMessage != null && errorMessage.contains("not found")) {
                                    logger.debug("🚫 EXPECTED: Original event not found: {}", originalEventId);
                                    sendError(ctx, 404, "Original event not found: " + originalEventId);
                                } else {
                                    logger.error("Error appending correction to event {} in event store {}: {}",
                                            originalEventId, eventStoreName, errorMessage, ex);
                                    sendError(ctx, 500, "Failed to append correction: " + errorMessage);
                                }
                                return null;
                            });
                    })
                    .exceptionally(throwable -> {
                        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                        if (isSetupNotFoundError(cause)) {
                            logger.debug("🚫 EXPECTED: Setup not found for correction: {} (setup: {})",
                                       eventStoreName, setupId);
                            sendError(ctx, 404, "Setup not found: " + setupId);
                        } else {
                            logger.error("Error setting up correction for event {}: {}", originalEventId, throwable.getMessage(), throwable);
                            sendError(ctx, 500, "Failed to setup correction: " + throwable.getMessage());
                        }
                        return null;
                    });

        } catch (Exception e) {
            logger.error("Error parsing correction request", e);
            sendError(ctx, 400, "Invalid request format: " + e.getMessage());
        }
    }

    /**
     * Converts a BiTemporalEvent to an EventResponse for REST API.
     */
    private EventResponse convertToEventResponse(BiTemporalEvent<?> event) {
        return new EventResponse(
            event.getEventId(),
            event.getEventType(),
            event.getPayload(),
            event.getValidTime(),
            null, // validTo not in BiTemporalEvent
            event.getTransactionTime(),
            event.getCorrelationId(),
            event.getAggregateId(), // Using aggregateId as causationId
            (int) event.getVersion(),
            event.getHeaders() != null ? Map.copyOf(event.getHeaders()) : Map.of()
        );
    }

    // ==================== SSE Streaming Methods ====================

    /**
     * Handles SSE connections for real-time bi-temporal event streaming.
     *
     * SSE URL: GET /api/v1/eventstores/{setupId}/{eventStoreName}/events/stream
     *
     * Query Parameters:
     * - eventType: Filter by event type (supports wildcards like "order.*")
     * - aggregateId: Filter by aggregate ID
     *
     * Headers:
     * - Last-Event-ID: Resume from specific event ID (SSE reconnection)
     */
    public void handleEventStream(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String eventStoreName = ctx.pathParam("eventStoreName");
        String connectionId = "event-sse-" + connectionIdCounter.incrementAndGet();

        logger.info("EventStore SSE connection request: {} for event store {} in setup {}",
                   connectionId, eventStoreName, setupId);

        // Set up SSE headers
        HttpServerResponse response = ctx.response();
        response.putHeader("Content-Type", "text/event-stream")
                .putHeader("Cache-Control", "no-cache")
                .putHeader("Connection", "keep-alive")
                .putHeader("Access-Control-Allow-Origin", "*")
                .putHeader("Access-Control-Allow-Headers", "Cache-Control, Last-Event-ID")
                .setChunked(true);

        // Create SSE connection wrapper
        EventStoreSSEConnection connection = new EventStoreSSEConnection(
            connectionId, response, setupId, eventStoreName);
        activeConnections.put(connectionId, connection);

        // Parse query parameters
        String eventTypeFilter = ctx.request().getParam("eventType");
        String aggregateIdFilter = ctx.request().getParam("aggregateId");
        connection.setEventTypeFilter(eventTypeFilter);
        connection.setAggregateIdFilter(aggregateIdFilter);

        // Check for Last-Event-ID header (SSE reconnection)
        String lastEventId = ctx.request().getHeader("Last-Event-ID");
        if (lastEventId != null && !lastEventId.trim().isEmpty()) {
            connection.setLastEventId(lastEventId);
            logger.info("SSE reconnection detected for connection {}, Last-Event-ID: {}",
                       connectionId, lastEventId);
        }

        // Handle connection close
        ctx.request().connection().closeHandler(v -> handleConnectionClose(connection));

        // Send initial connection event
        sendConnectionEvent(connection);

        // Start event streaming
        startEventStreaming(connection);
    }

    /**
     * Sends the initial connection event to the SSE client.
     */
    private void sendConnectionEvent(EventStoreSSEConnection connection) {
        JsonObject connectionInfo = new JsonObject()
            .put("type", "connection")
            .put("connectionId", connection.getConnectionId())
            .put("setupId", connection.getSetupId())
            .put("eventStoreName", connection.getEventStoreName())
            .put("eventTypeFilter", connection.getEventTypeFilter())
            .put("aggregateIdFilter", connection.getAggregateIdFilter())
            .put("timestamp", System.currentTimeMillis())
            .put("message", "Connected to PeeGeeQ EventStore SSE stream");

        sendSSEEvent(connection, "connection", connectionInfo, null);
    }

    /**
     * Sends an SSE event with optional event ID for reconnection support.
     */
    private void sendSSEEvent(EventStoreSSEConnection connection, String eventType,
                              JsonObject data, String eventId) {
        if (!connection.isActive()) {
            return;
        }

        try {
            StringBuilder sseEvent = new StringBuilder();

            // Add event type if specified
            if (eventType != null && !eventType.isEmpty()) {
                sseEvent.append("event: ").append(eventType).append("\n");
            }

            // Add event ID for reconnection (SSE standard)
            if (eventId != null && !eventId.isEmpty()) {
                sseEvent.append("id: ").append(eventId).append("\n");
            }

            // Add data
            sseEvent.append("data: ").append(data.encode()).append("\n");

            // Add empty line to complete the event
            sseEvent.append("\n");

            connection.getResponse().write(sseEvent.toString());
            connection.incrementEventsSent();
            connection.updateActivity();

            logger.trace("Sent SSE event to connection {}: {} (id: {})",
                        connection.getConnectionId(), eventType, eventId);

        } catch (Exception e) {
            logger.error("Error sending SSE event to connection {}: {}",
                        connection.getConnectionId(), e.getMessage(), e);
            connection.setActive(false);
        }
    }

    /**
     * Starts streaming events to the SSE connection using EventStore.subscribe().
     */
    private void startEventStreaming(EventStoreSSEConnection connection) {
        setupService.getSetupResult(connection.getSetupId())
            .thenAccept(setupResult -> {
                if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                    sendErrorEvent(connection, "Setup not found or not active: " + connection.getSetupId());
                    return;
                }

                // Get the event store
                @SuppressWarnings("unchecked")
                EventStore<Object> eventStore = (EventStore<Object>) setupResult.getEventStores()
                    .get(connection.getEventStoreName());

                if (eventStore == null) {
                    sendErrorEvent(connection, "Event store not found: " + connection.getEventStoreName());
                    return;
                }

                // Subscribe to events using the EventStore's reactive subscription
                String eventTypeFilter = connection.getEventTypeFilter();
                String aggregateIdFilter = connection.getAggregateIdFilter();

                logger.info("Starting event subscription for SSE connection {}: eventType={}, aggregateId={}",
                           connection.getConnectionId(), eventTypeFilter, aggregateIdFilter);

                eventStore.subscribe(eventTypeFilter, aggregateIdFilter, message -> {
                    try {
                        BiTemporalEvent<Object> event = message.getPayload();

                        // Handle SSE reconnection - skip events until we reach the resume point
                        String lastEventId = connection.getLastEventId();
                        if (lastEventId != null && !connection.isResumePointReached()) {
                            if (event.getEventId().equals(lastEventId)) {
                                connection.setResumePointReached(true);
                                logger.info("SSE connection {} reached resume point at event {}",
                                           connection.getConnectionId(), lastEventId);
                                return java.util.concurrent.CompletableFuture.completedFuture(null);
                            } else {
                                logger.trace("SSE connection {} skipping event {} (waiting for {})",
                                            connection.getConnectionId(), event.getEventId(), lastEventId);
                                return java.util.concurrent.CompletableFuture.completedFuture(null);
                            }
                        }

                        connection.incrementEventsReceived();

                        // Convert event to JSON
                        JsonObject eventData = new JsonObject()
                            .put("type", "event")
                            .put("eventId", event.getEventId())
                            .put("eventType", event.getEventType())
                            .put("aggregateId", event.getAggregateId())
                            .put("payload", event.getPayload())
                            .put("validTime", event.getValidTime() != null ?
                                event.getValidTime().toString() : null)
                            .put("transactionTime", event.getTransactionTime() != null ?
                                event.getTransactionTime().toString() : null)
                            .put("version", event.getVersion())
                            .put("correlationId", event.getCorrelationId())
                            .put("timestamp", System.currentTimeMillis());

                        // Add headers if present
                        if (event.getHeaders() != null && !event.getHeaders().isEmpty()) {
                            JsonObject headers = new JsonObject();
                            event.getHeaders().forEach(headers::put);
                            eventData.put("headers", headers);
                        }

                        // Send the event with event ID for reconnection support
                        sendSSEEvent(connection, "event", eventData, event.getEventId());

                        return java.util.concurrent.CompletableFuture.completedFuture(null);

                    } catch (Exception e) {
                        logger.error("Error processing event for SSE connection {}: {}",
                                    connection.getConnectionId(), e.getMessage(), e);
                        return java.util.concurrent.CompletableFuture.failedFuture(e);
                    }
                }).thenAccept(v -> {
                    logger.info("Event subscription established for SSE connection {}",
                               connection.getConnectionId());

                    // Start heartbeat timer if Vertx is available
                    if (vertx != null) {
                        startHeartbeatTimer(connection);
                    }

                    // Send subscription confirmation
                    JsonObject configEvent = new JsonObject()
                        .put("type", "subscribed")
                        .put("connectionId", connection.getConnectionId())
                        .put("eventTypeFilter", eventTypeFilter)
                        .put("aggregateIdFilter", aggregateIdFilter)
                        .put("timestamp", System.currentTimeMillis());

                    sendSSEEvent(connection, "subscribed", configEvent, null);

                }).exceptionally(throwable -> {
                    logger.error("Failed to subscribe for SSE connection {}: {}",
                                connection.getConnectionId(), throwable.getMessage(), throwable);
                    sendErrorEvent(connection, "Failed to subscribe: " + throwable.getMessage());
                    return null;
                });

            })
            .exceptionally(throwable -> {
                logger.error("Error setting up event streaming for SSE connection {}: {}",
                            connection.getConnectionId(), throwable.getMessage(), throwable);
                sendErrorEvent(connection, "Failed to setup streaming: " + throwable.getMessage());
                return null;
            });
    }

    /**
     * Sends an error event to the SSE client.
     */
    private void sendErrorEvent(EventStoreSSEConnection connection, String errorMessage) {
        JsonObject errorData = new JsonObject()
            .put("type", "error")
            .put("connectionId", connection.getConnectionId())
            .put("error", errorMessage)
            .put("timestamp", System.currentTimeMillis());

        sendSSEEvent(connection, "error", errorData, null);
    }

    /**
     * Sends a heartbeat event to keep the SSE connection alive.
     */
    private void sendHeartbeatEvent(EventStoreSSEConnection connection) {
        if (!connection.isActive() || !connection.isHealthy()) {
            return;
        }

        JsonObject heartbeat = new JsonObject()
            .put("type", "heartbeat")
            .put("connectionId", connection.getConnectionId())
            .put("eventsReceived", connection.getEventsReceived())
            .put("eventsSent", connection.getEventsSent())
            .put("timestamp", System.currentTimeMillis());

        sendSSEEvent(connection, "heartbeat", heartbeat, null);
    }

    /**
     * Starts a heartbeat timer for the SSE connection.
     */
    private void startHeartbeatTimer(EventStoreSSEConnection connection) {
        if (vertx == null) {
            return;
        }

        // Send heartbeat every 30 seconds
        vertx.setPeriodic(30000L, timerId -> {
            if (activeConnections.containsKey(connection.getConnectionId())) {
                sendHeartbeatEvent(connection);
            } else {
                // Connection is no longer active, cancel the timer
                vertx.cancelTimer(timerId);
            }
        });
    }

    /**
     * Handles SSE connection close.
     */
    private void handleConnectionClose(EventStoreSSEConnection connection) {
        logger.info("EventStore SSE connection closed: {}", connection.getConnectionId());

        activeConnections.remove(connection.getConnectionId());
        connection.cleanup();
    }

    /**
     * Gets the number of active SSE connections.
     */
    public int getActiveConnectionCount() {
        return activeConnections.size();
    }

    /**
     * Gets information about active SSE connections.
     */
    public JsonObject getConnectionInfo() {
        JsonObject info = new JsonObject()
            .put("activeConnections", activeConnections.size())
            .put("timestamp", System.currentTimeMillis());

        return info;
    }

}
