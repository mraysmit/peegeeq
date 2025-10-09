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
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import io.vertx.core.streams.ReadStream;


import java.util.concurrent.CountDownLatch;
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
@Testcontainers
public class PgNativeQueueTestContainers {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

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
        CountDownLatch latch = new CountDownLatch(1);

        queue.close()
            .onComplete(ar -> {
                vertx.close()
                    .onComplete(v -> latch.countDown());
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Failed to close resources");
    }

    @Test
    void testSendAndReceiveMessage() throws Exception {
        // Arrange
        String messageText = "Test message";
        JsonObject expectedPayload = new JsonObject().put("text", messageText);

        // Set up a CountDownLatch to wait for the message
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JsonObject> receivedMessage = new AtomicReference<>();

        // Act - Get the ReadStream from the queue
        ReadStream<JsonObject> stream = queue.receive();

        // Set up handlers for the stream
        stream.handler(message -> {
            receivedMessage.set(message);
            latch.countDown();
        });

        stream.exceptionHandler(error -> {
            System.err.println("Error receiving message: " + error.getMessage());
            latch.countDown();
        });

        // Wait a bit for the subscription to be set up
        Thread.sleep(1000);

        // Send a message
        CountDownLatch sendLatch = new CountDownLatch(1);
        queue.send(expectedPayload)
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    sendLatch.countDown();
                } else {
                    fail("Failed to send message: " + ar.cause().getMessage());
                }
            });

        // Wait for the send to complete
        assertTrue(sendLatch.await(5, TimeUnit.SECONDS), "Failed to send message");

        // Assert - Wait for the message to be received
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Message was not received within timeout");

        // Verify the received message
        JsonObject receivedPayload = receivedMessage.get();
        assertNotNull(receivedPayload, "Received message should not be null");
        assertEquals(messageText, receivedPayload.getString("text"));
    }

    @Test
    void testSendAndReceiveMultipleMessages() throws Exception {
        // Arrange
        int messageCount = 5;
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicReference<Integer> receivedCount = new AtomicReference<>(0);

        // Act - Get the ReadStream from the queue
        ReadStream<JsonObject> stream = queue.receive();

        // Set up handlers for the stream
        stream.handler(message -> {
            receivedCount.updateAndGet(count -> count + 1);
            latch.countDown();
        });

        stream.exceptionHandler(error -> {
            System.err.println("Error receiving message: " + error.getMessage());
        });

        // Wait a bit for the subscription to be set up
        Thread.sleep(1000);

        // Send multiple messages
        for (int i = 0; i < messageCount; i++) {
            JsonObject payload = new JsonObject()
                    .put("text", "Test message " + i)
                    .put("index", i);

            CountDownLatch sendLatch = new CountDownLatch(1);
            queue.send(payload)
                .onSuccess(v -> sendLatch.countDown())
                .onFailure(throwable -> fail("Failed to send message: " + throwable.getMessage()));

            // Wait for the send to complete
            assertTrue(sendLatch.await(5, TimeUnit.SECONDS), "Failed to send message");

            // Small delay between messages to ensure order
            Thread.sleep(100);
        }

        // Assert - Wait for all messages to be received
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Not all messages were received within timeout");
        assertEquals(messageCount, receivedCount.get(), "Should have received all messages");
    }

    @Test
    void testReceiveFirstMessage() throws Exception {
        // Arrange
        String messageText = "Test message with first handler";
        JsonObject expectedPayload = new JsonObject().put("text", messageText);

        // Set up a CountDownLatch to wait for the message
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JsonObject> receivedMessage = new AtomicReference<>();

        // Act - Get the ReadStream from the queue
        ReadStream<JsonObject> stream = queue.receive();

        // Set up handlers for the stream
        stream.handler(message -> {
            // Only handle the first message
            if (latch.getCount() > 0) {
                receivedMessage.set(message);
                latch.countDown();
                // Pause the stream after receiving the first message
                stream.pause();
            }
        });

        stream.exceptionHandler(error -> {
            System.err.println("Error receiving message: " + error.getMessage());
            latch.countDown();
        });

        // Wait a bit for the subscription to be set up
        Thread.sleep(1000);

        // Send a message
        CountDownLatch sendLatch = new CountDownLatch(1);
        queue.send(expectedPayload)
            .onSuccess(v -> sendLatch.countDown())
            .onFailure(throwable -> fail("Failed to send message: " + throwable.getMessage()));

        // Wait for the send to complete
        assertTrue(sendLatch.await(5, TimeUnit.SECONDS), "Failed to send message");

        // Assert - Wait for the message to be received
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Message was not received within timeout");

        // Verify the received message
        JsonObject receivedPayload = receivedMessage.get();
        assertNotNull(receivedPayload, "Received message should not be null");
        assertEquals(messageText, receivedPayload.getString("text"));
    }
}
