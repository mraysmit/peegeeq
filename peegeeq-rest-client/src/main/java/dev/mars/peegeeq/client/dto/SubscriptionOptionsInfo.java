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

package dev.mars.peegeeq.client.dto;

import java.time.Instant;

/**
 * Subscription options for a consumer group, as both subscription endpoints emit them.
 *
 * <p>Union of the update payload and the get payload, each carrying a nested
 * {@code subscriptionOptions} object with {@code startPosition} and the heartbeat
 * settings: {@code status} is null where the payload omits it (the update response),
 * and {@code startFromMessageId}/{@code startFromTimestamp} are null unless the
 * corresponding start position is configured. The previous shape (maxConcurrency/
 * visibilityTimeoutMs/maxRetries/retryDelayMs/autoAcknowledge/deadLetterQueue)
 * matched no server payload, and its defaults(...) factory fabricated values
 * (reshaped 2026-08-10, consumer-groups contract review).
 */
public record SubscriptionOptionsInfo(
    String setupId,
    String queueName,
    String groupName,
    String status,
    String startPosition,
    int heartbeatIntervalSeconds,
    int heartbeatTimeoutSeconds,
    Long startFromMessageId,
    Instant startFromTimestamp
) {
}
