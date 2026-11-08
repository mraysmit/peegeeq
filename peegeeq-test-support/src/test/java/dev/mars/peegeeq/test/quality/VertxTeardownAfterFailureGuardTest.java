package dev.mars.peegeeq.test.quality;

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

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Finding 13: after a failed test, an asynchronous {@code @AfterEach} must still be awaited.
 *
 * <p>A test method that throws leaves its {@link VertxTestContext} in the vertx-junit5 store, and
 * vertx-junit5 5.1 hands it to the {@code @AfterEach}. When that context has failed, the teardown
 * is cancelled and Vert.x closes under it. A teardown that closes pools, containers or child
 * processes is then cut off, and the next test fails for a second reason.
 * {@code VertxTeardownAfterFailureExtension}, registered by service loading like every suite
 * here, removes the leftover contexts. (On 5.0.x the teardown was not awaited at all after a
 * failed test, and the extension also awaited it.) The fixtures cover a body that throws with no
 * context, with a failed context, and with an open checkpoint. The run enables extension
 * autodetection exactly as the surefire configuration does. The fixtures are selected only
 * through {@link EngineTestKit}.</p>
 */
@Tag(TestCategories.CORE)
class VertxTeardownAfterFailureGuardTest {

    @Test
    void asyncTeardownOfAFailedTestRunsToCompletion() {
        Throwable reported = runFailingFixture(FailingTestWithAsyncTeardownFixture.class);

        assertBodyFailureReportedAndTeardownCompleted(reported);
        assertEquals(0, reported.getSuppressed().length,
                "the teardown completed, so nothing may be added to the reported failure: "
                        + Arrays.toString(reported.getSuppressed()));
    }

    /**
     * The test method fails its own context and then throws. vertx-junit5 skips the join, so the
     * failed context stays in the store next to the teardown context. The teardown must still be
     * awaited; the failed context of the test method must not stop the wait. Its failure is kept
     * as the one suppressed exception of the reported failure.
     */
    @Test
    void asyncTeardownRunsToCompletionWhenTheTestMethodFailedItsContextAndThrew() {
        Throwable reported = runFailingFixture(ContextFailedThenBodyThrowsFixture.class);

        assertBodyFailureReportedAndTeardownCompleted(reported);
        assertEquals(1, reported.getSuppressed().length,
                "the failure of the test method's context must be kept: " + Arrays.toString(reported.getSuppressed()));
        assertEquals(CONTEXT_FAILURE, reported.getSuppressed()[0].getMessage());
    }

    /**
     * The test method leaves a checkpoint open and then throws. The open context of the test
     * method must not be awaited in place of the teardown: that waits for the whole timeout and
     * reports a teardown timeout although the teardown finished.
     */
    @Test
    void asyncTeardownIsNotReportedAsTimedOutWhenTheTestMethodLeftACheckpointOpenAndThrew() {
        Throwable reported = runFailingFixture(CheckpointOpenThenBodyThrowsFixture.class);

        assertBodyFailureReportedAndTeardownCompleted(reported);
        assertEquals(0, reported.getSuppressed().length,
                "the teardown completed, so nothing may be added to the reported failure: "
                        + Arrays.toString(reported.getSuppressed()));
    }

    private static Throwable runFailingFixture(Class<?> fixture) {
        teardownCompleted.set(false);
        teardownFailure.set(null);

        EngineExecutionResults results = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(fixture))
                .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
                .execute();

        results.testEvents().assertStatistics(statistics ->
                statistics.started(1).finished(1).succeeded(0).failed(1));
        return results.testEvents().failed().list().get(0)
                .getRequiredPayload(TestExecutionResult.class)
                .getThrowable()
                .orElseThrow();
    }

    private static void assertBodyFailureReportedAndTeardownCompleted(Throwable reported) {
        assertEquals(BODY_FAILURE, reported.getMessage(), "the failure of the test body must be the reported failure");
        assertNull(teardownFailure.get(), "the teardown was cut off: " + teardownFailure.get());
        assertTrue(teardownCompleted.get(),
                "the asynchronous teardown of a failed test must run to completion before Vert.x closes");
    }

    private static final String BODY_FAILURE = "intentional fixture failure";
    private static final String CONTEXT_FAILURE = "intentional context failure";
    private static final AtomicBoolean teardownCompleted = new AtomicBoolean();
    private static final AtomicReference<Throwable> teardownFailure = new AtomicReference<>();

    private static void asyncTeardown(Vertx vertx, VertxTestContext testContext) {
        vertx.timer(500)
                .onSuccess(ignored -> {
                    teardownCompleted.set(true);
                    testContext.completeNow();
                })
                .onFailure(error -> {
                    teardownFailure.set(error);
                    testContext.failNow(error);
                });
    }

    @ExtendWith(VertxExtension.class)
    static class FailingTestWithAsyncTeardownFixture {
        @Test
        void failsAtOnce() {
            throw new AssertionError(BODY_FAILURE);
        }

        @AfterEach
        void tearDown(Vertx vertx, VertxTestContext testContext) {
            asyncTeardown(vertx, testContext);
        }
    }

    @ExtendWith(VertxExtension.class)
    static class ContextFailedThenBodyThrowsFixture {
        @Test
        void failsItsContextThenThrows(VertxTestContext testContext) {
            testContext.failNow(new AssertionError(CONTEXT_FAILURE));
            throw new AssertionError(BODY_FAILURE);
        }

        @AfterEach
        void tearDown(Vertx vertx, VertxTestContext testContext) {
            asyncTeardown(vertx, testContext);
        }
    }

    @ExtendWith(VertxExtension.class)
    @Timeout(value = 3, timeUnit = TimeUnit.SECONDS)
    static class CheckpointOpenThenBodyThrowsFixture {
        @Test
        void leavesACheckpointOpenThenThrows(VertxTestContext testContext) {
            testContext.checkpoint();
            throw new AssertionError(BODY_FAILURE);
        }

        @AfterEach
        void tearDown(Vertx vertx, VertxTestContext testContext) {
            asyncTeardown(vertx, testContext);
        }
    }
}
