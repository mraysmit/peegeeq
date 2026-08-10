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
 *
 * <p>Carries exactly the keys the endpoint reads: webhookUrl (required), headers
 * (optional custom headers added to every delivery request) and filters (optional).
 * The previous shape's secret/maxRetries/retryDelayMs/contentType fields were
 * serialized into keys the server never reads and were deleted (deadletter-webhooks
 * contract review, 2026-08-10).</p>
 */
public class WebhookSubscriptionRequest {

    private String webhookUrl;
    private Map<String, String> headers;
    private Map<String, String> filters;

    public WebhookSubscriptionRequest() {}

    public WebhookSubscriptionRequest(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    // Getters and setters
    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

    public Map<String, String> getFilters() { return filters; }
    public void setFilters(Map<String, String> filters) { this.filters = filters; }

    // Fluent builder methods
    public WebhookSubscriptionRequest withWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
        return this;
    }

    public WebhookSubscriptionRequest withHeaders(Map<String, String> headers) {
        this.headers = headers;
        return this;
    }

    public WebhookSubscriptionRequest withFilters(Map<String, String> filters) {
        this.filters = filters;
        return this;
    }
}
