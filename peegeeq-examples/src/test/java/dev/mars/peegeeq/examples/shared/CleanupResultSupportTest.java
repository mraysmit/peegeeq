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

package dev.mars.peegeeq.examples.shared;

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(TestCategories.CORE)
class CleanupResultSupportTest {

    @Test
    void succeedsWhenBothCloseOperationsSucceed() {
        Future<Void> result = CleanupResultSupport.merge(
            Future.succeededFuture(),
            Future.succeededFuture());

        assertTrue(result.succeeded());
    }

    @Test
    void reportsPrimaryFailure() {
        RuntimeException primaryFailure = new RuntimeException("factory close failed");

        Future<Void> result = CleanupResultSupport.merge(
            Future.failedFuture(primaryFailure),
            Future.succeededFuture());

        assertTrue(result.failed());
        assertSame(primaryFailure, result.cause());
        assertEquals(0, result.cause().getSuppressed().length);
    }

    @Test
    void reportsSecondaryFailureWhenPrimarySucceeds() {
        RuntimeException secondaryFailure = new RuntimeException("manager close failed");

        Future<Void> result = CleanupResultSupport.merge(
            Future.succeededFuture(),
            Future.failedFuture(secondaryFailure));

        assertTrue(result.failed());
        assertSame(secondaryFailure, result.cause());
    }

    @Test
    void suppressesSecondaryFailureWhenBothCloseOperationsFail() {
        RuntimeException primaryFailure = new RuntimeException("factory close failed");
        RuntimeException secondaryFailure = new RuntimeException("manager close failed");

        Future<Void> result = CleanupResultSupport.merge(
            Future.failedFuture(primaryFailure),
            Future.failedFuture(secondaryFailure));

        assertTrue(result.failed());
        assertSame(primaryFailure, result.cause());
        assertEquals(1, result.cause().getSuppressed().length);
        assertSame(secondaryFailure, result.cause().getSuppressed()[0]);
    }

    @Test
    void doesNotSuppressTheSameFailureInstance() {
        RuntimeException sharedFailure = new RuntimeException("shared close failure");

        Future<Void> result = CleanupResultSupport.merge(
            Future.failedFuture(sharedFailure),
            Future.failedFuture(sharedFailure));

        assertTrue(result.failed());
        assertSame(sharedFailure, result.cause());
        assertEquals(0, result.cause().getSuppressed().length);
    }

    @Test
    void waitsForBothCloseOperationsBeforeCompleting() {
        Promise<Void> primaryClose = Promise.promise();
        Promise<Void> secondaryClose = Promise.promise();
        RuntimeException primaryFailure = new RuntimeException("factory close failed");

        Future<Void> result = CleanupResultSupport.merge(
            primaryClose.future(),
            secondaryClose.future());

        primaryClose.fail(primaryFailure);
        assertFalse(result.isComplete());

        secondaryClose.complete();
        assertTrue(result.failed());
        assertSame(primaryFailure, result.cause());
    }
}
