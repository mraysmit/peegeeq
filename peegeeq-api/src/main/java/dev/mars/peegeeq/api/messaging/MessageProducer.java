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


import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for producing messages to a queue.
 * 
 * This interface is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * Interface for producing messages to a queue.
 * 
 * @param <T> The type of message payload
 */
public interface MessageProducer<T> extends AutoCloseable {
    
    /**
     * Sends a message with the given payload.
     * 
     * @param payload The message payload
     * @return A CompletableFuture that completes when the message is sent
     */
    CompletableFuture<Void> send(T payload);
    
    /**
     * Sends a message with the given payload and headers.
     * 
     * @param payload The message payload
     * @param headers The message headers
     * @return A CompletableFuture that completes when the message is sent
     */
    CompletableFuture<Void> send(T payload, Map<String, String> headers);
    
    /**
     * Sends a message with the given payload, headers, and correlation ID.
     * 
     * @param payload The message payload
     * @param headers The message headers
     * @param correlationId The correlation ID for message tracking
     * @return A CompletableFuture that completes when the message is sent
     */
    CompletableFuture<Void> send(T payload, Map<String, String> headers, String correlationId);
    
    /**
     * Sends a message with the given payload, headers, correlation ID, and message group.
     * 
     * @param payload The message payload
     * @param headers The message headers
     * @param correlationId The correlation ID for message tracking
     * @param messageGroup The message group for ordering
     * @return A CompletableFuture that completes when the message is sent
     */
    CompletableFuture<Void> send(T payload, Map<String, String> headers, String correlationId, String messageGroup);
    
    /**
     * Closes the producer and releases any resources.
     */
    @Override
    void close();
}
