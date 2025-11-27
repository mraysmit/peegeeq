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
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.Tag;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE unit tests for Subscription data model.
 * 
 * <p>Tests the Subscription builder, getters, business logic methods,
 * and object equality without requiring database access.</p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
class SubscriptionTest {

    @Test
    void testBuilderWithRequiredFields() {
        Subscription subscription = Subscription.builder()
            .topic("test-topic")
            .groupName("test-group")
            .build();
        
        assertNotNull(subscription);
        assertEquals("test-topic", subscription.getTopic());
        assertEquals("test-group", subscription.getGroupName());
        assertEquals(SubscriptionStatus.ACTIVE, subscription.getStatus());
        assertNotNull(subscription.getSubscribedAt());
        assertNotNull(subscription.getLastActiveAt());
        assertNotNull(subscription.getLastHeartbeatAt());
    }

    @Test
    void testBuilderWithAllFields() {
        Instant now = Instant.now();
        Instant earlier = now.minusSeconds(3600);
        
        Subscription subscription = Subscription.builder()
            .id(123L)
            .topic("test-topic")
            .groupName("test-group")
            .status(SubscriptionStatus.PAUSED)
            .subscribedAt(earlier)
            .lastActiveAt(now)
            .startFromMessageId(1000L)
            .startFromTimestamp(earlier)
            .heartbeatIntervalSeconds(30)
            .heartbeatTimeoutSeconds(120)
            .lastHeartbeatAt(now)
            .backfillStatus("IN_PROGRESS")
            .backfillCheckpointId(500L)
            .backfillProcessedMessages(250L)
            .backfillTotalMessages(1000L)
            .backfillStartedAt(earlier)
            .backfillCompletedAt(now)
            .build();
        
        assertEquals(123L, subscription.getId());
        assertEquals("test-topic", subscription.getTopic());
        assertEquals("test-group", subscription.getGroupName());
        assertEquals(SubscriptionStatus.PAUSED, subscription.getStatus());
        assertEquals(earlier, subscription.getSubscribedAt());
        assertEquals(now, subscription.getLastActiveAt());
        assertEquals(1000L, subscription.getStartFromMessageId());
        assertEquals(earlier, subscription.getStartFromTimestamp());
        assertEquals(30, subscription.getHeartbeatIntervalSeconds());
        assertEquals(120, subscription.getHeartbeatTimeoutSeconds());
        assertEquals(now, subscription.getLastHeartbeatAt());
        assertEquals("IN_PROGRESS", subscription.getBackfillStatus());
        assertEquals(500L, subscription.getBackfillCheckpointId());
        assertEquals(250L, subscription.getBackfillProcessedMessages());
        assertEquals(1000L, subscription.getBackfillTotalMessages());
        assertEquals(earlier, subscription.getBackfillStartedAt());
        assertEquals(now, subscription.getBackfillCompletedAt());
    }

    @Test
    void testBuilderRequiresTopicName() {
        Exception exception = assertThrows(NullPointerException.class, () -> {
            Subscription.builder()
                .groupName("test-group")
                .build();
        });
        
        assertTrue(exception.getMessage().contains("topic"));
    }

    @Test
    void testBuilderRequiresGroupName() {
        Exception exception = assertThrows(NullPointerException.class, () -> {
            Subscription.builder()
                .topic("test-topic")
                .build();
        });
        
        assertTrue(exception.getMessage().contains("groupName"));
    }

    @Test
    void testIsActiveWhenStatusIsActive() {
        Subscription subscription = Subscription.builder()
            .topic("test-topic")
            .groupName("test-group")
            .status(SubscriptionStatus.ACTIVE)
            .build();
        
        assertTrue(subscription.isActive());
    }

    @Test
    void testIsActiveWhenStatusIsNotActive() {
        Subscription paused = Subscription.builder()
            .topic("test-topic")
            .groupName("test-group")
            .status(SubscriptionStatus.PAUSED)
            .build();

        assertFalse(paused.isActive());

        Subscription cancelled = Subscription.builder()
            .topic("test-topic")
            .groupName("test-group")
            .status(SubscriptionStatus.CANCELLED)
            .build();

        assertFalse(cancelled.isActive());
    }

