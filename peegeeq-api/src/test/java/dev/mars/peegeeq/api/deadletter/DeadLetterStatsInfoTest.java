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

package dev.mars.peegeeq.api.deadletter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DeadLetterStatsInfo record.
 */
class DeadLetterStatsInfoTest {

    @Test
    @DisplayName("empty() creates stats with zero values")
    void empty_createsZeroStats() {
        DeadLetterStatsInfo stats = DeadLetterStatsInfo.empty();
        
        assertEquals(0, stats.totalMessages());
        assertEquals(0, stats.uniqueTopics());
        assertEquals(0, stats.uniqueTables());
        assertNull(stats.oldestFailure());
        assertNull(stats.newestFailure());
        assertEquals(0.0, stats.averageRetryCount());
    }

    @Test
    @DisplayName("isEmpty() returns true for empty stats")
    void isEmpty_returnsTrueForEmpty() {
        DeadLetterStatsInfo stats = DeadLetterStatsInfo.empty();
        assertTrue(stats.isEmpty());
    }

    @Test
    @DisplayName("isEmpty() returns false for non-empty stats")
    void isEmpty_returnsFalseForNonEmpty() {
        DeadLetterStatsInfo stats = DeadLetterStatsInfo.builder()
            .totalMessages(5)
            .build();
        assertFalse(stats.isEmpty());
    }

    @Test
    @DisplayName("builder creates valid instance")
    void builder_createsValidInstance() {
        Instant oldest = Instant.now().minusSeconds(3600);
        Instant newest = Instant.now();
        
        DeadLetterStatsInfo stats = DeadLetterStatsInfo.builder()
            .totalMessages(100)
            .uniqueTopics(5)
            .uniqueTables(3)
            .oldestFailure(oldest)
            .newestFailure(newest)
            .averageRetryCount(2.5)
            .build();

        assertEquals(100, stats.totalMessages());
        assertEquals(5, stats.uniqueTopics());
        assertEquals(3, stats.uniqueTables());
        assertEquals(oldest, stats.oldestFailure());
        assertEquals(newest, stats.newestFailure());
        assertEquals(2.5, stats.averageRetryCount());
    }

    @Test
    @DisplayName("record equality works correctly")
    void recordEquality_worksCorrectly() {
        Instant now = Instant.now();
        
        DeadLetterStatsInfo stats1 = DeadLetterStatsInfo.builder()
            .totalMessages(10)
            .uniqueTopics(2)
            .uniqueTables(1)
            .oldestFailure(now)
            .newestFailure(now)
            .averageRetryCount(1.5)
            .build();
        
        DeadLetterStatsInfo stats2 = DeadLetterStatsInfo.builder()
            .totalMessages(10)
            .uniqueTopics(2)
            .uniqueTables(1)
            .oldestFailure(now)
            .newestFailure(now)
            .averageRetryCount(1.5)
            .build();
        
        assertEquals(stats1, stats2);
        assertEquals(stats1.hashCode(), stats2.hashCode());
    }

    @Test
    @DisplayName("null timestamps are allowed")
    void nullTimestamps_areAllowed() {
        DeadLetterStatsInfo stats = DeadLetterStatsInfo.builder()
            .totalMessages(0)
            .oldestFailure(null)
            .newestFailure(null)
            .build();
        
        assertNull(stats.oldestFailure());
        assertNull(stats.newestFailure());
    }
}

