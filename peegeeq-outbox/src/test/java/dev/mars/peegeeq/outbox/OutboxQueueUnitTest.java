package dev.mars.peegeeq.outbox;

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
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.streams.ReadStream;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fast unit tests for OutboxQueue without database dependencies.
 * Tests basic construction, lifecycle, and method signatures.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-02
 * @version 1.0
 */
@Tag(TestCategories.CORE)
class OutboxQueueUnitTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxQueueUnitTest.class);

    private Vertx vertx;
    private OutboxQueue<String> queue;
    private PgConnectOptions connectOptions;
    private PoolOptions poolOptions;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        
        connectOptions = new PgConnectOptions()
            .setHost("localhost")
            .setPort(5432)
            .setDatabase("testdb")
            .setUser("testuser")
            .setPassword("testpass");
        
        poolOptions = new PoolOptions().setMaxSize(5);
        objectMapper = new ObjectMapper();
        
        queue = new OutboxQueue<>(vertx, connectOptions, poolOptions, 
                objectMapper, "test_table", String.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (queue != null) {
            try {
                queue.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.warn("Queue close failed in teardown, continuing", e);
            }
        }
        
        if (vertx != null) {
            try {
                vertx.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.warn("Vertx close failed in teardown, continuing", e);
            }
        }
    }

    // ========== Constructor Tests ==========

    @Test
    void testConstructor_ValidParameters() {
        assertNotNull(queue);
    }

    @Test
    void testConstructor_DifferentMessageTypes() {
        // String type (already created in setUp)
        assertNotNull(queue);
        
        // Integer type
        OutboxQueue<Integer> intQueue = new OutboxQueue<>(
            vertx, connectOptions, poolOptions, objectMapper, "int_table", Integer.class);
        assertNotNull(intQueue);
        intQueue.close();
        
        // Custom POJO type
        OutboxQueue<TestPayload> pojoQueue = new OutboxQueue<>(
            vertx, connectOptions, poolOptions, objectMapper, "pojo_table", TestPayload.class);
        assertNotNull(pojoQueue);
        pojoQueue.close();
    }

    @Test
    void testConstructor_DifferentTableNames() {
        OutboxQueue<String> queue1 = new OutboxQueue<>(
            vertx, connectOptions, poolOptions, objectMapper, "table_1", String.class);
        assertNotNull(queue1);
        queue1.close();
        
        OutboxQueue<String> queue2 = new OutboxQueue<>(
            vertx, connectOptions, poolOptions, objectMapper, "table_2", String.class);
        assertNotNull(queue2);
        queue2.close();
    }

    // ========== Send Method Tests ==========

    @Test
    void testSend_ReturnsNonNullFuture() {
        Future<Void> future = queue.send("test message");
        assertNotNull(future);
    }

    @Test
    void testSend_CompletesSuccessfully() throws Exception {
        Future<Void> future = queue.send("test message");
        assertNotNull(future);
        
        // Wait for completion
        future.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(future.succeeded());
    }

    @Test
    void testSend_MultipleMessages() throws Exception {
        Future<Void> future1 = queue.send("message 1");
        Future<Void> future2 = queue.send("message 2");
        Future<Void> future3 = queue.send("message 3");
        
        assertNotNull(future1);
        assertNotNull(future2);
        assertNotNull(future3);
        
        // All should complete successfully
        future1.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        future2.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        future3.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        
        assertTrue(future1.succeeded());
        assertTrue(future2.succeeded());
        assertTrue(future3.succeeded());
    }

    @Test
    void testSend_NullMessage() throws Exception {
        Future<Void> future = queue.send(null);
        assertNotNull(future);
        
        // Should still complete (implementation doesn't validate)
        future.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(future.succeeded());
    }

    // ========== Receive Method Tests ==========

    @Test
    void testReceive_ReturnsNonNullStream() {
        ReadStream<String> stream = queue.receive();
        assertNotNull(stream);
    }

    @Test
    void testReceive_ReturnsEmptyReadStream() {
        ReadStream<String> stream = queue.receive();
        assertNotNull(stream);
        assertInstanceOf(EmptyReadStream.class, stream);
    }

    @Test
    void testReceive_StreamCanBeConfigured() {
        ReadStream<String> stream = queue.receive();
        assertNotNull(stream);
        
        // Test fluent API
        ReadStream<String> configured = stream
            .handler(msg -> {})
            .exceptionHandler(err -> {})
            .pause()
            .resume();
        
        assertNotNull(configured);
    }

    // ========== Acknowledge Method Tests ==========

    @Test
    void testAcknowledge_ReturnsNonNullFuture() {
        Future<Void> future = queue.acknowledge("msg-123");
        assertNotNull(future);
    }

    @Test
    void testAcknowledge_CompletesSuccessfully() throws Exception {
        Future<Void> future = queue.acknowledge("msg-123");
        assertNotNull(future);
        
        future.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(future.succeeded());
    }

    @Test
    void testAcknowledge_MultipleMessageIds() throws Exception {
        Future<Void> future1 = queue.acknowledge("msg-1");
        Future<Void> future2 = queue.acknowledge("msg-2");
        Future<Void> future3 = queue.acknowledge("msg-3");
        
        assertNotNull(future1);
        assertNotNull(future2);
        assertNotNull(future3);
        
        future1.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        future2.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        future3.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        
        assertTrue(future1.succeeded());
        assertTrue(future2.succeeded());
        assertTrue(future3.succeeded());
    }

    @Test
    void testAcknowledge_NullMessageId() throws Exception {
        Future<Void> future = queue.acknowledge(null);
        assertNotNull(future);
        
        // Should still complete
        future.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(future.succeeded());
    }

    @Test
    void testAcknowledge_EmptyMessageId() throws Exception {
        Future<Void> future = queue.acknowledge("");
        assertNotNull(future);
        
        future.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(future.succeeded());
    }

    // ========== Close Method Tests ==========

    @Test
    void testClose_ReturnsNonNullFuture() {
        Future<Void> future = queue.close();
        assertNotNull(future);
    }

    @Test
    void testClose_CompletesSuccessfully() throws Exception {
        Future<Void> future = queue.close();
        assertNotNull(future);
        
        future.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(future.succeeded());
    }

    @Test
    void testClose_MultipleInvocations() throws Exception {
        Future<Void> future1 = queue.close();
        assertNotNull(future1);
        future1.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(future1.succeeded());
        
        // Second close should also complete (pool already closed)
        Future<Void> future2 = queue.close();
        assertNotNull(future2);
        // Note: Pool.close() might fail on second call, but that's pool behavior, not queue logic
    }

    // ========== CreateMessage Method Tests ==========

    @Test
    void testCreateMessage_ValidPayload() {
        Message<String> message = queue.createMessage("test payload");
        
        assertNotNull(message);
        assertNotNull(message.getId());
        assertEquals("test payload", message.getPayload());
    }

    @Test
    void testCreateMessage_NullPayload() {
        Message<String> message = queue.createMessage(null);
        
        assertNotNull(message);
        assertNotNull(message.getId());
        assertNull(message.getPayload());
    }

    @Test
    void testCreateMessage_GeneratesUniqueIds() {
        Message<String> message1 = queue.createMessage("payload 1");
        Message<String> message2 = queue.createMessage("payload 2");
        Message<String> message3 = queue.createMessage("payload 3");
        
        assertNotNull(message1.getId());
        assertNotNull(message2.getId());
        assertNotNull(message3.getId());
        
        // IDs should be unique
        assertNotEquals(message1.getId(), message2.getId());
        assertNotEquals(message1.getId(), message3.getId());
        assertNotEquals(message2.getId(), message3.getId());
    }

    @Test
    void testCreateMessage_ReturnsOutboxMessage() {
        Message<String> message = queue.createMessage("test");
        
        assertInstanceOf(OutboxMessage.class, message);
    }

    @Test
    void testCreateMessage_DifferentPayloadTypes() {
        OutboxQueue<Integer> intQueue = new OutboxQueue<>(
            vertx, connectOptions, poolOptions, objectMapper, "int_table", Integer.class);
        
        Message<Integer> intMessage = intQueue.createMessage(42);
        assertNotNull(intMessage);
        assertEquals(42, intMessage.getPayload());
        
        intQueue.close();
    }

    // ========== Integration Tests (Multiple Operations) ==========

    @Test
    void testSendThenAcknowledge() throws Exception {
        // Send a message
        Future<Void> sendFuture = queue.send("test message");
        sendFuture.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(sendFuture.succeeded());
        
        // Acknowledge it
        Future<Void> ackFuture = queue.acknowledge("msg-123");
        ackFuture.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(ackFuture.succeeded());
    }

    @Test
    void testReceiveThenClose() throws Exception {
        // Get stream
        ReadStream<String> stream = queue.receive();
        assertNotNull(stream);
        
        // Close queue
        Future<Void> closeFuture = queue.close();
        closeFuture.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(closeFuture.succeeded());
    }

    @Test
    void testCreateMessageThenSend() throws Exception {
        // Create message
        Message<String> message = queue.createMessage("test payload");
        assertNotNull(message);
        
        // Send the payload
        Future<Void> sendFuture = queue.send(message.getPayload());
        sendFuture.toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(sendFuture.succeeded());
    }

    // ========== Helper Classes ==========

    private static class TestPayload {
        private String data;
        
        public TestPayload() {}
        
        public TestPayload(String data) {
            this.data = data;
        }
        
        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
    }
}
