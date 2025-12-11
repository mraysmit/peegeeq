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

import java.time.Instant;
import java.util.List;

/**
 * Detailed information about a queue.
 */
public record QueueDetailsInfo(
    String queueName,
    String setupId,
    String implementationType,
    boolean healthy,
    long totalMessages,
    long pendingMessages,
    long processedMessages,
    long deadLetterMessages,
    int consumerCount,
    List<String> consumerIds,
    Instant createdAt
) {
    /**
     * Creates basic queue details.
     */
    public static QueueDetailsInfo basic(String queueName, String setupId, String implementationType) {
        return new QueueDetailsInfo(
            queueName, setupId, implementationType, true,
            0, 0, 0, 0, 0, List.of(), Instant.now()
        );
    }
}

