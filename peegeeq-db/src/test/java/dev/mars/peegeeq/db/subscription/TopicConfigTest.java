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

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE unit tests for TopicConfig data model.
 * 
 * <p>Tests the TopicConfig builder, validation, getters, and business logic
 * without requiring database access.</p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
class TopicConfigTest {

    @Test
    void testBuilderWithRequiredFields() {
        TopicConfig config = TopicConfig.builder()
            .topic("test-topic")
            .build();
        
        assertNotNull(config);
        assertEquals("test-topic", config.getTopic());
        assertEquals(TopicSemantics.QUEUE, config.getSemantics());
        assertEquals(24, config.getMessageRetentionHours());
        assertEquals(24, config.getZeroSubscriptionRetentionHours());
        assertFalse(config.isBlockWritesOnZeroSubscriptions());
        assertEquals("REFERENCE_COUNTING", config.getCompletionTrackingMode());
    }

    @Test
    void testBuilderWithAllFields() {
        Instant now = Instant.now();
        Instant earlier = now.minusSeconds(3600);
        
        TopicConfig config = TopicConfig.builder()
            .topic("test-topic")
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(48)
            .zeroSubscriptionRetentionHours(12)
            .blockWritesOnZeroSubscriptions(true)
            .completionTrackingMode("OFFSET_WATERMARK")
            .createdAt(earlier)
            .updatedAt(now)
            .build();
        
        assertEquals("test-topic", config.getTopic());
        assertEquals(TopicSemantics.PUB_SUB, config.getSemantics());
        assertEquals(48, config.getMessageRetentionHours());
        assertEquals(12, config.getZeroSubscriptionRetentionHours());
        assertTrue(config.isBlockWritesOnZeroSubscriptions());
        assertEquals("OFFSET_WATERMARK", config.getCompletionTrackingMode());
        assertEquals(earlier, config.getCreatedAt());
        assertEquals(now, config.getUpdatedAt());
    }

    @Test
    void testBuilderRequiresTopicName() {
        Exception exception = assertThrows(NullPointerException.class, () -> {
            TopicConfig.builder().build();
        });
        
        assertTrue(exception.getMessage().contains("topic"));
    }

    @Test
    void testBuilderRejectsNullTopic() {
        Exception exception = assertThrows(NullPointerException.class, () -> {
            TopicConfig.builder()
                .topic(null)
                .build();
        });
        
        assertTrue(exception.getMessage().contains("topic"));
    }

    @Test
    void testBuilderRejectsNullSemantics() {
        Exception exception = assertThrows(NullPointerException.class, () -> {
            TopicConfig.builder()
                .topic("test-topic")
                .semantics(null)
                .build();
        });
        
        assertTrue(exception.getMessage().contains("semantics"));
    }

    @Test
    void testBuilderRejectsNullCompletionTrackingMode() {
        Exception exception = assertThrows(NullPointerException.class, () -> {
            TopicConfig.builder()
                .topic("test-topic")
                .completionTrackingMode(null)
                .build();
        });
        
        assertTrue(exception.getMessage().contains("completionTrackingMode"));
    }

    @Test
    void testBuilderRejectsNegativeMessageRetentionHours() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            TopicConfig.builder()
                .topic("test-topic")
                .messageRetentionHours(-1)
                .build();
        });
        
        assertTrue(exception.getMessage().contains("messageRetentionHours"));
    }

    @Test
    void testBuilderRejectsZeroMessageRetentionHours() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            TopicConfig.builder()
                .topic("test-topic")
                .messageRetentionHours(0)
                .build();
        });
        
        assertTrue(exception.getMessage().contains("messageRetentionHours"));
    }

    @Test
    void testBuilderRejectsNegativeZeroSubscriptionRetentionHours() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            TopicConfig.builder()
                .topic("test-topic")
                .zeroSubscriptionRetentionHours(-1)
                .build();
        });
        
        assertTrue(exception.getMessage().contains("zeroSubscriptionRetentionHours"));
    }

    @Test
    void testBuilderRejectsZeroZeroSubscriptionRetentionHours() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            TopicConfig.builder()
                .topic("test-topic")
                .zeroSubscriptionRetentionHours(0)
                .build();
        });

        assertTrue(exception.getMessage().contains("zeroSubscriptionRetentionHours"));
    }

    @Test
    void testIsPubSubWhenSemanticsIsPubSub() {
        TopicConfig config = TopicConfig.builder()
            .topic("test-topic")
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        assertTrue(config.isPubSub());
        assertFalse(config.isQueue());
    }

    @Test
    void testIsQueueWhenSemanticsIsQueue() {
        TopicConfig config = TopicConfig.builder()
            .topic("test-topic")
            .semantics(TopicSemantics.QUEUE)
            .build();

        assertTrue(config.isQueue());
        assertFalse(config.isPubSub());
    }

    @Test
    void testEqualsAndHashCode() {
        TopicConfig config1 = TopicConfig.builder()
            .topic("test-topic")
            .semantics(TopicSemantics.QUEUE)
            .build();

        TopicConfig config2 = TopicConfig.builder()
            .topic("test-topic")
            .semantics(TopicSemantics.PUB_SUB)
            .build();

        TopicConfig config3 = TopicConfig.builder()
            .topic("different-topic")
            .build();

        // Same topic should be equal (equality based on topic only)
        assertEquals(config1, config2);
        assertEquals(config1.hashCode(), config2.hashCode());

        // Different topic should not be equal
        assertNotEquals(config1, config3);
    }

    @Test
    void testEqualsSameObject() {
        TopicConfig config = TopicConfig.builder()
            .topic("test-topic")
            .build();

        assertEquals(config, config);
    }

    @Test
    void testEqualsNull() {
        TopicConfig config = TopicConfig.builder()
            .topic("test-topic")
            .build();

        assertNotEquals(config, null);
    }

    @Test
    void testEqualsDifferentClass() {
        TopicConfig config = TopicConfig.builder()
            .topic("test-topic")
            .build();

        assertNotEquals(config, "not a topic config");
    }

    @Test
    void testToString() {
        TopicConfig config = TopicConfig.builder()
            .topic("test-topic")
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(48)
            .zeroSubscriptionRetentionHours(12)
            .blockWritesOnZeroSubscriptions(true)
            .completionTrackingMode("OFFSET_WATERMARK")
            .build();

        String str = config.toString();

        assertTrue(str.contains("test-topic"));
        assertTrue(str.contains("PUB_SUB"));
        assertTrue(str.contains("48"));
        assertTrue(str.contains("12"));
        assertTrue(str.contains("true"));
        assertTrue(str.contains("OFFSET_WATERMARK"));
    }

    @Test
    void testBuilderDefaults() {
        TopicConfig config = TopicConfig.builder()
            .topic("test-topic")
            .build();

        // Verify default values
        assertEquals(TopicSemantics.QUEUE, config.getSemantics());
        assertEquals(24, config.getMessageRetentionHours());
        assertEquals(24, config.getZeroSubscriptionRetentionHours());
        assertFalse(config.isBlockWritesOnZeroSubscriptions());
        assertEquals("REFERENCE_COUNTING", config.getCompletionTrackingMode());
        assertNotNull(config.getCreatedAt());
        assertNotNull(config.getUpdatedAt());
    }
}



