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

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SubscriptionInfo record.
 */
class SubscriptionInfoTest {

    @Test
    @DisplayName("builder creates valid instance with defaults")
    void builder_createsValidInstanceWithDefaults() {
        SubscriptionInfo info = SubscriptionInfo.builder()
            .id(1L)
            .topic("test-topic")
            .groupName("test-group")
            .build();

        assertEquals(1L, info.id());
        assertEquals("test-topic", info.topic());
        assertEquals("test-group", info.groupName());
        assertEquals(SubscriptionState.ACTIVE, info.state());
        assertEquals(60, info.heartbeatIntervalSeconds());
        assertEquals(300, info.heartbeatTimeoutSeconds());
        assertEquals("NONE", info.backfillStatus());
        assertEquals(0L, info.backfillProcessedMessages());
    }

    @Test
    @DisplayName("isActive() returns true for ACTIVE state")
    void isActive_returnsTrueForActiveState() {
        SubscriptionInfo info = SubscriptionInfo.builder()
            .state(SubscriptionState.ACTIVE)
            .build();
        
        assertTrue(info.isActive());
    }

    @Test
    @DisplayName("isActive() returns false for non-ACTIVE states")
    void isActive_returnsFalseForNonActiveStates() {
        SubscriptionInfo paused = SubscriptionInfo.builder()
            .state(SubscriptionState.PAUSED)
            .build();
        assertFalse(paused.isActive());
        
        SubscriptionInfo cancelled = SubscriptionInfo.builder()
            .state(SubscriptionState.CANCELLED)
            .build();
        assertFalse(cancelled.isActive());
        
        SubscriptionInfo dead = SubscriptionInfo.builder()
            .state(SubscriptionState.DEAD)
            .build();
        assertFalse(dead.isActive());
    }

    @Test
    @DisplayName("isHeartbeatTimedOut() returns false when no heartbeat")
    void isHeartbeatTimedOut_returnsFalseWhenNoHeartbeat() {
        SubscriptionInfo info = SubscriptionInfo.builder()
            .lastHeartbeatAt(null)
            .build();
        
        assertFalse(info.isHeartbeatTimedOut());
    }

    @Test
    @DisplayName("isHeartbeatTimedOut() returns false for recent heartbeat")
    void isHeartbeatTimedOut_returnsFalseForRecentHeartbeat() {
        SubscriptionInfo info = SubscriptionInfo.builder()
            .lastHeartbeatAt(Instant.now())
            .heartbeatTimeoutSeconds(300)
            .build();
        
        assertFalse(info.isHeartbeatTimedOut());
    }

    @Test
    @DisplayName("isHeartbeatTimedOut() returns true for old heartbeat")
    void isHeartbeatTimedOut_returnsTrueForOldHeartbeat() {
        SubscriptionInfo info = SubscriptionInfo.builder()
            .lastHeartbeatAt(Instant.now().minusSeconds(600))
            .heartbeatTimeoutSeconds(300)
            .build();
        
        assertTrue(info.isHeartbeatTimedOut());
    }

    @Test
    @DisplayName("builder sets all fields correctly")
    void builder_setsAllFieldsCorrectly() {
        Instant now = Instant.now();
        
        SubscriptionInfo info = SubscriptionInfo.builder()
            .id(1L)
            .topic("orders")
            .groupName("order-processor")
            .state(SubscriptionState.PAUSED)
            .subscribedAt(now.minusSeconds(3600))
            .lastActiveAt(now.minusSeconds(60))
            .startFromMessageId(100L)
            .startFromTimestamp(now.minusSeconds(7200))
            .heartbeatIntervalSeconds(30)
            .heartbeatTimeoutSeconds(120)
            .lastHeartbeatAt(now.minusSeconds(10))
            .backfillStatus("IN_PROGRESS")
            .backfillCheckpointId(50L)
            .backfillProcessedMessages(500L)
            .backfillTotalMessages(1000L)
            .backfillStartedAt(now.minusSeconds(300))
            .backfillCompletedAt(null)
            .build();

        assertEquals(1L, info.id());
        assertEquals("orders", info.topic());
        assertEquals("order-processor", info.groupName());
        assertEquals(SubscriptionState.PAUSED, info.state());
        assertEquals(100L, info.startFromMessageId());
        assertEquals(30, info.heartbeatIntervalSeconds());
        assertEquals(120, info.heartbeatTimeoutSeconds());
        assertEquals("IN_PROGRESS", info.backfillStatus());
        assertEquals(50L, info.backfillCheckpointId());
        assertEquals(500L, info.backfillProcessedMessages());
        assertEquals(1000L, info.backfillTotalMessages());
    }
}

