package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.subscription.SubscriptionStore;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Derives the subscription store of a queue from its queue factory.
 *
 * <p>A REST request that creates a subscription does not name the store. The queue
 * configuration is the source of truth: the queue factory of the queue declares the message
 * table that the message ids of its subscriptions belong to.</p>
 */
final class QueueSubscriptionStore {

    private static final Logger logger = LoggerFactory.getLogger(QueueSubscriptionStore.class);

    private QueueSubscriptionStore() {
    }

    /**
     * Returns the subscription store of a queue of a setup.
     *
     * @return Future containing the store. It fails with HTTP 404 when the setup has no queue of
     *         that name, and with HTTP 501 when the queue implementation declares no store.
     */
    static Future<SubscriptionStore> of(DatabaseSetupService setupService, String setupId, String queueName) {
        return setupService.getSetupResult(setupId)
            .compose(setupResult -> {
                QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                if (queueFactory == null) {
                    return Future.failedFuture(new ResponseException(404, "Queue not found: " + queueName));
                }
                return of(queueFactory, setupId, queueName);
            });
    }

    /**
     * Returns the subscription store that the queue factory declares.
     *
     * @return Future containing the store. It fails with HTTP 501 when the queue implementation
     *         declares no store: such a queue cannot have subscriptions.
     */
    static Future<SubscriptionStore> of(QueueFactory queueFactory, String setupId, String queueName) {
        try {
            return Future.succeededFuture(queueFactory.getSubscriptionStore());
        } catch (UnsupportedOperationException e) {
            logger.error("No subscription store for queue '{}' in setup '{}': {}",
                queueName, setupId, e.getMessage());
            return Future.failedFuture(new ResponseException(501,
                "Queue '" + queueName + "' cannot have subscriptions: " + e.getMessage()));
        }
    }
}
