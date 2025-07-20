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


/**
 * Interface for consuming messages from a queue.
 * 
 * This interface is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * Interface for consuming messages from a queue.
 * 
 * @param <T> The type of message payload
 */
public interface MessageConsumer<T> extends AutoCloseable {
    
    /**
     * Subscribes to messages with the given handler.
     * 
     * @param handler The message handler to process received messages
     */
    void subscribe(MessageHandler<T> handler);
    
    /**
     * Unsubscribes from message processing.
     */
    void unsubscribe();
    
    /**
     * Closes the consumer and releases any resources.
     */
    @Override
    void close();
}
