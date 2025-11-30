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

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
@DisplayName("Message Filter Predicate Tests")
class MessageFilterTest {

    @Test
    @DisplayName("Should filter messages by exact header value")
    void testByHeader() {
        Predicate<Message<String>> filter = MessageFilter.byHeader("key", "value");
        
        assertTrue(filter.test(createMessage(Map.of("key", "value"))));
        assertFalse(filter.test(createMessage(Map.of("key", "other"))));
        assertFalse(filter.test(createMessage(Map.of("other", "value"))));
        assertFalse(filter.test(createMessage(null)));
    }

    @Test
    @DisplayName("Should filter messages by header value in a set")
    void testByHeaderIn() {
        Predicate<Message<String>> filter = MessageFilter.byHeaderIn("key", Set.of("v1", "v2"));
        
        assertTrue(filter.test(createMessage(Map.of("key", "v1"))));
        assertTrue(filter.test(createMessage(Map.of("key", "v2"))));
        assertFalse(filter.test(createMessage(Map.of("key", "v3"))));
        assertFalse(filter.test(createMessage(Map.of("other", "v1"))));
        assertFalse(filter.test(createMessage(null)));
        
        // Null checks
        assertFalse(MessageFilter.<String>byHeaderIn(null, Set.of("v")).test(createMessage(Map.of("k", "v"))));
        assertFalse(MessageFilter.<String>byHeaderIn("k", null).test(createMessage(Map.of("k", "v"))));
    }

    @Test
    @DisplayName("Should filter messages by header value matching regex pattern")
    void testByHeaderPattern() {
        Predicate<Message<String>> filter = MessageFilter.byHeaderPattern("key", Pattern.compile("^v.*"));
        
        assertTrue(filter.test(createMessage(Map.of("key", "value"))));
        assertTrue(filter.test(createMessage(Map.of("key", "v1"))));
        assertFalse(filter.test(createMessage(Map.of("key", "other"))));
        assertFalse(filter.test(createMessage(null)));
        
        // Null checks
        assertFalse(MessageFilter.<String>byHeaderPattern(null, Pattern.compile(".*")).test(createMessage(Map.of("k", "v"))));
        assertFalse(MessageFilter.<String>byHeaderPattern("k", null).test(createMessage(Map.of("k", "v"))));
    }

    @Test
    @DisplayName("Should filter messages by multiple exact header values")
    void testByHeaders() {
        Predicate<Message<String>> filter = MessageFilter.byHeaders(Map.of("k1", "v1", "k2", "v2"));
        
        assertTrue(filter.test(createMessage(Map.of("k1", "v1", "k2", "v2"))));
        assertTrue(filter.test(createMessage(Map.of("k1", "v1", "k2", "v2", "k3", "v3"))));
        assertFalse(filter.test(createMessage(Map.of("k1", "v1"))));
        assertFalse(filter.test(createMessage(Map.of("k1", "v1", "k2", "other"))));
        assertFalse(filter.test(createMessage(null)));
        
        // Null checks
        assertFalse(MessageFilter.<String>byHeaders(null).test(createMessage(Map.of("k", "v"))));
    }

    @Test
    @DisplayName("Should filter messages matching any of the provided header criteria")
    void testByAnyHeader() {
        Predicate<Message<String>> filter = MessageFilter.byAnyHeader(Map.of(
            "k1", Set.of("v1a", "v1b"),
            "k2", Set.of("v2")
        ));
        
        assertTrue(filter.test(createMessage(Map.of("k1", "v1a"))));
        assertTrue(filter.test(createMessage(Map.of("k1", "v1b"))));
        assertTrue(filter.test(createMessage(Map.of("k2", "v2"))));
        assertTrue(filter.test(createMessage(Map.of("k1", "v1a", "k2", "v2"))));
        assertFalse(filter.test(createMessage(Map.of("k1", "other"))));
        assertFalse(filter.test(createMessage(Map.of("k3", "v3"))));
        assertFalse(filter.test(createMessage(null)));
        
        // Null checks
        assertFalse(MessageFilter.<String>byAnyHeader(null).test(createMessage(Map.of("k", "v"))));
    }

