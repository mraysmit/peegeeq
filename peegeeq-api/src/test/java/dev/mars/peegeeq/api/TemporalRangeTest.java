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

package dev.mars.peegeeq.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TemporalRange class.
 */
class TemporalRangeTest {

    @Test
    @DisplayName("all() creates unbounded range")
    void all_createsUnboundedRange() {
        TemporalRange range = TemporalRange.all();
        
        assertNull(range.getStart());
        assertNull(range.getEnd());
        assertTrue(range.isUnbounded());
        assertTrue(range.isStartInclusive());
        assertTrue(range.isEndInclusive());
    }

    @Test
    @DisplayName("from() creates range with start only")
    void from_createsRangeWithStartOnly() {
        Instant start = Instant.now();
        TemporalRange range = TemporalRange.from(start);
        
        assertEquals(start, range.getStart());
        assertNull(range.getEnd());
        assertFalse(range.isUnbounded());
    }

    @Test
    @DisplayName("until() creates range with end only")
    void until_createsRangeWithEndOnly() {
        Instant end = Instant.now();
        TemporalRange range = TemporalRange.until(end);
        
        assertNull(range.getStart());
        assertEquals(end, range.getEnd());
        assertFalse(range.isUnbounded());
    }

    @Test
    @DisplayName("at() creates point-in-time range")
    void at_createsPointInTimeRange() {
        Instant point = Instant.now();
        TemporalRange range = TemporalRange.at(point);
        
        assertEquals(point, range.getStart());
        assertEquals(point, range.getEnd());
        assertFalse(range.isUnbounded());
    }

    @Test
    @DisplayName("constructor validates start before end")
    void constructor_validatesStartBeforeEnd() {
        Instant now = Instant.now();
        Instant earlier = now.minusSeconds(60);
        
        assertThrows(IllegalArgumentException.class, () ->
            new TemporalRange(now, earlier)
        );
    }

    @Test
    @DisplayName("contains() returns true for time within range")
    void contains_returnsTrueForTimeWithinRange() {
        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now().plusSeconds(60);
        Instant middle = Instant.now();
        
        TemporalRange range = new TemporalRange(start, end);
        
        assertTrue(range.contains(middle));
        assertTrue(range.contains(start)); // inclusive
        assertTrue(range.contains(end));   // inclusive
    }

    @Test
    @DisplayName("contains() returns false for time outside range")
    void contains_returnsFalseForTimeOutsideRange() {
        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now().plusSeconds(60);
        
        TemporalRange range = new TemporalRange(start, end);
        
        assertFalse(range.contains(start.minusSeconds(1)));
        assertFalse(range.contains(end.plusSeconds(1)));
    }

    @Test
    @DisplayName("contains() returns false for null time")
    void contains_returnsFalseForNullTime() {
        TemporalRange range = TemporalRange.all();
        assertFalse(range.contains(null));
    }

    @Test
    @DisplayName("contains() with exclusive bounds")
    void contains_withExclusiveBounds() {
        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now().plusSeconds(60);
        
        TemporalRange range = new TemporalRange(start, end, false, false);
        
        assertFalse(range.contains(start)); // exclusive
        assertFalse(range.contains(end));   // exclusive
        assertTrue(range.contains(Instant.now())); // middle is still in
    }

    @Test
    @DisplayName("equals and hashCode work correctly")
    void equalsAndHashCode_workCorrectly() {
        Instant start = Instant.parse("2025-01-01T00:00:00Z");
        Instant end = Instant.parse("2025-12-31T23:59:59Z");
        
        TemporalRange range1 = new TemporalRange(start, end);
        TemporalRange range2 = new TemporalRange(start, end);
        
        assertEquals(range1, range2);
        assertEquals(range1.hashCode(), range2.hashCode());
    }

    @Test
    @DisplayName("toString() formats correctly")
    void toString_formatsCorrectly() {
        Instant start = Instant.parse("2025-01-01T00:00:00Z");
        Instant end = Instant.parse("2025-12-31T23:59:59Z");
        
        TemporalRange inclusive = new TemporalRange(start, end, true, true);
        assertTrue(inclusive.toString().startsWith("["));
        assertTrue(inclusive.toString().endsWith("]"));
        
        TemporalRange exclusive = new TemporalRange(start, end, false, false);
        assertTrue(exclusive.toString().startsWith("("));
        assertTrue(exclusive.toString().endsWith(")"));
    }

    @Test
    @DisplayName("unbounded range contains any time")
    void unboundedRange_containsAnyTime() {
        TemporalRange range = TemporalRange.all();
        
        assertTrue(range.contains(Instant.MIN));
        assertTrue(range.contains(Instant.now()));
        assertTrue(range.contains(Instant.MAX));
    }
}

