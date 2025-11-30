package dev.mars.peegeeq.api;

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
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
class SimpleBiTemporalEventTest {

    @Test
    void testValidCreation() {
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Map<String, String> headers = new HashMap<>();
        headers.put("key", "value");

        SimpleBiTemporalEvent<String> event = new SimpleBiTemporalEvent<>(
            eventId, "TEST_TYPE", "payload",
            now, now, 1L,
            null, headers,
            "corr-1", "agg-1",
            false, null
        );

        assertEquals(eventId, event.getEventId());
        assertEquals("TEST_TYPE", event.getEventType());
        assertEquals("payload", event.getPayload());
        assertEquals(now, event.getValidTime());
        assertEquals(now, event.getTransactionTime());
        assertEquals(1L, event.getVersion());
        assertNull(event.getPreviousVersionId());
        assertFalse(event.isCorrection());
        assertNull(event.getCorrectionReason());
        assertEquals("corr-1", event.getCorrelationId());
        assertEquals("agg-1", event.getAggregateId());
        assertEquals("value", event.getHeaders().get("key"));
    }

    @Test
    void testValidCorrectionCreation() {
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        
        SimpleBiTemporalEvent<String> event = new SimpleBiTemporalEvent<>(
            eventId, "TEST_TYPE", "payload",
            now, now, 2L,
            "prev-id", null,
            "corr-1", "agg-1",
            true, "Fixing typo"
        );

        assertEquals(2L, event.getVersion());
        assertEquals("prev-id", event.getPreviousVersionId());
        assertTrue(event.isCorrection());
        assertEquals("Fixing typo", event.getCorrectionReason());
    }

    @Test
    void testNullChecks() {
        Instant now = Instant.now();
        
        assertThrows(NullPointerException.class, () -> new SimpleBiTemporalEvent<>(
            null, "type", "payload", now, now, 1L, null, null, null, null, false, null
        ), "Should throw NPE for null eventId");

        assertThrows(NullPointerException.class, () -> new SimpleBiTemporalEvent<>(
            "id", null, "payload", now, now, 1L, null, null, null, null, false, null
        ), "Should throw NPE for null eventType");

        assertThrows(NullPointerException.class, () -> new SimpleBiTemporalEvent<>(
            "id", "type", null, now, now, 1L, null, null, null, null, false, null
        ), "Should throw NPE for null payload");

        assertThrows(NullPointerException.class, () -> new SimpleBiTemporalEvent<>(
            "id", "type", "payload", null, now, 1L, null, null, null, null, false, null
        ), "Should throw NPE for null validTime");

        assertThrows(NullPointerException.class, () -> new SimpleBiTemporalEvent<>(
            "id", "type", "payload", now, null, 1L, null, null, null, null, false, null
        ), "Should throw NPE for null transactionTime");
    }

    @Test
    void testVersionValidation() {
        Instant now = Instant.now();
        
        // Version <= 0
        assertThrows(IllegalArgumentException.class, () -> new SimpleBiTemporalEvent<>(
            "id", "type", "payload", now, now, 0L, null, null, null, null, false, null
        ), "Should throw IAE for version 0");

        assertThrows(IllegalArgumentException.class, () -> new SimpleBiTemporalEvent<>(
            "id", "type", "payload", now, now, -1L, null, null, null, null, false, null
        ), "Should throw IAE for negative version");

        // Version 1 with previous ID
        assertThrows(IllegalArgumentException.class, () -> new SimpleBiTemporalEvent<>(
            "id", "type", "payload", now, now, 1L, "prev-id", null, null, null, false, null
        ), "Should throw IAE for version 1 with previousVersionId");

        // Version 2 without previous ID
        assertThrows(IllegalArgumentException.class, () -> new SimpleBiTemporalEvent<>(
            "id", "type", "payload", now, now, 2L, null, null, null, null, false, null
        ), "Should throw IAE for version > 1 without previousVersionId");
    }

