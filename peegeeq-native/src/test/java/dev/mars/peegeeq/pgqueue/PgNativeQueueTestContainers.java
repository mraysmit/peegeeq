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
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import io.vertx.core.streams.ReadStream;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests for the PgNativeQueue class using TestContainers.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * Comprehensive integration tests for the PgNativeQueue class using TestContainers.
 * This class focuses on testing the send and receive functionality with real PostgreSQL notifications.
 */
@ExtendWith(VertxExtension.class)
@Testcontainers
public class PgNativeQueueTestContainers {

    @Container
    private static final PostgreSQLContainer<?> postgres = createPostgresContainer();

    private static PostgreSQLContainer<?> createPostgresContainer() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("testdb");
        container.withUsername("testuser");
        container.withPassword("testpass");
        return container;
    }

    private Vertx vertx;
    private PgNativeQueue<JsonObject> queue;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        objectMapper = new ObjectMapper();

        // Create connection options from TestContainer
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(postgres.getDatabaseName())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword());

        // Create pool options
        PoolOptions poolOptions = new PoolOptions()
                .setMaxSize(5);

        // Create queue
        queue = new PgNativeQueue<>(vertx, connectOptions, poolOptions, 
                objectMapper, "test_channel", JsonObject.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        queue.close()
            .compose(v -> vertx.close())
            .toCompletionStage().toCompletableFuture()
            .orTimeout(5, TimeUnit.SECONDS)
            .join();
    }

    @Test
    void testSendAndReceiveMessage(VertxTestContext testContext) throws Exception {
        // Arrange
        String messageText = "Test message";
        JsonObject expectedPayload = new JsonObject().put("text", messageText);

        Checkpoint messageReceived = testContext.checkpoint();
        AtomicReference<JsonObject> receivedMessage = new AtomicReference<>();

        // Act - Get the ReadStream from the queue
        ReadStream<JsonObject> stream = queue.receive();

        // Set up handlers for the stream
        stream.handler(message -> {
            receivedMessage.set(message);
            messageReceived.flag();
        });

        stream.exceptionHandler(testContext::failNow);

        // Wait for subscription setup, then send
        vertx.setTimer(1000, id -> {
            queue.send(expectedPayload)
                .onFailure(testContext::failNow);
        });

        // Wait for the message to be received
        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Message was not received within timeout");

        // Verify the received message
        JsonObject receivedPayload = receivedMessage.get();
        assertNotNull(receivedPayload, "Received message should not be null");
        assertEquals(messageText, receivedPayload.getString("text"));
    }

    @Test
    void testSendAndReceiveMultipleMessages(VertxTestContext testContext) throws Exception {
        // Arrange
        int messageCount = 5;
        Checkpoint messagesReceived = testContext.checkpoint(messageCount);
        AtomicReference<Integer> receivedCount = new AtomicReference<>(0);

        // Act - Get the ReadStream from the queue
        ReadStream<JsonObject> stream = queue.receive();

        // Set up handlers for the stream
        stream.handler(message -> {
            receivedCount.updateAndGet(count -> count + 1);
            messagesReceived.flag();
        });

        stream.exceptionHandler(testContext::failNow);

        // Wait for subscription setup, then send
        vertx.setTimer(1000, id -> {
            for (int i = 0; i < messageCount; i++) {
                JsonObject payload = new JsonObject()
                        .put("text", "Test message " + i)
                        .put("index", i);
                queue.send(payload)
                    .onFailure(testContext::failNow);
            }
        });

        // Wait for all messages to be received
        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Not all messages were received within timeout");
        assertEquals(messageCount, receivedCount.get(), "Should have received all messages");
    }

    @Test
    void testReceiveFirstMessage(VertxTestContext testContext) throws Exception {
        // Arrange
        String messageText = "Test message with first handler";
        JsonObject expectedPayload = new JsonObject().put("text", messageText);

        Checkpoint firstMessageReceived = testContext.checkpoint();
        AtomicReference<JsonObject> receivedMessage = new AtomicReference<>();

        // Act - Get the ReadStream from the queue
        ReadStream<JsonObject> stream = queue.receive();

        // Set up handlers for the stream
        stream.handler(message -> {
            // Only handle the first message
            if (receivedMessage.compareAndSet(null, message)) {
                firstMessageReceived.flag();
                // Pause the stream after receiving the first message
                stream.pause();
            }
        });

        stream.exceptionHandler(testContext::failNow);

        // Wait for subscription setup, then send
        vertx.setTimer(1000, id -> {
            queue.send(expectedPayload)
                .onFailure(testContext::failNow);
        });

        // Wait for the message to be received
        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Message was not received within timeout");

        // Verify the received message
        JsonObject receivedPayload = receivedMessage.get();
        assertNotNull(receivedPayload, "Received message should not be null");
        assertEquals(messageText, receivedPayload.getString("text"));
    }
}
