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

package dev.mars.peegeeq.api.messaging;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(TestCategories.CORE)
class SubscriptionOptionsDurableTest {

    @Test
    void durableFieldsHaveBackwardCompatibleDefaults() {
        SubscriptionOptions options = SubscriptionOptions.defaults();

        assertFalse(options.isDurableEnabled());
        assertNull(options.getSubscriptionName());
        assertNull(options.getConsumerId());
        assertEquals(500, options.getReplayBatchSize());
    }

    @Test
    void durableFieldsCanBeConfigured() {
        SubscriptionOptions options = SubscriptionOptions.builder()
            .durableEnabled(true)
            .subscriptionName("orders-projection")
            .consumerId("projection-instance-1")
            .replayBatchSize(100)
            .build();

        assertTrue(options.isDurableEnabled());
        assertEquals("orders-projection", options.getSubscriptionName());
        assertEquals("projection-instance-1", options.getConsumerId());
        assertEquals(100, options.getReplayBatchSize());
    }

    @Test
    void durableSubscriptionRequiresNonBlankName() {
        IllegalArgumentException missing = assertThrows(
            IllegalArgumentException.class,
            () -> SubscriptionOptions.builder().durableEnabled(true).build());
        IllegalArgumentException blank = assertThrows(
            IllegalArgumentException.class,
            () -> SubscriptionOptions.builder()
                .durableEnabled(true)
                .subscriptionName("   ")
                .build());

        assertTrue(missing.getMessage().contains("subscriptionName"));
        assertTrue(blank.getMessage().contains("subscriptionName"));
    }

    @Test
    void nonDurableSubscriptionDoesNotRequireName() {
        SubscriptionOptions options = SubscriptionOptions.builder()
            .durableEnabled(false)
            .build();

        assertNull(options.getSubscriptionName());
    }

    @Test
    void replayBatchSizeMustBePositive() {
        IllegalArgumentException zero = assertThrows(
            IllegalArgumentException.class,
            () -> SubscriptionOptions.builder().replayBatchSize(0));
        IllegalArgumentException negative = assertThrows(
            IllegalArgumentException.class,
            () -> SubscriptionOptions.builder().replayBatchSize(-1));

        assertTrue(zero.getMessage().contains("replayBatchSize"));
        assertTrue(negative.getMessage().contains("replayBatchSize"));
    }

    @Test
    void durableFieldsParticipateInValueSemantics() {
        SubscriptionOptions first = SubscriptionOptions.builder()
            .durableEnabled(true)
            .subscriptionName("orders-projection")
            .consumerId("instance-1")
            .replayBatchSize(100)
            .build();
        SubscriptionOptions equal = SubscriptionOptions.builder()
            .durableEnabled(true)
            .subscriptionName("orders-projection")
            .consumerId("instance-1")
            .replayBatchSize(100)
            .build();
        SubscriptionOptions different = SubscriptionOptions.builder()
            .durableEnabled(true)
            .subscriptionName("audit-projection")
            .consumerId("instance-1")
            .replayBatchSize(100)
            .build();

        assertEquals(first, equal);
        assertEquals(first.hashCode(), equal.hashCode());
        assertNotEquals(first, different);
        assertTrue(first.toString().contains("durableEnabled=true"));
        assertTrue(first.toString().contains("subscriptionName=orders-projection"));
        assertTrue(first.toString().contains("consumerId=instance-1"));
        assertTrue(first.toString().contains("replayBatchSize=100"));
    }
}
