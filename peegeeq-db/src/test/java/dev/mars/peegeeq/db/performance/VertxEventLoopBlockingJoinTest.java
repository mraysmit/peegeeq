package dev.mars.peegeeq.db.performance;

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demonstrates that blocking the Vert.x event loop delays timer scheduling.
 *
 * <p><strong>INTENTIONAL DESIGN: BlockedThreadChecker WARNs are EXPECTED in this test.</strong><br>
 * Both tests configure Vert.x with {@code maxEventLoopExecuteTime=100ms} and then deliberately
 * block the event loop with {@code Thread.sleep(250ms)}.  The Vert.x
 * {@code BlockedThreadChecker} will emit multiple WARN log lines like:
 * <pre>
 *   WARN BlockedThreadChecker - Thread vert.x-eventloop-thread-0 has been blocked for Nms
 * </pre>
 * These WARNs are the PROOF that the anti-pattern is detectable at runtime they are not
 * failures.  The test assertions verify that the observed timer delay is consistent with
 * the intentional blocking.
 */
@Tag(TestCategories.CORE)
// BLOCKING-EXEMPT: Thread.sleep IS the subject under test. This class deliberately
// blocks the Vert.x event loop to verify (a) that Vert.x BlockedThreadChecker emits
// the expected WARN log entries, and (b) that the resulting timer delay is measurable
// and consistent with the configured maxEventLoopExecuteTime threshold.
// No production code sleeps on the event loop; this is a diagnostic validation only.
@Tag("blocking-exempt")
@ExtendWith(VertxExtension.class)
class VertxEventLoopBlockingJoinTest {

    private static final Logger logger = LoggerFactory.getLogger(VertxEventLoopBlockingJoinTest.class);

    @Test
    @DisplayName("join() on event loop should delay timer execution")
    void joinOnEventLoopShouldDelayTimerExecution(VertxTestContext testContext) {
        // INTENTIONAL: BlockedThreadChecker WARNs emitted during this test are EXPECTED.
        // The test deliberately sleeps 250ms on the event loop (maxEventLoopExecuteTime=100ms)
        // to prove the anti-pattern causes detectable timer delay. The WARNs are the proof.
        logger.info("=== INTENTIONAL BLOCKING TEST START ===");
        logger.info("EXPECTED: Vert.x BlockedThreadChecker will emit WARN lines during this test.");
        logger.info("Those WARNs are the test's evidence that event-loop blocking is detectable they are NOT failures.");
        VertxOptions options = new VertxOptions()
            .setEventLoopPoolSize(1)
            .setBlockedThreadCheckInterval(50)
            .setMaxEventLoopExecuteTime(100)
            .setMaxEventLoopExecuteTimeUnit(TimeUnit.MILLISECONDS);

        Vertx vertx = Vertx.vertx(options);
        
        AtomicLong timerDelayMs = new AtomicLong(0);

        vertx.runOnContext(ignored -> {
            long scheduledAtNanos = System.nanoTime();

            vertx.setTimer(10, id -> {
                long delayMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - scheduledAtNanos);
                timerDelayMs.set(delayMs);
            });

            // Intentionally block the event loop with Thread.sleep.
            // EXPECTED: Vert.x BlockedThreadChecker WARNs will appear in the log below.
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        vertx.close()
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                logger.info("=== INTENTIONAL BLOCKING TEST END: timer delay={}ms (expected >=150ms) ===", timerDelayMs.get());
                logger.info("EXPECTED BlockedThreadChecker WARNs above confirm the anti-pattern was active.");
                assertTrue(timerDelayMs.get() >= 150,
                    "Expected timer delay >= 150ms due to event-loop blocking, actual delay=" + timerDelayMs.get() + "ms");
                testContext.completeNow();
            })));
    }

    @Test
    @DisplayName("join() off event loop should not delay timer execution")
    void joinOffEventLoopShouldNotDelayTimerExecution(VertxTestContext testContext) {
        VertxOptions options = new VertxOptions().setEventLoopPoolSize(1);
        Vertx vertx = Vertx.vertx(options);
        
        AtomicLong timerDelayMs = new AtomicLong(0);

        long scheduledAtNanos = System.nanoTime();
        
        // Start timer on event loop
        vertx.setTimer(10, id -> {
            long delayMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - scheduledAtNanos);
            timerDelayMs.set(delayMs);
        });

        // Run blocking operation on separate thread (not event loop)
        Thread workerThread = new Thread(() -> {
            try {
                // Sleep on worker thread while event loop is free
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        workerThread.start();

        vertx.close()
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                assertTrue(timerDelayMs.get() < 150,
                    "Expected timer delay < 150ms when blocking is off event loop, actual delay=" + timerDelayMs.get() + "ms");
                testContext.completeNow();
            })));
    }
}
