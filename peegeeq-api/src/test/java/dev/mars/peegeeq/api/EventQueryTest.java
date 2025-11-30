package dev.mars.peegeeq.api;

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

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
@DisplayName("Event Query and Temporal Range Tests")
class EventQueryTest {

    @Test
    @DisplayName("Should create TemporalRange with correct boundaries and inclusivity")
    void testTemporalRangeCreation() {
        Instant start = Instant.now();
        Instant end = start.plusSeconds(60);

        // Test full constructor
        TemporalRange range = new TemporalRange(start, end, true, false);
        assertEquals(start, range.getStart());
        assertEquals(end, range.getEnd());
        assertTrue(range.isStartInclusive());
        assertFalse(range.isEndInclusive());
        assertFalse(range.isUnbounded());

        // Test inclusive constructor
        range = new TemporalRange(start, end);
        assertTrue(range.isStartInclusive());
        assertTrue(range.isEndInclusive());

        // Test unbounded constructor
        range = new TemporalRange();
        assertNull(range.getStart());
        assertNull(range.getEnd());
        assertTrue(range.isUnbounded());

        // Test static factories
        range = TemporalRange.from(start);
        assertEquals(start, range.getStart());
        assertNull(range.getEnd());

        range = TemporalRange.until(end);
        assertNull(range.getStart());
        assertEquals(end, range.getEnd());

        range = TemporalRange.at(start);
        assertEquals(start, range.getStart());
        assertEquals(start, range.getEnd());

        range = TemporalRange.all();
        assertTrue(range.isUnbounded());
    }

    @Test
    @DisplayName("Should throw exception for invalid temporal range (start > end)")
    void testTemporalRangeValidation() {
        Instant start = Instant.now();
        Instant end = start.minusSeconds(1);

        assertThrows(IllegalArgumentException.class, () -> {
            new TemporalRange(start, end, true, true);
        });
    }

    @Test
    @DisplayName("Should correctly determine if a timestamp is within the range")
    void testTemporalRangeContains() {
        Instant now = Instant.now();
        Instant start = now.minusSeconds(10);
        Instant end = now.plusSeconds(10);

        // [start, end]
        TemporalRange range = new TemporalRange(start, end, true, true);
        assertTrue(range.contains(now));
        assertTrue(range.contains(start));
        assertTrue(range.contains(end));
        assertFalse(range.contains(start.minusSeconds(1)));
        assertFalse(range.contains(end.plusSeconds(1)));
        assertFalse(range.contains(null));

        // (start, end)
        range = new TemporalRange(start, end, false, false);
        assertTrue(range.contains(now));
        assertFalse(range.contains(start));
        assertFalse(range.contains(end));

        // Unbounded start
        range = TemporalRange.until(end);
        assertTrue(range.contains(start));
        assertTrue(range.contains(end));
        assertFalse(range.contains(end.plusSeconds(1)));

        // Unbounded end
        range = TemporalRange.from(start);
        assertTrue(range.contains(start));
        assertTrue(range.contains(end));
        assertFalse(range.contains(start.minusSeconds(1)));
    }

    @Test
    @DisplayName("Should implement equals and hashCode correctly for TemporalRange")
    void testTemporalRangeEqualsAndHashCode() {
        Instant start = Instant.now();
        Instant end = start.plusSeconds(60);

        TemporalRange range1 = new TemporalRange(start, end, true, true);
        TemporalRange range2 = new TemporalRange(start, end, true, true);
        TemporalRange range3 = new TemporalRange(start, end, false, true);

        assertEquals(range1, range2);
        assertEquals(range1.hashCode(), range2.hashCode());
        assertNotEquals(range1, range3);
        assertNotEquals(range1, null);
        assertNotEquals(range1, "string");
    }

    @Test
    @DisplayName("Should return correct string representation for TemporalRange")
    void testTemporalRangeToString() {
        Instant start = Instant.now();
        Instant end = start.plusSeconds(60);

        TemporalRange range = new TemporalRange(start, end, true, false);
        String str = range.toString();
        assertTrue(str.startsWith("["));
        assertTrue(str.endsWith(")"));
        assertTrue(str.contains(start.toString()));
        assertTrue(str.contains(end.toString()));

        range = new TemporalRange();
        str = range.toString();
        assertTrue(str.contains("-∞"));
        assertTrue(str.contains("+∞"));
    }

