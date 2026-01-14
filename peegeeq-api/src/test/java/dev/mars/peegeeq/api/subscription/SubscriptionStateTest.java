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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SubscriptionState enum.
 */
@Tag("core")
class SubscriptionStateTest {

    @Test
    @DisplayName("all states exist")
    void allStatesExist() {
        assertEquals(4, SubscriptionState.values().length);
        assertNotNull(SubscriptionState.ACTIVE);
        assertNotNull(SubscriptionState.PAUSED);
        assertNotNull(SubscriptionState.CANCELLED);
        assertNotNull(SubscriptionState.DEAD);
    }

    @Test
    @DisplayName("valueOf works for all states")
    void valueOf_worksForAllStates() {
        assertEquals(SubscriptionState.ACTIVE, SubscriptionState.valueOf("ACTIVE"));
        assertEquals(SubscriptionState.PAUSED, SubscriptionState.valueOf("PAUSED"));
        assertEquals(SubscriptionState.CANCELLED, SubscriptionState.valueOf("CANCELLED"));
        assertEquals(SubscriptionState.DEAD, SubscriptionState.valueOf("DEAD"));
    }

    @Test
    @DisplayName("name() returns correct string")
    void name_returnsCorrectString() {
        assertEquals("ACTIVE", SubscriptionState.ACTIVE.name());
        assertEquals("PAUSED", SubscriptionState.PAUSED.name());
        assertEquals("CANCELLED", SubscriptionState.CANCELLED.name());
        assertEquals("DEAD", SubscriptionState.DEAD.name());
    }

    @Test
    @DisplayName("ordinal values are sequential")
    void ordinal_valuesAreSequential() {
        assertEquals(0, SubscriptionState.ACTIVE.ordinal());
        assertEquals(1, SubscriptionState.PAUSED.ordinal());
        assertEquals(2, SubscriptionState.CANCELLED.ordinal());
        assertEquals(3, SubscriptionState.DEAD.ordinal());
    }
}

