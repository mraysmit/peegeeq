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

import dev.mars.peegeeq.api.messaging.StartPosition;

import java.time.Instant;

/**
 * A request to update the settings of an existing subscription.
 *
 * <p>A null field was not supplied by the caller. The update changes only the heartbeat
 * settings that are supplied. It never changes the lifecycle status and never moves the
 * stored start bound.</p>
 *
 * <p>The start position fields do not request a change. When {@code startPosition} is
 * supplied, the update succeeds only if it names the stored start bound. See
 * {@link SubscriptionService#updateSubscriptionSettings(String, String, SubscriptionSettingsUpdate)}.</p>
 *
 * @param heartbeatIntervalSeconds The new heartbeat interval, or null to keep the stored value
 * @param heartbeatTimeoutSeconds The new heartbeat timeout, or null to keep the stored value
 * @param startPosition The start position the caller names, or null when it names none
 * @param startFromMessageId The message id, required with {@link StartPosition#FROM_MESSAGE_ID}
 * @param startFromTimestamp The timestamp, required with {@link StartPosition#FROM_TIMESTAMP}
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-12
 * @version 1.0
 */
public record SubscriptionSettingsUpdate(
    Integer heartbeatIntervalSeconds,
    Integer heartbeatTimeoutSeconds,
    StartPosition startPosition,
    Long startFromMessageId,
    Instant startFromTimestamp
) {

    public SubscriptionSettingsUpdate {
        if (heartbeatIntervalSeconds != null && heartbeatIntervalSeconds <= 0) {
            throw new IllegalArgumentException("heartbeatIntervalSeconds must be positive");
        }
        if (heartbeatTimeoutSeconds != null && heartbeatTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("heartbeatTimeoutSeconds must be positive");
        }
        if (heartbeatIntervalSeconds != null && heartbeatTimeoutSeconds != null
                && heartbeatTimeoutSeconds <= heartbeatIntervalSeconds) {
            throw new IllegalArgumentException("heartbeatTimeoutSeconds must be greater than heartbeatIntervalSeconds");
        }
        if (startPosition == null && (startFromMessageId != null || startFromTimestamp != null)) {
            throw new IllegalArgumentException(
                "startPosition must be provided with startFromMessageId or startFromTimestamp");
        }
        if (startPosition == StartPosition.FROM_MESSAGE_ID && startFromMessageId == null) {
            throw new IllegalArgumentException("startFromMessageId must be provided when startPosition is FROM_MESSAGE_ID");
        }
        if (startPosition == StartPosition.FROM_TIMESTAMP && startFromTimestamp == null) {
            throw new IllegalArgumentException("startFromTimestamp must be provided when startPosition is FROM_TIMESTAMP");
        }
    }

    /**
     * Returns true when the caller names a start bound.
     */
    public boolean namesBound() {
        return startPosition != null;
    }
}