    @Test
    @DisplayName("Should build EventQuery with all fields correctly set")
    void testEventQueryBuilder() {
        TemporalRange range = TemporalRange.all();
        Map<String, String> headers = Map.of("k", "v");

        EventQuery query = EventQuery.builder()
            .eventType("TYPE")
            .aggregateId("agg-1")
            .correlationId("corr-1")
            .validTimeRange(range)
            .transactionTimeRange(range)
            .headerFilters(headers)
            .limit(50)
            .offset(10)
            .sortOrder(EventQuery.SortOrder.VALID_TIME_DESC)
            .includeCorrections(false)
            .versionRange(1L, 5L)
            .build();

        assertEquals("TYPE", query.getEventType().get());
        assertEquals("agg-1", query.getAggregateId().get());
        assertEquals("corr-1", query.getCorrelationId().get());
        assertEquals(range, query.getValidTimeRange().get());
        assertEquals(range, query.getTransactionTimeRange().get());
        assertEquals("v", query.getHeaderFilters().get("k"));
        assertEquals(50, query.getLimit());
        assertEquals(10, query.getOffset());
        assertEquals(EventQuery.SortOrder.VALID_TIME_DESC, query.getSortOrder());
        assertFalse(query.isIncludeCorrections());
        assertEquals(1L, query.getMinVersion().get());
        assertEquals(5L, query.getMaxVersion().get());
    }

    @Test
    @DisplayName("Should create EventQuery using static factory methods")
    void testEventQueryFactories() {
        EventQuery query = EventQuery.all();
        assertEquals(1000, query.getLimit());
        assertTrue(query.isIncludeCorrections());

        query = EventQuery.forEventType("TYPE");
        assertEquals("TYPE", query.getEventType().get());

        query = EventQuery.forAggregate("agg-1");
        assertEquals("agg-1", query.getAggregateId().get());

        query = EventQuery.forAggregateAndType("agg-1", "TYPE");
        assertEquals("agg-1", query.getAggregateId().get());
        assertEquals("TYPE", query.getEventType().get());

        Instant now = Instant.now();
        query = EventQuery.asOfValidTime(now);
        assertNotNull(query.getValidTimeRange().get());
        assertNotNull(query.getTransactionTimeRange().get());

        query = EventQuery.asOfTransactionTime(now);
        assertNotNull(query.getTransactionTimeRange().get());
    }

    @Test
    @DisplayName("Should validate EventQuery parameters (limit, offset, sortOrder)")
    void testEventQueryValidation() {
        assertThrows(IllegalArgumentException.class, () -> EventQuery.builder().limit(0));
        assertThrows(IllegalArgumentException.class, () -> EventQuery.builder().limit(-1));
        assertThrows(IllegalArgumentException.class, () -> EventQuery.builder().offset(-1));
        assertThrows(NullPointerException.class, () -> EventQuery.builder().sortOrder(null));
    }

    @Test
    @DisplayName("Should implement equals and hashCode correctly for EventQuery")
    void testEventQueryEqualsAndHashCode() {
        EventQuery q1 = EventQuery.forEventType("TYPE");
        EventQuery q2 = EventQuery.forEventType("TYPE");
        EventQuery q3 = EventQuery.forEventType("OTHER");

        assertEquals(q1, q2);
        assertEquals(q1.hashCode(), q2.hashCode());
        assertNotEquals(q1, q3);
        assertNotEquals(q1, null);
        assertNotEquals(q1, "string");
    }

    @Test
    @DisplayName("Should return correct string representation for EventQuery")
    void testEventQueryToString() {
        EventQuery query = EventQuery.forEventType("TYPE");
        String str = query.toString();
        assertTrue(str.contains("TYPE"));
        assertTrue(str.contains("limit=1000"));
    }
}
