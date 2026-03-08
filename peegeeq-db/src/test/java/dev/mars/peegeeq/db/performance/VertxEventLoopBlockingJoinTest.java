package dev.mars.peegeeq.db.performance;

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demonstrates that calling join() on the event loop blocks scheduling.
 */
@Tag(TestCategories.CORE)
class VertxEventLoopBlockingJoinTest {

    @Test
    @DisplayName("join() on event loop should delay timer execution")
    void joinOnEventLoopShouldDelayTimerExecution() throws InterruptedException {
        VertxOptions options = new VertxOptions()
            .setEventLoopPoolSize(1)
            .setBlockedThreadCheckInterval(50)
            .setMaxEventLoopExecuteTime(100)
            .setMaxEventLoopExecuteTimeUnit(TimeUnit.MILLISECONDS);

        Vertx vertx = Vertx.vertx(options);
        try {
            CountDownLatch timerObserved = new CountDownLatch(1);
            AtomicLong timerDelayMs = new AtomicLong(0);

            vertx.runOnContext(ignored -> {
                long scheduledAtNanos = System.nanoTime();

                vertx.setTimer(10, id -> {
                    long delayMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - scheduledAtNanos);
                    timerDelayMs.set(delayMs);
                    timerObserved.countDown();
                });

                // Intentionally block the event loop for a short period.
                CompletableFuture<Void> unblockLater = new CompletableFuture<>();
                CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    unblockLater.complete(null);
                });
                unblockLater.join();
            });

            assertTrue(timerObserved.await(5, TimeUnit.SECONDS), "Timer callback should eventually run");
            assertTrue(
                timerDelayMs.get() >= 150,
                "Expected timer delay >= 150ms due to event-loop blocking, actual delay=" + timerDelayMs.get() + "ms"
            );
        } finally {
            CountDownLatch closed = new CountDownLatch(1);
            vertx.close().onComplete(ar -> closed.countDown());
            assertTrue(closed.await(5, TimeUnit.SECONDS), "Vertx should close cleanly");
        }
    }

    @Test
    @DisplayName("join() off event loop should not delay timer execution")
    void joinOffEventLoopShouldNotDelayTimerExecution() throws InterruptedException {
        VertxOptions options = new VertxOptions().setEventLoopPoolSize(1);
        Vertx vertx = Vertx.vertx(options);
        try {
            CountDownLatch timerObserved = new CountDownLatch(1);
            CountDownLatch workerDone = new CountDownLatch(1);
            AtomicLong timerDelayMs = new AtomicLong(0);

            long scheduledAtNanos = System.nanoTime();
            vertx.setTimer(10, id -> {
                long delayMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - scheduledAtNanos);
                timerDelayMs.set(delayMs);
                timerObserved.countDown();
            });

            CompletableFuture.runAsync(() -> {
                CompletableFuture<Void> waitOnVertxTimer = new CompletableFuture<>();
                vertx.setTimer(250, id -> waitOnVertxTimer.complete(null));
                waitOnVertxTimer.join();
                workerDone.countDown();
            });

            assertTrue(timerObserved.await(5, TimeUnit.SECONDS), "Timer callback should run quickly");
            assertTrue(workerDone.await(5, TimeUnit.SECONDS), "Worker should complete after waiting");
            assertTrue(
                timerDelayMs.get() < 150,
                "Expected timer delay < 150ms when join() is off event loop, actual delay=" + timerDelayMs.get() + "ms"
            );
        } finally {
            CountDownLatch closed = new CountDownLatch(1);
            vertx.close().onComplete(ar -> closed.countDown());
            assertTrue(closed.await(5, TimeUnit.SECONDS), "Vertx should close cleanly");
        }
    }
}
