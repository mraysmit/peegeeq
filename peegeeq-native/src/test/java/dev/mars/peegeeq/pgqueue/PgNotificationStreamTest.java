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
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the PgNotificationStream class.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@Tag(TestCategories.CORE)
public class PgNotificationStreamTest {

    private Vertx vertx;

    private ObjectMapper objectMapper;

    private PgNotificationStream<String> stream;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        objectMapper = new ObjectMapper();
        stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
    }

    @AfterEach
    void tearDown() {
        if (vertx != null) {
            vertx.close();
        }
    }

    @Test
    void testExceptionHandler() {
        // Arrange
        AtomicBoolean handlerCalled = new AtomicBoolean(false);
        Handler<Throwable> handler = throwable -> handlerCalled.set(true);

        // Act
        PgNotificationStream<String> result = (PgNotificationStream<String>) stream.exceptionHandler(handler);

        // Assert
        assertSame(stream, result);
        // Handler should not be called unless there's an exception
        assertFalse(handlerCalled.get(), "Exception handler should not be called without an exception");
    }

    @Test
    void testHandler() {
        // Arrange
        AtomicInteger callCount = new AtomicInteger(0);
        AtomicReference<String> receivedMessage = new AtomicReference<>();
        Handler<String> handler = message -> {
            callCount.incrementAndGet();
            receivedMessage.set(message);
        };

        // Act
        PgNotificationStream<String> result = (PgNotificationStream<String>) stream.handler(handler);

        // Assert
        assertSame(stream, result);
        // Handler should not be called until a notification is sent
        assertEquals(0, callCount.get(), "Handler should not be called until notification is sent");
    }

    @Test
    void testPause() {
        // Act
        PgNotificationStream<String> result = (PgNotificationStream<String>) stream.pause();

        // Assert
        assertSame(stream, result);

        // Verify that the stream is paused by sending a notification and checking that the handler is not called
        AtomicInteger callCount = new AtomicInteger(0);
        Handler<String> handler = message -> callCount.incrementAndGet();
        stream.handler(handler);

        stream.handleNotification("test");

        // Handler should not be called when stream is paused
        assertEquals(0, callCount.get(), "Handler should not be called when stream is paused");
    }

    @Test
    void testResume() throws InterruptedException {
        // Arrange
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger callCount = new AtomicInteger(0);
        AtomicReference<String> receivedMessage = new AtomicReference<>();
        Handler<String> handler = message -> {
            callCount.incrementAndGet();
            receivedMessage.set(message);
            latch.countDown();
        };
        stream.handler(handler);
        stream.pause();

        // Act
        PgNotificationStream<String> result = (PgNotificationStream<String>) stream.resume();

        // Assert
        assertSame(stream, result);

        // Verify that the stream is resumed by sending a notification and checking that the handler is called
        stream.handleNotification("test");

        // Wait for async execution and verify handler was called
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Handler should be called within 1 second when stream is resumed");
        assertEquals(1, callCount.get(), "Handler should be called when stream is resumed");
        assertEquals("test", receivedMessage.get(), "Handler should receive the correct message");
    }

    @Test
    void testEndHandler() {
        // Arrange
        AtomicBoolean handlerCalled = new AtomicBoolean(false);
        Handler<Void> handler = voidValue -> handlerCalled.set(true);

        // Act
        PgNotificationStream<String> result = (PgNotificationStream<String>) stream.endHandler(handler);

        // Assert
        assertSame(stream, result);
        // End handler should not be called until stream ends
        assertFalse(handlerCalled.get(), "End handler should not be called until stream ends");
    }

    @Test
    void testFetch() {
        // Act
        PgNotificationStream<String> result = (PgNotificationStream<String>) stream.fetch(10);

        // Assert
        assertSame(stream, result);
    }

    @Test
    void testHandleNotification() throws InterruptedException {
        // Arrange
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger callCount = new AtomicInteger(0);
        AtomicReference<String> receivedMessage = new AtomicReference<>();
        Handler<String> handler = message -> {
            callCount.incrementAndGet();
            receivedMessage.set(message);
            latch.countDown();
        };
        stream.handler(handler);

        // Act
        stream.handleNotification("test");

        // Assert - wait for async execution
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Handler should be called within 1 second");
        assertEquals(1, callCount.get(), "Handler should be called once");
        assertEquals("test", receivedMessage.get(), "Handler should receive the correct message");
    }

    @Test
    void testHandleNotificationWhenPaused() {
        // Arrange
        AtomicInteger callCount = new AtomicInteger(0);
        Handler<String> handler = message -> callCount.incrementAndGet();
        stream.handler(handler);
        stream.pause();

        // Act
        stream.handleNotification("test");

        // Assert
        assertEquals(0, callCount.get(), "Handler should not be called when stream is paused");
    }

    /**
     * Tests error handling in the notification stream.
     * This test intentionally creates a test exception to verify that the stream
     * properly forwards errors to registered exception handlers.
     *
     * NOTE: The ERROR log that appears during this test is EXPECTED and INTENTIONAL.
     */
    @Test
    void testHandleError() throws InterruptedException {
        // Arrange
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> receivedError = new AtomicReference<>();
        AtomicInteger callCount = new AtomicInteger(0);
        Handler<Throwable> handler = error -> {
            callCount.incrementAndGet();
            receivedError.set(error);
            latch.countDown();
        };
        stream.exceptionHandler(handler);

        // INTENTIONAL TEST ERROR: This exception is created to test error handling
        Exception error = new RuntimeException("INTENTIONAL TEST ERROR: Testing error handling in notification stream");

        // Act
        stream.handleError(error);

        // Assert - wait for async execution
        assertTrue(latch.await(1, TimeUnit.SECONDS), "Exception handler should be called within 1 second");
        assertEquals(1, callCount.get(), "Exception handler should be called once");
        assertSame(error, receivedError.get(), "Exception handler should receive the correct error");
    }

    @Test
    void testHandleEnd() throws InterruptedException {
        // Arrange
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean handlerCalled = new AtomicBoolean(false);
        AtomicInteger callCount = new AtomicInteger(0);
        Handler<Void> handler = voidValue -> {
            callCount.incrementAndGet();
            handlerCalled.set(true);
            latch.countDown();
        };
        stream.endHandler(handler);

        // Act
        stream.handleEnd();

        // Assert - wait for async execution
        assertTrue(latch.await(1, TimeUnit.SECONDS), "End handler should be called within 1 second");
        assertEquals(1, callCount.get(), "End handler should be called once");
        assertTrue(handlerCalled.get(), "End handler should be called");
    }
}
