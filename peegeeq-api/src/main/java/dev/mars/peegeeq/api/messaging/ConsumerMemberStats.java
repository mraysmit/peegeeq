package dev.mars.peegeeq.api.messaging;

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
    
    public String getConsumerId() { return consumerId; }
    public String getGroupName() { return groupName; }
    public String getTopic() { return topic; }
    public boolean isActive() { return active; }
    public long getMessagesProcessed() { return messagesProcessed; }
    public long getMessagesFailed() { return messagesFailed; }
    public long getMessagesFiltered() { return messagesFiltered; }
    public double getAverageProcessingTimeMs() { return averageProcessingTimeMs; }
    public double getMessagesPerSecond() { return messagesPerSecond; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastActiveAt() { return lastActiveAt; }
    public String getLastError() { return lastError; }
    
    /**
     * Gets the total number of messages handled (processed + failed + filtered).
     * 
     * @return The total message count
     */
    public long getTotalMessagesHandled() {
        return messagesProcessed + messagesFailed + messagesFiltered;
    }
    
    /**
     * Gets the success rate as a percentage (0.0 to 100.0).
     * 
     * @return The success rate percentage
     */
    public double getSuccessRatePercent() {
        long totalHandled = getTotalMessagesHandled();
        if (totalHandled == 0) return 100.0;
        return (double) messagesProcessed / totalHandled * 100.0;
    }
    
    /**
     * Gets the failure rate as a percentage (0.0 to 100.0).
     * 
     * @return The failure rate percentage
     */
    public double getFailureRatePercent() {
        long totalHandled = getTotalMessagesHandled();
        if (totalHandled == 0) return 0.0;
        return (double) messagesFailed / totalHandled * 100.0;
    }
    
    /**
     * Gets the filter rate as a percentage (0.0 to 100.0).
     * 
     * @return The filter rate percentage
     */
    public double getFilterRatePercent() {
        long totalHandled = getTotalMessagesHandled();
        if (totalHandled == 0) return 0.0;
        return (double) messagesFiltered / totalHandled * 100.0;
    }
    
    /**
     * Checks if this consumer has encountered any errors.
     * 
     * @return true if there are failed messages or a last error, false otherwise
     */
    public boolean hasErrors() {
        return messagesFailed > 0 || (lastError != null && !lastError.trim().isEmpty());
    }
    
    /**
     * Gets the uptime duration in milliseconds since creation.
     * 
     * @return The uptime in milliseconds
     */
    public long getUptimeMs() {
        if (createdAt == null) return 0;
        Instant now = lastActiveAt != null ? lastActiveAt : Instant.now();
        return now.toEpochMilli() - createdAt.toEpochMilli();
    }
    
    /**
     * Gets the idle time in milliseconds since last activity.
     * 
     * @return The idle time in milliseconds, or 0 if lastActiveAt is null
     */
    public long getIdleTimeMs() {
        if (lastActiveAt == null) return 0;
        return Instant.now().toEpochMilli() - lastActiveAt.toEpochMilli();
    }
    
    @Override
    public String toString() {
        return String.format(
            "ConsumerMemberStats{consumerId='%s', groupName='%s', topic='%s', " +
            "active=%s, processed=%d, failed=%d, filtered=%d, avgProcessingTime=%.2fms, " +
            "messagesPerSecond=%.2f, successRate=%.1f%%, hasErrors=%s}",
            consumerId, groupName, topic, active, messagesProcessed, messagesFailed, 
            messagesFiltered, averageProcessingTimeMs, messagesPerSecond, 
            getSuccessRatePercent(), hasErrors()
        );
    }
}
