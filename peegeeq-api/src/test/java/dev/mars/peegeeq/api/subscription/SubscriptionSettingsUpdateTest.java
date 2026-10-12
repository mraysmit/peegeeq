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

import dev.mars.peegeeq.api.messaging.StartPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the validation of a settings-update request.
 */
@Tag("core")
class SubscriptionSettingsUpdateTest {

    @Test
    @DisplayName("an update with heartbeat settings only names no bound")
    void heartbeatSettingsOnly_namesNoBound() {
        SubscriptionSettingsUpdate update = new SubscriptionSettingsUpdate(30, 120, null, null, null);

        assertEquals(30, update.heartbeatIntervalSeconds());
        assertEquals(120, update.heartbeatTimeoutSeconds());
        assertFalse(update.namesBound());
    }

    @Test
    @DisplayName("an update with no field is valid and names no bound")
    void emptyUpdate_isValid() {
        SubscriptionSettingsUpdate update = new SubscriptionSettingsUpdate(null, null, null, null, null);

        assertNull(update.heartbeatIntervalSeconds());
        assertNull(update.heartbeatTimeoutSeconds());
        assertFalse(update.namesBound());
    }

    @Test
    @DisplayName("an update with a start position names a bound")
    void startPosition_namesBound() {
        SubscriptionSettingsUpdate update =
            new SubscriptionSettingsUpdate(null, null, StartPosition.FROM_MESSAGE_ID, 42L, null);

        assertTrue(update.namesBound());
        assertEquals(StartPosition.FROM_MESSAGE_ID, update.startPosition());
        assertEquals(42L, update.startFromMessageId());
    }

    @Test
    @DisplayName("a heartbeat interval that is not positive is rejected")
    void nonPositiveInterval_isRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new SubscriptionSettingsUpdate(0, null, null, null, null));
        assertEquals("heartbeatIntervalSeconds must be positive", error.getMessage());
    }

    @Test
    @DisplayName("a heartbeat timeout that is not positive is rejected")
    void nonPositiveTimeout_isRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new SubscriptionSettingsUpdate(null, -1, null, null, null));
        assertEquals("heartbeatTimeoutSeconds must be positive", error.getMessage());
    }

    @Test
    @DisplayName("a timeout not greater than the interval is rejected when both are supplied")
    void timeoutNotGreaterThanInterval_isRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new SubscriptionSettingsUpdate(60, 60, null, null, null));
        assertEquals("heartbeatTimeoutSeconds must be greater than heartbeatIntervalSeconds", error.getMessage());
    }

    @Test
    @DisplayName("FROM_MESSAGE_ID without a message id is rejected")
    void fromMessageIdWithoutId_isRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new SubscriptionSettingsUpdate(null, null, StartPosition.FROM_MESSAGE_ID, null, null));
        assertEquals("startFromMessageId must be provided when startPosition is FROM_MESSAGE_ID",
            error.getMessage());
    }

    @Test
    @DisplayName("FROM_TIMESTAMP without a timestamp is rejected")
    void fromTimestampWithoutTimestamp_isRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new SubscriptionSettingsUpdate(null, null, StartPosition.FROM_TIMESTAMP, null, null));
        assertEquals("startFromTimestamp must be provided when startPosition is FROM_TIMESTAMP",
            error.getMessage());
    }

    @Test
    @DisplayName("a message id or a timestamp without a start position is rejected")
    void boundValueWithoutStartPosition_isRejected() {
        IllegalArgumentException idError = assertThrows(IllegalArgumentException.class,
            () -> new SubscriptionSettingsUpdate(null, null, null, 42L, null));
        assertEquals("startPosition must be provided with startFromMessageId or startFromTimestamp",
            idError.getMessage());

        IllegalArgumentException timestampError = assertThrows(IllegalArgumentException.class,
            () -> new SubscriptionSettingsUpdate(null, null, null, null, Instant.parse("2026-01-01T00:00:00Z")));
        assertEquals("startPosition must be provided with startFromMessageId or startFromTimestamp",
            timestampError.getMessage());
    }
}
