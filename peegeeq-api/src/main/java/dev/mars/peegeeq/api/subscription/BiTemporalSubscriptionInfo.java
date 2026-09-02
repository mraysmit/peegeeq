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

package dev.mars.peegeeq.api.subscription;

import java.time.Instant;

/**
 * Immutable metadata for a durable bitemporal subscription.
 *
 * @param id database identifier
 * @param tableName event-store table name
 * @param subscriptionName stable subscription name
 * @param consumerGroup durable consumer group
 * @param eventType event type filter, or null for all types
 * @param aggregateId aggregate filter, or null for all aggregates
 * @param state current lifecycle state
 * @param startFromEventId initial event cursor
 * @param lastProcessedId last successfully processed event cursor
 * @param subscribedAt subscription creation time
 * @param lastActiveAt last activation time
 * @param lastProcessedAt last cursor advancement time
 * @param heartbeatIntervalSeconds expected heartbeat interval
 * @param heartbeatTimeoutSeconds heartbeat timeout
 * @param lastHeartbeatAt last recorded heartbeat
 */
public record BiTemporalSubscriptionInfo(
        Long id,
        String tableName,
        String subscriptionName,
        String consumerGroup,
        String eventType,
        String aggregateId,
        SubscriptionState state,
        Long startFromEventId,
        Long lastProcessedId,
        Instant subscribedAt,
        Instant lastActiveAt,
        Instant lastProcessedAt,
        int heartbeatIntervalSeconds,
        int heartbeatTimeoutSeconds,
        Instant lastHeartbeatAt) {

    public boolean isActive() {
        return state == SubscriptionState.ACTIVE;
    }
}
