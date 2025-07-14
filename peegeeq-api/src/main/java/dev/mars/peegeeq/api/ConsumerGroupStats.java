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
import java.util.Map;

/**
 * Statistics for a consumer group.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-14
 * @version 1.0
 */
public class ConsumerGroupStats {
    
    private final String groupName;
    private final String topic;
    private final int activeConsumerCount;
    private final int totalConsumerCount;
    private final long totalMessagesProcessed;
    private final long totalMessagesFailed;
    private final long totalMessagesFiltered;
    private final double averageProcessingTimeMs;
    private final double messagesPerSecond;
    private final Instant createdAt;
    private final Instant lastActiveAt;
    private final Map<String, ConsumerMemberStats> memberStats;
    
    public ConsumerGroupStats(String groupName, String topic, int activeConsumerCount, 
                             int totalConsumerCount, long totalMessagesProcessed, 
                             long totalMessagesFailed, long totalMessagesFiltered,
                             double averageProcessingTimeMs, double messagesPerSecond,
                             Instant createdAt, Instant lastActiveAt,
                             Map<String, ConsumerMemberStats> memberStats) {
        this.groupName = groupName;
        this.topic = topic;
        this.activeConsumerCount = activeConsumerCount;
        this.totalConsumerCount = totalConsumerCount;
        this.totalMessagesProcessed = totalMessagesProcessed;
        this.totalMessagesFailed = totalMessagesFailed;
        this.totalMessagesFiltered = totalMessagesFiltered;
        this.averageProcessingTimeMs = averageProcessingTimeMs;
        this.messagesPerSecond = messagesPerSecond;
        this.createdAt = createdAt;
        this.lastActiveAt = lastActiveAt;
        this.memberStats = memberStats;
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
     * Gets the number of active consumers.
     */
    public int getActiveConsumerCount() {
        return activeConsumerCount;
    }
    
    /**
     * Gets the total number of consumers (active and inactive).
     */
    public int getTotalConsumerCount() {
        return totalConsumerCount;
    }
    
    /**
     * Gets the total number of messages processed by all consumers in the group.
     */
    public long getTotalMessagesProcessed() {
        return totalMessagesProcessed;
    }
    
    /**
     * Gets the total number of messages that failed processing.
     */
    public long getTotalMessagesFailed() {
        return totalMessagesFailed;
    }
    
    /**
     * Gets the total number of messages filtered out by group or member filters.
     */
    public long getTotalMessagesFiltered() {
        return totalMessagesFiltered;
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
     * Gets the timestamp when the consumer group was created.
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    /**
     * Gets the timestamp when the consumer group was last active.
     */
    public Instant getLastActiveAt() {
        return lastActiveAt;
    }
    
    /**
     * Gets statistics for individual consumer members.
     */
    public Map<String, ConsumerMemberStats> getMemberStats() {
        return memberStats;
    }
    
    /**
     * Calculates the success rate as a percentage.
     */
    public double getSuccessRate() {
        long total = totalMessagesProcessed + totalMessagesFailed;
        return total > 0 ? (double) totalMessagesProcessed / total * 100.0 : 0.0;
    }
    
    /**
     * Calculates the failure rate as a percentage.
     */
    public double getFailureRate() {
        long total = totalMessagesProcessed + totalMessagesFailed;
        return total > 0 ? (double) totalMessagesFailed / total * 100.0 : 0.0;
    }
    
    @Override
    public String toString() {
        return String.format(
            "ConsumerGroupStats{groupName='%s', topic='%s', activeConsumers=%d/%d, " +
            "processed=%d, failed=%d, filtered=%d, avgTime=%.2fms, rate=%.2f msg/s, " +
            "successRate=%.2f%%}",
            groupName, topic, activeConsumerCount, totalConsumerCount,
            totalMessagesProcessed, totalMessagesFailed, totalMessagesFiltered,
            averageProcessingTimeMs, messagesPerSecond, getSuccessRate()
        );
    }
}
