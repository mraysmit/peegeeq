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
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import dev.mars.peegeeq.test.vertx.TestCleanup;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgException;
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
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
public class PgNativeQueueContainersTest {

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private Vertx vertx;
    private PgNativeQueue<JsonObject> queue;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp(Vertx vertx) {
        // The extension owns this Vert.x and closes it after the test. A test that closes its
        // own Vert.x from a callback on that Vert.x never sees the close complete.
        this.vertx = vertx;
        objectMapper = new ObjectMapper();

        // Create pool options
        PoolOptions poolOptions = new PoolOptions()
                .setMaxSize(5);

        // Create queue
        queue = new PgNativeQueue<>(vertx, connectOptions(postgres.getFirstMappedPort()), poolOptions,
                objectMapper, "test_channel", JsonObject.class);
    }

    private static PgConnectOptions connectOptions(int port) {
        return new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(port)
                .setDatabase(postgres.getDatabaseName())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword());
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
        JsonObject expectedPayload = new JsonObject().put("text", messageText);

        // A strict checkpoint: a second delivery fails the test.
        Checkpoint messageReceived = testContext.checkpoint();

        // Act: listen() completes once LISTEN has run; a NOTIFY sent before that is lost.
        queue.listen()
            .compose(stream -> {
                stream.handler(message -> {
                    testContext.verify(() -> {
                        assertNotNull(message, "Received message should not be null");
                        assertEquals(messageText, message.getString("text"));
                    });
                    messageReceived.flag();
                });
                stream.exceptionHandler(testContext::failNow);
                return queue.send(expectedPayload);
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
    void testSendAndReceiveMultipleMessages(VertxTestContext testContext) {
        // Arrange
        int messageCount = 5;
        // A strict checkpoint: exactly five deliveries.
        Checkpoint messagesReceived = testContext.checkpoint(messageCount);

        // Act: send once LISTEN has run.
        queue.listen()
            .compose(stream -> {
                stream.handler(message -> messagesReceived.flag());
                stream.exceptionHandler(testContext::failNow);
                Future<Void> sends = Future.succeededFuture();
                for (int i = 0; i < messageCount; i++) {
                    JsonObject payload = new JsonObject()
                            .put("text", "Test message " + i)
                            .put("index", i);
                    sends = sends.compose(v -> queue.send(payload));
                }
                return sends;
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
    void testReceiveFirstMessage(VertxTestContext testContext) {
        // Arrange
        String messageText = "Test message with first handler";
        JsonObject expectedPayload = new JsonObject().put("text", messageText);

        Checkpoint firstMessageReceived = testContext.checkpoint();
        AtomicReference<JsonObject> receivedMessage = new AtomicReference<>();

        // Act: send once LISTEN has run.
        queue.listen()
            .compose(stream -> {
                stream.handler(message -> {
                    // Only handle the first message
                    if (receivedMessage.compareAndSet(null, message)) {
                        testContext.verify(() -> assertEquals(messageText, message.getString("text")));
                        firstMessageReceived.flag();
                        // Pause the stream after receiving the first message
                        stream.pause();
                    }
                });
                stream.exceptionHandler(testContext::failNow);
                return queue.send(expectedPayload);
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.pgqueue.PgNativeQueue",
            message = "Failed to LISTEN on channel invalid channel: ",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.pgqueue.PgNotificationStream",
            message = "Handling error: ",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = PgException.class)
    void listenFailsWhenTheListenStatementIsRejected(VertxTestContext testContext) {
        // A channel name with a space is not an identifier, so the server rejects the LISTEN.
        PgNativeQueue<JsonObject> invalidChannel = new PgNativeQueue<>(vertx,
                connectOptions(postgres.getFirstMappedPort()), new PoolOptions().setMaxSize(1),
                objectMapper, "invalid channel", JsonObject.class);

        invalidChannel.listen()
            .transform(TestCleanup.cleanup(invalidChannel::close))
            .onSuccess(stream -> testContext.failNow(new AssertionError("LISTEN of an invalid channel must fail")))
            .onFailure(error -> testContext.verify(() -> {
                assertInstanceOf(PgException.class, error, "the server's rejection must reach the caller");
                testContext.completeNow();
            }));
    }

    @Test
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.pgqueue.PgNativeQueue",
            message = "Failed to establish LISTEN connection for channel unreachable_channel: ",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.pgqueue.PgNotificationStream",
            message = "Handling error: ",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = java.net.ConnectException.class)
    void listenFailsWhenTheServerIsUnreachable(VertxTestContext testContext) {
        // Port 1 on the container host has no listener, so the connection is refused.
        PgNativeQueue<JsonObject> unreachable = new PgNativeQueue<>(vertx, connectOptions(1),
                new PoolOptions().setMaxSize(1), objectMapper, "unreachable_channel", JsonObject.class);

        unreachable.listen()
            .transform(TestCleanup.cleanup(unreachable::close))
            .onSuccess(stream -> testContext.failNow(new AssertionError("listen() must fail without a server")))
            .onFailure(error -> testContext.verify(() -> {
                assertFalse(error instanceof IllegalStateException,
                        "the connection failure must reach the caller, not a closed-queue failure: " + error);
                testContext.completeNow();
            }));
    }

    @Test
    void listenFailsWhenTheQueueWasClosedBeforeTheConnectionArrived(VertxTestContext testContext) {
        PgNativeQueue<JsonObject> closedQueue = new PgNativeQueue<>(vertx,
                connectOptions(postgres.getFirstMappedPort()), new PoolOptions().setMaxSize(1),
                objectMapper, "closed_channel", JsonObject.class);

        closedQueue.close()
            .compose(closed -> closedQueue.listen())
            .onSuccess(stream -> testContext.failNow(new AssertionError("listen() after close() must fail")))
            .onFailure(error -> testContext.verify(() -> {
                assertInstanceOf(IllegalStateException.class, error);
                assertEquals("PgNativeQueue for channel closed_channel was closed before LISTEN was established",
                        error.getMessage());
                testContext.completeNow();
            }));
    }
}
