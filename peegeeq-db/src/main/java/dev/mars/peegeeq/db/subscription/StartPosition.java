package dev.mars.peegeeq.db.subscription;

/**
 * Start position options for late-joining consumer groups.
 * 
 * <p>This enum defines where a new or resuming consumer group should start
 * consuming messages from when subscribing to a topic.</p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-12
 * @version 1.0
 */
public enum StartPosition {
    /**
     * Start consuming from the current moment.
     * Only messages published after subscription will be received.
     * This is the default and most common option.
     */
    FROM_NOW,
    
    /**
     * Start consuming from the beginning of available messages.
     * All existing messages in the topic will be backfilled to this consumer group.
     * Use with caution - may trigger large backfill operations.
     */
    FROM_BEGINNING,
    
    /**
     * Start consuming from a specific message ID.
     * Messages with ID >= start_from_message_id will be received.
     * Useful for resuming from a known checkpoint.
     */
    FROM_MESSAGE_ID,
    
    /**
     * Start consuming from a specific timestamp.
     * Messages created at or after the timestamp will be received.
     * Useful for time-based replay scenarios.
     */
    FROM_TIMESTAMP
}

