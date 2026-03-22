package dev.mars.peegeeq.pgqueue;

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

import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgNativeConsumerGroupSafetyTest {

    @Test
    void selectConsumerIndexHandlesIntegerMinValueHash() {
        int index = PgNativeConsumerGroup.selectConsumerIndex(Integer.MIN_VALUE, 3);
        assertTrue(index >= 0 && index < 3, "Index must stay within bounds");
    }

    @Test
    void startWithSubscriptionOptionsRejectsNull() {
        PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                "group-a",
                "topic-a",
                String.class,
                null,
                null,
                null,
                null,
                null);

        assertThrows(IllegalArgumentException.class,
            () -> group.start((SubscriptionOptions) null));
    }
}
