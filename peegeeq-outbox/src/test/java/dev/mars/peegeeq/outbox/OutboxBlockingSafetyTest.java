package dev.mars.peegeeq.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(TestCategories.CORE)
class OutboxBlockingSafetyTest {

    @Test
    void outboxFactoryBlockingApisFailFastOnEventLoopThread() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            OutboxFactory factory = new OutboxFactory(null);

            // isHealthy(), getStats(), createBrowser(), and close() are all reactive
            // (return Future) and are safe to call from the event loop.
            Throwable closeError = invokeOnEventLoop(vertx, () -> {
                factory.close();
                return null;
            });
            assertNull(closeError,
                    "close() should not throw on event loop; got: " + closeError);
        } finally {
            vertx.close().await();
        }
    }

    @Test
    void outboxFactoryCreateBrowserOnEventLoopReturnsFutureWithoutThrowing() throws Exception {
        // createBrowser() is now reactive (returns Future<QueueBrowser<T>>) and must NOT
        // throw synchronously on the event loop. Without a real DatabaseService the future
        // will fail asynchronously, but no synchronous exception is acceptable.
        Vertx vertx = Vertx.vertx();
        try {
            OutboxFactory factory = new OutboxFactory(null);

            Throwable thrown = invokeOnEventLoop(vertx, () -> factory.createBrowser("topic-a", String.class));

            assertNull(thrown,
                    "createBrowser() should not throw on event loop; got: " + thrown);
        } finally {
            vertx.close().await();
        }
    }

    @Test
    void outboxConsumerGroupStartWithSubscriptionOptionsIsNonBlockingAndSafeOnEventLoop() throws Exception {
        // start(SubscriptionOptions) returns Future<Void> and is non-blocking,
        // so it should NOT throw on the event loop. Without a real DatabaseService
        // the start will fail asynchronously, but it must not throw synchronously.
        Vertx vertx = Vertx.vertx();
        try {
            OutboxConsumerGroup<String> group = new OutboxConsumerGroup<>(
                    "group-a",
                    "topic-a",
                    String.class,
                    (dev.mars.peegeeq.api.database.DatabaseService) null,
                    new ObjectMapper(),
                    null,
                    null);

            Throwable thrown = invokeOnEventLoop(vertx, () -> {
                group.start(SubscriptionOptions.builder().build());
                return null;
            });

            // No synchronous exception should be thrown async failures are in the Future
            assertNull(thrown,
                    "start(SubscriptionOptions) should not throw on event loop; got: " + thrown);
        } finally {
            vertx.close().await();
        }
    }

    private static <T> Throwable invokeOnEventLoop(Vertx vertx, java.util.concurrent.Callable<T> action) throws Exception {
        Promise<Throwable> outcome = Promise.promise();
        vertx.runOnContext(v -> {
            try {
                action.call();
                outcome.complete(null);
            } catch (Throwable t) {
                outcome.complete(t);
            }
        });
        return outcome.future().await();
    }

    private static void assertIllegalStateWithMessage(Throwable thrown, String expectedMessage) {
        assertNotNull(thrown, "Expected an exception to be thrown");
        assertInstanceOf(IllegalStateException.class, thrown,
                "Expected IllegalStateException but got: " + thrown.getClass().getName());
        assertTrue(thrown.getMessage().contains(expectedMessage),
                "Expected message to contain: " + expectedMessage + ", but was: " + thrown.getMessage());
    }
}
