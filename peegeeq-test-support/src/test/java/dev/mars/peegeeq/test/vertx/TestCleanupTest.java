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

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.mars.peegeeq.test.vertx.TestCleanup.cleanup;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cleanup helper approved for tests (tasks.md, rulings of the 1b review): it runs every
 * cleanup exactly once, preserves an operation failure, returns a cleanup failure when the
 * operation succeeded, adds cleanup failures as suppressed exceptions when both fail, continues
 * later cleanup after an earlier cleanup failure, and treats a thrown exception or a null cleanup
 * Future as a cleanup failure. {@code Future.eventually} does none of the last four: it discards
 * the cleanup's failure.
 *
 * <p>Every Future here completes synchronously, so each result is settled when it is read.</p>
 */
@Tag(TestCategories.CORE)
class TestCleanupTest {

    private static final RuntimeException OPERATION_FAILURE = new RuntimeException("operation failed");

    @Test
    void successfulOperationAndCleanupKeepTheOperationValue() {
        AtomicInteger runs = new AtomicInteger();

        Future<String> result = Future.succeededFuture("value")
            .transform(cleanup(() -> {
                runs.incrementAndGet();
                return Future.succeededFuture();
            }));

        assertTrue(result.succeeded());
        assertEquals("value", result.result());
        assertEquals(1, runs.get(), "the cleanup runs exactly once");
    }

    @Test
    void cleanupFailureFailsASuccessfulOperation() {
        RuntimeException closeFailure = new RuntimeException("close failed");

        Future<String> result = Future.succeededFuture("value")
            .transform(cleanup(() -> Future.failedFuture(closeFailure)));

        assertTrue(result.failed(), "a failed cleanup must fail the chain");
        assertSame(closeFailure, result.cause());
    }

    @Test
    void operationFailureIsKeptAndTheCleanupFailureIsSuppressed() {
        RuntimeException operationFailure = new RuntimeException("operation failed");
        RuntimeException closeFailure = new RuntimeException("close failed");
        AtomicInteger runs = new AtomicInteger();

        Future<String> result = Future.<String>failedFuture(operationFailure)
            .transform(cleanup(() -> {
                runs.incrementAndGet();
                return Future.failedFuture(closeFailure);
            }));

        assertEquals(1, runs.get(), "the cleanup runs after a failed operation");
        assertSame(operationFailure, result.cause(), "the operation failure is the result");
        assertArrayEquals(new Throwable[] {closeFailure}, operationFailure.getSuppressed());
    }

    @Test
    void operationFailureIsKeptWhenTheCleanupSucceeds() {
        RuntimeException operationFailure = new RuntimeException("operation failed");

        Future<String> result = Future.<String>failedFuture(operationFailure)
            .transform(cleanup(Future::succeededFuture));

        assertSame(operationFailure, result.cause());
        assertEquals(0, operationFailure.getSuppressed().length);
    }

    @Test
    void laterCleanupRunsAfterAnEarlierCleanupFailureAndItsFailureIsSuppressed() {
        RuntimeException first = new RuntimeException("first close failed");
        RuntimeException second = new RuntimeException("second close failed");
        List<String> order = new ArrayList<>();

        Future<Void> result = Future.<Void>succeededFuture()
            .transform(cleanup(
                () -> {
                    order.add("first");
                    return Future.failedFuture(first);
                },
                () -> {
                    order.add("second");
                    return Future.failedFuture(second);
                },
                () -> {
                    order.add("third");
                    return Future.succeededFuture();
                }));

        assertEquals(List.of("first", "second", "third"), order);
        assertSame(first, result.cause(), "the first cleanup failure is the result");
        assertArrayEquals(new Throwable[] {second}, first.getSuppressed());
    }

    @Test
    void aCleanupStartsOnlyAfterThePreviousCleanupHasSettled() {
        Promise<Void> firstClose = Promise.promise();
        List<String> order = new ArrayList<>();

        Future<Void> result = Future.<Void>succeededFuture()
            .transform(cleanup(
                () -> {
                    order.add("first");
                    return firstClose.future();
                },
                () -> {
                    order.add("second");
                    return Future.succeededFuture();
                }));

        assertEquals(List.of("first"), order, "the second cleanup must wait for the first");
        assertFalse(result.isComplete());

        firstClose.complete();

        assertEquals(List.of("first", "second"), order);
        assertTrue(result.succeeded());
    }

    @Test
    void aThrowingCleanupIsACleanupFailureAndLaterCleanupStillRuns() {
        IllegalStateException thrown = new IllegalStateException("close threw");
        AtomicInteger laterRuns = new AtomicInteger();

        Future<Void> result = Future.<Void>succeededFuture()
            .transform(cleanup(
                () -> {
                    throw thrown;
                },
                () -> {
                    laterRuns.incrementAndGet();
                    return Future.succeededFuture();
                }));

        assertSame(thrown, result.cause());
        assertEquals(1, laterRuns.get());
    }

    @Test
    void aThrownErrorIsACleanupFailure() {
        AssertionError thrown = new AssertionError("assertion inside a cleanup");

        Future<Void> result = Future.<Void>succeededFuture()
            .transform(cleanup(() -> {
                throw thrown;
            }));

        assertSame(thrown, result.cause());
    }

    @Test
    void aNullCleanupFutureIsACleanupFailure() {
        Future<Void> result = Future.<Void>succeededFuture()
            .transform(cleanup(() -> null));

        assertTrue(result.failed());
        assertInstanceOf(NullPointerException.class, result.cause());
        assertEquals("Cleanup step 1 returned null instead of a Future", result.cause().getMessage());
    }

    @Test
    void runIsTheTeardownFormAndReportsTheFirstFailure() {
        RuntimeException closeFailure = new RuntimeException("close failed");
        AtomicInteger runs = new AtomicInteger();

        Future<Void> result = TestCleanup.run(
            () -> {
                runs.incrementAndGet();
                return Future.failedFuture(closeFailure);
            },
            () -> {
                runs.incrementAndGet();
                return Future.succeededFuture();
            });

        assertEquals(2, runs.get());
        assertSame(closeFailure, result.cause());
    }

    @Test
    void theSameFailureIsNotSuppressedOntoItself() {
        Future<Void> result = Future.<Void>failedFuture(OPERATION_FAILURE)
            .transform(cleanup(() -> Future.failedFuture(OPERATION_FAILURE)));

        assertSame(OPERATION_FAILURE, result.cause());
        assertEquals(0, OPERATION_FAILURE.getSuppressed().length);
    }
}
