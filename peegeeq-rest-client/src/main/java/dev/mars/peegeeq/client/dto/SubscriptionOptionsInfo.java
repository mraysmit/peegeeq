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

/**
 * Information about subscription options for a consumer group.
 */
public record SubscriptionOptionsInfo(
    String setupId,
    String queueName,
    String groupName,
    String status,
    int maxConcurrency,
    long visibilityTimeoutMs,
    int maxRetries,
    long retryDelayMs,
    boolean autoAcknowledge,
    String deadLetterQueue
) {
    /**
     * Creates default subscription options.
     */
    public static SubscriptionOptionsInfo defaults(String setupId, String queueName, String groupName) {
        return new SubscriptionOptionsInfo(
            setupId, queueName, groupName, "ACTIVE",
            1, 30000, 3, 1000, false, null
        );
    }
}

