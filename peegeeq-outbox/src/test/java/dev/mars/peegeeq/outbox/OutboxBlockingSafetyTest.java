package dev.mars.peegeeq.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(TestCategories.CORE)
class OutboxBlockingSafetyTest {

    @Test
    void outboxFactoryBlockingApisFailFastOnEventLoopThread() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            OutboxFactory factory = new OutboxFactory(null);

            Throwable isHealthyError = invokeOnEventLoop(vertx, factory::isHealthy);
            assertIllegalStateWithMessage(isHealthyError, "Do not call blocking isHealthy() on event-loop thread");

            Throwable getStatsError = invokeOnEventLoop(vertx, () -> factory.getStats("topic-a"));
            assertIllegalStateWithMessage(getStatsError, "Do not call blocking getStats() on event-loop thread");

            Throwable createBrowserError = invokeOnEventLoop(vertx, () -> factory.createBrowser("topic-a", String.class));
            assertIllegalStateWithMessage(createBrowserError,
                    "Do not call blocking createBrowser() on event-loop thread");

            Throwable closeError = invokeOnEventLoop(vertx, () -> {
                try {
                    factory.close();
                    return null;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            // close() exception is wrapped by RuntimeException in this helper branch
            Throwable effectiveCloseError = closeError.getCause() != null ? closeError.getCause() : closeError;
            assertIllegalStateWithMessage(effectiveCloseError,
                    "Do not call blocking close() on event-loop thread");
        } finally {
            CountDownLatch closeLatch = new CountDownLatch(1);
            vertx.close().onComplete(ar -> closeLatch.countDown());
            closeLatch.await(10, TimeUnit.SECONDS);
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

            // No synchronous exception should be thrown — async failures are in the Future
            assertTrue(thrown == null,
                    "start(SubscriptionOptions) should not throw on event loop; got: " + thrown);
        } finally {
            CountDownLatch closeLatch = new CountDownLatch(1);
            vertx.close().onComplete(ar -> closeLatch.countDown());
            closeLatch.await(10, TimeUnit.SECONDS);
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
        CountDownLatch latch = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> result = new java.util.concurrent.atomic.AtomicReference<>();
        outcome.future().onComplete(ar -> {
            result.set(ar.result());
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
        return result.get();
    }

    private static void assertIllegalStateWithMessage(Throwable thrown, String expectedMessage) {
        assertNotNull(thrown, "Expected an exception to be thrown");
        assertTrue(thrown instanceof IllegalStateException,
                "Expected IllegalStateException but got: " + thrown.getClass().getName());
        assertTrue(thrown.getMessage().contains(expectedMessage),
                "Expected message to contain: " + expectedMessage + ", but was: " + thrown.getMessage());
    }
}
