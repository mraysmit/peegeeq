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
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
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
@ExtendWith(VertxExtension.class)
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
                CountDownLatch queueCloseLatch = new CountDownLatch(1);
                queue.close().onComplete(ar -> queueCloseLatch.countDown());
                queueCloseLatch.await(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.warn("Queue close failed in teardown, continuing", e);
            }
        }
        
        if (vertx != null) {
            try {
                CountDownLatch vertxCloseLatch = new CountDownLatch(1);
                vertx.close().onComplete(ar -> vertxCloseLatch.countDown());
                vertxCloseLatch.await(2, TimeUnit.SECONDS);
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
    void testSend_CompletesSuccessfully(VertxTestContext testContext) {
        queue.send("test message")
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testSend_MultipleMessages(VertxTestContext testContext) {
        Checkpoint cp = testContext.checkpoint(3);
        queue.send("message 1").onSuccess(v -> cp.flag()).onFailure(testContext::failNow);
        queue.send("message 2").onSuccess(v -> cp.flag()).onFailure(testContext::failNow);
        queue.send("message 3").onSuccess(v -> cp.flag()).onFailure(testContext::failNow);
    }

    @Test
    void testSend_NullMessage(VertxTestContext testContext) {
        queue.send(null)
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
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
    void testAcknowledge_CompletesSuccessfully(VertxTestContext testContext) {
        queue.acknowledge("msg-123")
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testAcknowledge_MultipleMessageIds(VertxTestContext testContext) {
        Checkpoint cp = testContext.checkpoint(3);
        queue.acknowledge("msg-1").onSuccess(v -> cp.flag()).onFailure(testContext::failNow);
        queue.acknowledge("msg-2").onSuccess(v -> cp.flag()).onFailure(testContext::failNow);
        queue.acknowledge("msg-3").onSuccess(v -> cp.flag()).onFailure(testContext::failNow);
    }

    @Test
    void testAcknowledge_NullMessageId(VertxTestContext testContext) {
        queue.acknowledge(null)
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testAcknowledge_EmptyMessageId(VertxTestContext testContext) {
        queue.acknowledge("")
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    // ========== Close Method Tests ==========

    @Test
    void testClose_ReturnsNonNullFuture() {
        Future<Void> future = queue.close();
        assertNotNull(future);
    }

    @Test
    void testClose_CompletesSuccessfully(VertxTestContext testContext) {
        queue.close()
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testClose_MultipleInvocations(VertxTestContext testContext) {
        queue.close()
            .onSuccess(v -> {
                // Second close should also complete (pool already closed)
                Future<Void> future2 = queue.close();
                assertNotNull(future2);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
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
    void testSendThenAcknowledge(VertxTestContext testContext) {
        queue.send("test message")
            .compose(v -> queue.acknowledge("msg-123"))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testReceiveThenClose(VertxTestContext testContext) {
        ReadStream<String> stream = queue.receive();
        assertNotNull(stream);
        
        queue.close()
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testCreateMessageThenSend(VertxTestContext testContext) {
        Message<String> message = queue.createMessage("test payload");
        assertNotNull(message);
        
        queue.send(message.getPayload())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
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