    @Test
    @DisplayName("Should filter messages based on priority levels (HIGH > NORMAL > LOW)")
    void testByPriority() {
        // High priority filter
        Predicate<Message<String>> highFilter = MessageFilter.byPriority("HIGH");
        assertTrue(highFilter.test(createMessage(Map.of("priority", "HIGH"))));
        assertFalse(highFilter.test(createMessage(Map.of("priority", "NORMAL"))));
        assertFalse(highFilter.test(createMessage(Map.of("priority", "LOW"))));
        assertFalse(highFilter.test(createMessage(Map.of()))); // Default is LOW

        // Normal priority filter (accepts HIGH and NORMAL)
        Predicate<Message<String>> normalFilter = MessageFilter.byPriority("NORMAL");
        assertTrue(normalFilter.test(createMessage(Map.of("priority", "HIGH"))));
        assertTrue(normalFilter.test(createMessage(Map.of("priority", "NORMAL"))));
        assertFalse(normalFilter.test(createMessage(Map.of("priority", "LOW"))));
        assertFalse(normalFilter.test(createMessage(Map.of())));

        // Low priority filter (accepts all)
        Predicate<Message<String>> lowFilter = MessageFilter.byPriority("LOW");
        assertTrue(lowFilter.test(createMessage(Map.of("priority", "HIGH"))));
        assertTrue(lowFilter.test(createMessage(Map.of("priority", "NORMAL"))));
        assertTrue(lowFilter.test(createMessage(Map.of("priority", "LOW"))));
        assertTrue(lowFilter.test(createMessage(Map.of())));
        
        // Null/Invalid checks
        assertFalse(MessageFilter.<String>byPriority(null).test(createMessage(Map.of())));
        assertFalse(MessageFilter.<String>byPriority("INVALID").test(createMessage(Map.of("priority", "HIGH"))));
    }

    @Test
    @DisplayName("Should filter messages using convenience methods (Region, Type, Source)")
    void testConvenienceFilters() {
        // Region
        assertTrue(MessageFilter.<String>byRegion(Set.of("US")).test(createMessage(Map.of("region", "US"))));
        assertFalse(MessageFilter.<String>byRegion(Set.of("US")).test(createMessage(Map.of("region", "EU"))));

        // Type
        assertTrue(MessageFilter.<String>byType(Set.of("ORDER")).test(createMessage(Map.of("type", "ORDER"))));
        assertFalse(MessageFilter.<String>byType(Set.of("ORDER")).test(createMessage(Map.of("type", "PAYMENT"))));

        // Source
        assertTrue(MessageFilter.<String>bySource(Set.of("WEB")).test(createMessage(Map.of("source", "WEB"))));
        assertFalse(MessageFilter.<String>bySource(Set.of("WEB")).test(createMessage(Map.of("source", "MOBILE"))));
    }

    @Test
    @DisplayName("Should correctly accept or reject all messages")
    void testAcceptAndRejectAll() {
        assertTrue(MessageFilter.<String>acceptAll().test(createMessage(null)));
        assertFalse(MessageFilter.<String>rejectAll().test(createMessage(null)));
    }

    @Test
    @DisplayName("Should correctly combine filters using AND, OR, NOT logic")
    void testLogicCombinators() {
        Predicate<Message<String>> trueFilter = MessageFilter.acceptAll();
        Predicate<Message<String>> falseFilter = MessageFilter.rejectAll();

        // AND
        assertTrue(MessageFilter.and(trueFilter, trueFilter).test(null));
        assertFalse(MessageFilter.and(trueFilter, falseFilter).test(null));
        assertFalse(MessageFilter.and(falseFilter, trueFilter).test(null));
        assertFalse(MessageFilter.and(falseFilter, falseFilter).test(null));

        // OR
        assertTrue(MessageFilter.or(trueFilter, trueFilter).test(null));
        assertTrue(MessageFilter.or(trueFilter, falseFilter).test(null));
        assertTrue(MessageFilter.or(falseFilter, trueFilter).test(null));
        assertFalse(MessageFilter.or(falseFilter, falseFilter).test(null));

        // NOT
        assertFalse(MessageFilter.not(trueFilter).test(null));
        assertTrue(MessageFilter.not(falseFilter).test(null));
    }

    private Message<String> createMessage(Map<String, String> headers) {
        return new SimpleMessage<>("id", "topic", "payload", headers);
    }
}
