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

package dev.mars.peegeeq.client.dto;

/**
 * Result of an event store deletion operation.
 *
 * <p>Contains confirmation details returned from both the Management API and Standard REST API
 * delete event store endpoints.</p>
 *
 * <p><b>Endpoints that return this:</b></p>
 * <ul>
 *   <li>Management API: {@code DELETE /api/v1/management/event-stores/:storeId}</li>
 *   <li>Standard REST API: {@code DELETE /api/v1/eventstores/:setupId/:eventStoreName}</li>
 * </ul>
 *
 * <p><b>Example Response:</b></p>
 * <pre>{@code
 * {
 *   "message": "Event store 'order_events' deleted successfully from setup 'production'",
 *   "setupId": "production",
 *   "storeName": "order_events",
 *   "storeId": "production-order_events",
 *   "note": "Event store and associated data have been removed",
 *   "timestamp": 1767340616246
 * }
 * }</pre>
 *
 * @since 1.0
 */
public class EventStoreDeletionResult {

    private String message;
    private String setupId;
    private String storeName;
    private String storeId;
    private String note;
    private long timestamp;

    /**
     * Default constructor for JSON deserialization.
     */
    public EventStoreDeletionResult() {
    }

    /**
     * Creates a new event store deletion result.
     *
     * @param message confirmation message
     * @param setupId the setup identifier
     * @param storeName the event store name
     * @param storeId composite ID in format setupId-storeName
     * @param note additional information about the deletion
     * @param timestamp unix timestamp in milliseconds
     */
    public EventStoreDeletionResult(String message, String setupId, String storeName,
                                     String storeId, String note, long timestamp) {
        this.message = message;
        this.setupId = setupId;
        this.storeName = storeName;
        this.storeId = storeId;
        this.note = note;
        this.timestamp = timestamp;
    }

    /**
     * Gets the confirmation message.
     *
     * @return confirmation message, typically in format
     *         "Event store '{storeName}' deleted successfully from setup '{setupId}'"
     */
    public String getMessage() {
        return message;
    }

    /**
     * Sets the confirmation message.
     *
     * @param message the message
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * Gets the setup identifier.
     *
     * <p>This is the setupId parsed from the request (either from separate parameter
     * or from composite storeId).</p>
     *
     * @return the setup identifier (e.g., "production", "prod-us-east")
     */
    public String getSetupId() {
        return setupId;
    }

    /**
     * Sets the setup identifier.
     *
     * @param setupId the setup ID
     */
    public void setSetupId(String setupId) {
        this.setupId = setupId;
    }

    /**
     * Gets the event store name.
     *
     * <p>This is the storeName parsed from the request (either from separate parameter
     * or from composite storeId).</p>
     *
     * @return the event store name (e.g., "order_events", "audit_events")
     */
    public String getStoreName() {
        return storeName;
    }

    /**
     * Sets the event store name.
     *
     * @param storeName the store name
     */
    public void setStoreName(String storeName) {
        this.storeName = storeName;
    }

    /**
     * Gets the composite store identifier.
     *
     * <p>Format: {@code setupId-storeName}</p>
     * <p>This is provided for convenience and consistency between both API patterns.</p>
     *
     * @return composite ID (e.g., "production-order_events", "prod-us-east-order_events")
     */
    public String getStoreId() {
        return storeId;
    }

    /**
     * Sets the composite store identifier.
     *
     * @param storeId the composite store ID
     */
    public void setStoreId(String storeId) {
        this.storeId = storeId;
    }

    /**
     * Gets additional notes about the deletion.
     *
     * <p>Typically provides information about what was removed and any cleanup actions.</p>
     *
     * @return additional notes (e.g., "Event store and associated data have been removed")
     */
    public String getNote() {
        return note;
    }

    /**
     * Sets additional notes.
     *
     * @param note the note text
     */
    public void setNote(String note) {
        this.note = note;
    }

    /**
     * Gets the timestamp when the deletion occurred.
     *
     * @return unix timestamp in milliseconds
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Sets the timestamp.
     *
     * @param timestamp unix timestamp in milliseconds
     */
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "EventStoreDeletionResult{" +
                "message='" + message + '\'' +
                ", setupId='" + setupId + '\'' +
                ", storeName='" + storeName + '\'' +
                ", storeId='" + storeId + '\'' +
                ", note='" + note + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}

