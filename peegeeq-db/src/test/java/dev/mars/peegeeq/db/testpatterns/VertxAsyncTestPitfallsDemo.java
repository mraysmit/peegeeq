package dev.mars.peegeeq.db.testpatterns;

/*
 * Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
 */

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executable counter-examples that demonstrate how Vert.x + JUnit5 async tests can
 * silently swallow assertion failures.
 *
 * <p><b>This class is NOT a real test class.</b> Every test here uses a trivial
 * always-false assertion ({@code assertTrue(1 > 2)}) so that the *only* thing the
 * reader has to observe is whether the framework correctly reports the failure.
 * In most demos here, it does not.</p>
 *
 * <h2>Why this exists</h2>
 * <p>The Tier 1 async-test sweep across PeeGeeQ uncovered repeated cases where
 * assertion failures were swallowed by the Vert.x callback machinery, the test
 * context, or by hand-rolled sync bridges, producing green builds for broken
 * code. This class catalogues the exact patterns so that:
 * <ul>
 *   <li>Reviewers can recognise them on sight in PRs.</li>
 *   <li>New contributors can run a single demo and see the lie with their own eyes.</li>
 *   <li>Future tooling (ArchUnit / Checkstyle rules) has a fixture to validate against.</li>
 * </ul>
 *
 * <h2>How to run a single demo</h2>
 * <p>The class is {@link Disabled} at class level so it never participates in normal
 * builds. To observe an individual demo, temporarily remove the class-level
 * {@code @Disabled} (or move it to the specific method) and run:
 * <pre>
 * mvn test -pl :peegeeq-db -Dtest=VertxAsyncTestPitfallsDemo#timeoutMaskingAssertion
 * </pre>
 * Then compare the surefire output against the {@code EXPECTED} / {@code ACTUAL}
 * documentation on each method.
 *
 * <h2>Do not copy these patterns</h2>
 * <p>The canonical safe form is documented in
 * {@code docs-design/testing/PEEGEEQ_VERTX_TEST_SAFETY_PATTERNS.md} and demonstrated
 * by {@link #canonicalSafeFormCorrectlyReportsFailure()} at the bottom of this file.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-05-15
 */
@Disabled("Counter-example fixture. Enable individually to observe Vert.x async test failure swallowing. Do NOT enable in CI.")
// BLOCKING-EXEMPT: Pedagogical counter-example fixture (@Disabled in CI).
// The blocking and fire-and-forget patterns present in this class ARE the behaviours
// under demonstration  the class intentionally shows what NOT to do so that
// developers can observe Vert.x test failure-swallowing pitfalls first-hand.
@Tag("blocking-exempt")
@ExtendWith(VertxExtension.class)
public class VertxAsyncTestPitfallsDemo {

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        if (vertx != null) {
            vertx.close();
        }
    }

    // -------------------------------------------------------------------------
    // CATEGORY A: failures that appear as TIMEOUTS instead of assertion failures
    // (bad: hides the real diagnostic; at least the test does not pass)
    // -------------------------------------------------------------------------

    /**
     * Anti-pattern: assertion inside a bare {@code .onSuccess} lambda, with no
     * {@code ctx.verify(...)} wrapper.
     * <p>
     * EXPECTED (an honest framework): "AssertionFailedError: 1 &gt; 2"
     * <br>
     * ACTUAL: test hangs until {@code awaitCompletion} default timeout, surefire
     * reports a generic timeout failure. The {@code AssertionError} is caught by
     * Vert.x as a handler-thrown exception and logged at WARN  it never reaches
     * the {@link VertxTestContext}.
     */
    @Test
    @DisplayName("A1: assertion in raw .onSuccess is masked as a timeout")
    void timeoutMaskingAssertion(VertxTestContext ctx) {
        Future.succeededFuture("value")
            .onSuccess(v -> {
                assertTrue(1 > 2, "this failure will be swallowed");
                ctx.completeNow();
            })
            .onFailure(ctx::failNow);
    }

    /**
     * Anti-pattern: terminal {@code .onSuccess} with no {@code .onFailure} branch.
     * If the future fails, nothing routes the cause to the test context.
     * <p>
     * EXPECTED: "RuntimeException: hidden cause"
     * <br>
     * ACTUAL: test hangs to timeout; the real cause is logged but not reported
     * by surefire.
     */
    @Test
    @DisplayName("A2: future failure with no .onFailure is masked as a timeout")
    void timeoutMaskingFutureFailure(VertxTestContext ctx) {
        Future.<String>failedFuture(new RuntimeException("hidden cause"))
            .onSuccess(v -> ctx.completeNow());
        // no .onFailure  cause vanishes
    }

    // -------------------------------------------------------------------------
    // CATEGORY B: failures that appear as GREEN BUILDS (catastrophic)
    // -------------------------------------------------------------------------

    /**
     * Anti-pattern: manually constructing a {@code VertxTestContext}, calling
     * {@code awaitCompletion} with a short timeout, ignoring its boolean return,
     * and writing trivial post-await assertions against partial state.
     * <p>
     * EXPECTED: timeout failure ("test did not complete within 100ms").
     * <br>
     * ACTUAL: returns green. The future never completed, but {@code 1 &gt; 0} is
     * trivially true and there is no other check.
     */
    @Test
    @DisplayName("B1: ignored awaitCompletion return value lets the test pass-by-timeout")
    void passByIgnoredAwaitTimeout() throws InterruptedException {
        VertxTestContext ctx = new VertxTestContext();
        // future that will never complete
        vertx.setTimer(60_000, id -> ctx.completeNow());
        ctx.awaitCompletion(100, TimeUnit.MILLISECONDS); // return value discarded
        // post-await trivial assertion that has nothing to do with the work above
        assertTrue(1 > 0, "this passes even though the async work never finished");
    }

    /**
     * Anti-pattern: {@code completeNow()} called in the first {@code .compose}
     * step, with assertions in later steps that will be silently ignored once
     * the context is already complete.
     * <p>
     * EXPECTED: "AssertionFailedError: 1 &gt; 2"
     * <br>
     * ACTUAL: green. The second compose step's failure is delivered to a context
     * that is already complete; further {@code failNow} calls are no-ops.
     */
    @Test
    @DisplayName("B2: completeNow() on the wrong compose branch masks downstream failures")
    void passByEarlyCompleteNow(VertxTestContext ctx) {
        Future.succeededFuture("step1")
            .compose(a -> {
                ctx.completeNow(); // PREMATURE  test is done as far as JUnit knows
                return Future.succeededFuture("step2");
            })
            .compose(b -> {
                // this assertion failure has nowhere to go: ctx is already complete
                assertTrue(1 > 2, "swallowed; ctx already complete");
                return Future.succeededFuture();
            })
            .onFailure(ctx::failNow); // failNow on completed context is ignored
    }

    /**
     * Anti-pattern: synchronous bridge via {@code .toCompletionStage().get()}
     * combined with a broad {@code catch (Exception)} that silently drops the
     * wrapped {@link AssertionError}.
     * <p>
     * EXPECTED: "AssertionFailedError: 1 &gt; 2"
     * <br>
     * ACTUAL: green. The {@code AssertionError} is wrapped in an
     * {@link ExecutionException} and caught (and discarded) by the broad
     * {@code catch} block.
     */
    @Test
    @DisplayName("B3: sync bridge + broad catch swallows wrapped AssertionError")
    void passByBridgeSwallowingExecutionException() {
        Future<Void> work = Future.future(promise -> {
            // pretend this is an assertion happening on a worker thread
            try {
                assertTrue(1 > 2, "real failure inside the future");
                promise.complete();
            } catch (AssertionError ae) {
                promise.fail(ae);
            }
        });

        try {
            work.toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS);
        } catch (Exception swallowed) {
            // anti-pattern: catch Exception, log nothing, move on
            // surefire sees no exception escape  test passes
        }
        // no assertion after the try/catch; the failure is gone
    }

    /**
     * Anti-pattern: {@code ctx.verify(...)} is called <i>after</i>
     * {@code ctx.completeNow()} has already been invoked on a different branch
     * of the same chain. The verify routes its {@code AssertionError} to a
     * test context that is already complete, so the failure is dropped.
     * <p>
     * EXPECTED: "AssertionFailedError: 1 &gt; 2"
     * <br>
     * ACTUAL: green. {@code VertxTestContext} ignores failures after completion.
     */
    @Test
    @DisplayName("B4: ctx.verify after ctx.completeNow drops the AssertionError")
    void passByVerifyAfterCompleteNow(VertxTestContext ctx) {
        Future.succeededFuture("ok")
            .onSuccess(v -> {
                ctx.completeNow(); // FIRST  context is now closed
                // SECOND  verify on a closed context is a no-op
                ctx.verify(() -> assertTrue(1 > 2, "swallowed; ctx closed"));
            });
    }

    // -------------------------------------------------------------------------
    // CONTROL: the canonical safe form, included so the contrast is unambiguous
    // -------------------------------------------------------------------------

    /**
     * Canonical Tier 1 safe form: {@code .onComplete(ctx.succeeding(x -> ctx.verify(...)))}
     * with assertions wrapped in {@code ctx.verify} and exactly one
     * {@code ctx.completeNow()} on the intended terminal branch.
     * <p>
     * EXPECTED: "AssertionFailedError: 1 &gt; 2"
     * <br>
     * ACTUAL: matches expected. Surefire reports the assertion failure with the
     * correct message and stack frame. This is what the rest of the codebase
     * should look like.
     */
    @Test
    @DisplayName("CONTROL: canonical safe form correctly reports the failure")
    void canonicalSafeFormCorrectlyReportsFailure(VertxTestContext ctx) {
        Future.succeededFuture("value")
            .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                assertTrue(1 > 2, "this failure WILL be reported");
                ctx.completeNow();
            })));
    }
}
