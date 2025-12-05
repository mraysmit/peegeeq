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

import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents an active Server-Sent Events (SSE) connection for real-time bi-temporal event streaming.
 * 
 * Manages the state and configuration of a single SSE connection for event store subscriptions,
 * including event type filters, aggregate ID filters, and connection lifecycle.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-05
 * @version 1.0
 */
public class EventStoreSSEConnection {
    
    private static final Logger logger = LoggerFactory.getLogger(EventStoreSSEConnection.class);
    
    private final String connectionId;
    private final HttpServerResponse response;
    private final String setupId;
    private final String eventStoreName;
    private final long createdAt;
    
    // Connection state
    private volatile boolean active = true;
    
    // Subscription filters
    private String eventTypeFilter;      // Filter by event type (supports wildcards like "order.*")
    private String aggregateIdFilter;    // Filter by aggregate ID
    
    // Reconnection support
    private String lastEventId;          // Last-Event-ID for SSE reconnection
    private volatile boolean resumePointReached = false;
    
    // Statistics
    private volatile long eventsReceived = 0;
    private volatile long eventsSent = 0;
    private volatile long lastActivityTime;
    
    public EventStoreSSEConnection(String connectionId, HttpServerResponse response, 
                                   String setupId, String eventStoreName) {
        this.connectionId = connectionId;
        this.response = response;
        this.setupId = setupId;
        this.eventStoreName = eventStoreName;
        this.createdAt = System.currentTimeMillis();
        this.lastActivityTime = this.createdAt;
        
        logger.debug("Created EventStore SSE connection: {} for event store {} in setup {}", 
                    connectionId, eventStoreName, setupId);
    }
    
    /**
     * Updates the last activity timestamp.
     */
    public void updateActivity() {
        this.lastActivityTime = System.currentTimeMillis();
    }
    
    /**
     * Checks if the connection is still active and healthy.
     */
    public boolean isHealthy() {
        if (!active) {
            return false;
        }
        
        // Check if HTTP response is still open
        if (response.closed()) {
            logger.debug("EventStore SSE connection {} is closed", connectionId);
            active = false;
            return false;
        }
        
        // Check for timeout (no activity for more than 5 minutes)
        long inactiveTime = System.currentTimeMillis() - lastActivityTime;
        if (inactiveTime > 300000) { // 5 minutes
            logger.warn("EventStore SSE connection {} has been inactive for {} ms", connectionId, inactiveTime);
            return false;
        }
        
        return true;
    }
    
    /**
     * Increments the events received counter.
     */
    public void incrementEventsReceived() {
        eventsReceived++;
    }
    
    /**
     * Increments the events sent counter.
     */
    public void incrementEventsSent() {
        eventsSent++;
    }
    
    /**
     * Cleans up resources associated with this connection.
     */
    public void cleanup() {
        logger.debug("Cleaning up EventStore SSE connection: {}", connectionId);
        
        active = false;
        
        // Close HTTP response if still open
        if (!response.closed()) {
            try {
                response.end();
                logger.debug("Closed HTTP response for EventStore SSE connection: {}", connectionId);
            } catch (Exception e) {
                logger.warn("Error closing HTTP response for EventStore SSE connection {}: {}", 
                           connectionId, e.getMessage());
            }
        }
        
        logger.info("EventStore SSE connection {} cleaned up. Events received: {}, sent: {}", 
                   connectionId, eventsReceived, eventsSent);
    }
    
    /**
     * Gets connection statistics.
     */
    public JsonObject getStatistics() {
        return new JsonObject()
            .put("connectionId", connectionId)
            .put("setupId", setupId)
            .put("eventStoreName", eventStoreName)
            .put("active", active)
            .put("eventTypeFilter", eventTypeFilter)
            .put("aggregateIdFilter", aggregateIdFilter)
            .put("eventsReceived", eventsReceived)
            .put("eventsSent", eventsSent)
            .put("createdAt", createdAt)
            .put("lastActivityTime", lastActivityTime)
            .put("uptimeMs", System.currentTimeMillis() - createdAt);
    }
    
    // Getters and setters
    public String getConnectionId() { return connectionId; }
    public HttpServerResponse getResponse() { return response; }
    public String getSetupId() { return setupId; }
    public String getEventStoreName() { return eventStoreName; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getEventTypeFilter() { return eventTypeFilter; }
    public void setEventTypeFilter(String eventTypeFilter) { this.eventTypeFilter = eventTypeFilter; }
    public String getAggregateIdFilter() { return aggregateIdFilter; }
    public void setAggregateIdFilter(String aggregateIdFilter) { this.aggregateIdFilter = aggregateIdFilter; }
    public String getLastEventId() { return lastEventId; }
    public void setLastEventId(String lastEventId) { this.lastEventId = lastEventId; }
    public boolean isResumePointReached() { return resumePointReached; }
    public void setResumePointReached(boolean resumePointReached) { this.resumePointReached = resumePointReached; }
    public long getEventsReceived() { return eventsReceived; }
    public long getEventsSent() { return eventsSent; }
    public long getCreatedAt() { return createdAt; }
    public long getLastActivityTime() { return lastActivityTime; }
}

