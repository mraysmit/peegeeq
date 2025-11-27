package dev.mars.peegeeq.db.subscription;

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

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE unit tests for SubscriptionStatus enum.
 * 
 * <p>Tests the SubscriptionStatus enum values and state transitions.</p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
class SubscriptionStatusTest {

    @Test
    void testEnumValues() {
        SubscriptionStatus[] values = SubscriptionStatus.values();
        
        assertEquals(4, values.length);
        assertEquals(SubscriptionStatus.ACTIVE, values[0]);
        assertEquals(SubscriptionStatus.PAUSED, values[1]);
        assertEquals(SubscriptionStatus.CANCELLED, values[2]);
        assertEquals(SubscriptionStatus.DEAD, values[3]);
    }

    @Test
    void testValueOf() {
        assertEquals(SubscriptionStatus.ACTIVE, SubscriptionStatus.valueOf("ACTIVE"));
        assertEquals(SubscriptionStatus.PAUSED, SubscriptionStatus.valueOf("PAUSED"));
        assertEquals(SubscriptionStatus.CANCELLED, SubscriptionStatus.valueOf("CANCELLED"));
        assertEquals(SubscriptionStatus.DEAD, SubscriptionStatus.valueOf("DEAD"));
    }

    @Test
    void testValueOfInvalid() {
        assertThrows(IllegalArgumentException.class, () -> {
            SubscriptionStatus.valueOf("INVALID");
        });
    }

    @Test
    void testEnumEquality() {
        SubscriptionStatus active1 = SubscriptionStatus.ACTIVE;
        SubscriptionStatus active2 = SubscriptionStatus.valueOf("ACTIVE");
        
        assertSame(active1, active2);
        assertEquals(active1, active2);
    }

    @Test
    void testEnumToString() {
        assertEquals("ACTIVE", SubscriptionStatus.ACTIVE.toString());
        assertEquals("PAUSED", SubscriptionStatus.PAUSED.toString());
        assertEquals("CANCELLED", SubscriptionStatus.CANCELLED.toString());
        assertEquals("DEAD", SubscriptionStatus.DEAD.toString());
    }

    @Test
    void testEnumName() {
        assertEquals("ACTIVE", SubscriptionStatus.ACTIVE.name());
        assertEquals("PAUSED", SubscriptionStatus.PAUSED.name());
        assertEquals("CANCELLED", SubscriptionStatus.CANCELLED.name());
        assertEquals("DEAD", SubscriptionStatus.DEAD.name());
    }

    @Test
    void testEnumOrdinal() {
        assertEquals(0, SubscriptionStatus.ACTIVE.ordinal());
        assertEquals(1, SubscriptionStatus.PAUSED.ordinal());
        assertEquals(2, SubscriptionStatus.CANCELLED.ordinal());
        assertEquals(3, SubscriptionStatus.DEAD.ordinal());
    }

    @Test
    void testAllStatusesAreDistinct() {
        assertNotEquals(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAUSED);
        assertNotEquals(SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELLED);
        assertNotEquals(SubscriptionStatus.ACTIVE, SubscriptionStatus.DEAD);
        assertNotEquals(SubscriptionStatus.PAUSED, SubscriptionStatus.CANCELLED);
        assertNotEquals(SubscriptionStatus.PAUSED, SubscriptionStatus.DEAD);
        assertNotEquals(SubscriptionStatus.CANCELLED, SubscriptionStatus.DEAD);
    }
}

