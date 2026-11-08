package dev.mars.peegeeq.test.vertx;

/*
 * Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Cleanup for test Future chains that reports a failed cleanup.
 *
 * <p>{@code Future.eventually(supplier)} runs a cleanup and discards its outcome: a failed or
 * throwing close leaves the chain successful, so the test passes while the resource stays open.
 * {@link #cleanup} replaces it in tests. The steps run in order, each after the previous one has
 * settled, and every step runs exactly once whatever the outcome before it. The result is:</p>
 * <ul>
 *   <li>the operation's value when the operation and every step succeeded;</li>
 *   <li>the operation's failure when the operation failed, with each step failure added to it
 *       as a suppressed exception;</li>
 *   <li>otherwise the first step failure, with later step failures suppressed onto it.</li>
 * </ul>
 * <p>A step that throws, or returns {@code null} instead of a Future, is a step failure.</p>
 *
 * <pre>{@code
 * group.start()
 *     .compose(v -> producer.send("message"))
 *     .transform(cleanup(() -> group.close(), () -> manager.closeReactive()))
 *     .onSuccess(v -> testContext.completeNow())
 *     .onFailure(testContext::failNow);
 * }</pre>
 *
 * <p>The guard in {@code VertxAsyncForbiddenPatternsGuardTest} rejects {@code .eventually(} in
 * test sources. Production code may still use it where a cleanup failure is logged and must not
 * change the outcome.</p>
 */
public final class TestCleanup {

    private TestCleanup() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * A function for {@link Future#transform} that runs {@code steps} in order once the operation
     * has settled, and returns the operation's outcome unless a step failed.
     */
    @SafeVarargs
    public static <T> Function<AsyncResult<T>, Future<T>> cleanup(Supplier<? extends Future<?>>... steps) {
        List<Supplier<? extends Future<?>>> ordered = List.of(steps);
        return outcome -> runSteps(ordered, 0, outcome.failed() ? outcome.cause() : null)
            .compose(firstFailure -> firstFailure == null
                ? Future.succeededFuture(outcome.result())
                : Future.failedFuture(firstFailure));
    }

    /** The teardown form: runs {@code steps} in order and fails with the first step failure. */
    @SafeVarargs
    public static Future<Void> run(Supplier<? extends Future<?>>... steps) {
        return Future.<Void>succeededFuture().transform(cleanup(steps));
    }

    /**
     * Runs the step at {@code index} and the steps after it. The returned Future always succeeds;
     * its value is the first failure seen, or {@code null} when there was none.
     */
    private static Future<Throwable> runSteps(List<Supplier<? extends Future<?>>> steps, int index,
                                              Throwable firstFailure) {
        if (index == steps.size()) {
            return Future.succeededFuture(firstFailure);
        }
        Future<?> step;
        try {
            step = steps.get(index).get();
            if (step == null) {
                step = Future.failedFuture(new NullPointerException(
                    "Cleanup step " + (index + 1) + " returned null instead of a Future"));
            }
        } catch (Throwable thrown) {
            step = Future.failedFuture(thrown);
        }
        return step.transform(stepOutcome ->
            runSteps(steps, index + 1, record(firstFailure, stepOutcome)));
    }

    private static Throwable record(Throwable firstFailure, AsyncResult<?> stepOutcome) {
        if (stepOutcome.succeeded()) {
            return firstFailure;
        }
        Throwable stepFailure = stepOutcome.cause();
        if (firstFailure == null) {
            return stepFailure;
        }
        if (stepFailure != firstFailure) {
            firstFailure.addSuppressed(stepFailure);
        }
        return firstFailure;
    }
}
