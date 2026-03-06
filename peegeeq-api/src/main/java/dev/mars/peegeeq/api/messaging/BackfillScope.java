package dev.mars.peegeeq.api.messaging;

/**
 * Defines which message set a backfill operation should target.
 */
public enum BackfillScope {
    /**
     * Backfill only messages that are currently still in-flight
     * (PENDING or PROCESSING).
     */
    PENDING_ONLY,

    /**
     * Backfill all retained messages, including COMPLETED rows.
     */
    ALL_RETAINED
}