    @Test
    void testIsHeartbeatTimedOutWhenNoHeartbeat() {
        Subscription subscription = Subscription.builder()
            .topic("test-topic")
            .groupName("test-group")
            .lastHeartbeatAt(null)
            .build();

        assertFalse(subscription.isHeartbeatTimedOut());
    }

    @Test
    void testIsHeartbeatTimedOutWhenNotTimedOut() {
        Instant recentHeartbeat = Instant.now().minusSeconds(60);

        Subscription subscription = Subscription.builder()
            .topic("test-topic")
            .groupName("test-group")
            .lastHeartbeatAt(recentHeartbeat)
            .heartbeatTimeoutSeconds(300)
            .build();

        assertFalse(subscription.isHeartbeatTimedOut());
    }

    @Test
    void testIsHeartbeatTimedOutWhenTimedOut() {
        Instant oldHeartbeat = Instant.now().minusSeconds(400);

        Subscription subscription = Subscription.builder()
            .topic("test-topic")
            .groupName("test-group")
            .lastHeartbeatAt(oldHeartbeat)
            .heartbeatTimeoutSeconds(300)
            .build();

        assertTrue(subscription.isHeartbeatTimedOut());
    }

    @Test
    void testEqualsAndHashCode() {
        Subscription sub1 = Subscription.builder()
            .id(1L)
            .topic("test-topic")
            .groupName("test-group")
            .build();

        Subscription sub2 = Subscription.builder()
            .id(1L)
            .topic("test-topic")
            .groupName("test-group")
            .status(SubscriptionStatus.PAUSED)
            .build();

        Subscription sub3 = Subscription.builder()
            .id(2L)
            .topic("test-topic")
            .groupName("test-group")
            .build();

        // Same id, topic, groupName should be equal
        assertEquals(sub1, sub2);
        assertEquals(sub1.hashCode(), sub2.hashCode());

        // Different id should not be equal
        assertNotEquals(sub1, sub3);
    }

    @Test
    void testEqualsSameObject() {
        Subscription subscription = Subscription.builder()
            .topic("test-topic")
            .groupName("test-group")
            .build();

        assertEquals(subscription, subscription);
    }

    @Test
    void testEqualsNull() {
        Subscription subscription = Subscription.builder()
            .topic("test-topic")
            .groupName("test-group")
            .build();

        assertNotEquals(subscription, null);
    }

    @Test
    void testEqualsDifferentClass() {
        Subscription subscription = Subscription.builder()
            .topic("test-topic")
            .groupName("test-group")
            .build();

        assertNotEquals(subscription, "not a subscription");
    }

    @Test
    void testToString() {
        Subscription subscription = Subscription.builder()
            .id(123L)
            .topic("test-topic")
            .groupName("test-group")
            .status(SubscriptionStatus.ACTIVE)
            .build();

        String str = subscription.toString();

        assertTrue(str.contains("123"));
        assertTrue(str.contains("test-topic"));
        assertTrue(str.contains("test-group"));
        assertTrue(str.contains("ACTIVE"));
    }

    @Test
    void testBuilderDefaults() {
        Subscription subscription = Subscription.builder()
            .topic("test-topic")
            .groupName("test-group")
            .build();

        // Verify default values
        assertEquals(SubscriptionStatus.ACTIVE, subscription.getStatus());
        assertEquals(60, subscription.getHeartbeatIntervalSeconds());
        assertEquals(300, subscription.getHeartbeatTimeoutSeconds());
        assertEquals("NONE", subscription.getBackfillStatus());
        assertEquals(0L, subscription.getBackfillProcessedMessages());
        assertNotNull(subscription.getSubscribedAt());
        assertNotNull(subscription.getLastActiveAt());
        assertNotNull(subscription.getLastHeartbeatAt());
    }
}


