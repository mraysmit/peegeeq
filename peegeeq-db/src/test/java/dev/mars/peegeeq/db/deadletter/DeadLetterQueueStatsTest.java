package dev.mars.peegeeq.db.deadletter;

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

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE unit tests for DeadLetterQueueStats data model.
 * 
 * <p>Tests the DeadLetterQueueStats constructor, getters, business logic,
 * and object equality without requiring database access.</p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
class DeadLetterQueueStatsTest {

    @Test
    void testConstructorWithAllFields() {
        Instant oldest = Instant.now().minusSeconds(7200);
        Instant newest = Instant.now();
        
        DeadLetterQueueStats stats = new DeadLetterQueueStats(
            100L,
            5,
            3,
            oldest,
            newest,
            2.5
        );
        
        assertEquals(100L, stats.getTotalMessages());
        assertEquals(5, stats.getUniqueTopics());
        assertEquals(3, stats.getUniqueTables());
        assertEquals(oldest, stats.getOldestFailure());
        assertEquals(newest, stats.getNewestFailure());
        assertEquals(2.5, stats.getAverageRetryCount(), 0.001);
    }

    @Test
    void testConstructorWithNullTimestamps() {
        DeadLetterQueueStats stats = new DeadLetterQueueStats(
            0L, 0, 0, null, null, 0.0
        );
        
        assertEquals(0L, stats.getTotalMessages());
        assertNull(stats.getOldestFailure());
        assertNull(stats.getNewestFailure());
    }

    @Test
    void testIsEmptyWhenZeroMessages() {
        DeadLetterQueueStats stats = new DeadLetterQueueStats(
            0L, 0, 0, null, null, 0.0
        );
        
        assertTrue(stats.isEmpty());
    }

    @Test
    void testIsEmptyWhenHasMessages() {
        Instant now = Instant.now();
        DeadLetterQueueStats stats = new DeadLetterQueueStats(
            10L, 2, 1, now, now, 1.5
        );
        
        assertFalse(stats.isEmpty());
    }

    @Test
    void testEqualsAndHashCode() {
        Instant oldest = Instant.now().minusSeconds(7200);
        Instant newest = Instant.now();
        
        DeadLetterQueueStats stats1 = new DeadLetterQueueStats(
            100L, 5, 3, oldest, newest, 2.5
        );
        
        DeadLetterQueueStats stats2 = new DeadLetterQueueStats(
            100L, 5, 3, oldest, newest, 2.5
        );
        
        DeadLetterQueueStats stats3 = new DeadLetterQueueStats(
            200L, 5, 3, oldest, newest, 2.5
        );
        
        assertEquals(stats1, stats2);
        assertEquals(stats1.hashCode(), stats2.hashCode());
        assertNotEquals(stats1, stats3);
    }

    @Test
    void testEqualsSameObject() {
        Instant now = Instant.now();
        DeadLetterQueueStats stats = new DeadLetterQueueStats(
            10L, 2, 1, now, now, 1.5
        );
        
        assertEquals(stats, stats);
    }

    @Test
    void testEqualsNull() {
        Instant now = Instant.now();
        DeadLetterQueueStats stats = new DeadLetterQueueStats(
            10L, 2, 1, now, now, 1.5
        );
        
        assertNotEquals(stats, null);
    }

    @Test
    void testEqualsDifferentClass() {
        Instant now = Instant.now();
        DeadLetterQueueStats stats = new DeadLetterQueueStats(
            10L, 2, 1, now, now, 1.5
        );
        
        assertNotEquals(stats, "not stats");
    }

    @Test
    void testToString() {
        Instant oldest = Instant.now().minusSeconds(7200);
        Instant newest = Instant.now();
        
        DeadLetterQueueStats stats = new DeadLetterQueueStats(
            100L, 5, 3, oldest, newest, 2.56
        );
        
        String str = stats.toString();
        
        assertTrue(str.contains("100"));
        assertTrue(str.contains("5"));
        assertTrue(str.contains("3"));
        assertTrue(str.contains("2.56"));
    }

    @Test
    void testToStringFormatsAverageRetryCount() {
        DeadLetterQueueStats stats = new DeadLetterQueueStats(
            10L, 1, 1, null, null, 3.14159
        );
        
        String str = stats.toString();
        
        // Should format to 2 decimal places
        assertTrue(str.contains("3.14"));
    }
}

