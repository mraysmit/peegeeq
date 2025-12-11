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

package dev.mars.peegeeq.api.deadletter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DeadLetterMessageInfo record.
 */
class DeadLetterMessageInfoTest {

    @Test
    @DisplayName("builder creates valid instance")
    void builder_createsValidInstance() {
        Instant now = Instant.now();
        DeadLetterMessageInfo info = DeadLetterMessageInfo.builder()
            .id(1L)
            .originalTable("messages")
            .originalId(100L)
            .topic("test-topic")
            .payload("{\"data\":\"test\"}")
            .originalCreatedAt(now.minusSeconds(60))
            .failedAt(now)
            .failureReason("Processing timeout")
            .retryCount(3)
            .headers(Map.of("key", "value"))
            .correlationId("corr-123")
            .messageGroup("group-1")
            .build();

        assertEquals(1L, info.id());
        assertEquals("messages", info.originalTable());
        assertEquals(100L, info.originalId());
        assertEquals("test-topic", info.topic());
        assertEquals("{\"data\":\"test\"}", info.payload());
        assertEquals(now.minusSeconds(60), info.originalCreatedAt());
        assertEquals(now, info.failedAt());
        assertEquals("Processing timeout", info.failureReason());
        assertEquals(3, info.retryCount());
        assertEquals(Map.of("key", "value"), info.headers());
        assertEquals("corr-123", info.correlationId());
        assertEquals("group-1", info.messageGroup());
    }

    @Test
    @DisplayName("null originalTable throws NullPointerException")
    void nullOriginalTable_throwsNPE() {
        Instant now = Instant.now();
        assertThrows(NullPointerException.class, () ->
            DeadLetterMessageInfo.builder()
                .originalTable(null)
                .topic("test")
                .payload("data")
                .originalCreatedAt(now)
                .failedAt(now)
                .failureReason("error")
                .build()
        );
    }

    @Test
    @DisplayName("null topic throws NullPointerException")
    void nullTopic_throwsNPE() {
        Instant now = Instant.now();
        assertThrows(NullPointerException.class, () ->
            DeadLetterMessageInfo.builder()
                .originalTable("messages")
                .topic(null)
                .payload("data")
                .originalCreatedAt(now)
                .failedAt(now)
                .failureReason("error")
                .build()
        );
    }

    @Test
    @DisplayName("null payload throws NullPointerException")
    void nullPayload_throwsNPE() {
        Instant now = Instant.now();
        assertThrows(NullPointerException.class, () ->
            DeadLetterMessageInfo.builder()
                .originalTable("messages")
                .topic("test")
                .payload(null)
                .originalCreatedAt(now)
                .failedAt(now)
                .failureReason("error")
                .build()
        );
    }

    @Test
    @DisplayName("headers are immutable copy")
    void headers_areImmutableCopy() {
        Instant now = Instant.now();
        Map<String, String> mutableHeaders = new java.util.HashMap<>();
        mutableHeaders.put("key", "value");
        
        DeadLetterMessageInfo info = DeadLetterMessageInfo.builder()
            .originalTable("messages")
            .topic("test")
            .payload("data")
            .originalCreatedAt(now)
            .failedAt(now)
            .failureReason("error")
            .headers(mutableHeaders)
            .build();
        
        // Modify original map
        mutableHeaders.put("key2", "value2");
        
        // Info headers should not be affected
        assertFalse(info.headers().containsKey("key2"));
        assertEquals(1, info.headers().size());
    }

    @Test
    @DisplayName("null headers are allowed")
    void nullHeaders_areAllowed() {
        Instant now = Instant.now();
        DeadLetterMessageInfo info = DeadLetterMessageInfo.builder()
            .originalTable("messages")
            .topic("test")
            .payload("data")
            .originalCreatedAt(now)
            .failedAt(now)
            .failureReason("error")
            .headers(null)
            .build();
        
        assertNull(info.headers());
    }
}

