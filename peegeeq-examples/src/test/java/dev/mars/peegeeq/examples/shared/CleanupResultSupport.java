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

import io.vertx.core.Future;

/**
 * Combines ordered cleanup results while retaining the primary failure.
 */
public final class CleanupResultSupport {

    private CleanupResultSupport() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Waits for both close operations and reports the primary failure first.
     * If both operations fail, the secondary failure is attached as suppressed.
     */
    public static Future<Void> merge(Future<Void> primaryClose, Future<Void> secondaryClose) {
        return Future.join(primaryClose, secondaryClose)
            .transform(ignored -> mergeCompletedResults(primaryClose, secondaryClose));
    }

    private static Future<Void> mergeCompletedResults(
            Future<Void> primaryClose,
            Future<Void> secondaryClose) {
        if (primaryClose.failed()) {
            Throwable failure = primaryClose.cause();
            if (secondaryClose.failed() && secondaryClose.cause() != failure) {
                failure.addSuppressed(secondaryClose.cause());
            }
            return Future.failedFuture(failure);
        }
        if (secondaryClose.failed()) {
            return Future.failedFuture(secondaryClose.cause());
        }
        return Future.succeededFuture();
    }
}
