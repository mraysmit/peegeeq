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
    
    public String getGroupName() { return groupName; }
    public String getTopic() { return topic; }
    public int getActiveConsumerCount() { return activeConsumerCount; }
    public int getTotalConsumerCount() { return totalConsumerCount; }
    public long getTotalMessagesProcessed() { return totalMessagesProcessed; }
    public long getTotalMessagesFailed() { return totalMessagesFailed; }
    public long getTotalMessagesFiltered() { return totalMessagesFiltered; }
    public double getAverageProcessingTimeMs() { return averageProcessingTimeMs; }
    public double getMessagesPerSecond() { return messagesPerSecond; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastActiveAt() { return lastActiveAt; }
    public Map<String, ConsumerMemberStats> getMemberStats() { return memberStats; }
    
    /**
     * Gets the total number of messages handled (processed + failed + filtered).
     * 
     * @return The total message count
     */
    public long getTotalMessagesHandled() {
        return totalMessagesProcessed + totalMessagesFailed + totalMessagesFiltered;
    }
    
    /**
     * Gets the success rate as a percentage (0.0 to 100.0).
     * 
     * @return The success rate percentage
     */
    public double getSuccessRatePercent() {
        long totalHandled = getTotalMessagesHandled();
        if (totalHandled == 0) return 100.0;
        return (double) totalMessagesProcessed / totalHandled * 100.0;
    }
    
    /**
     * Gets the failure rate as a percentage (0.0 to 100.0).
     * 
     * @return The failure rate percentage
     */
    public double getFailureRatePercent() {
        long totalHandled = getTotalMessagesHandled();
        if (totalHandled == 0) return 0.0;
        return (double) totalMessagesFailed / totalHandled * 100.0;
    }
    
    /**
     * Gets the filter rate as a percentage (0.0 to 100.0).
     * 
     * @return The filter rate percentage
     */
    public double getFilterRatePercent() {
        long totalHandled = getTotalMessagesHandled();
        if (totalHandled == 0) return 0.0;
        return (double) totalMessagesFiltered / totalHandled * 100.0;
    }
    
    /**
     * Checks if the consumer group is currently active.
     * 
     * @return true if there are active consumers, false otherwise
     */
    public boolean isActive() {
        return activeConsumerCount > 0;
    }
    
    /**
     * Gets the efficiency ratio (active consumers / total consumers).
     * 
     * @return The efficiency ratio (0.0 to 1.0)
     */
    public double getEfficiencyRatio() {
        if (totalConsumerCount == 0) return 0.0;
        return (double) activeConsumerCount / totalConsumerCount;
    }
    
    @Override
    public String toString() {
        return String.format(
            "ConsumerGroupStats{groupName='%s', topic='%s', activeConsumers=%d/%d, " +
            "processed=%d, failed=%d, filtered=%d, avgProcessingTime=%.2fms, " +
            "messagesPerSecond=%.2f, successRate=%.1f%%}",
            groupName, topic, activeConsumerCount, totalConsumerCount,
            totalMessagesProcessed, totalMessagesFailed, totalMessagesFiltered,
            averageProcessingTimeMs, messagesPerSecond, getSuccessRatePercent()
        );
    }
}
