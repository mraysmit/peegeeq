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

package dev.mars.peegeeq.rest.webhook;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a webhook subscription for push-based message delivery.
 * 
 * When a subscription is active, messages are pushed to the specified webhook URL
 * via HTTP POST requests. This follows the scalable push-based pattern instead of
 * the anti-pattern request/response polling.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-22
 * @version 1.0
 */
public class WebhookSubscription {
    
    private final String subscriptionId;
    private final String setupId;
    private final String queueName;
    private final String webhookUrl;
    private final Map<String, String> headers;
    private final Map<String, String> filters;
    private final Instant createdAt;
    private volatile WebhookSubscriptionStatus status;
    private volatile Instant lastDeliveryAttempt;
    private volatile Instant lastSuccessfulDelivery;
    private volatile int consecutiveFailures;
    
    public WebhookSubscription(String subscriptionId, String setupId, String queueName,
                              String webhookUrl, Map<String, String> headers,
                              Map<String, String> filters) {
        this.subscriptionId = Objects.requireNonNull(subscriptionId, "subscriptionId cannot be null");
        this.setupId = Objects.requireNonNull(setupId, "setupId cannot be null");
        this.queueName = Objects.requireNonNull(queueName, "queueName cannot be null");
        this.webhookUrl = Objects.requireNonNull(webhookUrl, "webhookUrl cannot be null");
        this.headers = headers;
        this.filters = filters;
        this.createdAt = Instant.now();
        this.status = WebhookSubscriptionStatus.ACTIVE;
        this.consecutiveFailures = 0;
    }
    
    // Getters
    public String getSubscriptionId() {
        return subscriptionId;
    }
    
    public String getSetupId() {
        return setupId;
    }
    
    public String getQueueName() {
        return queueName;
    }
    
    public String getWebhookUrl() {
        return webhookUrl;
    }
    
    public Map<String, String> getHeaders() {
        return headers;
    }
    
    public Map<String, String> getFilters() {
        return filters;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public WebhookSubscriptionStatus getStatus() {
        return status;
    }
    
    public void setStatus(WebhookSubscriptionStatus status) {
        this.status = status;
    }
    
    public Instant getLastDeliveryAttempt() {
        return lastDeliveryAttempt;
    }
    
    public void setLastDeliveryAttempt(Instant lastDeliveryAttempt) {
        this.lastDeliveryAttempt = lastDeliveryAttempt;
    }
    
    public Instant getLastSuccessfulDelivery() {
        return lastSuccessfulDelivery;
    }
    
    public void setLastSuccessfulDelivery(Instant lastSuccessfulDelivery) {
        this.lastSuccessfulDelivery = lastSuccessfulDelivery;
    }
    
    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }
    
    public void incrementConsecutiveFailures() {
        this.consecutiveFailures++;
    }
    
    public void resetConsecutiveFailures() {
        this.consecutiveFailures = 0;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WebhookSubscription that = (WebhookSubscription) o;
        return Objects.equals(subscriptionId, that.subscriptionId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(subscriptionId);
    }
    
    @Override
    public String toString() {
        return "WebhookSubscription{" +
                "subscriptionId='" + subscriptionId + '\'' +
                ", setupId='" + setupId + '\'' +
                ", queueName='" + queueName + '\'' +
                ", webhookUrl='" + webhookUrl + '\'' +
                ", status=" + status +
                ", consecutiveFailures=" + consecutiveFailures +
                '}';
    }
}
