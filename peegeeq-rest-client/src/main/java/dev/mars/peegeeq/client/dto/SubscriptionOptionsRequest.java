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
 *
 * <p>Serializes exactly the keys the server's subscription-options parser reads:
 * {@code startPosition} (FROM_NOW/FROM_BEGINNING/FROM_MESSAGE_ID/FROM_TIMESTAMP),
 * {@code startFromMessageId}, {@code startFromTimestamp} (ISO-8601 string),
 * {@code heartbeatIntervalSeconds} and {@code heartbeatTimeoutSeconds}. Null fields
 * are omitted server-side (the parser skips JSON nulls). The previous shape
 * (maxConcurrency/visibilityTimeoutMs/maxRetries/retryDelayMs/autoAcknowledge/
 * deadLetterQueue) had no reader on the server — every field was silently ignored
 * (reshaped 2026-08-10, consumer-groups contract review).
 */
public class SubscriptionOptionsRequest {

    private String startPosition;
    private Long startFromMessageId;
    private String startFromTimestamp;
    private Integer heartbeatIntervalSeconds;
    private Integer heartbeatTimeoutSeconds;

    public SubscriptionOptionsRequest() {}

    // Getters and setters
    public String getStartPosition() { return startPosition; }
    public void setStartPosition(String startPosition) { this.startPosition = startPosition; }

    public Long getStartFromMessageId() { return startFromMessageId; }
    public void setStartFromMessageId(Long startFromMessageId) { this.startFromMessageId = startFromMessageId; }

    public String getStartFromTimestamp() { return startFromTimestamp; }
    public void setStartFromTimestamp(String startFromTimestamp) { this.startFromTimestamp = startFromTimestamp; }

    public Integer getHeartbeatIntervalSeconds() { return heartbeatIntervalSeconds; }
    public void setHeartbeatIntervalSeconds(Integer heartbeatIntervalSeconds) { this.heartbeatIntervalSeconds = heartbeatIntervalSeconds; }

    public Integer getHeartbeatTimeoutSeconds() { return heartbeatTimeoutSeconds; }
    public void setHeartbeatTimeoutSeconds(Integer heartbeatTimeoutSeconds) { this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds; }

    // Fluent builder methods
    public SubscriptionOptionsRequest withStartPosition(String startPosition) {
        this.startPosition = startPosition;
        return this;
    }

    public SubscriptionOptionsRequest withStartFromMessageId(Long startFromMessageId) {
        this.startFromMessageId = startFromMessageId;
        return this;
    }

    public SubscriptionOptionsRequest withStartFromTimestamp(String startFromTimestamp) {
        this.startFromTimestamp = startFromTimestamp;
        return this;
    }

    public SubscriptionOptionsRequest withHeartbeatIntervalSeconds(Integer heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        return this;
    }

    public SubscriptionOptionsRequest withHeartbeatTimeoutSeconds(Integer heartbeatTimeoutSeconds) {
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
        return this;
    }
}
