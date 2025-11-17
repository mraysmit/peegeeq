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

// No imports needed - all classes are in the same package now
import java.util.Set;
import java.util.function.Predicate;

/**
 * Interface for managing a group of consumers that work together to process messages.
 * Consumer groups provide load balancing, message filtering, and coordinated message processing.
 * 
 * @param <T> The type of message payload
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-14
 * @version 1.0
 */
public interface ConsumerGroup<T> extends AutoCloseable {
    
    /**
     * Gets the name of this consumer group.
     * 
     * @return The consumer group name
     */
    String getGroupName();
    
    /**
     * Gets the topic this consumer group is subscribed to.
     * 
     * @return The topic name
     */
    String getTopic();
    
    /**
     * Adds a consumer to this group with the specified consumer ID.
     * 
     * @param consumerId The unique identifier for this consumer within the group
     * @param handler The message handler for processing messages
     * @return A consumer group member instance
     */
    ConsumerGroupMember<T> addConsumer(String consumerId, MessageHandler<T> handler);
    
    /**
     * Adds a consumer to this group with message filtering.
     * 
     * @param consumerId The unique identifier for this consumer within the group
     * @param handler The message handler for processing messages
     * @param messageFilter A predicate to filter messages for this consumer
     * @return A consumer group member instance
     */
    ConsumerGroupMember<T> addConsumer(String consumerId, MessageHandler<T> handler, Predicate<Message<T>> messageFilter);
    
    /**
     * Removes a consumer from this group.
     * 
     * @param consumerId The consumer ID to remove
     * @return true if the consumer was removed, false if it wasn't found
     */
    boolean removeConsumer(String consumerId);
    
    /**
     * Gets all consumer IDs in this group.
     * 
     * @return A set of consumer IDs
     */
    Set<String> getConsumerIds();
    
    /**
     * Gets the number of active consumers in this group.
     * 
     * @return The number of active consumers
     */
    int getActiveConsumerCount();
    
    /**
     * Starts the consumer group. All added consumers will begin processing messages.
     * <p>
     * This method starts the consumer group without subscription management.
     * For late-joining consumer scenarios, use {@link #start(Object)} with SubscriptionOptions,
     * or manage subscriptions separately via SubscriptionManager.
     * </p>
     */
    void start();
    
    /**
     * Starts the consumer group with subscription options.
     * <p>
     * This method enables late-joining consumer patterns by accepting subscription configuration.
     * </p>
     * <p>
     * <strong>Note:</strong> The underlying implementation handles subscription creation at the
     * database layer before starting message consumption. This is a convenience method that
     * combines subscription management with consumer group startup.
     * </p>
     * <p>
     * <strong>Usage Example:</strong>
     * <pre>{@code
     * // Late-joining consumer - backfill all historical messages
     * SubscriptionOptions options = SubscriptionOptions.builder()
     *     .startPosition(StartPosition.FROM_BEGINNING)
     *     .build();
     * consumerGroup.start(options);
     * }</pre>
     * </p>
     * 
     * @param subscriptionOptions The subscription configuration options
     * @throws IllegalArgumentException if subscriptionOptions is null
     * @throws IllegalStateException if the consumer group is closed or already active
     * @since 1.1.0
     */
    void start(SubscriptionOptions subscriptionOptions);
    
    /**
     * Stops the consumer group. All consumers will stop processing messages.
     */
    void stop();
    
    /**
     * Checks if the consumer group is currently active.
     * 
     * @return true if the group is active, false otherwise
     */
    boolean isActive();
    
    /**
     * Gets statistics for this consumer group.
     * 
     * @return Consumer group statistics
     */
    ConsumerGroupStats getStats();
    
    /**
     * Sets a message handler for this consumer group.
     * <p>
     * This is a convenience method for simple single-consumer group scenarios.
     * It creates a default consumer internally and sets the provided handler.
     * For multiple consumers with different handlers, use {@link #addConsumer(String, MessageHandler)} instead.
     * </p>
     * <p>
     * <strong>Usage Example:</strong>
     * <pre>{@code
     * ConsumerGroup<OrderEvent> emailService = queueFactory.createConsumerGroup(
     *     "email-service", "orders.events", OrderEvent.class);
     * 
     * emailService.setMessageHandler(message -> {
     *     logger.info("Processing order: {}", message.getPayload().orderId());
     *     sendEmail(message.getPayload());
     *     return CompletableFuture.completedFuture(null);
     * });
     * 
     * emailService.start();
     * }</pre>
     * </p>
     * 
     * @param handler The message handler for processing messages
     * @return A consumer group member instance representing the default consumer
     * @throws IllegalStateException if the consumer group is closed or if a handler has already been set
     * @since 1.1.0
     */
    ConsumerGroupMember<T> setMessageHandler(MessageHandler<T> handler);
    
    /**
     * Sets a global message filter for the entire consumer group.
     * Messages that don't pass this filter won't be delivered to any consumer in the group.
     * 
     * @param groupFilter The group-level message filter
     */
    void setGroupFilter(Predicate<Message<T>> groupFilter);
    
    /**
     * Gets the current group-level message filter.
     * 
     * @return The group filter, or null if no filter is set
     */
    Predicate<Message<T>> getGroupFilter();
    
    /**
     * Closes the consumer group and releases all resources.
     */
    @Override
    void close();
}
