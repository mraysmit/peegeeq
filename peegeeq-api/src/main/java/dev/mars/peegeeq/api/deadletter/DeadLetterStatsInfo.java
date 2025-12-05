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

/**
 * Statistics for the dead letter queue.
 * 
 * This record is part of the PeeGeeQ API layer, providing
 * abstraction over implementation-specific dead letter queue details.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-05
 * @version 1.0
 */
public record DeadLetterStatsInfo(
    long totalMessages,
    int uniqueTopics,
    int uniqueTables,
    Instant oldestFailure,
    Instant newestFailure,
    double averageRetryCount
) {
    /**
     * Creates an empty stats instance (no messages in DLQ).
     */
    public static DeadLetterStatsInfo empty() {
        return new DeadLetterStatsInfo(0, 0, 0, null, null, 0.0);
    }
    
    /**
     * Returns true if the dead letter queue is empty.
     */
    public boolean isEmpty() {
        return totalMessages == 0;
    }
    
    /**
     * Builder for creating DeadLetterStatsInfo instances.
     */
    public static class Builder {
        private long totalMessages;
        private int uniqueTopics;
        private int uniqueTables;
        private Instant oldestFailure;
        private Instant newestFailure;
        private double averageRetryCount;
        
        public Builder totalMessages(long totalMessages) { this.totalMessages = totalMessages; return this; }
        public Builder uniqueTopics(int uniqueTopics) { this.uniqueTopics = uniqueTopics; return this; }
        public Builder uniqueTables(int uniqueTables) { this.uniqueTables = uniqueTables; return this; }
        public Builder oldestFailure(Instant oldestFailure) { this.oldestFailure = oldestFailure; return this; }
        public Builder newestFailure(Instant newestFailure) { this.newestFailure = newestFailure; return this; }
        public Builder averageRetryCount(double averageRetryCount) { this.averageRetryCount = averageRetryCount; return this; }
        
        public DeadLetterStatsInfo build() {
            return new DeadLetterStatsInfo(
                totalMessages, uniqueTopics, uniqueTables, 
                oldestFailure, newestFailure, averageRetryCount
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

