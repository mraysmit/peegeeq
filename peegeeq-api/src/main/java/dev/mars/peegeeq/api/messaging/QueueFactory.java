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

import io.vertx.core.Future;

/**
 * Unified factory interface for creating message producers and consumers.
 * 
 * This interface is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * Unified factory interface for creating message producers and consumers.
 * This interface provides a consistent way to create queue implementations
 * without exposing implementation-specific dependencies.
 * 
 * @param <T> The type of message payload
 */
public interface QueueFactory {
    
    /**
     * Creates a message producer for the specified topic.
     *
     * @param topic The topic to produce messages to
     * @param payloadType The type of message payload
     * @return A message producer instance
     */
    <T> MessageProducer<T> createProducer(String topic, Class<T> payloadType);
    
    /**
     * Creates a message consumer for the specified topic.
     *
     * @param topic The topic to consume messages from
     * @param payloadType The type of message payload
     * @return A message consumer instance
     */
    <T> MessageConsumer<T> createConsumer(String topic, Class<T> payloadType);

    /**
     * Creates a message consumer for the specified topic with custom configuration.
     * This method allows specifying consumer behavior such as polling vs LISTEN/NOTIFY modes.
     *
     * @param topic The topic to consume messages from
     * @param payloadType The type of message payload
     * @param consumerConfig The consumer configuration specifying operational mode and settings
     * @return A message consumer instance configured according to the provided settings
     * @since 1.1.0
     */
    default <T> MessageConsumer<T> createConsumer(String topic, Class<T> payloadType, Object consumerConfig) {
        // Default implementation falls back to basic createConsumer for backward compatibility
        // Implementations should override this method to support advanced consumer configuration
        return createConsumer(topic, payloadType);
    }

    /**
     * Creates a consumer group for the specified topic.
     *
     * @param groupName The name of the consumer group
     * @param topic The topic to consume messages from
     * @param payloadType The type of message payload
     * @return A consumer group instance
     */
    <T> ConsumerGroup<T> createConsumerGroup(String groupName, String topic, Class<T> payloadType);

    /**
     * Creates a queue browser for inspecting messages without consuming them.
     * This is useful for debugging, monitoring, and management purposes.
     *
     * @param topic The topic/queue to browse
     * @param payloadType The type of message payload
     * @return A Future completing with a queue browser instance
     */
    <T> Future<QueueBrowser<T>> createBrowser(String topic, Class<T> payloadType);

    /**
     * Gets the implementation type of this factory.
     *
     * @return The implementation type (e.g., "native", "outbox")
     */
    String getImplementationType();

    /**
     * Checks if the factory is healthy and ready to create queues.
     *
     * @return a Future that completes with {@code true} if healthy, {@code false} otherwise
     */
    io.vertx.core.Future<Boolean> isHealthy();

    /**
     * Gets statistics for a specific queue/topic.
     *
     * @param topic The topic/queue name to get statistics for
     * @return a Future that completes with queue statistics including message counts and processing metrics
     */
    default io.vertx.core.Future<QueueStats> getStats(String topic) {
        // Default implementation returns basic stats with zeros
        // Implementations should override this to provide real statistics
        return io.vertx.core.Future.succeededFuture(QueueStats.basic(topic, 0, 0, 0));
    }

    /**
     * Counts messages currently stored for the given topic without consuming them.
     *
     * @param topic The topic/queue name
     * @return a Future that completes with the current message count
     */
    default io.vertx.core.Future<Long> countMessages(String topic) {
        return io.vertx.core.Future.failedFuture(
                new UnsupportedOperationException("Message counting not supported by this queue implementation"));
    }

    /**
     * Deletes all stored messages for the given topic without removing the queue definition.
     *
     * @param topic The topic/queue name
     * @return a Future that completes with the number of deleted messages
     */
    default io.vertx.core.Future<Integer> purgeMessages(String topic) {
        return io.vertx.core.Future.failedFuture(
                new UnsupportedOperationException("Message purge not supported by this queue implementation"));
    }

    /**
     * Closes the factory and releases all resources.
     *
     * @return a Future that completes when all resources are released
     */
    Future<Void> close();
}
