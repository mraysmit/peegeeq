package dev.mars.peegeeq.api;

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

import java.time.Instant;
import java.util.Map;

/**
 * Represents a bi-temporal event in the append-only log store.
 * 
 * Bi-temporal events track two time dimensions:
 * - Valid Time: When the event actually happened in the real world
 * - Transaction Time: When the event was recorded in the system
 * 
 * This interface is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities with
 * bi-temporal event sourcing support.
 * 
 * @param <T> The type of event payload
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-15
 * @version 1.0
 */
public interface BiTemporalEvent<T> {
    
    /**
     * Gets the unique identifier of the event.
     * This ID is immutable and unique across all events.
     *
     * @return The event ID
     */
    String getEventId();
    
    /**
     * Gets the event type identifier.
     * Used for type safety and deserialization.
     *
     * @return The event type
     */
    String getEventType();
    
    /**
     * Gets the payload of the event.
     *
     * @return The event payload
     */
    T getPayload();
    
    /**
     * Gets the valid time - when the event actually happened in the real world.
     * This represents the business time of the event.
     *
     * @return The valid time timestamp
     */
    Instant getValidTime();
    
    /**
     * Gets the transaction time - when the event was recorded in the system.
     * This represents the system time when the event was persisted.
     *
     * @return The transaction time timestamp
     */
    Instant getTransactionTime();
    
    /**
     * Gets the version number of this event.
     * Used for tracking event evolution and corrections.
     *
     * @return The event version
     */
    long getVersion();
    
    /**
     * Gets the ID of the previous version of this event, if any.
     * Used for tracking event corrections and updates.
     *
     * @return The previous version ID, or null if this is the first version
     */
    String getPreviousVersionId();
    
    /**
     * Gets the headers associated with the event.
     * Can contain metadata, correlation IDs, etc.
     *
     * @return The event headers
     */
    Map<String, String> getHeaders();
    
    /**
     * Gets the correlation ID for tracking related events.
     *
     * @return The correlation ID, or null if not set
     */
    String getCorrelationId();
    
    /**
     * Gets the aggregate ID that this event belongs to.
     * Used for grouping related events together.
     *
     * @return The aggregate ID, or null if not set
     */
    String getAggregateId();
    
    /**
     * Indicates whether this event is a correction of a previous event.
     *
     * @return true if this is a correction, false otherwise
     */
    boolean isCorrection();
    
    /**
     * Gets the reason for the correction, if this is a correction event.
     *
     * @return The correction reason, or null if not a correction
     */
    String getCorrectionReason();
}
