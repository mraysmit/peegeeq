package dev.mars.peegeeq.api.subscription;

/**
 * The message table that the message ids of a subscription belong to.
 *
 * <p>The start bound of a subscription is a message id. Each message table has its own ids,
 * so the same number names different messages in the two tables. The caller that creates a
 * subscription names the store, and {@code FROM_NOW} is resolved against that table.</p>
 *
 * <p>The store is not saved with the subscription. It is derived from the queue: the queue
 * factory of a queue returns it through
 * {@link dev.mars.peegeeq.api.messaging.QueueFactory#getSubscriptionStore()}.</p>
 */
public enum SubscriptionStore {

    /** The {@code outbox} table, which holds the messages of an outbox queue. */
    OUTBOX,

    /** The {@code queue_messages} table, which holds the messages of a native queue. */
    NATIVE
}
