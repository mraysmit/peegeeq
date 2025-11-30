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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
@DisplayName("Simple Message Implementation Tests")
class SimpleMessageTest {

    @Test
    @DisplayName("Should correctly initialize all fields via full constructor")
    void testFullConstructor() {
        String id = UUID.randomUUID().toString();
        String topic = "test-topic";
        String payload = "payload";
        Map<String, String> headers = Map.of("k", "v");
        String correlationId = "corr-1";
        String group = "group-1";
        Instant createdAt = Instant.now();

        SimpleMessage<String> message = new SimpleMessage<>(
            id, topic, payload, headers, correlationId, group, createdAt
        );

        assertEquals(id, message.getId());
        assertEquals(topic, message.getTopic());
        assertEquals(payload, message.getPayload());
        assertEquals("v", message.getHeaders().get("k"));
        assertEquals(correlationId, message.getCorrelationId());
        assertEquals(group, message.getMessageGroup());
        assertEquals(createdAt, message.getCreatedAt());
    }

    @Test
    @DisplayName("Should correctly initialize fields via convenience constructors")
    void testConvenienceConstructors() {
        String id = "id";
        String topic = "topic";
        String payload = "payload";

        // Constructor 2
        SimpleMessage<String> m1 = new SimpleMessage<>(id, topic, payload, null, "corr", "group");
        assertNotNull(m1.getCreatedAt());
        assertTrue(m1.getHeaders().isEmpty());

        // Constructor 3
        SimpleMessage<String> m2 = new SimpleMessage<>(id, topic, payload, Map.of("k", "v"));
        assertNull(m2.getCorrelationId());
        assertNull(m2.getMessageGroup());
        assertEquals("v", m2.getHeaders().get("k"));

        // Constructor 4
        SimpleMessage<String> m3 = new SimpleMessage<>(id, topic, payload);
        assertTrue(m3.getHeaders().isEmpty());
        assertNull(m3.getCorrelationId());
    }

    @Test
    @DisplayName("Should throw NullPointerException for required fields")
    void testNullChecks() {
        assertThrows(NullPointerException.class, () -> new SimpleMessage<>(null, "t", "p"));
        assertThrows(NullPointerException.class, () -> new SimpleMessage<>("i", null, "p"));
        assertThrows(NullPointerException.class, () -> new SimpleMessage<>("i", "t", null));
    }

    @Test
    @DisplayName("Should implement equals and hashCode correctly")
    void testEqualsAndHashCode() {
        String id = "id";
        Instant now = Instant.now();
        
        SimpleMessage<String> m1 = new SimpleMessage<>(id, "t", "p", null, null, null, now);
        SimpleMessage<String> m2 = new SimpleMessage<>(id, "t", "p", null, null, null, now);
        SimpleMessage<String> m3 = new SimpleMessage<>("other", "t", "p", null, null, null, now);

        assertEquals(m1, m2);
        assertEquals(m1.hashCode(), m2.hashCode());
        assertNotEquals(m1, m3);
        assertNotEquals(m1, null);
        assertNotEquals(m1, "string");
    }

    @Test
    @DisplayName("Should return correct string representation")
    void testToString() {
        SimpleMessage<String> m = new SimpleMessage<>("id", "topic", "payload");
        String str = m.toString();
        assertTrue(str.contains("id"));
        assertTrue(str.contains("topic"));
        assertTrue(str.contains("payload"));
    }
}
