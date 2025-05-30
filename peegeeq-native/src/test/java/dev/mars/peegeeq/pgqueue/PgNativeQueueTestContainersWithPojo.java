package dev.mars.peegeeq.pgqueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import io.vertx.core.streams.ReadStream;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests for the PgNativeQueue class using TestContainers and a POJO message class.
 * This class focuses on testing the send and receive functionality with real PostgreSQL notifications.
 */
@Testcontainers
public class PgNativeQueueTestContainersWithPojo {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private Vertx vertx;
    private PgNativeQueue<TestMessage> queue;
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
                objectMapper, "test_channel", TestMessage.class);
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
        TestMessage expectedMessage = new TestMessage(messageText);

        // Set up a CountDownLatch to wait for the message
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TestMessage> receivedMessage = new AtomicReference<>();

        // Act - Get the ReadStream from the queue
        ReadStream<TestMessage> stream = queue.receive();

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
        queue.send(expectedMessage)
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
        TestMessage receivedTestMessage = receivedMessage.get();
        assertNotNull(receivedTestMessage, "Received message should not be null");
        assertEquals(messageText, receivedTestMessage.getText());
    }

    @Test
    void testSendAndReceiveMultipleMessages() throws Exception {
        // Arrange
        int messageCount = 5;
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicReference<Integer> receivedCount = new AtomicReference<>(0);

        // Act - Get the ReadStream from the queue
        ReadStream<TestMessage> stream = queue.receive();

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
            TestMessage message = new TestMessage("Test message " + i, i);

            CountDownLatch sendLatch = new CountDownLatch(1);
            queue.send(message)
                .onComplete(ar -> {
                    if (ar.succeeded()) {
                        sendLatch.countDown();
                    } else {
                        fail("Failed to send message: " + ar.cause().getMessage());
                    }
                });

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
        TestMessage expectedMessage = new TestMessage(messageText);

        // Set up a CountDownLatch to wait for the message
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TestMessage> receivedMessage = new AtomicReference<>();

        // Act - Get the ReadStream from the queue
        ReadStream<TestMessage> stream = queue.receive();

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
        queue.send(expectedMessage)
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
        TestMessage receivedTestMessage = receivedMessage.get();
        assertNotNull(receivedTestMessage, "Received message should not be null");
        assertEquals(messageText, receivedTestMessage.getText());
    }
}