    @Test
    void testCorrectionValidation() {
        Instant now = Instant.now();
        
        // Correction without reason
        assertThrows(IllegalArgumentException.class, () -> new SimpleBiTemporalEvent<>(
            "id", "type", "payload", now, now, 2L, "prev", null, null, null, true, null
        ), "Should throw IAE for correction without reason");

        // Not correction but has reason
        assertThrows(IllegalArgumentException.class, () -> new SimpleBiTemporalEvent<>(
            "id", "type", "payload", now, now, 1L, null, null, null, null, false, "reason"
        ), "Should throw IAE for non-correction with reason");
    }
    
    @Test
    void testHeadersImmutability() {
        Map<String, String> headers = new HashMap<>();
        headers.put("key", "value");
        Instant now = Instant.now();
        
        SimpleBiTemporalEvent<String> event = new SimpleBiTemporalEvent<>(
            "id", "type", "payload", now, now, 1L, null, headers, null, null, false, null
        );
        
        // Verify we can read
        assertEquals("value", event.getHeaders().get("key"));
        
        // Verify we cannot modify the returned map
        assertThrows(UnsupportedOperationException.class, () -> {
            event.getHeaders().put("new", "value");
        }, "Headers map should be immutable");
        
        // Verify modifying original map doesn't affect event
        headers.put("key", "changed");
        assertEquals("value", event.getHeaders().get("key"), "Event headers should be a copy");
    }
    
    @Test
    void testNullHeadersHandling() {
        Instant now = Instant.now();
        SimpleBiTemporalEvent<String> event = new SimpleBiTemporalEvent<>(
            "id", "type", "payload", now, now, 1L, null, null, null, null, false, null
        );
        
        assertNotNull(event.getHeaders());
        assertTrue(event.getHeaders().isEmpty());
    }

    @Test
    void testConvenienceConstructors() {
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        
        // Constructor 2: Minimal
        SimpleBiTemporalEvent<String> event1 = new SimpleBiTemporalEvent<>(
            eventId, "TEST_TYPE", "payload", now, now
        );
        assertEquals(eventId, event1.getEventId());
        assertEquals(1L, event1.getVersion());
        assertFalse(event1.isCorrection());
        assertTrue(event1.getHeaders().isEmpty());
        
        // Constructor 3: With headers/correlation
        Map<String, String> headers = Map.of("k", "v");
        SimpleBiTemporalEvent<String> event2 = new SimpleBiTemporalEvent<>(
            eventId, "TEST_TYPE", "payload", now, now,
            headers, "corr-1", "agg-1"
        );
        assertEquals("corr-1", event2.getCorrelationId());
        assertEquals("agg-1", event2.getAggregateId());
        assertEquals("v", event2.getHeaders().get("k"));
        assertEquals(1L, event2.getVersion());
    }

    @Test
    void testEqualsAndHashCode() {
        Instant now = Instant.now();
        String id1 = "id1";
        String id2 = "id2";
        
        SimpleBiTemporalEvent<String> event1 = new SimpleBiTemporalEvent<>(
            id1, "type", "payload", now, now
        );
        SimpleBiTemporalEvent<String> event1Copy = new SimpleBiTemporalEvent<>(
            id1, "type", "payload", now, now
        );
        SimpleBiTemporalEvent<String> event2 = new SimpleBiTemporalEvent<>(
            id2, "type", "payload", now, now
        );
        
        // Equals
        assertEquals(event1, event1, "Should be equal to self");
        assertEquals(event1, event1Copy, "Should be equal to copy");
        assertNotEquals(event1, event2, "Should not be equal to different event");
        assertNotEquals(event1, null, "Should not be equal to null");
        assertNotEquals(event1, "string", "Should not be equal to other type");
        
        // HashCode
        assertEquals(event1.hashCode(), event1Copy.hashCode(), "HashCodes should match");
        assertNotEquals(event1.hashCode(), event2.hashCode(), "HashCodes should differ (usually)");
    }

    @Test
    void testToString() {
        Instant now = Instant.now();
        SimpleBiTemporalEvent<String> event = new SimpleBiTemporalEvent<>(
            "id-123", "TEST_TYPE", "payload", now, now
        );
        
        String str = event.toString();
        assertTrue(str.contains("id-123"));
        assertTrue(str.contains("TEST_TYPE"));
        assertTrue(str.contains("version=1"));
    }
}
