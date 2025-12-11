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
    /**
     * Creates a basic member info.
     */
    public static ConsumerGroupMemberInfo create(String memberId, String memberName, String groupName) {
        return new ConsumerGroupMemberInfo(
            memberId, memberName, groupName, null, true, Instant.now(), 1
        );
    }
}

