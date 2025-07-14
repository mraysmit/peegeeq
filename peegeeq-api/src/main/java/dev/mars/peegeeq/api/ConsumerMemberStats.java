package dev.mars.peegeeq.api;

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
 * Statistics for an individual consumer group member.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-14
 * @version 1.0
 */
public class ConsumerMemberStats {
    
    private final String consumerId;
    private final String groupName;
    private final String topic;
    private final boolean active;
    private final long messagesProcessed;
    private final long messagesFailed;
    private final long messagesFiltered;
    private final double averageProcessingTimeMs;
    private final double messagesPerSecond;
    private final Instant createdAt;
    private final Instant lastActiveAt;
    private final String lastError;
    
    public ConsumerMemberStats(String consumerId, String groupName, String topic, 
                              boolean active, long messagesProcessed, long messagesFailed,
                              long messagesFiltered, double averageProcessingTimeMs, 
                              double messagesPerSecond, Instant createdAt, 
                              Instant lastActiveAt, String lastError) {
        this.consumerId = consumerId;
        this.groupName = groupName;
        this.topic = topic;
        this.active = active;
        this.messagesProcessed = messagesProcessed;
        this.messagesFailed = messagesFailed;
        this.messagesFiltered = messagesFiltered;
        this.averageProcessingTimeMs = averageProcessingTimeMs;
        this.messagesPerSecond = messagesPerSecond;
        this.createdAt = createdAt;
        this.lastActiveAt = lastActiveAt;
        this.lastError = lastError;
    }
    
    /**
     * Gets the consumer ID.
     */
    public String getConsumerId() {
        return consumerId;
    }
    
    /**
     * Gets the consumer group name.
     */
    public String getGroupName() {
        return groupName;
    }
    
    /**
     * Gets the topic name.
     */
    public String getTopic() {
        return topic;
    }
    
    /**
     * Checks if the consumer is currently active.
     */
    public boolean isActive() {
        return active;
    }
    
    /**
     * Gets the number of messages processed by this consumer.
     */
    public long getMessagesProcessed() {
        return messagesProcessed;
    }
    
    /**
     * Gets the number of messages that failed processing.
     */
    public long getMessagesFailed() {
        return messagesFailed;
    }
    
    /**
     * Gets the number of messages filtered out by this consumer.
     */
    public long getMessagesFiltered() {
        return messagesFiltered;
    }
    
    /**
     * Gets the average message processing time in milliseconds.
     */
    public double getAverageProcessingTimeMs() {
        return averageProcessingTimeMs;
    }
    
    /**
     * Gets the current messages per second processing rate.
     */
    public double getMessagesPerSecond() {
        return messagesPerSecond;
    }
    
    /**
     * Gets the timestamp when this consumer was created.
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    /**
     * Gets the timestamp when this consumer was last active.
     */
    public Instant getLastActiveAt() {
        return lastActiveAt;
    }
    
    /**
     * Gets the last error message, if any.
     */
    public String getLastError() {
        return lastError;
    }
    
    /**
     * Calculates the success rate as a percentage.
     */
    public double getSuccessRate() {
        long total = messagesProcessed + messagesFailed;
        return total > 0 ? (double) messagesProcessed / total * 100.0 : 0.0;
    }
    
    /**
     * Calculates the failure rate as a percentage.
     */
    public double getFailureRate() {
        long total = messagesProcessed + messagesFailed;
        return total > 0 ? (double) messagesFailed / total * 100.0 : 0.0;
    }
    
    @Override
    public String toString() {
        return String.format(
            "ConsumerMemberStats{consumerId='%s', group='%s', topic='%s', active=%s, " +
            "processed=%d, failed=%d, filtered=%d, avgTime=%.2fms, rate=%.2f msg/s, " +
            "successRate=%.2f%%}",
            consumerId, groupName, topic, active, messagesProcessed, messagesFailed,
            messagesFiltered, averageProcessingTimeMs, messagesPerSecond, getSuccessRate()
        );
    }
}
