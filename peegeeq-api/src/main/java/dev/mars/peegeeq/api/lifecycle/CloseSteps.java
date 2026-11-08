package dev.mars.peegeeq.api.lifecycle;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
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

import io.vertx.core.Future;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A sequence of close steps that all run and that reports the first failure.
 *
 * <p>Each step runs after the previous one settles, whatever its outcome. A step that fails,
 * throws, or returns no Future is logged at ERROR on the owner's logger, because the component
 * it closes may still hold connections or threads. The result fails with the first failure;
 * later failures are suppressed on it. A failed preceding Future counts as the first failure.</p>
 *
 * <p>{@code Future.eventually} is not a substitute: it discards the failure of its supplier,
 * so a chain of {@code eventually} steps reports a failed close as success (finding 6).</p>
 */
public final class CloseSteps {

    private final Logger logger;
    private final AtomicReference<Throwable> firstFailure = new AtomicReference<>();
    private Future<Void> chain;

    private CloseSteps(Future<?> preceding, Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.chain = preceding.transform(outcome -> {
            if (outcome.failed()) {
                firstFailure.set(outcome.cause());
            }
            return Future.<Void>succeededFuture();
        });
    }

    /**
     * Starts a sequence after {@code preceding} settles.
     *
     * @param preceding the work the close follows, for example an in-flight start
     * @param logger    the logger of the closing component
     */
    public static CloseSteps after(Future<?> preceding, Logger logger) {
        return new CloseSteps(Objects.requireNonNull(preceding, "preceding"), logger);
    }

    /** Adds a step that runs after the previous step settles. */
    public CloseSteps then(String step, Supplier<Future<Void>> action) {
        chain = chain.compose(ignored -> run(step, action));
        return this;
    }

    /** The outcome of the whole sequence: success, or the first failure. */
    public Future<Void> result() {
        return chain.compose(ignored -> {
            Throwable failure = firstFailure.get();
            return failure == null ? Future.<Void>succeededFuture() : Future.<Void>failedFuture(failure);
        });
    }

    private Future<Void> run(String step, Supplier<Future<Void>> action) {
        Future<Void> stepFuture;
        try {
            stepFuture = Objects.requireNonNull(action.get(), "returned no Future");
        } catch (RuntimeException thrown) {
            stepFuture = Future.failedFuture(thrown);
        }
        return stepFuture.transform(outcome -> {
            if (outcome.failed()) {
                record(step, outcome.cause());
            }
            return Future.<Void>succeededFuture();
        });
    }

    private void record(String step, Throwable failure) {
        logger.error("Close step '{}' failed: {}", step, failure.getMessage(), failure);
        if (!firstFailure.compareAndSet(null, failure) && firstFailure.get() != failure) {
            firstFailure.get().addSuppressed(failure);
        }
    }
}
