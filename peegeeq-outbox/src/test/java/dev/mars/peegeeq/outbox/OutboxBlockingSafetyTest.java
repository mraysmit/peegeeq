package dev.mars.peegeeq.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies that OutboxFactory and OutboxConsumerGroup APIs are safe to call from the
 * Vert.x event loop thread  i.e. they do not perform blocking work synchronously.
 *
 * <p>Each test schedules an API call onto the event loop via {@link #invokeOnEventLoop}
 * and asserts that no synchronous exception was thrown. Asynchronous failures in the
 * returned {@code Future} are expected and intentionally ignored here; the concern is
 * only that the call does not block or throw before returning.
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
class OutboxBlockingSafetyTest {

    private static PeeGeeQConfiguration minimalTestConfig() {
        Properties props = new Properties();
        props.setProperty("peegeeq.database.host", "localhost");
        props.setProperty("peegeeq.database.name", "test");
        props.setProperty("peegeeq.database.username", "test");
        props.setProperty("peegeeq.database.schema", "public");
        return new PeeGeeQConfiguration("default", props);
    }

    @Test
    void outboxFactoryBlockingApisFailFastOnEventLoopThread(Vertx vertx, VertxTestContext testContext) {
        OutboxFactory factory = new OutboxFactory(null, minimalTestConfig());

        // isHealthy(), getStats(), createBrowser(), and close() are all reactive
        // (return Future) and are safe to call from the event loop.
        invokeOnEventLoop(vertx, () -> {
            factory.close();
            return null;
        }).onComplete(testContext.succeeding(closeError -> testContext.verify(() -> {
            assertNull(closeError,
                    "close() should not throw on event loop; got: " + closeError);
            testContext.completeNow();
        })));
    }

    @Test
    void outboxFactoryCreateBrowserOnEventLoopReturnsFutureWithoutThrowing(Vertx vertx, VertxTestContext testContext) {
        // createBrowser() is now reactive (returns Future<QueueBrowser<T>>) and must NOT
        // throw synchronously on the event loop. Without a real DatabaseService the future
        // will fail asynchronously, but no synchronous exception is acceptable.
        OutboxFactory factory = new OutboxFactory(null, minimalTestConfig());

        invokeOnEventLoop(vertx, () -> factory.createBrowser("topic-a", String.class))
                .onComplete(testContext.succeeding(thrown -> testContext.verify(() -> {
                    assertNull(thrown,
                            "createBrowser() should not throw on event loop; got: " + thrown);
                    testContext.completeNow();
                })));
    }

    @Test
    void outboxConsumerGroupStartWithSubscriptionOptionsIsNonBlockingAndSafeOnEventLoop(Vertx vertx, VertxTestContext testContext) {
        // start(SubscriptionOptions) returns Future<Void> and is non-blocking,
        // so it should NOT throw on the event loop. Without a real DatabaseService
        // the start will fail asynchronously, but it must not throw synchronously.
        OutboxConsumerGroup<String> group = new OutboxConsumerGroup<>(
                "group-a",
                "topic-a",
                String.class,
                (dev.mars.peegeeq.api.database.DatabaseService) null,
                new ObjectMapper(),
                null,
                null);

        invokeOnEventLoop(vertx, () -> {
            group.start(SubscriptionOptions.builder().build());
            return null;
        }).onComplete(testContext.succeeding(thrown -> testContext.verify(() -> {
            // No synchronous exception should be thrown; async failures are in the Future
            assertNull(thrown,
                    "start(SubscriptionOptions) should not throw on event loop; got: " + thrown);
            testContext.completeNow();
        })));
    }

    /**
     * Schedules {@code action} on the Vert.x event loop and captures any synchronous
     * exception it throws. Returns a {@code Future} that resolves to:
     * <ul>
     *   <li>{@code null}  the action completed without throwing synchronously</li>
     *   <li>a {@code Throwable}  the synchronous exception the action threw</li>
     * </ul>
     * The future itself never fails; exceptions are surfaced as a non-null result value
     * so callers can assert on them with {@code assertNull}.
     */
    private static <T> Future<Throwable> invokeOnEventLoop(Vertx vertx, java.util.concurrent.Callable<T> action) {
        Promise<Throwable> outcome = Promise.promise();
        vertx.runOnContext(v -> {
            try {
                action.call();
                outcome.complete(null);
            } catch (Throwable t) {
                outcome.complete(t);
            }
        });
        return outcome.future();
    }

}
