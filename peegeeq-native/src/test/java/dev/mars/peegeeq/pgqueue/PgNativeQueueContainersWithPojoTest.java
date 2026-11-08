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
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import io.vertx.core.streams.ReadStream;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests for the PgNativeQueue class using TestContainers and a POJO message class.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * Comprehensive integration tests for the PgNativeQueue class using TestContainers and a POJO message class.
 * This class focuses on testing the send and receive functionality with real PostgreSQL notifications.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
public class PgNativeQueueContainersWithPojoTest {

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private Vertx vertx;
    private PgNativeQueue<TestMessage> queue;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp(Vertx vertx) {
        // The extension owns this Vert.x and closes it after the test. A test that closes its
        // own Vert.x from a callback on that Vert.x never sees the close complete.
        this.vertx = vertx;
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
                objectMapper, "test_channel", TestMessage.class);
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        queue.close()
            .onComplete(ctx.succeedingThenComplete());
    }

    @Test
    @Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
    void testSendAndReceiveMessage(VertxTestContext testContext) {
        // Arrange
        String messageText = "Test message";
        TestMessage expectedMessage = new TestMessage(messageText);

        // A strict checkpoint: a second delivery fails the test.
        Checkpoint messageReceived = testContext.checkpoint();

        // Act - Get the ReadStream from the queue
        ReadStream<TestMessage> stream = queue.receive();

        // Set up handlers for the stream
        stream.handler(message -> {
            testContext.verify(() -> {
                assertNotNull(message, "Received message should not be null");
                assertEquals(messageText, message.getText());
            });
            messageReceived.flag();
        });

        stream.exceptionHandler(testContext::failNow);

        // Wait for subscription setup, then send
        vertx.setTimer(1000, id -> {
            queue.send(expectedMessage)
                .onFailure(testContext::failNow);
        });
    }

    @Test
    @Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
    void testSendAndReceiveMultipleMessages(VertxTestContext testContext) {
        // Arrange
        int messageCount = 5;
        // A strict checkpoint: exactly five deliveries.
        Checkpoint messagesReceived = testContext.checkpoint(messageCount);

        // Act - Get the ReadStream from the queue
        ReadStream<TestMessage> stream = queue.receive();

        // Set up handlers for the stream
        stream.handler(message -> messagesReceived.flag());

        stream.exceptionHandler(testContext::failNow);

        // Wait for subscription setup, then send
        vertx.setTimer(1000, id -> {
            for (int i = 0; i < messageCount; i++) {
                TestMessage message = new TestMessage("Test message " + i, i);
                queue.send(message)
                    .onFailure(testContext::failNow);
            }
        });
    }

    @Test
    @Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
    void testReceiveFirstMessage(VertxTestContext testContext) {
        // Arrange
        String messageText = "Test message with first handler";
        TestMessage expectedMessage = new TestMessage(messageText);

        Checkpoint firstMessageReceived = testContext.checkpoint();
        AtomicReference<TestMessage> receivedMessage = new AtomicReference<>();

        // Act - Get the ReadStream from the queue
        ReadStream<TestMessage> stream = queue.receive();

        // Set up handlers for the stream
        stream.handler(message -> {
            // Only handle the first message
            if (receivedMessage.compareAndSet(null, message)) {
                testContext.verify(() -> assertEquals(messageText, message.getText()));
                firstMessageReceived.flag();
                // Pause the stream after receiving the first message
                stream.pause();
            }
        });

        stream.exceptionHandler(testContext::failNow);

        // Wait for subscription setup, then send
        vertx.setTimer(1000, id -> {
            queue.send(expectedMessage)
                .onFailure(testContext::failNow);
        });
    }
}
