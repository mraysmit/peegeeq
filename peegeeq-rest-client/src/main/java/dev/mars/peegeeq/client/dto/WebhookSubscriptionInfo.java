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
 *
 * <p>Carries exactly the fields the endpoints emit. The create payload carries
 * {subscriptionId, setupId, queueName, webhookUrl, status, createdAt}; the get
 * payload adds consecutiveFailures and, once a delivery has been attempted,
 * lastDeliveryAttempt/lastSuccessfulDelivery. consecutiveFailures is therefore
 * nullable — the create payload omits it. The previous shape's maxRetries/
 * retryDelayMs/messagesDelivered/messagesFailed/lastDeliveryAt fields had no
 * source in any payload and were deleted (deadletter-webhooks contract review,
 * 2026-08-10).</p>
 */
public record WebhookSubscriptionInfo(
    String subscriptionId,
    String setupId,
    String queueName,
    String webhookUrl,
    String status,
    Instant createdAt,
    Integer consecutiveFailures,
    Instant lastDeliveryAttempt,
    Instant lastSuccessfulDelivery
) {
}
