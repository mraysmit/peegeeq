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
public interface QueueFactory extends AutoCloseable {
    
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
     * Gets the implementation type of this factory.
     * 
     * @return The implementation type (e.g., "native", "outbox")
     */
    String getImplementationType();
    
    /**
     * Checks if the factory is healthy and ready to create queues.
     * 
     * @return true if the factory is healthy, false otherwise
     */
    boolean isHealthy();
    
    /**
     * Closes the factory and releases all resources.
     */
    @Override
    void close() throws Exception;
}
