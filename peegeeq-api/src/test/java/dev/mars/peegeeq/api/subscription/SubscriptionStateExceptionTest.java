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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SubscriptionStateException}.
 */
@Tag("core")
class SubscriptionStateExceptionTest {

    @Test
    @DisplayName("the exception carries the refusing state and is an IllegalStateException")
    void carriesTheRefusingState() {
        SubscriptionStateException error =
            new SubscriptionStateException("Cannot pause a DEAD subscription", SubscriptionState.DEAD);

        assertInstanceOf(IllegalStateException.class, error);
        assertEquals(SubscriptionState.DEAD, error.getCurrentState());
    }

    @Test
    @DisplayName("the exception requires the refusing state")
    void requiresTheRefusingState() {
        assertThrows(NullPointerException.class,
            () -> new SubscriptionStateException("message", null));
    }
}
