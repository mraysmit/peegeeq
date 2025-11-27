package dev.mars.peegeeq.db.consumer;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE tests for OutboxMessage.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
public class OutboxMessageCoreTest {

    @Test
    void testOutboxMessageBuilder() {
        Instant now = Instant.now();
        JsonObject payload = new JsonObject().put("key", "value");
        JsonObject headers = new JsonObject().put("header", "value");

        OutboxMessage message = OutboxMessage.builder()
            .id(1L)
            .topic("test-topic")
            .payload(payload)
            .headers(headers)
            .correlationId("corr-123")
            .messageGroup("group-1")
            .createdAt(now)
            .requiredConsumerGroups(3)
            .completedConsumerGroups(1)
            .build();

        assertEquals(1L, message.getId());
        assertEquals("test-topic", message.getTopic());
        assertEquals(payload, message.getPayload());
        assertEquals(headers, message.getHeaders());
        assertEquals("corr-123", message.getCorrelationId());
        assertEquals("group-1", message.getMessageGroup());
        assertEquals(now, message.getCreatedAt());
        assertEquals(3, message.getRequiredConsumerGroups());
        assertEquals(1, message.getCompletedConsumerGroups());
    }

    @Test
    void testOutboxMessageBuilderWithNulls() {
        OutboxMessage message = OutboxMessage.builder().build();

        assertNull(message.getId());
        assertNull(message.getTopic());
        assertNull(message.getPayload());
        assertNull(message.getHeaders());
        assertNull(message.getCorrelationId());
        assertNull(message.getMessageGroup());
        assertNull(message.getCreatedAt());
        assertNull(message.getRequiredConsumerGroups());
        assertNull(message.getCompletedConsumerGroups());
    }

    @Test
    void testOutboxMessageToString() {
        Instant now = Instant.now();
        OutboxMessage message = OutboxMessage.builder()
            .id(1L)
            .topic("test-topic")
            .correlationId("corr-123")
            .messageGroup("group-1")
            .createdAt(now)
            .requiredConsumerGroups(3)
            .completedConsumerGroups(1)
            .build();

        String toString = message.toString();
        assertTrue(toString.contains("id=1"));
        assertTrue(toString.contains("topic='test-topic'"));
        assertTrue(toString.contains("correlationId='corr-123'"));
        assertTrue(toString.contains("messageGroup='group-1'"));
        assertTrue(toString.contains("requiredConsumerGroups=3"));
        assertTrue(toString.contains("completedConsumerGroups=1"));
    }

    @Test
    void testOutboxMessageBuilderChaining() {
        OutboxMessage message = OutboxMessage.builder()
            .id(1L)
            .topic("test-topic")
            .payload(new JsonObject())
            .headers(new JsonObject())
            .correlationId("corr-123")
            .messageGroup("group-1")
            .createdAt(Instant.now())
            .requiredConsumerGroups(3)
            .completedConsumerGroups(1)
            .build();

        assertNotNull(message);
    }

    @Test
    void testOutboxMessageGetters() {
        Instant now = Instant.now();
        JsonObject payload = new JsonObject().put("data", "test");
        JsonObject headers = new JsonObject().put("type", "event");

        OutboxMessage message = OutboxMessage.builder()
            .id(42L)
            .topic("events")
            .payload(payload)
            .headers(headers)
            .correlationId("abc-def")
            .messageGroup("group-x")
            .createdAt(now)
            .requiredConsumerGroups(5)
            .completedConsumerGroups(2)
            .build();

        assertEquals(42L, message.getId());
        assertEquals("events", message.getTopic());
        assertSame(payload, message.getPayload());
        assertSame(headers, message.getHeaders());
        assertEquals("abc-def", message.getCorrelationId());
        assertEquals("group-x", message.getMessageGroup());
        assertEquals(now, message.getCreatedAt());
        assertEquals(5, message.getRequiredConsumerGroups());
        assertEquals(2, message.getCompletedConsumerGroups());
    }
}

