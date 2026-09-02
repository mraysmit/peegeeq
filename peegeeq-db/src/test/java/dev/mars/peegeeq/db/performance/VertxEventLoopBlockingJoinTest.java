package dev.mars.peegeeq.db.performance;

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the scheduling contract that makes blocking an event loop unsafe.
 *
 * <p>Event-loop callbacks execute serially, so queued work cannot progress until the current
 * callback returns. Potentially blocking work belongs on a worker, where it can remain pending
 * without preventing event-loop callbacks from running.
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
class VertxEventLoopBlockingJoinTest {

    @Test
    @DisplayName("queued timer runs only after the current event-loop callback returns")
    void queuedTimerWaitsForCurrentEventLoopCallback(VertxTestContext testContext) {
        Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
        List<String> executionOrder = new CopyOnWriteArrayList<>();
        Promise<Void> timerObserved = Promise.promise();

        vertx.runOnContext(ignored -> {
            executionOrder.add("callback-start");
            vertx.setTimer(1, timerId -> {
                executionOrder.add("timer");
                timerObserved.complete();
            });
            executionOrder.add("callback-end");
        });

        timerObserved.future()
            .map(ignored -> {
                assertEquals(List.of("callback-start", "callback-end", "timer"), executionOrder,
                    "A timer queued by an event-loop callback must wait for that callback to return");
                return (Void) null;
            })
            .eventually(vertx::close)
            .onSuccess(ignored -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("pending worker coordination does not prevent event-loop progress")
    void pendingWorkerDoesNotPreventEventLoopProgress(VertxTestContext testContext) {
        Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
        Phaser workerRelease = new Phaser(2);
        Promise<Void> workerStarted = Promise.promise();
        Promise<Void> eventLoopProgressed = Promise.promise();
        AtomicBoolean workerUsedEventLoop = new AtomicBoolean(true);
        AtomicBoolean callbackUsedEventLoop = new AtomicBoolean(false);

        Future<Void> worker = vertx.<Void>executeBlocking(() -> {
            workerUsedEventLoop.set(Context.isOnEventLoopThread());
            workerStarted.complete();
            workerRelease.arriveAndAwaitAdvance();
            return null;
        });

        Future<Void> progress = workerStarted.future().compose(ignored -> {
            vertx.runOnContext(contextIgnored -> {
                callbackUsedEventLoop.set(Context.isOnEventLoopThread());
                workerRelease.arriveAndDeregister();
                eventLoopProgressed.complete();
            });
            return eventLoopProgressed.future();
        });

        Future.all(worker, progress)
            .map(ignored -> {
                assertFalse(workerUsedEventLoop.get(),
                    "executeBlocking work must run outside the event loop");
                assertTrue(callbackUsedEventLoop.get(),
                    "The event loop must progress while worker coordination is pending");
                return (Void) null;
            })
            .eventually(vertx::close)
            .onSuccess(ignored -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }
}
