package dev.mars.peegeeq.api.messaging;

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
@DisplayName("Consumer Statistics Tests")
class ConsumerStatsTest {

    @Test
    @DisplayName("Should calculate consumer statistics and rates correctly")
    void testConsumerMemberStats() {
        Instant now = Instant.now();
        Instant lastActive = now.minusSeconds(60);
        
        ConsumerMemberStats stats = new ConsumerMemberStats(
            "consumer-1", "group-1", "topic-1",
            true, 100, 5, 10,
            50.5, 10.0,
            now.minusSeconds(3600), lastActive, "Last Error"
        );

        assertEquals("consumer-1", stats.getConsumerId());
        assertEquals("group-1", stats.getGroupName());
        assertEquals("topic-1", stats.getTopic());
        assertTrue(stats.isActive());
        assertEquals(100, stats.getMessagesProcessed());
        assertEquals(5, stats.getMessagesFailed());
        assertEquals(10, stats.getMessagesFiltered());
        assertEquals(50.5, stats.getAverageProcessingTimeMs());
        assertEquals(10.0, stats.getMessagesPerSecond());
        assertEquals(now.minusSeconds(3600), stats.getCreatedAt());
        assertEquals(lastActive, stats.getLastActiveAt());
        assertEquals("Last Error", stats.getLastError());

        assertEquals(115, stats.getTotalMessagesHandled());
        
        // 100 / 115 * 100 = 86.956...
        assertEquals(100.0 / 115.0 * 100.0, stats.getSuccessRatePercent(), 0.001);
        
        // 5 / 115 * 100 = 4.347...
        assertEquals(5.0 / 115.0 * 100.0, stats.getFailureRatePercent(), 0.001);
        
        // 10 / 115 * 100 = 8.695...
        assertEquals(10.0 / 115.0 * 100.0, stats.getFilterRatePercent(), 0.001);
        
        System.out.println("DEBUG: messagesFailed=" + stats.getMessagesFailed());
        System.out.println("DEBUG: lastError=" + stats.getLastError());
        System.out.println("DEBUG: hasErrors=" + stats.hasErrors());

        assertTrue(stats.hasErrors());
        
        // Uptime is calculated based on lastActiveAt if present
        // Created at T-3600s, Last Active at T-60s -> Uptime = 3540s = 3,540,000ms
        assertTrue(stats.getUptimeMs() >= 3540000);
        assertTrue(stats.getIdleTimeMs() >= 60000);
        
        String str = stats.toString();
        assertTrue(str.contains("consumer-1"));
        assertTrue(str.contains("87.0%")); // Success rate formatted
    }

    @Test
    @DisplayName("Should handle edge cases and empty values in statistics")
    void testConsumerMemberStatsEdgeCases() {
        Instant now = Instant.now();
        
        ConsumerMemberStats stats = new ConsumerMemberStats(
            "c1", "g1", "t1", false, 0, 0, 0, 0, 0, null, null, null
        );

        assertEquals(0, stats.getTotalMessagesHandled());
        assertEquals(100.0, stats.getSuccessRatePercent());
        assertEquals(0.0, stats.getFailureRatePercent());
        assertEquals(0.0, stats.getFilterRatePercent());
        assertFalse(stats.hasErrors());
        assertEquals(0, stats.getUptimeMs());
        assertEquals(0, stats.getIdleTimeMs());
        
        // Test empty error string
        stats = new ConsumerMemberStats(
            "c1", "g1", "t1", false, 0, 0, 0, 0, 0, now, now, ""
        );
        assertFalse(stats.hasErrors());
        
        // Test whitespace error string
        stats = new ConsumerMemberStats(
            "c1", "g1", "t1", false, 0, 0, 0, 0, 0, now, now, "   "
        );
        assertFalse(stats.hasErrors());
    }

    @Test
    @DisplayName("Should calculate consumer group statistics correctly")
    void testConsumerGroupStats() {
        Instant now = Instant.now();
        ConsumerMemberStats memberStats = new ConsumerMemberStats(
            "c1", "g1", "t1", true, 10, 0, 0, 10, 1, now, now, null
        );
        Map<String, ConsumerMemberStats> members = Map.of("c1", memberStats);
        
        ConsumerGroupStats stats = new ConsumerGroupStats(
            "group-1", "topic-1", 1, 2,
            100, 5, 10,
            50.5, 10.0,
            now.minusSeconds(3600), now,
            members
        );

        assertEquals("group-1", stats.getGroupName());
        assertEquals("topic-1", stats.getTopic());
        assertEquals(1, stats.getActiveConsumerCount());
        assertEquals(2, stats.getTotalConsumerCount());
        assertEquals(100, stats.getTotalMessagesProcessed());
        assertEquals(5, stats.getTotalMessagesFailed());
        assertEquals(10, stats.getTotalMessagesFiltered());
        assertEquals(50.5, stats.getAverageProcessingTimeMs());
        assertEquals(10.0, stats.getMessagesPerSecond());
        assertEquals(now.minusSeconds(3600), stats.getCreatedAt());
        assertEquals(now, stats.getLastActiveAt());
        assertEquals(members, stats.getMemberStats());

        assertEquals(115, stats.getTotalMessagesHandled());
        assertEquals(100.0 / 115.0 * 100.0, stats.getSuccessRatePercent(), 0.001);
        assertEquals(5.0 / 115.0 * 100.0, stats.getFailureRatePercent(), 0.001);
        assertEquals(10.0 / 115.0 * 100.0, stats.getFilterRatePercent(), 0.001);
        
        assertTrue(stats.isActive());
        assertEquals(0.5, stats.getEfficiencyRatio());
        
        String str = stats.toString();
        assertTrue(str.contains("group-1"));
        assertTrue(str.contains("1/2"));
    }

    @Test
    @DisplayName("Should handle edge cases for consumer group statistics")
    void testConsumerGroupStatsEdgeCases() {
        ConsumerGroupStats stats = new ConsumerGroupStats(
            "g1", "t1", 0, 0, 0, 0, 0, 0, 0, null, null, Map.of()
        );

        assertEquals(0, stats.getTotalMessagesHandled());
        assertEquals(100.0, stats.getSuccessRatePercent());
        assertEquals(0.0, stats.getFailureRatePercent());
        assertEquals(0.0, stats.getFilterRatePercent());
        assertFalse(stats.isActive());
        assertEquals(0.0, stats.getEfficiencyRatio());
    }
}
