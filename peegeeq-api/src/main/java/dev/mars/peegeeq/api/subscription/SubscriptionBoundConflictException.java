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

package dev.mars.peegeeq.api.subscription;

/**
 * Thrown when a settings update names a start bound that is not the stored start bound of
 * the subscription. The subscription row is unchanged.
 *
 * <p>The stored start bound is set when the subscription is created. A settings update
 * cannot move it. {@code FROM_NOW} also raises this failure on an existing subscription,
 * because it does not identify the stored bound.</p>
 *
 * <p>A typed exception so callers (e.g. REST handlers mapping it to HTTP 409) can classify
 * it by type rather than by message text.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-12
 * @version 1.0
 */
public class SubscriptionBoundConflictException extends IllegalStateException {
    public SubscriptionBoundConflictException(String message) {
        super(message);
    }
}
