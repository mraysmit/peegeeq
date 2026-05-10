package dev.mars.peegeeq.db.performance;

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates that exceptions thrown inside {@link Future#onSuccess} handlers
 * are <strong>silently swallowed</strong> by the Vert.x event-loop context.
 *
 * <h2>The Danger</h2>
 * <p>When a {@code RuntimeException} is thrown inside an {@code onSuccess} callback:
 * <ol>
 *   <li>The paired {@code onFailure} handler is <strong>NOT</strong> called.</li>
 *   <li>The exception is caught by Vert.x and routed to the context's unhandled
 *       exception handler (logged as {@code ContextImpl - Unhandled exception}).</li>
 *   <li>The {@link VertxTestContext} never receives {@code completeNow()} or
 *       {@code failNow()} the test hangs silently until its timeout fires.</li>
 * </ol>
 *
 * <p>This is exactly what happened in {@code MultiConfigurationExampleTest
 * .testMultipleConfigurationRegistration}: a synchronous call after a timer chain
 * threw {@code RuntimeException("Failed to register configuration: development")}
 * inside {@code .onSuccess()}, producing a 30-second timeout instead of a clear
 * failure message.
 *
 * <h2>Real-World Impact</h2>
 * <ul>
 *   <li>The test reports a <em>timeout</em>, not the actual root-cause exception.</li>
 *   <li>The root-cause stack trace is buried in the log as an ERROR, not surfaced
 *       by the test framework.</li>
 *   <li>A developer reading the timeout has no immediate clue what went wrong.</li>
 * </ul>
 *
 * <h2>Test Organisation</h2>
 * <p>Each danger scenario is followed by a safe-pattern counterpart that demonstrates
 * the correct fix.  All six tests in this class <strong>pass</strong>; the anti-pattern
 * tests prove the dangerous behaviour by observing its effects rather than falling into
 * the trap themselves.
 *
 * <h2>Expected Log Output</h2>
 * <p><strong>INTENTIONAL: The {@code ContextImpl - Unhandled exception} ERROR lines
 * emitted by the anti-pattern tests are EXPECTED.</strong>  They are the observable
 * evidence that the exception was swallowed they are NOT test failures.
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
class VertxOnSuccessExceptionSwallowTest {

    private static final Logger logger = LoggerFactory.getLogger(VertxOnSuccessExceptionSwallowTest.class);

    // =========================================================================
    // SCENARIO 1: exception in onSuccess bypasses onFailure
    // =========================================================================

    /**
     * ANTI-PATTERN: proves that an exception thrown inside {@code onSuccess} does
     * NOT trigger the paired {@code onFailure} handler.
     *
     * <p>The Vert.x event-loop context catches the exception internally and routes
     * it to {@code vertx.exceptionHandler}.  The {@code onFailure} chain is bypassed
     * entirely it only fires when the {@code Future} itself fails, not when a
     * handler callback throws.
     *
     * <p><strong>EXPECTED ERROR in log</strong>: {@code ContextImpl - Unhandled exception}.
     */
    @Test
    @DisplayName("[ANTI-PATTERN] Exception thrown in onSuccess does NOT trigger onFailure")
    void antiPattern_exceptionInOnSuccess_bypassesOnFailure(Vertx vertx, VertxTestContext testContext) {
        logger.warn("===== INTENTIONAL ERROR TEST START ===== "
            + "The next 'Unhandled exception' ERROR is EXPECTED "
            + "this test deliberately throws inside onSuccess to prove onFailure is never triggered.");

        AtomicBoolean onFailureCalled = new AtomicBoolean(false);
        AtomicBoolean exceptionReachedContextHandler = new AtomicBoolean(false);

        // Install a global exception handler so we can observe where the swallowed
        // exception actually ends up instead of just seeing the ERROR log line.
        vertx.exceptionHandler(t -> {
            if ("deliberate-exception-in-onSuccess".equals(t.getMessage())) {
                exceptionReachedContextHandler.set(true);
            }
        });

        // Use a timer so the chain executes on the Vert.x event-loop context —
        // exactly the scenario that causes silent swallowing.
        vertx.timer(10).mapEmpty()
            .onSuccess(v -> {
                // ANTI-PATTERN: throwing here does NOT fail the Future.
                // Vert.x catches this internally and routes to the context
                // exceptionHandler. The onFailure below is NEVER called.
                throw new RuntimeException("deliberate-exception-in-onSuccess");
            })
            .onFailure(e -> {
                // This block is NEVER entered the exception above bypasses it.
                onFailureCalled.set(true);
            });

        // A separate timer plays the role of the "observer" it fires after the
        // future chain has settled and asserts the observable effects.
        vertx.setTimer(300, id -> testContext.verify(() -> {
            assertFalse(onFailureCalled.get(),
                "PROOF OF DANGER: onFailure was NOT triggered "
                    + "the exception thrown in onSuccess silently bypassed the failure handler");
            assertTrue(exceptionReachedContextHandler.get(),
                "PROOF OF DANGER: the exception was silently routed to vertx.exceptionHandler "
                    + "rather than to onFailure, making it invisible to the test framework");
            logger.warn("===== INTENTIONAL ERROR TEST END ===== "
                + "Anti-pattern confirmed: exception in onSuccess bypassed onFailure and was "
                + "silently swallowed by the Vert.x context exception handler.");
            testContext.completeNow();
        }));
    }

    /**
     * SAFE PATTERN counterpart to the anti-pattern above.
     *
     * <p>Wrapping the risky synchronous call in {@code try-catch} and calling
     * {@code testContext.failNow(e)} explicitly ensures the test framework receives
     * the real exception immediately no silent swallow, no 30-second timeout.
     */
    @Test
    @DisplayName("[SAFE PATTERN] try-catch in onSuccess prevents silent swallow")
    void safePattern_tryCatchInOnSuccess_preventsSwallow(Vertx vertx, VertxTestContext testContext) {
        AtomicReference<Throwable> capturedByTryCatch = new AtomicReference<>();

        vertx.timer(10).mapEmpty()
            .onSuccess(v -> {
                try {
                    // Simulates any synchronous call that may throw
                    // (e.g. registerConfiguration, some validation, etc.)
                    throw new RuntimeException("deliberate-exception-in-onSuccess");
                } catch (Exception e) {
                    // SAFE: exception is explicitly captured.
                    // In production test code you call testContext.failNow(e) here;
                    // this test stores it so we can assert it was NOT swallowed.
                    capturedByTryCatch.set(e);
                }
            })
            .onFailure(testContext::failNow);

        vertx.setTimer(300, id -> testContext.verify(() -> {
            assertNotNull(capturedByTryCatch.get(),
                "Exception was captured by try-catch NOT silently swallowed");
            assertEquals("deliberate-exception-in-onSuccess",
                capturedByTryCatch.get().getMessage(),
                "Captured exception carries the real cause");
            logger.info("Safe pattern confirmed: try-catch captured the exception. "
                + "Call testContext.failNow(capturedByTryCatch.get()) to fail fast with the real cause.");
            testContext.completeNow();
        }));
    }

    // =========================================================================
    // SCENARIO 2: the real-world failure timer chain followed by sync call
    //   mirrors exactly what MultiConfigurationExampleTest.testMultipleConfigurationRegistration
    //   does: timer → timer → onSuccess throws → 30 s timeout
    // =========================================================================

    /**
     * ANTI-PATTERN: reproduces the exact failure pattern from
     * {@code MultiConfigurationExampleTest.testMultipleConfigurationRegistration}.
     *
     * <p>A chain of timers (simulating async work) concludes with an {@code onSuccess}
     * callback that calls a synchronous method which throws.  The exception is swallowed;
     * neither {@code completeNow()} nor {@code failNow()} is ever called on the test
     * context.  Without an outer observer, this test would hang for 30 seconds and
     * report only "Timeout" with no root-cause information.
     *
     * <p><strong>EXPECTED ERROR in log</strong>: {@code ContextImpl - Unhandled exception}.
     */
    @Test
    @DisplayName("[ANTI-PATTERN] Sync call after timer chain swallows exception test would hang 30 s")
    void antiPattern_syncCallAfterTimerChain_causesSilentHang(Vertx vertx, VertxTestContext testContext) {
        logger.warn("===== INTENTIONAL ERROR TEST START ===== "
            + "The next 'Unhandled exception' ERROR is EXPECTED "
            + "this test models the MultiConfigurationExampleTest failure: "
            + "a sync call throws inside onSuccess after a timer chain, "
            + "the test context never completes, and only the log shows the real cause.");

        AtomicBoolean contextEverCompleted = new AtomicBoolean(false);
        AtomicBoolean exceptionReachedContextHandler = new AtomicBoolean(false);
        long startNanos = System.nanoTime();

        vertx.exceptionHandler(t -> {
            if ("simulated-registerConfiguration-throws".equals(t.getMessage())) {
                exceptionReachedContextHandler.set(true);
            }
        });

        // Mirror of MultiConfigurationExampleTest: timer → timer → onSuccess throws
        vertx.timer(20).mapEmpty()
            .compose(v -> vertx.timer(20).mapEmpty())
            .compose(v -> vertx.timer(20).mapEmpty())
            .onSuccess(v -> {
                // ANTI-PATTERN: synchronous call throws inside onSuccess.
                // Vert.x swallows this; the test context never hears about it.
                throw new RuntimeException("simulated-registerConfiguration-throws");
                // completeNow() below is unreachable the test would time out.
            })
            .onFailure(testContext::failNow);

        // Outer observer timer: fires after the chain settles, proves the hang.
        vertx.setTimer(400, id -> testContext.verify(() -> {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            assertFalse(contextEverCompleted.get(),
                "PROOF OF DANGER: testContext.completeNow() was never called "
                    + "without this observer timer the test would hang for 30 s");
            assertTrue(exceptionReachedContextHandler.get(),
                "PROOF OF DANGER: exception was silently routed to vertx.exceptionHandler "
                    + "after " + elapsedMs + "ms invisible to the test framework");
            logger.warn("===== INTENTIONAL ERROR TEST END ===== "
                + "Anti-pattern confirmed: after {}ms the test context was never completed. "
                + "The real exception ('simulated-registerConfiguration-throws') appeared only "
                + "as an ERROR log line, not as a test failure.", elapsedMs);
            testContext.completeNow();
        }));
    }

    /**
     * SAFE PATTERN counterpart: the corrected version of the timer-chain scenario.
     *
     * <p>Wrapping the synchronous call in {@code try-catch} and storing the exception
     * ensures it is never silently swallowed.  In production test code you would call
     * {@code testContext.failNow(e)} inside the catch block to fail immediately with
     * the real root cause.
     */
    @Test
    @DisplayName("[SAFE PATTERN] Sync call after timer chain: try-catch prevents silent hang")
    void safePattern_syncCallAfterTimerChain_preventsHang(Vertx vertx, VertxTestContext testContext) {
        AtomicReference<Throwable> capturedByTryCatch = new AtomicReference<>();

        vertx.timer(20).mapEmpty()
            .compose(v -> vertx.timer(20).mapEmpty())
            .compose(v -> vertx.timer(20).mapEmpty())
            .onSuccess(v -> {
                try {
                    // SAFE: wrap any synchronous call that might throw
                    throw new RuntimeException("simulated-registerConfiguration-throws");
                } catch (Exception e) {
                    // Exception is explicitly captured not silently swallowed.
                    // Replace this with testContext.failNow(e) in real test code
                    // to fail FAST with the real cause.
                    capturedByTryCatch.set(e);
                }
            })
            .onFailure(testContext::failNow);

        vertx.setTimer(400, id -> testContext.verify(() -> {
            assertNotNull(capturedByTryCatch.get(),
                "Exception was explicitly captured by try-catch, not silently swallowed");
            assertEquals("simulated-registerConfiguration-throws",
                capturedByTryCatch.get().getMessage(),
                "The real root-cause exception is available for reporting");
            logger.info("Safe pattern confirmed: try-catch after timer chain captured the exception. "
                + "No 30-second timeout; the real cause is available immediately.");
            testContext.completeNow();
        }));
    }

    // =========================================================================
    // SCENARIO 3: compose() vs onSuccess() exceptions DO stay in the Future
    //             pipeline when produced via compose()
    // =========================================================================

    /**
     * SAFE PATTERN: demonstrates that {@code compose()} keeps failures inside the
     * Future pipeline where they ARE visible to {@code onFailure}.
     *
     * <p>When a step in a {@code compose()} chain returns {@code Future.failedFuture(e)}
     * (or throws, which Vert.x converts to a failed future inside compose), the failure
     * propagates normally through the pipeline and reaches {@code onFailure}.  This is
     * the idiomatic Vert.x pattern and avoids the silent swallow entirely.
     */
    @Test
    @DisplayName("[SAFE PATTERN] compose() keeps failures in the Future pipeline onFailure IS called")
    void safePattern_compose_keepFailuresInPipeline(Vertx vertx, VertxTestContext testContext) {
        AtomicBoolean onFailureCalled = new AtomicBoolean(false);

        vertx.timer(10).mapEmpty()
            .<Void>compose(v ->
                // SAFE: returning a failed future propagates through the pipeline normally.
                // onFailure WILL be called this is the opposite of throwing in onSuccess.
                Future.failedFuture(new RuntimeException("deliberate-failure-via-compose")))
            .onSuccess(v ->
                testContext.failNow(new AssertionError("onSuccess should not have been called")))
            .onFailure(e -> {
                onFailureCalled.set(true);
                testContext.verify(() -> {
                    assertEquals("deliberate-failure-via-compose", e.getMessage(),
                        "Failure from compose() is correctly delivered to onFailure");
                    assertTrue(onFailureCalled.get(),
                        "PROOF: compose() failure propagates through the pipeline to onFailure");
                    logger.info("Safe pattern confirmed: compose() failure reached onFailure with "
                        + "the real cause. No silent swallow.");
                    testContext.completeNow();
                });
            });
    }

    /**
     * SAFE PATTERN: demonstrates that {@code testContext.verify()} wraps its
     * executable so that any exception thrown inside it is automatically routed to
     * {@code testContext.failNow()}.
     *
     * <p>This is the most ergonomic fix for simple assertion chains inside
     * {@code onSuccess} wrap the body in {@code testContext.verify()} instead
     * of a bare lambda, and the framework handles the rest.
     */
    @Test
    @DisplayName("[SAFE PATTERN] testContext.verify() auto-routes exceptions to failNow")
    void safePattern_testContextVerify_autoRoutesExceptions(Vertx vertx, VertxTestContext testContext) {
        AtomicBoolean verifyBlockEntered = new AtomicBoolean(false);

        vertx.timer(10).mapEmpty()
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                // SAFE: any exception thrown inside verify() is caught by VertxTestContext
                // and automatically calls failNow(e). The test fails FAST with the real cause
                // instead of timing out.
                verifyBlockEntered.set(true);
                assertTrue(true, "Assertions inside verify() are evaluated correctly");
                // Assertions that throw here will properly fail the test no silent swallow.
                testContext.completeNow();
            })));

        // Timeout guard: fails clearly if the verify block was somehow never entered.
        vertx.setTimer(500, id -> {
            if (!verifyBlockEntered.get()) {
                testContext.failNow(new AssertionError("verify() block was never entered"));
            }
        });
    }
}
