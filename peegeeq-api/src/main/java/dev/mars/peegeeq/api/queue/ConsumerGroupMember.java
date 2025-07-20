package dev.mars.peegeeq.api.queue;

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
public interface ConsumerGroupMember<T> {
    
    /**
     * Gets the unique identifier of this consumer within the group.
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
     * Gets the timestamp when this consumer joined the group.
     * 
     * @return The join timestamp
     */
    Instant getJoinedAt();
    
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
     * Only messages that pass this filter will be delivered to this consumer.
     * 
     * @param messageFilter The message filter predicate
     */
    void setMessageFilter(Predicate<Message<T>> messageFilter);
    
    /**
     * Checks if this consumer is currently active and processing messages.
     * 
     * @return true if the consumer is active, false otherwise
     */
    boolean isActive();
    
    /**
     * Starts this consumer. It will begin processing messages from the group.
     */
    void start();
    
    /**
     * Stops this consumer. It will stop processing messages.
     */
    void stop();
    
    /**
     * Gets statistics for this consumer member.
     * 
     * @return Consumer member statistics
     */
    ConsumerMemberStats getStats();
    
    /**
     * Gets the last time this consumer processed a message.
     * 
     * @return The last activity timestamp, or null if no messages have been processed
     */
    Instant getLastActivity();
    
    /**
     * Gets the current processing state of this consumer.
     * 
     * @return The processing state
     */
    ProcessingState getProcessingState();
    
    /**
     * Enumeration of possible processing states for a consumer member.
     */
    enum ProcessingState {
        /**
         * Consumer is idle and waiting for messages.
         */
        IDLE,
        
        /**
         * Consumer is currently processing a message.
         */
        PROCESSING,
        
        /**
         * Consumer is stopped and not processing messages.
         */
        STOPPED,
        
        /**
         * Consumer encountered an error and is in error state.
         */
        ERROR
    }
}
