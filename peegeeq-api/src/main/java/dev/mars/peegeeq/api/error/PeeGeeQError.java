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
package dev.mars.peegeeq.api.error;

import java.time.Instant;

/**
 * Immutable error record representing a PeeGeeQ error response.
 * 
 * @param code      The standard error code (e.g., PGQERR0050)
 * @param message   Human-readable error message
 * @param timestamp When the error occurred
 * @param details   Optional additional details (can be null)
 */
public record PeeGeeQError(
    String code,
    String message,
    Instant timestamp,
    String details
) {
    /**
     * Creates an error with code and message, using current timestamp.
     */
    public static PeeGeeQError of(String code, String message) {
        return new PeeGeeQError(code, message, Instant.now(), null);
    }

    /**
     * Creates an error with code, message, and details, using current timestamp.
     */
    public static PeeGeeQError of(String code, String message, String details) {
        return new PeeGeeQError(code, message, Instant.now(), details);
    }

    /**
     * Creates a subscription not found error.
     */
    public static PeeGeeQError subscriptionNotFound(String subscriptionId) {
        return of(PeeGeeQErrorCodes.SUBSCRIPTION_NOT_FOUND, 
                  "Subscription not found: " + subscriptionId);
    }

    /**
     * Creates a setup not found error.
     */
    public static PeeGeeQError setupNotFound(String setupId) {
        return of(PeeGeeQErrorCodes.SETUP_NOT_FOUND, 
                  "Setup not found: " + setupId);
    }

    /**
     * Creates a setup inactive error.
     */
    public static PeeGeeQError setupInactive(String setupId) {
        return of(PeeGeeQErrorCodes.SETUP_INACTIVE, 
                  "Setup not found or inactive: " + setupId);
    }

    /**
     * Creates a queue not found error.
     */
    public static PeeGeeQError queueNotFound(String queueName) {
        return of(PeeGeeQErrorCodes.QUEUE_NOT_FOUND, 
                  "Queue not found: " + queueName);
    }

    /**
     * Creates a DLQ message not found error.
     */
    public static PeeGeeQError dlqMessageNotFound(long messageId) {
        return of(PeeGeeQErrorCodes.DLQ_MESSAGE_NOT_FOUND, 
                  "Dead letter message not found: " + messageId);
    }

    /**
     * Creates an event store not found error.
     */
    public static PeeGeeQError eventStoreNotFound(String eventStoreName) {
        return of(PeeGeeQErrorCodes.EVENT_STORE_NOT_FOUND, 
                  "Event store not found: " + eventStoreName);
    }

    /**
     * Creates an event not found error.
     */
    public static PeeGeeQError eventNotFound(String eventId) {
        return of(PeeGeeQErrorCodes.EVENT_NOT_FOUND, 
                  "Event not found: " + eventId);
    }

    /**
     * Creates a webhook not found error.
     */
    public static PeeGeeQError webhookNotFound(String subscriptionId) {
        return of(PeeGeeQErrorCodes.WEBHOOK_NOT_FOUND, 
                  "Subscription not found: " + subscriptionId);
    }

    /**
     * Creates a health check not found error.
     */
    public static PeeGeeQError healthCheckNotFound(String setupId) {
        return of(PeeGeeQErrorCodes.SETUP_NOT_FOUND, 
                  "Setup not found: " + setupId);
    }

    /**
     * Creates an invalid request error.
     */
    public static PeeGeeQError invalidRequest(String message) {
        return of(PeeGeeQErrorCodes.INVALID_REQUEST, message);
    }

    /**
     * Creates a validation failed error.
     */
    public static PeeGeeQError validationFailed(String message) {
        return of(PeeGeeQErrorCodes.VALIDATION_FAILED, message);
    }

    /**
     * Creates a missing required field error.
     */
    public static PeeGeeQError missingRequiredField(String fieldName) {
        return of(PeeGeeQErrorCodes.MISSING_REQUIRED_FIELD, 
                  "Missing required field: " + fieldName);
    }

    /**
     * Creates an internal error.
     */
    public static PeeGeeQError internalError(String message) {
        return of(PeeGeeQErrorCodes.INTERNAL_ERROR, message);
    }

    /**
     * Creates an internal error with details.
     */
    public static PeeGeeQError internalError(String message, String details) {
        return of(PeeGeeQErrorCodes.INTERNAL_ERROR, message, details);
    }
}

