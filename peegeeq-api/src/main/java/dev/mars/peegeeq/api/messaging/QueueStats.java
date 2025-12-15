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
 * Statistics for a message queue.
 * 
 * Provides comprehensive metrics about queue state including message counts,
 * processing rates, and timing information.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-13
 * @version 1.0
 */
public class QueueStats {
    
    private final String queueName;
    private final long totalMessages;
    private final long pendingMessages;
    private final long processedMessages;
    private final long inFlightMessages;
    private final long deadLetteredMessages;
    private final double messagesPerSecond;
    private final double avgProcessingTimeMs;
    private final Instant createdAt;
    private final Instant lastMessageAt;
    
    public QueueStats(String queueName, long totalMessages, long pendingMessages, 
                      long processedMessages, long inFlightMessages, long deadLetteredMessages,
                      double messagesPerSecond, double avgProcessingTimeMs,
                      Instant createdAt, Instant lastMessageAt) {
        this.queueName = queueName;
        this.totalMessages = totalMessages;
        this.pendingMessages = pendingMessages;
        this.processedMessages = processedMessages;
        this.inFlightMessages = inFlightMessages;
        this.deadLetteredMessages = deadLetteredMessages;
        this.messagesPerSecond = messagesPerSecond;
        this.avgProcessingTimeMs = avgProcessingTimeMs;
        this.createdAt = createdAt;
        this.lastMessageAt = lastMessageAt;
    }
    
    /**
     * Creates basic queue stats with minimal information.
     * 
     * @param queueName The queue name
     * @param totalMessages Total messages ever sent to the queue
     * @param pendingMessages Messages waiting to be processed
     * @param processedMessages Messages successfully processed
     * @return A QueueStats instance with basic information
     */
    public static QueueStats basic(String queueName, long totalMessages, 
                                   long pendingMessages, long processedMessages) {
        return new QueueStats(queueName, totalMessages, pendingMessages, processedMessages,
                             0, 0, 0.0, 0.0, null, null);
    }
    
    public String getQueueName() { return queueName; }
    public long getTotalMessages() { return totalMessages; }
    public long getPendingMessages() { return pendingMessages; }
    public long getProcessedMessages() { return processedMessages; }
    public long getInFlightMessages() { return inFlightMessages; }
    public long getDeadLetteredMessages() { return deadLetteredMessages; }
    public double getMessagesPerSecond() { return messagesPerSecond; }
    public double getAvgProcessingTimeMs() { return avgProcessingTimeMs; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastMessageAt() { return lastMessageAt; }
    
    /**
     * Gets the success rate as a percentage (0.0 to 100.0).
     * 
     * @return The success rate percentage
     */
    public double getSuccessRatePercent() {
        if (totalMessages == 0) return 100.0;
        return (double) processedMessages / totalMessages * 100.0;
    }
    
    /**
     * Gets the dead letter rate as a percentage (0.0 to 100.0).
     * 
     * @return The dead letter rate percentage
     */
    public double getDeadLetterRatePercent() {
        if (totalMessages == 0) return 0.0;
        return (double) deadLetteredMessages / totalMessages * 100.0;
    }
    
    /**
     * Checks if the queue has pending messages.
     * 
     * @return true if there are pending messages, false otherwise
     */
    public boolean hasPendingMessages() {
        return pendingMessages > 0;
    }
    
    /**
     * Checks if the queue is healthy (no dead letters and processing normally).
     * 
     * @return true if the queue appears healthy
     */
    public boolean isHealthy() {
        return deadLetteredMessages == 0 || getDeadLetterRatePercent() < 5.0;
    }
    
    @Override
    public String toString() {
        return String.format(
            "QueueStats{queueName='%s', total=%d, pending=%d, processed=%d, " +
            "inFlight=%d, deadLettered=%d, rate=%.2f/s, avgTime=%.2fms}",
            queueName, totalMessages, pendingMessages, processedMessages,
            inFlightMessages, deadLetteredMessages, messagesPerSecond, avgProcessingTimeMs
        );
    }
}

