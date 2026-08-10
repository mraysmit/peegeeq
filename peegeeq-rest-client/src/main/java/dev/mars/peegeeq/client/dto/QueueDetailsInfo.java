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

/**
 * Detailed information about a queue.
 *
 * <p>Reshaped 2026-08-10 (messages-stats contract review): the former fields
 * pendingMessages/processedMessages/deadLetterMessages/consumerIds had no source in
 * the {@code GET /api/v1/queues/:setupId/:queueName} payload and were dropped rather
 * than default-filled.
 */
public record QueueDetailsInfo(
    String queueName,
    String setupId,
    String implementationType,
    boolean healthy,
    long totalMessages,
    int consumerCount,
    Instant createdAt
) {
    /**
     * Creates basic queue details.
     */
    public static QueueDetailsInfo basic(String queueName, String setupId, String implementationType) {
        return new QueueDetailsInfo(
            queueName, setupId, implementationType, true,
            0, 0, Instant.now()
        );
    }
}
