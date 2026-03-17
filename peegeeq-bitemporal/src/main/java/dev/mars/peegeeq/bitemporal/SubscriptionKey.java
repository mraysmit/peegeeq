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

package dev.mars.peegeeq.bitemporal;

import java.util.Objects;

/**
 * Structured subscription identifier for bitemporal reactive notifications.
 * Package-private to avoid expanding the public API surface.
 */
final class SubscriptionKey {
    private final String eventType;
    private final String aggregateId;

    private SubscriptionKey(String eventType, String aggregateId) {
        this.eventType = eventType;
        this.aggregateId = aggregateId;
    }

    static SubscriptionKey of(String eventType, String aggregateId) {
        return new SubscriptionKey(eventType, aggregateId);
    }

    static SubscriptionKey allEvents() {
        return new SubscriptionKey(null, null);
    }

    boolean hasWildcardEventType() {
        return eventType != null && eventType.contains("*");
    }

    boolean aggregateMatches(String incomingAggregateId) {
        return aggregateId == null || Objects.equals(aggregateId, incomingAggregateId);
    }

    String eventType() {
        return eventType;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SubscriptionKey that)) {
            return false;
        }
        return Objects.equals(eventType, that.eventType)
                && Objects.equals(aggregateId, that.aggregateId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventType, aggregateId);
    }

    @Override
    public String toString() {
        return "SubscriptionKey{eventType='" + eventType + "', aggregateId='" + aggregateId + "'}";
    }
}