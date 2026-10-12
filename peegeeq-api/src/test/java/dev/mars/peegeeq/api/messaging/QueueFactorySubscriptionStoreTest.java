package dev.mars.peegeeq.api.messaging;

import io.vertx.core.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the default of {@link QueueFactory#getSubscriptionStore()}.
 *
 * <p>The interface gives no default store. A queue implementation that does not declare its
 * store cannot have subscriptions, so the default throws.</p>
 */
@Tag("core")
class QueueFactorySubscriptionStoreTest {

    @Test
    void anImplementationThatDeclaresNoStoreHasNoSubscriptionStore() {
        QueueFactory factory = new UndeclaredStoreQueueFactory();

        UnsupportedOperationException failure =
            assertThrows(UnsupportedOperationException.class, factory::getSubscriptionStore);

        assertTrue(failure.getMessage().contains("undeclared"),
            "The failure must name the implementation type, got: " + failure.getMessage());
    }

    /** A queue implementation that declares no subscription store. */
    private static final class UndeclaredStoreQueueFactory implements QueueFactory {

        @Override
        public <T> MessageProducer<T> createProducer(String topic, Class<T> payloadType) {
            throw new UnsupportedOperationException("This test factory creates no producer");
        }

        @Override
        public <T> MessageConsumer<T> createConsumer(String topic, Class<T> payloadType) {
            throw new UnsupportedOperationException("This test factory creates no consumer");
        }

        @Override
        public <T> ConsumerGroup<T> createConsumerGroup(String groupName, String topic, Class<T> payloadType) {
            throw new UnsupportedOperationException("This test factory creates no consumer group");
        }

        @Override
        public <T> Future<QueueBrowser<T>> createBrowser(String topic, Class<T> payloadType) {
            return Future.failedFuture(new UnsupportedOperationException("This test factory creates no browser"));
        }

        @Override
        public String getImplementationType() {
            return "undeclared";
        }

        @Override
        public Future<Boolean> isHealthy() {
            return Future.succeededFuture(true);
        }

        @Override
        public Future<Void> close() {
            return Future.succeededFuture();
        }
    }
}
