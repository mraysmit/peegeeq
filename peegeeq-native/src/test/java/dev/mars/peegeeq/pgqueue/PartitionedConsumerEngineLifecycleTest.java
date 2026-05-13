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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.api.messaging.SimpleMessage;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.consumer.OutboxMessage;
import dev.mars.peegeeq.db.consumer.PartitionedConsumerEngine;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE tests for {@link PartitionedConsumerEngine}.
 *
 * <p>Covers state machine transitions, start/stop/close guard paths,
 * payload/header parsing, and start-failure handling all without
 * a database. The engine's constructor hardwires DB collaborators;
 * when no pool is registered, async operations fail with
 * {@code IllegalStateException} which exercises the failure paths.</p>
 */
@Tag(TestCategories.CORE)
@DisplayName("PartitionedConsumerEngine CORE tests")
class PartitionedConsumerEngineLifecycleTest {

    private Vertx vertx;
    private PgConnectionManager connectionManager;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        connectionManager = new PgConnectionManager(vertx);
    }

    @AfterEach
    void tearDown() {
        if (connectionManager != null) {
            try { connectionManager.close(); } catch (Exception ignored) {}
        }
        if (vertx != null) {
            try { vertx.close(); } catch (Exception ignored) {}
        }
    }

    private PartitionedConsumerEngine<String> createEngine(String topic, String group, String instance) {
        return new PartitionedConsumerEngine<>(
                vertx, connectionManager, "test-svc",
                topic, group, instance,
                String.class, new ObjectMapper()
        );
    }

    private <T> void installMessageHandler(PartitionedConsumerEngine<T> engine, MessageHandler<T> handler) {
        try {
            var field = PartitionedConsumerEngine.class.getDeclaredField("messageHandler");
            field.setAccessible(true);
            field.set(engine, handler);
        } catch (ReflectiveOperationException e) {
            fail("Failed to install messageHandler for test", e);
        }
    }

    // ========================================================================
    // State machine: initial state, stop, close
    // ========================================================================

    @Nested
    @DisplayName("State Machine")
    class StateMachine {

        @Test
        @DisplayName("new engine is not running and has no assigned partitions")
        void newEngine_isNotRunning() {
            var engine = createEngine("t", "g", "i");
            assertFalse(engine.isRunning());
            assertTrue(engine.getAssignedPartitions().isEmpty());
        }

        @Test
        @DisplayName("stop() when not running is a no-op")
        void stopWhenNotRunning_succeeds() {
            var engine = createEngine("t", "g", "i");
            Future<Void> f = engine.stop();
            assertTrue(f.succeeded(), "stop() should succeed immediately when not running");
            assertFalse(engine.isRunning());
        }

        @Test
        @DisplayName("close() when not running succeeds")
        void closeWhenNotRunning_succeeds() {
            var engine = createEngine("t", "g", "i");
            Future<Void> f = engine.close();
            assertTrue(f.succeeded(), "close() should succeed when not running");
            assertFalse(engine.isRunning());
        }

        @Test
        @DisplayName("stop() is idempotent when called multiple times")
        void stopIsIdempotent() {
            var engine = createEngine("t", "g", "i");
            assertTrue(engine.stop().succeeded());
            assertTrue(engine.stop().succeeded());
            assertFalse(engine.isRunning());
        }

        @Test
        @DisplayName("close() is idempotent when called multiple times")
        void closeIsIdempotent() {
            var engine = createEngine("t", "g", "i");
            assertTrue(engine.close().succeeded());
            assertTrue(engine.close().succeeded());
            assertFalse(engine.isRunning());
        }

        @Test
        @DisplayName("close() then stop() still succeeds")
        void closeThenStop_succeeds() {
            var engine = createEngine("t", "g", "i");
            assertTrue(engine.close().succeeded());
            assertTrue(engine.stop().succeeded(), "stop() after close() should be a no-op");
        }
    }

    // ========================================================================
    // start() guard paths
    // ========================================================================

    @Nested
    @DisplayName("Start Guards")
    class StartGuards {

        @Test
        @DisplayName("start() rejects null handler")
        void startRejectsNullHandler() {
            var engine = createEngine("t", "g", "i");
            assertThrows(NullPointerException.class, () -> engine.start(null));
        }

        @Test
        @DisplayName("start() after close() is rejected with IllegalStateException")
        void startAfterClose_rejected() {
            var engine = createEngine("t", "g", "i");
            assertTrue(engine.close().succeeded());

            Future<Void> result = engine.start(msg -> Future.succeededFuture());
            assertTrue(result.failed(), "start() after close() should fail");
            assertInstanceOf(IllegalStateException.class, result.cause());
            assertTrue(result.cause().getMessage().contains("closed"),
                    "Error should mention 'closed': " + result.cause().getMessage());
        }

        @Test
        @DisplayName("start() with no pool fails with IllegalStateException (no DB)")
        void startNoPool_failsGracefully() {
            var engine = createEngine("t", "g", "i");

            Future<Void> result = engine.start(msg -> Future.succeededFuture());
            assertTrue(result.failed(), "start() should fail when no pool is configured");
            assertInstanceOf(IllegalStateException.class, result.cause(),
                    "Failure cause should be IllegalStateException for missing pool");
            assertTrue(result.cause().getMessage().contains("No reactive pool"),
                    "Error should mention missing pool: " + result.cause().getMessage());
        }

        @Test
        @DisplayName("running flag resets to false after start failure")
        void startNoPool_runningResetsFalse() {
            var engine = createEngine("t", "g", "i");

            Future<Void> result = engine.start(msg -> Future.succeededFuture());
            assertTrue(result.failed(), "start() should fail when no pool is configured");
            assertFalse(engine.isRunning(),
                    "running should reset to false after start failure so callers can retry safely");
        }

        @Test
        @DisplayName("second start() after failure retries startup instead of reporting already running")
        void doubleStart_retriesAfterFailure() {
            var engine = createEngine("t", "g", "i");

            // First start fails and should reset running=false
            Future<Void> first = engine.start(msg -> Future.succeededFuture());
            assertTrue(first.failed(), "first start should fail without a pool");
            assertFalse(engine.isRunning(), "running should be reset after failed start");

            // Second start should retry the real startup path, not fail as "already running"
            Future<Void> second = engine.start(msg -> Future.succeededFuture());
            assertTrue(second.failed(), "second start should still fail without a pool");
            assertInstanceOf(IllegalStateException.class, second.cause());
            assertTrue(second.cause().getMessage().contains("No reactive pool"),
                    "Second start should retry startup and fail on missing pool, was: " + second.cause().getMessage());
            assertFalse(engine.isRunning(), "running should remain false after repeated start failure");
        }

        @Test
        @DisplayName("close() succeeds after failed start and keeps engine stopped")
        void closeAfterFailedStart_succeeds() {
            var engine = createEngine("t", "g", "i");

            Future<Void> startResult = engine.start(msg -> Future.succeededFuture());
            assertTrue(startResult.failed(), "start should fail without a pool");
            assertFalse(engine.isRunning(), "running should already be false after failed start");

            Future<Void> closeResult = engine.close();
            assertTrue(closeResult.succeeded(), "close() should succeed after failed start");
            assertFalse(engine.isRunning(), "running should be false after close()");
        }

        @Test
        @DisplayName("stop() after failed start is a no-op")
        void stopAfterFailedStart_isNoOp() {
            var engine = createEngine("t", "g", "i");

            Future<Void> startResult = engine.start(msg -> Future.succeededFuture());
            assertTrue(startResult.failed(), "start should fail without a pool");
            assertFalse(engine.isRunning(), "running should not remain set after failed start");

            Future<Void> stopResult = engine.stop();
            assertTrue(stopResult.succeeded(), "stop() should be a no-op after failed start cleanup");
            assertFalse(engine.isRunning(), "running should be false after stop()");

            Future<Void> stopAgain = engine.stop();
            assertTrue(stopAgain.succeeded(), "second stop should be no-op");
        }
    }

    // ========================================================================
    // Constructor validation
    // ========================================================================

    @Nested
    @DisplayName("Constructor Validation")
    class ConstructorValidation {

        @Test
        @DisplayName("null vertx rejected")
        void nullVertx() {
            assertThrows(NullPointerException.class, () ->
                    new PartitionedConsumerEngine<>(null, connectionManager, "svc", "t", "g", "i", String.class, null));
        }

        @Test
        @DisplayName("null connectionManager rejected")
        void nullConnectionManager() {
            assertThrows(NullPointerException.class, () ->
                    new PartitionedConsumerEngine<>(vertx, null, "svc", "t", "g", "i", String.class, null));
        }

        @Test
        @DisplayName("null serviceId rejected")
        void nullServiceId() {
            assertThrows(NullPointerException.class, () ->
                    new PartitionedConsumerEngine<>(vertx, connectionManager, null, "t", "g", "i", String.class, null));
        }

        @Test
        @DisplayName("null topic rejected")
        void nullTopic() {
            assertThrows(NullPointerException.class, () ->
                    new PartitionedConsumerEngine<>(vertx, connectionManager, "svc", null, "g", "i", String.class, null));
        }

        @Test
        @DisplayName("null groupName rejected")
        void nullGroupName() {
            assertThrows(NullPointerException.class, () ->
                    new PartitionedConsumerEngine<>(vertx, connectionManager, "svc", "t", null, "i", String.class, null));
        }

        @Test
        @DisplayName("null instanceId rejected")
        void nullInstanceId() {
            assertThrows(NullPointerException.class, () ->
                    new PartitionedConsumerEngine<>(vertx, connectionManager, "svc", "t", "g", null, String.class, null));
        }

        @Test
        @DisplayName("null payloadType rejected")
        void nullPayloadType() {
            assertThrows(NullPointerException.class, () ->
                    new PartitionedConsumerEngine<>(vertx, connectionManager, "svc", "t", "g", "i", null, null));
        }

        @Test
        @DisplayName("null objectMapper allowed (optional)")
        void nullObjectMapper_allowed() {
            assertDoesNotThrow(() ->
                    new PartitionedConsumerEngine<>(vertx, connectionManager, "svc", "t", "g", "i", String.class, null));
        }
    }

    // ========================================================================
    // parsePayload pure function, no DB
    // ========================================================================

    @Nested
    @DisplayName("parsePayload")
    class ParsePayload {

        @Test
        @DisplayName("null payload returns null")
        void nullPayload() {
            var engine = createEngine("t", "g", "i");
            assertNull(engine.parsePayload(null));
        }

        @Test
        @DisplayName("String type extracts 'value' field")
        void stringType_extractsDataField() {
            var engine = createEngine("t", "g", "i");
            JsonObject payload = new JsonObject().put("value", "hello world");
            assertEquals("hello world", engine.parsePayload(payload));
        }

        @Test
        @DisplayName("String type falls back to full JSON when no 'data' field")
        void stringType_fallsBackToFullJson() {
            var engine = createEngine("t", "g", "i");
            JsonObject payload = new JsonObject().put("key", "value");
            String result = engine.parsePayload(payload);
            assertNotNull(result);
            assertTrue(result.contains("key"), "Should contain the JSON content: " + result);
        }

        @Test
        @DisplayName("JsonObject type returns the payload directly")
        void jsonObjectType() {
            var engine = new PartitionedConsumerEngine<>(
                    vertx, connectionManager, "test-svc",
                    "t", "g", "i",
                    JsonObject.class, new ObjectMapper()
            );
            JsonObject payload = new JsonObject().put("nested", true);
            JsonObject result = engine.parsePayload(payload);
            assertSame(payload, result);
        }

        @Test
        @DisplayName("custom type deserialized via ObjectMapper")
        void customType_viaObjectMapper() {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());

            var engine = new PartitionedConsumerEngine<>(
                    vertx, connectionManager, "test-svc",
                    "t", "g", "i",
                    Map.class, mapper
            );
            JsonObject payload = new JsonObject().put("key", "value");
            @SuppressWarnings("unchecked")
            Map<String, Object> result = engine.parsePayload(payload);
            assertEquals("value", result.get("key"));
        }

        @Test
        @DisplayName("custom type without ObjectMapper throws RuntimeException")
        void customType_noObjectMapper_throws() {
            var engine = new PartitionedConsumerEngine<>(
                    vertx, connectionManager, "test-svc",
                    "t", "g", "i",
                    Map.class, null  // no ObjectMapper
            );
            JsonObject payload = new JsonObject().put("key", "value");
            RuntimeException ex = assertThrows(RuntimeException.class, () -> engine.parsePayload(payload));
            assertTrue(ex.getMessage().contains("Cannot deserialize"), ex.getMessage());
        }

        @Test
        @DisplayName("invalid JSON for custom type throws RuntimeException")
        void invalidJson_forCustomType_throws() {
            ObjectMapper mapper = new ObjectMapper();
            var engine = new PartitionedConsumerEngine<>(
                    vertx, connectionManager, "test-svc",
                    "t", "g", "i",
                    Integer.class, mapper
            );
            JsonObject payload = new JsonObject().put("not", "an integer");
            RuntimeException ex = assertThrows(RuntimeException.class, () -> engine.parsePayload(payload));
            assertTrue(ex.getMessage().contains("Failed to deserialize"), ex.getMessage());
        }
    }

    // ========================================================================
    // parseHeaders pure function, no DB
    // ========================================================================

    @Nested
    @DisplayName("parseHeaders")
    class ParseHeaders {

        @Test
        @DisplayName("null headers returns empty map")
        void nullHeaders() {
            var engine = createEngine("t", "g", "i");
            Map<String, String> result = engine.parseHeaders(null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("empty headers returns empty map")
        void emptyHeaders() {
            var engine = createEngine("t", "g", "i");
            Map<String, String> result = engine.parseHeaders(new JsonObject());
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("extracts all header fields as strings")
        void extractsHeaderFields() {
            var engine = createEngine("t", "g", "i");
            JsonObject headers = new JsonObject()
                    .put("traceparent", "00-abc-def-01")
                    .put("content-type", "application/json")
                    .put("custom", "value");
            Map<String, String> result = engine.parseHeaders(headers);
            assertEquals(3, result.size());
            assertEquals("00-abc-def-01", result.get("traceparent"));
            assertEquals("application/json", result.get("content-type"));
            assertEquals("value", result.get("custom"));
        }
    }

    // ========================================================================
    // isOffsetWatermarkTopic static method, no pool → failed future
    // ========================================================================

    @Nested
    @DisplayName("isOffsetWatermarkTopic")
    class IsOffsetWatermarkTopic {

        @Test
        @DisplayName("no pool → failed future with IllegalStateException")
        void noPool_failedFuture() {
            Future<Boolean> result = PartitionedConsumerEngine.isOffsetWatermarkTopic(
                    connectionManager, "test-svc", "any-topic");
            assertTrue(result.failed());
            assertInstanceOf(IllegalStateException.class, result.cause());
            assertTrue(result.cause().getMessage().contains("No reactive pool"),
                    "Should mention missing pool: " + result.cause().getMessage());
        }
    }

    // ========================================================================
    // dispatchMessage message construction and handler dispatch
    // ========================================================================

    @Nested
    @DisplayName("dispatchMessage")
    class DispatchMessage {

        @Test
        @DisplayName("dispatches OutboxMessage to handler as Message<String>")
        void dispatchesMessageToHandler() {
            var engine = createEngine("test-topic", "test-group", "inst-1");

            AtomicReference<Message<String>> received = new AtomicReference<>();
            installMessageHandler(engine, msg -> {
                received.set(msg);
                return Future.succeededFuture();
            });

            OutboxMessage outboxMsg = OutboxMessage.builder()
                    .id(42L)
                    .topic("test-topic")
                    .payload(new JsonObject().put("value", "hello world"))
                    .headers(new JsonObject().put("traceparent", "00-abc-def-01"))
                    .correlationId("corr-1")
                    .messageGroup("grp-1")
                    .createdAt(Instant.now())
                    .build();

            Future<Void> result = engine.dispatchMessage(outboxMsg);
            assertTrue(result.succeeded(), "dispatchMessage should succeed");
            assertNotNull(received.get(), "handler should receive message");
            assertEquals("42", received.get().getId());
            assertEquals("hello world", received.get().getPayload());
            assertEquals("00-abc-def-01", received.get().getHeaders().get("traceparent"));

            // Verify topic mapping via SimpleMessage
            assertInstanceOf(SimpleMessage.class, received.get());
            assertEquals("test-topic", ((SimpleMessage<String>) received.get()).getTopic());
        }

        @Test
        @DisplayName("handler failure propagated as failed future")
        void handlerFailure_returnsFailedFuture() {
            var engine = createEngine("test-topic", "test-group", "inst-1");
            installMessageHandler(engine, msg -> Future.failedFuture(new RuntimeException("handler boom")));

            OutboxMessage outboxMsg = OutboxMessage.builder()
                    .id(1L)
                    .topic("test-topic")
                    .payload(new JsonObject().put("value", "test"))
                    .build();

            Future<Void> result = engine.dispatchMessage(outboxMsg);
            assertTrue(result.failed(), "dispatchMessage should propagate handler failure");
            assertTrue(result.cause().getMessage().contains("handler boom"));
        }

        @Test
        @DisplayName("parse exception returns failed future")
        void parseException_returnsFailedFuture() {
            // Engine with Integer payloadType but no valid integer payload
            var engine = new PartitionedConsumerEngine<>(
                    vertx, connectionManager, "test-svc",
                    "t", "g", "i",
                    Integer.class, new ObjectMapper()
            );
                installMessageHandler(engine, msg -> Future.succeededFuture());

            OutboxMessage outboxMsg = OutboxMessage.builder()
                    .id(1L)
                    .topic("t")
                    .payload(new JsonObject().put("not", "an integer"))
                    .build();

            Future<Void> result = engine.dispatchMessage(outboxMsg);
            assertTrue(result.failed(), "dispatchMessage should fail on parse error");
        }

        @Test
        @DisplayName("null payload dispatched as null")
        void nullPayload_dispatchedAsNull() {
            var engine = createEngine("test-topic", "test-group", "inst-1");
            AtomicReference<Message<String>> received = new AtomicReference<>();
            installMessageHandler(engine, msg -> {
                received.set(msg);
                return Future.succeededFuture();
            });

            OutboxMessage outboxMsg = OutboxMessage.builder()
                    .id(1L)
                    .topic("test-topic")
                    .payload(null) // null payload
                    .build();

            // SimpleMessage requires non-null payload, so this should fail
            Future<Void> result = engine.dispatchMessage(outboxMsg);
            assertTrue(result.failed(), "dispatchMessage should fail when payload is null (SimpleMessage rejects null)");
        }

        @Test
        @DisplayName("message with all fields populated")
        void fullMessage_allFieldsMapped() {
            var engine = createEngine("orders", "order-group", "inst-1");
            AtomicReference<Message<String>> received = new AtomicReference<>();
            installMessageHandler(engine, msg -> {
                received.set(msg);
                return Future.succeededFuture();
            });

            Instant created = Instant.parse("2026-04-13T10:00:00Z");
            OutboxMessage outboxMsg = OutboxMessage.builder()
                    .id(99L)
                    .topic("orders")
                    .payload(new JsonObject().put("value", "order-data"))
                    .headers(new JsonObject()
                            .put("traceparent", "00-trace-span-01")
                            .put("content-type", "application/json"))
                    .correlationId("corr-99")
                    .messageGroup("order-group")
                    .createdAt(created)
                    .build();

            Future<Void> result = engine.dispatchMessage(outboxMsg);
            assertTrue(result.succeeded());

            Message<String> msg = received.get();
            assertNotNull(msg);
            assertEquals("99", msg.getId());
            assertEquals("order-data", msg.getPayload());
            assertEquals(created, msg.getCreatedAt());
            assertEquals(2, msg.getHeaders().size());
            assertEquals("00-trace-span-01", msg.getHeaders().get("traceparent"));

            // SimpleMessage exposes additional fields
            assertInstanceOf(SimpleMessage.class, msg);
            SimpleMessage<String> sm = (SimpleMessage<String>) msg;
            assertEquals("orders", sm.getTopic());
            assertEquals("corr-99", sm.getCorrelationId());
            assertEquals("order-group", sm.getMessageGroup());
        }
    }
}
