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
 *
 * <p>{@code memberCount} and {@code lastActivity} are null when the source payload
 * carries no value for them: the create response emits neither, the management
 * listing emits no lastActivity, and the consumer-group handler encodes "no stats
 * yet" as 0L epoch millis, which also maps to null. The previous shape carried a
 * {@code pendingMessages} field with no source in any server payload and a
 * {@code create(...)} factory that default-filled it; both were deleted
 * (reshaped 2026-08-10, consumer-groups contract review).
 */
public record ConsumerGroupInfo(
    String groupName,
    String queueName,
    Integer memberCount,
    Instant lastActivity
) {
}
