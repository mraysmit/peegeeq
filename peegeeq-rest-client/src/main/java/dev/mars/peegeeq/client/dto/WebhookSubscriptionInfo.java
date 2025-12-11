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
 * Information about a webhook subscription.
 */
public record WebhookSubscriptionInfo(
    String subscriptionId,
    String setupId,
    String queueName,
    String webhookUrl,
    String status,
    int maxRetries,
    long retryDelayMs,
    long messagesDelivered,
    long messagesFailed,
    Instant createdAt,
    Instant lastDeliveryAt
) {
    /**
     * Creates a basic webhook subscription info.
     */
    public static WebhookSubscriptionInfo create(String subscriptionId, String setupId, 
                                                   String queueName, String webhookUrl) {
        return new WebhookSubscriptionInfo(
            subscriptionId, setupId, queueName, webhookUrl, 
            "ACTIVE", 3, 1000, 0, 0, Instant.now(), null
        );
    }
}

