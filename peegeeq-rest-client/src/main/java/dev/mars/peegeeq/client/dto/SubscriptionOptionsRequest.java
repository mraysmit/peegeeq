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
 * Request object for setting subscription options.
 */
public class SubscriptionOptionsRequest {

    private int maxConcurrency = 1;
    private long visibilityTimeoutMs = 30000;
    private int maxRetries = 3;
    private long retryDelayMs = 1000;
    private boolean autoAcknowledge = false;
    private String deadLetterQueue;

    public SubscriptionOptionsRequest() {}

    // Getters and setters
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }

    public long getVisibilityTimeoutMs() { return visibilityTimeoutMs; }
    public void setVisibilityTimeoutMs(long visibilityTimeoutMs) { this.visibilityTimeoutMs = visibilityTimeoutMs; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public long getRetryDelayMs() { return retryDelayMs; }
    public void setRetryDelayMs(long retryDelayMs) { this.retryDelayMs = retryDelayMs; }

    public boolean isAutoAcknowledge() { return autoAcknowledge; }
    public void setAutoAcknowledge(boolean autoAcknowledge) { this.autoAcknowledge = autoAcknowledge; }

    public String getDeadLetterQueue() { return deadLetterQueue; }
    public void setDeadLetterQueue(String deadLetterQueue) { this.deadLetterQueue = deadLetterQueue; }

    // Fluent builder methods
    public SubscriptionOptionsRequest withMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
        return this;
    }

    public SubscriptionOptionsRequest withVisibilityTimeoutMs(long visibilityTimeoutMs) {
        this.visibilityTimeoutMs = visibilityTimeoutMs;
        return this;
    }

    public SubscriptionOptionsRequest withMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
        return this;
    }

    public SubscriptionOptionsRequest withRetryDelayMs(long retryDelayMs) {
        this.retryDelayMs = retryDelayMs;
        return this;
    }

    public SubscriptionOptionsRequest withAutoAcknowledge(boolean autoAcknowledge) {
        this.autoAcknowledge = autoAcknowledge;
        return this;
    }

    public SubscriptionOptionsRequest withDeadLetterQueue(String deadLetterQueue) {
        this.deadLetterQueue = deadLetterQueue;
        return this;
    }
}

