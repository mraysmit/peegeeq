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

import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CloseSteps}: every step runs, a failure is logged at ERROR, and the first failure is
 * the result (finding 6). {@code eventually} discards the failure of its supplier, so a chain
 * of {@code eventually} steps reported a failed close as success.
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
class CloseStepsCoreTest {

    private static final Logger logger = LoggerFactory.getLogger(CloseStepsCoreTest.class);

    @Test
    void allStepsSucceedingGivesSuccessInOrder(VertxTestContext testContext) {
        List<String> ran = new ArrayList<>();
        CloseSteps.after(Future.succeededFuture(), logger)
                .then("a", () -> record(ran, "a"))
                .then("b", () -> record(ran, "b"))
                .result()
                .onComplete(result -> testContext.verify(() -> {
                    assertTrue(result.succeeded(), String.valueOf(result.cause()));
                    assertEquals(List.of("a", "b"), ran);
                    testContext.completeNow();
                }));
    }

    @Test
    @ExpectedErrorLog(logger = "dev.mars.peegeeq.api.lifecycle.CloseStepsCoreTest", message = "Close step 'a' failed: a broke",
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS, throwableType = IllegalStateException.class)
    void aFailedStepFailsTheResultAndTheNextStepStillRuns(VertxTestContext testContext) {
        List<String> ran = new ArrayList<>();
        CloseSteps.after(Future.succeededFuture(), logger)
                .then("a", () -> Future.failedFuture(new IllegalStateException("a broke")))
                .then("b", () -> record(ran, "b"))
                .result()
                .onComplete(result -> testContext.verify(() -> {
                    assertTrue(result.failed(), "a failed step must fail the result");
                    assertEquals("a broke", result.cause().getMessage());
                    assertEquals(List.of("b"), ran, "the next step must run after a failure");
                    testContext.completeNow();
                }));
    }

    @Test
    @ExpectedErrorLog(logger = "dev.mars.peegeeq.api.lifecycle.CloseStepsCoreTest", message = "Close step 'a' failed: a threw",
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS, throwableType = IllegalArgumentException.class)
    void aThrowingStepIsAFailedStep(VertxTestContext testContext) {
        List<String> ran = new ArrayList<>();
        CloseSteps.after(Future.succeededFuture(), logger)
                .then("a", () -> {
                    throw new IllegalArgumentException("a threw");
                })
                .then("b", () -> record(ran, "b"))
                .result()
                .onComplete(result -> testContext.verify(() -> {
                    assertInstanceOf(IllegalArgumentException.class, result.cause());
                    assertEquals(List.of("b"), ran);
                    testContext.completeNow();
                }));
    }

    @Test
    @ExpectedErrorLog(logger = "dev.mars.peegeeq.api.lifecycle.CloseStepsCoreTest", message = "Close step 'a' failed: returned no Future",
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS, throwableType = NullPointerException.class)
    void aStepReturningNullIsAFailedStep(VertxTestContext testContext) {
        CloseSteps.after(Future.succeededFuture(), logger)
                .then("a", () -> null)
                .result()
                .onComplete(result -> testContext.verify(() -> {
                    assertInstanceOf(NullPointerException.class, result.cause());
                    testContext.completeNow();
                }));
    }

    @Test
    @ExpectedErrorLog(logger = "dev.mars.peegeeq.api.lifecycle.CloseStepsCoreTest", message = "Close step 'a' failed: first",
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS, throwableType = IllegalStateException.class)
    @ExpectedErrorLog(logger = "dev.mars.peegeeq.api.lifecycle.CloseStepsCoreTest", message = "Close step 'b' failed: second",
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS, throwableType = IllegalStateException.class)
    void theFirstFailureIsTheResultAndLaterOnesAreSuppressed(VertxTestContext testContext) {
        CloseSteps.after(Future.succeededFuture(), logger)
                .then("a", () -> Future.failedFuture(new IllegalStateException("first")))
                .then("b", () -> Future.failedFuture(new IllegalStateException("second")))
                .result()
                .onComplete(result -> testContext.verify(() -> {
                    assertEquals("first", result.cause().getMessage());
                    assertEquals(1, result.cause().getSuppressed().length);
                    assertEquals("second", result.cause().getSuppressed()[0].getMessage());
                    testContext.completeNow();
                }));
    }

    @Test
    void aFailedPrecedingFutureIsTheResultAndEveryStepStillRuns(VertxTestContext testContext) {
        List<String> ran = new ArrayList<>();
        CloseSteps.after(Future.failedFuture(new IllegalStateException("start failed")), logger)
                .then("a", () -> record(ran, "a"))
                .result()
                .onComplete(result -> testContext.verify(() -> {
                    assertEquals("start failed", result.cause().getMessage(),
                            "the preceding failure is reported, not hidden by the cleanup");
                    assertEquals(List.of("a"), ran);
                    testContext.completeNow();
                }));
    }

    private static Future<Void> record(List<String> ran, String step) {
        ran.add(step);
        return Future.succeededFuture();
    }
}
