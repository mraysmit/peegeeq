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

import java.util.Map;

/**
 * Request object for creating a webhook subscription.
 */
public class WebhookSubscriptionRequest {

    private String webhookUrl;
    private String secret;
    private Map<String, String> headers;
    private int maxRetries = 3;
    private long retryDelayMs = 1000;
    private String contentType = "application/json";

    public WebhookSubscriptionRequest() {}

    public WebhookSubscriptionRequest(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    // Getters and setters
    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public long getRetryDelayMs() { return retryDelayMs; }
    public void setRetryDelayMs(long retryDelayMs) { this.retryDelayMs = retryDelayMs; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    // Fluent builder methods
    public WebhookSubscriptionRequest withWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
        return this;
    }

    public WebhookSubscriptionRequest withSecret(String secret) {
        this.secret = secret;
        return this;
    }

    public WebhookSubscriptionRequest withMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
        return this;
    }

    public WebhookSubscriptionRequest withRetryDelayMs(long retryDelayMs) {
        this.retryDelayMs = retryDelayMs;
        return this;
    }
}

