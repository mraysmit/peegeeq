package dev.mars.peegeeq.db.subscription;

/**
 * Topic semantics enumeration for message distribution.
 * 
 * <p>This enum defines how messages published to a topic are distributed
 * to consumer groups.</p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-12
 * @version 1.0
 */
public enum TopicSemantics {
    /**
     * Queue semantics - messages are distributed to ONE consumer group.
     * This is the default behavior and maintains backward compatibility.
     * Each message is delivered to exactly one consumer group.
     */
    QUEUE,
    
    /**
     * Publish-Subscribe semantics - messages are replicated to ALL active subscriptions.
     * Each message is delivered to every active consumer group subscribed to the topic.
     * This enables fan-out patterns where multiple consumers process the same message.
     */
    PUB_SUB
}

