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
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE unit tests for TopicSemantics enum.
 * 
 * <p>Tests the TopicSemantics enum values and behavior.</p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
class TopicSemanticsTest {

    @Test
    void testEnumValues() {
        TopicSemantics[] values = TopicSemantics.values();
        
        assertEquals(2, values.length);
        assertEquals(TopicSemantics.QUEUE, values[0]);
        assertEquals(TopicSemantics.PUB_SUB, values[1]);
    }

    @Test
    void testValueOf() {
        assertEquals(TopicSemantics.QUEUE, TopicSemantics.valueOf("QUEUE"));
        assertEquals(TopicSemantics.PUB_SUB, TopicSemantics.valueOf("PUB_SUB"));
    }

    @Test
    void testValueOfInvalid() {
        assertThrows(IllegalArgumentException.class, () -> {
            TopicSemantics.valueOf("INVALID");
        });
    }

    @Test
    void testEnumEquality() {
        TopicSemantics queue1 = TopicSemantics.QUEUE;
        TopicSemantics queue2 = TopicSemantics.valueOf("QUEUE");
        
        assertSame(queue1, queue2);
        assertEquals(queue1, queue2);
    }

    @Test
    void testEnumToString() {
        assertEquals("QUEUE", TopicSemantics.QUEUE.toString());
        assertEquals("PUB_SUB", TopicSemantics.PUB_SUB.toString());
    }

    @Test
    void testEnumName() {
        assertEquals("QUEUE", TopicSemantics.QUEUE.name());
        assertEquals("PUB_SUB", TopicSemantics.PUB_SUB.name());
    }

    @Test
    void testEnumOrdinal() {
        assertEquals(0, TopicSemantics.QUEUE.ordinal());
        assertEquals(1, TopicSemantics.PUB_SUB.ordinal());
    }
}

