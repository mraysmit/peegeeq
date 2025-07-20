package dev.mars.peegeeq.outbox;

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

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;

import dev.mars.peegeeq.api.messaging.ConsumerMemberStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Implementation of a consumer group member for the outbox pattern.
 * 
 * @param <T> The type of message payload
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-14
 * @version 1.0
 */
public class OutboxConsumerGroupMember<T> implements dev.mars.peegeeq.api.messaging.ConsumerGroupMember<T> {
    
    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerGroupMember.class);
    
    private final String consumerId;
    private final String groupName;
    private final String topic;
    private final MessageHandler<T> messageHandler;
    @SuppressWarnings("unused") // Reserved for future group coordination features
    private final OutboxConsumerGroup<T> parentGroup;
    private final Instant createdAt;
    
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong processedMessageCount = new AtomicLong(0);
    private final AtomicLong failedMessageCount = new AtomicLong(0);
    private final AtomicLong filteredMessageCount = new AtomicLong(0);
    private final AtomicReference<Instant> lastActiveAt = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    
    private volatile Predicate<Message<T>> messageFilter;
    
    // Performance tracking
    private final AtomicLong totalProcessingTimeMs = new AtomicLong(0);
    
    public OutboxConsumerGroupMember(String consumerId, String groupName, String topic,
                                    MessageHandler<T> messageHandler, 
                                    Predicate<Message<T>> messageFilter,
                                    OutboxConsumerGroup<T> parentGroup) {
        this.consumerId = consumerId;
        this.groupName = groupName;
        this.topic = topic;
        this.messageHandler = messageHandler;
        this.messageFilter = messageFilter;
        this.parentGroup = parentGroup;
        this.createdAt = Instant.now();
        this.lastActiveAt.set(createdAt);
        
        logger.debug("Created outbox consumer group member '{}' in group '{}' for topic '{}'", 
            consumerId, groupName, topic);
    }
    
    @Override
    public String getConsumerId() {
        return consumerId;
    }
    
    @Override
    public String getGroupName() {
        return groupName;
    }
    
    @Override
    public String getTopic() {
        return topic;
    }

    @Override
    public Instant getJoinedAt() {
        return createdAt;
    }

    @Override
    public MessageHandler<T> getMessageHandler() {
        return messageHandler;
    }
    
    @Override
    public Predicate<Message<T>> getMessageFilter() {
        return messageFilter;
    }
    
    @Override
    public void setMessageFilter(Predicate<Message<T>> messageFilter) {
        this.messageFilter = messageFilter;
        logger.debug("Updated message filter for outbox consumer '{}' in group '{}'", consumerId, groupName);
    }
    
    @Override
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("Consumer member is closed");
        }
        
        if (active.compareAndSet(false, true)) {
            lastActiveAt.set(Instant.now());
            logger.info("Started outbox consumer member '{}' in group '{}' for topic '{}'", 
                consumerId, groupName, topic);
        }
    }
    
    @Override
    public void stop() {
        if (active.compareAndSet(true, false)) {
            logger.info("Stopped outbox consumer member '{}' in group '{}' for topic '{}'", 
                consumerId, groupName, topic);
        }
    }
    
    @Override
    public boolean isActive() {
        return active.get() && !closed.get();
    }
    
    @Override
    public Instant getLastActivity() {
        return lastActiveAt.get();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt.get();
    }

    public long getProcessedMessageCount() {
        return processedMessageCount.get();
    }

    public long getFailedMessageCount() {
        return failedMessageCount.get();
    }
    
    @Override
    public ConsumerMemberStats getStats() {
        long processed = processedMessageCount.get();
        long failed = failedMessageCount.get();
        long filtered = filteredMessageCount.get();
        
        // Calculate average processing time
        double avgProcessingTime = processed > 0 ? 
            (double) totalProcessingTimeMs.get() / processed : 0.0;
        
        // Calculate messages per second (rough estimate based on recent activity)
        Instant lastActive = lastActiveAt.get();
        double messagesPerSecond = 0.0;
        if (lastActive != null && processed > 0) {
            long secondsSinceCreation = java.time.Duration.between(createdAt, lastActive).getSeconds();
            if (secondsSinceCreation > 0) {
                messagesPerSecond = (double) processed / secondsSinceCreation;
            }
        }
        
        return new ConsumerMemberStats(
            consumerId, groupName, topic, isActive(), processed, failed, filtered,
            avgProcessingTime, messagesPerSecond, createdAt, lastActive, lastError.get()
        );
    }

    @Override
    public ProcessingState getProcessingState() {
        if (closed.get()) {
            return ProcessingState.STOPPED;
        }
        if (!isActive()) {
            return ProcessingState.IDLE;
        }
        // For simplicity, we'll return IDLE when not actively processing
        // In a real implementation, you might track when a message is being processed
        return ProcessingState.IDLE;
    }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            stop();
            logger.debug("Closed outbox consumer member '{}' in group '{}' for topic '{}'", 
                consumerId, groupName, topic);
        }
    }
    
    /**
     * Checks if this consumer accepts the given message based on its filter.
     * 
     * @param message The message to check
     * @return true if the message should be processed by this consumer
     */
    public boolean acceptsMessage(Message<T> message) {
        if (!isActive()) {
            return false;
        }
        
        if (messageFilter == null) {
            return true; // No filter means accept all messages
        }
        
        try {
            logger.debug("Testing message filter for consumer '{}' in group '{}'. Message ID: {}, Headers: {}",
                consumerId, groupName, message.getId(), message.getHeaders());
            return messageFilter.test(message);
        } catch (Exception e) {
            logger.warn("Error applying message filter for outbox consumer '{}' in group '{}'. Message ID: {}, Headers: {}, Error: {}",
                consumerId, groupName, message.getId(), message.getHeaders(), e.getMessage(), e);
            return false; // Reject message if filter throws exception
        }
    }
    
    /**
     * Processes a message using this consumer's message handler.
     * 
     * @param message The message to process
     * @return A CompletableFuture that completes when processing is done
     */
    public CompletableFuture<Void> processMessage(Message<T> message) {
        if (!isActive()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Consumer member is not active"));
        }
        
        // Check filter again (defensive programming)
        if (!acceptsMessage(message)) {
            filteredMessageCount.incrementAndGet();
            logger.debug("Message {} filtered out by outbox consumer '{}' in group '{}'", 
                message.getId(), consumerId, groupName);
            return CompletableFuture.completedFuture(null);
        }
        
        lastActiveAt.set(Instant.now());
        long startTime = System.currentTimeMillis();
        
        logger.debug("Processing message {} with outbox consumer '{}' in group '{}'", 
            message.getId(), consumerId, groupName);
        
        return messageHandler.handle(message)
            .whenComplete((result, error) -> {
                long processingTime = System.currentTimeMillis() - startTime;
                totalProcessingTimeMs.addAndGet(processingTime);
                
                if (error != null) {
                    failedMessageCount.incrementAndGet();
                    lastError.set(error.getMessage());
                    logger.warn("Failed to process message {} with outbox consumer '{}' in group '{}': {}", 
                        message.getId(), consumerId, groupName, error.getMessage());
                } else {
                    processedMessageCount.incrementAndGet();
                    lastError.set(null);
                    logger.debug("Successfully processed message {} with outbox consumer '{}' in group '{}' ({}ms)", 
                        message.getId(), consumerId, groupName, processingTime);
                }
                
                lastActiveAt.set(Instant.now());
            });
    }
}
