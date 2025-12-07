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


import io.vertx.core.Future;
import io.vertx.core.streams.ReadStream;

/**
 * Core interface for the reactive message queue.
 *
 * This interface is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities using
 * Vert.x 5.x reactive patterns.
 *
 * @param <T> The type of message payload
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.1 - Renamed from PgQueue to ReactiveQueue for cleaner API contracts
 */
public interface ReactiveQueue<T> {

    /**
     * Sends a message to the queue.
     *
     * @param message The message to send
     * @return A Future that completes when the message is sent
     */
    Future<Void> send(T message);

    /**
     * Receives messages from the queue.
     *
     * @return A ReadStream of messages from the queue
     */
    ReadStream<T> receive();

    /**
     * Acknowledges that a message has been processed.
     *
     * @param messageId The ID of the message to acknowledge
     * @return A Future that completes when the message is acknowledged
     */
    Future<Void> acknowledge(String messageId);

    /**
     * Closes the queue connection.
     *
     * @return A Future that completes when the connection is closed
     */
    Future<Void> close();
}
