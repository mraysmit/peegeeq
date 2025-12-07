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
 * Information about a consumer group.
 */
public record ConsumerGroupInfo(
    String groupName,
    String queueName,
    int memberCount,
    long pendingMessages,
    Instant lastActivity
) {
    /**
     * Creates a new consumer group info.
     */
    public static ConsumerGroupInfo create(String groupName, String queueName) {
        return new ConsumerGroupInfo(groupName, queueName, 0, 0, Instant.now());
    }
}

