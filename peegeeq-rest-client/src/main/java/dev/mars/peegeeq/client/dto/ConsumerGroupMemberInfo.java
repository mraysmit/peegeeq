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
 * Information about a consumer group member.
 *
 * <p>Every field has a source in the join payload; the server's member id key is
 * {@code consumerId}, mapped to {@code memberId} here. {@code joinedAt} is null
 * when the server reports none. The previous {@code create(...)} factory
 * default-filled topic/isActive/joinedAt/memberCount and was deleted
 * (consumer-groups contract review, 2026-08-10).
 */
public record ConsumerGroupMemberInfo(
    String memberId,
    String memberName,
    String groupName,
    String topic,
    boolean isActive,
    Instant joinedAt,
    int memberCount
) {
}
