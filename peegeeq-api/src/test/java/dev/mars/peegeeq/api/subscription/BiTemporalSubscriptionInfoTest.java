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

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(TestCategories.CORE)
class BiTemporalSubscriptionInfoTest {

    @Test
    void exposesDurableIdentityFilterCursorAndLifecycleState() {
        Instant now = Instant.parse("2026-09-02T06:00:00Z");
        BiTemporalSubscriptionInfo info = new BiTemporalSubscriptionInfo(
            7L,
            "order_events",
            "orders-projection",
            "reporting",
            "order.*",
            "order-123",
            SubscriptionState.ACTIVE,
            10L,
            42L,
            now,
            now,
            now,
            60,
            300,
            now);

        assertEquals("order_events", info.tableName());
        assertEquals("orders-projection", info.subscriptionName());
        assertEquals("reporting", info.consumerGroup());
        assertEquals("order.*", info.eventType());
        assertEquals("order-123", info.aggregateId());
        assertEquals(10L, info.startFromEventId());
        assertEquals(42L, info.lastProcessedId());
        assertTrue(info.isActive());
    }

    @Test
    void reportsNonActiveLifecycleStates() {
        BiTemporalSubscriptionInfo paused = new BiTemporalSubscriptionInfo(
            7L, "order_events", "orders-projection", "reporting",
            null, null, SubscriptionState.PAUSED, 0L, 0L,
            null, null, null, 60, 300, null);

        assertFalse(paused.isActive());
    }
}
