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
import java.util.function.Predicate;

/**
 * Represents a member of a consumer group.
 * Each member has its own identity, message handler, and optional message filter.
 * 
 * @param <T> The type of message payload
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-14
 * @version 1.0
 */
public interface ConsumerGroupMember<T> extends AutoCloseable {
    
    /**
     * Gets the unique identifier for this consumer within the group.
     * 
     * @return The consumer ID
     */
    String getConsumerId();
    
    /**
     * Gets the name of the consumer group this member belongs to.
     * 
     * @return The consumer group name
     */
    String getGroupName();
    
    /**
     * Gets the topic this consumer is subscribed to.
     * 
     * @return The topic name
     */
    String getTopic();
    
    /**
     * Gets the message handler for this consumer.
     * 
     * @return The message handler
     */
    MessageHandler<T> getMessageHandler();
    
    /**
     * Gets the message filter for this consumer.
     * 
     * @return The message filter, or null if no filter is set
     */
    Predicate<Message<T>> getMessageFilter();
    
    /**
     * Sets a message filter for this consumer.
     * 
     * @param messageFilter The message filter to apply
     */
    void setMessageFilter(Predicate<Message<T>> messageFilter);
    
    /**
     * Starts this consumer member. It will begin processing messages.
     */
    void start();
    
    /**
     * Stops this consumer member. It will stop processing messages.
     */
    void stop();
    
    /**
     * Checks if this consumer member is currently active.
     * 
     * @return true if active, false otherwise
     */
    boolean isActive();
    
    /**
     * Gets the timestamp when this consumer was created.
     * 
     * @return The creation timestamp
     */
    Instant getCreatedAt();
    
    /**
     * Gets the timestamp when this consumer was last active.
     * 
     * @return The last active timestamp
     */
    Instant getLastActiveAt();
    
    /**
     * Gets the number of messages processed by this consumer.
     * 
     * @return The message count
     */
    long getProcessedMessageCount();
    
    /**
     * Gets the number of messages that failed processing by this consumer.
     * 
     * @return The failed message count
     */
    long getFailedMessageCount();
    
    /**
     * Gets statistics for this consumer member.
     * 
     * @return Consumer member statistics
     */
    ConsumerMemberStats getStats();
    
    /**
     * Closes this consumer member and releases any resources.
     */
    @Override
    void close();
}
