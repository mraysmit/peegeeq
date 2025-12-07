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

package dev.mars.peegeeq.client.dto;

/**
 * Result of sending a message to a queue.
 */
public record MessageSendResult(
    String message,
    String queueName,
    String setupId,
    String messageId,
    String correlationId,
    String messageGroup,
    Integer priority,
    Long delaySeconds,
    long timestamp,
    String messageType,
    int customHeadersCount
) {
    /**
     * Creates a simple result with just the message ID.
     */
    public static MessageSendResult simple(String messageId, String queueName, String setupId) {
        return new MessageSendResult(
            "Message sent successfully",
            queueName,
            setupId,
            messageId,
            null,
            null,
            null,
            null,
            System.currentTimeMillis(),
            null,
            0
        );
    }
}

