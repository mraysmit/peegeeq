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

import java.util.Objects;

/**
 * Thrown when a subscription lifecycle call is refused because of the current status of
 * the subscription. The subscription row is unchanged.
 *
 * <p>Examples: a heartbeat on a STOPPED, DEAD, or CANCELLED subscription; {@code pause} on
 * a DEAD or CANCELLED subscription; {@code resume} on a STOPPED or CANCELLED subscription;
 * {@code subscribe} on a CANCELLED subscription.</p>
 *
 * <p>A typed exception so callers (e.g. REST handlers mapping it to HTTP 409, or a running
 * consumer group that must stop) can classify it by type and status rather than by
 * message text.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-12
 * @version 1.0
 */
public class SubscriptionStateException extends IllegalStateException {

    private final SubscriptionState currentState;

    public SubscriptionStateException(String message, SubscriptionState currentState) {
        super(message);
        this.currentState = Objects.requireNonNull(currentState, "currentState cannot be null");
    }

    /**
     * Returns the status that refused the call.
     */
    public SubscriptionState getCurrentState() {
        return currentState;
    }
}
