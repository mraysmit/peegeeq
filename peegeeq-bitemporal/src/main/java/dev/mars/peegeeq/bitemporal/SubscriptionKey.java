/*
 * Copyright (c) 2025 Cityline Ltd
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of Cityline Ltd.
 * You shall not disclose such confidential information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Cityline Ltd.
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