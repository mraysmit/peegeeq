package dev.mars.peegeeq.api.setup;

/**
 * Thrown when a queue is added again with another implementation type while consumer groups
 * are subscribed to it.
 *
 * <p>The start bound of a subscription is a message id in the message table of the queue
 * implementation. Another implementation type has another message table, and the same number
 * names another message there. The change is refused and nothing is changed.</p>
 */
public class QueueImplementationChangeException extends IllegalStateException {

    public QueueImplementationChangeException(String message) {
        super(message);
    }
}
