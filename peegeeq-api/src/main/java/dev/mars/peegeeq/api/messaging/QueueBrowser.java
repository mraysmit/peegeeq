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

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for browsing messages in a queue without consuming them.
 * 
 * This allows inspection of queue contents for debugging, monitoring,
 * and management purposes without affecting message delivery.
 * 
 * @param <T> The type of message payload
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public interface QueueBrowser<T> extends AutoCloseable {
    
    /**
     * Browse messages in the queue without consuming them.
     * 
     * @param limit Maximum number of messages to return
     * @param offset Number of messages to skip from the beginning
     * @return A CompletableFuture containing the list of messages
     */
    CompletableFuture<List<Message<T>>> browse(int limit, int offset);
    
    /**
     * Browse messages in the queue without consuming them, starting from the beginning.
     * 
     * @param limit Maximum number of messages to return
     * @return A CompletableFuture containing the list of messages
     */
    default CompletableFuture<List<Message<T>>> browse(int limit) {
        return browse(limit, 0);
    }
    
    /**
     * Get the topic/queue name this browser is associated with.
     * 
     * @return The topic name
     */
    String getTopic();
    
    /**
     * Close the browser and release any resources.
     */
    @Override
    void close();
}

