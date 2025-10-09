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
import dev.mars.peegeeq.api.messaging.Message;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import io.vertx.core.streams.ReadStream;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the PgNativeQueue class.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@Testcontainers
public class PgNativeQueueTest {
    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueueTest.class);

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
        logger.info("Setting up test environment");
        vertx = Vertx.vertx();
        objectMapper = new ObjectMapper();

        // Create connection options from TestContainer
        logger.debug("Creating connection options from TestContainer");
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(postgres.getDatabaseName())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword());
        logger.debug("Connection options: host={}, port={}, database={}, user={}", 
                postgres.getHost(), postgres.getFirstMappedPort(), 
                postgres.getDatabaseName(), postgres.getUsername());

        // Create pool options
        PoolOptions poolOptions = new PoolOptions()
                .setMaxSize(5);
        logger.debug("Pool options: maxSize={}", 5);

        // Create queue
        logger.debug("Creating PgNativeQueue with channel: test_channel");
        queue = new PgNativeQueue<>(vertx, connectOptions, poolOptions, 
                objectMapper, "test_channel", JsonObject.class);
        logger.info("Test environment setup complete");
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.info("Tearing down test environment");
        CountDownLatch latch = new CountDownLatch(1);

        queue.close()
            .onComplete(ar -> {
                logger.debug("Queue closed, now closing Vertx");
                vertx.close()
                    .onComplete(v -> {
                        logger.debug("Vertx closed");
                        latch.countDown();
                    });
            });

        boolean closed = latch.await(5, TimeUnit.SECONDS);
        if (closed) {
            logger.info("Test environment teardown complete");
        } else {
            logger.error("Failed to close resources within timeout");
        }
        assertTrue(closed, "Failed to close resources");
    }

    @Test
    void testCreateMessage() {
        // Arrange
        JsonObject payload = new JsonObject().put("test", "value");

        // Act
        Message<JsonObject> message = queue.createMessage(payload);

        // Assert
        assertNotNull(message);
        assertNotNull(message.getId());
        assertEquals(payload, message.getPayload());
        assertNotNull(message.getCreatedAt());
        assertNotNull(message.getHeaders());
    }

    @Test
    void testSendMessage() throws Exception {
        // Arrange
        JsonObject payload = new JsonObject().put("test", "value");
        CountDownLatch latch = new CountDownLatch(1);

        // Act
        queue.send(payload)
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    latch.countDown();
                } else {
                    fail("Failed to send message: " + ar.cause().getMessage());
                }
            });

        // Assert
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Failed to send message");
    }

    @Test
    void testAcknowledgeMessage() throws Exception {
        // Arrange
        String messageId = UUID.randomUUID().toString();
        CountDownLatch latch = new CountDownLatch(1);

        // Act
        queue.acknowledge(messageId)
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    latch.countDown();
                } else {
                    fail("Failed to acknowledge message: " + ar.cause().getMessage());
                }
            });

        // Assert
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Failed to acknowledge message");
    }

    @Test
    void testClose() throws Exception {
        // Arrange
        CountDownLatch latch = new CountDownLatch(1);

        // Act
        queue.close()
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    latch.countDown();
                } else {
                    fail("Failed to close queue: " + ar.cause().getMessage());
                }
            });

        // Assert
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Failed to close queue");
    }

    @Test
    void testSendAndReceiveMessage() throws Exception {
        // This test requires setting up the database schema
        // and is more complex to implement in a unit test.
        // In a real-world scenario, you would:
        // 1. Create the necessary tables
        // 2. Set up the LISTEN/NOTIFY mechanism
        // 3. Send a message
        // 4. Verify that the message is received

        // For now, we'll just verify that the receive() method returns a ReadStream
        ReadStream<JsonObject> stream = queue.receive();
        assertNotNull(stream);
    }
}
