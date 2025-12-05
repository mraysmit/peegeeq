package dev.mars.peegeeq.api.deadletter;

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

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a message in the dead letter queue.
 * 
 * This record is part of the PeeGeeQ API layer, providing
 * abstraction over implementation-specific dead letter queue details.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-05
 * @version 1.0
 */
public record DeadLetterMessageInfo(
    long id,
    String originalTable,
    long originalId,
    String topic,
    String payload,
    Instant originalCreatedAt,
    Instant failedAt,
    String failureReason,
    int retryCount,
    Map<String, String> headers,
    String correlationId,
    String messageGroup
) {
    /**
     * Compact constructor with validation.
     */
    public DeadLetterMessageInfo {
        Objects.requireNonNull(originalTable, "Original table cannot be null");
        Objects.requireNonNull(topic, "Topic cannot be null");
        Objects.requireNonNull(payload, "Payload cannot be null");
        Objects.requireNonNull(originalCreatedAt, "Original created at cannot be null");
        Objects.requireNonNull(failedAt, "Failed at cannot be null");
        Objects.requireNonNull(failureReason, "Failure reason cannot be null");
        headers = headers != null ? Map.copyOf(headers) : null;
    }
    
    /**
     * Builder for creating DeadLetterMessageInfo instances.
     */
    public static class Builder {
        private long id;
        private String originalTable;
        private long originalId;
        private String topic;
        private String payload;
        private Instant originalCreatedAt;
        private Instant failedAt;
        private String failureReason;
        private int retryCount;
        private Map<String, String> headers;
        private String correlationId;
        private String messageGroup;
        
        public Builder id(long id) { this.id = id; return this; }
        public Builder originalTable(String originalTable) { this.originalTable = originalTable; return this; }
        public Builder originalId(long originalId) { this.originalId = originalId; return this; }
        public Builder topic(String topic) { this.topic = topic; return this; }
        public Builder payload(String payload) { this.payload = payload; return this; }
        public Builder originalCreatedAt(Instant originalCreatedAt) { this.originalCreatedAt = originalCreatedAt; return this; }
        public Builder failedAt(Instant failedAt) { this.failedAt = failedAt; return this; }
        public Builder failureReason(String failureReason) { this.failureReason = failureReason; return this; }
        public Builder retryCount(int retryCount) { this.retryCount = retryCount; return this; }
        public Builder headers(Map<String, String> headers) { this.headers = headers; return this; }
        public Builder correlationId(String correlationId) { this.correlationId = correlationId; return this; }
        public Builder messageGroup(String messageGroup) { this.messageGroup = messageGroup; return this; }
        
        public DeadLetterMessageInfo build() {
            return new DeadLetterMessageInfo(
                id, originalTable, originalId, topic, payload, originalCreatedAt,
                failedAt, failureReason, retryCount, headers, correlationId, messageGroup
            );
        }
    }
    
    /**
     * Creates a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }
}

