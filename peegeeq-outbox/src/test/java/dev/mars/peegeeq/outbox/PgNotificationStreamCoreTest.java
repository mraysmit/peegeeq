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
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PgNotificationStream.
 * Tests the ReadStream implementation for PostgreSQL notifications.
 * 
 * Tagged as CORE because this tests pure stream logic without database operations.
 * PgNotificationStream is a ReadStream adapter that doesn't directly touch the database.
 */
@Tag(TestCategories.CORE)
public class PgNotificationStreamCoreTest {

    private Vertx vertx;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        System.err.println("=== PgNotificationStreamCoreTest SETUP STARTED ===");
        vertx = Vertx.vertx();
        objectMapper = new ObjectMapper();
        System.err.println("=== PgNotificationStreamCoreTest SETUP COMPLETED ===");
    }

    @AfterEach
    void tearDown() {
        System.err.println("=== PgNotificationStreamCoreTest TEARDOWN STARTED ===");
        if (vertx != null) {
            vertx.close();
        }
        System.err.println("=== PgNotificationStreamCoreTest TEARDOWN COMPLETED ===");
    }

    @Test
    void testStreamCreation() {
        System.err.println("=== TEST: testStreamCreation STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        assertNotNull(stream, "Stream should be created");
        
        System.err.println("=== TEST: testStreamCreation COMPLETED ===");
    }

    @Test
    void testSetDataHandler() {
        System.err.println("=== TEST: testSetDataHandler STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        Handler<String> handler = message -> {
            // Handler logic
        };
        
        assertNotNull(stream.handler(handler), "Setting handler should return stream");
        
        System.err.println("=== TEST: testSetDataHandler COMPLETED ===");
    }

    @Test
    void testSetExceptionHandler() {
        System.err.println("=== TEST: testSetExceptionHandler STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        Handler<Throwable> exceptionHandler = error -> {
            // Exception handler logic
        };
        
        assertNotNull(stream.exceptionHandler(exceptionHandler), "Setting exception handler should return stream");
        
        System.err.println("=== TEST: testSetExceptionHandler COMPLETED ===");
    }

    @Test
    void testSetEndHandler() {
        System.err.println("=== TEST: testSetEndHandler STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        Handler<Void> endHandler = v -> {
            // End handler logic
        };
        
        assertNotNull(stream.endHandler(endHandler), "Setting end handler should return stream");
        
        System.err.println("=== TEST: testSetEndHandler COMPLETED ===");
    }

    @Test
    void testPauseStream() {
        System.err.println("=== TEST: testPauseStream STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        assertNotNull(stream.pause(), "Pause should return stream");
        
        System.err.println("=== TEST: testPauseStream COMPLETED ===");
    }

    @Test
    void testResumeStream() {
        System.err.println("=== TEST: testResumeStream STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        assertNotNull(stream.resume(), "Resume should return stream");
        
        System.err.println("=== TEST: testResumeStream COMPLETED ===");
    }

    @Test
    void testFetch() {
        System.err.println("=== TEST: testFetch STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        assertNotNull(stream.fetch(10), "Fetch should return stream");
        
        System.err.println("=== TEST: testFetch COMPLETED ===");
    }

    @Test
    void testHandleNotification() throws Exception {
        System.err.println("=== TEST: testHandleNotification STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();
        
        stream.handler(message -> {
            receivedMessage.set(message);
            latch.countDown();
        });
        
        String testMessage = "Test notification";
        stream.handleNotification(testMessage);
        
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Should receive notification");
        assertEquals(testMessage, receivedMessage.get(), "Should receive correct message");
        
        System.err.println("=== TEST: testHandleNotification COMPLETED ===");
    }

    @Test
    void testHandleNotificationWhenPaused() throws Exception {
        System.err.println("=== TEST: testHandleNotificationWhenPaused STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        AtomicInteger messageCount = new AtomicInteger(0);
        
        stream.handler(message -> {
            messageCount.incrementAndGet();
        });
        
        // Pause the stream
        stream.pause();
        
        // Try to handle notification while paused
        stream.handleNotification("Message while paused");
        
        // Wait a bit
        Thread.sleep(1000);
        
        // Should not have received the message
        assertEquals(0, messageCount.get(), "Should not receive messages while paused");
        
        System.err.println("=== TEST: testHandleNotificationWhenPaused COMPLETED ===");
    }

    @Test
    void testHandleNotificationAfterResume() throws Exception {
        System.err.println("=== TEST: testHandleNotificationAfterResume STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();
        
        stream.handler(message -> {
            receivedMessage.set(message);
            latch.countDown();
        });
        
        // Pause then resume
        stream.pause();
        stream.resume();
        
        // Send notification after resume
        String testMessage = "Message after resume";
        stream.handleNotification(testMessage);
        
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Should receive notification after resume");
        assertEquals(testMessage, receivedMessage.get(), "Should receive correct message");
        
        System.err.println("=== TEST: testHandleNotificationAfterResume COMPLETED ===");
    }

    @Test
    void testHandleMultipleNotifications() throws Exception {
        System.err.println("=== TEST: testHandleMultipleNotifications STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        int notificationCount = 5;
        CountDownLatch latch = new CountDownLatch(notificationCount);
        AtomicInteger receivedCount = new AtomicInteger(0);
        
        stream.handler(message -> {
            receivedCount.incrementAndGet();
            latch.countDown();
        });
        
        // Send multiple notifications
        for (int i = 0; i < notificationCount; i++) {
            stream.handleNotification("Notification " + i);
        }
        
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Should receive all notifications");
        assertEquals(notificationCount, receivedCount.get(), "Should receive correct number of notifications");
        
        System.err.println("=== TEST: testHandleMultipleNotifications COMPLETED ===");
    }

    @Test
    void testHandleError() throws Exception {
        System.err.println("=== TEST: testHandleError STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> receivedError = new AtomicReference<>();
        
        stream.exceptionHandler(error -> {
            receivedError.set(error);
            latch.countDown();
        });
        
        RuntimeException testError = new RuntimeException("Test error");
        stream.handleError(testError);
        
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Should receive error");
        assertEquals(testError, receivedError.get(), "Should receive correct error");
        
        System.err.println("=== TEST: testHandleError COMPLETED ===");
    }

    @Test
    void testHandleEnd() throws Exception {
        System.err.println("=== TEST: testHandleEnd STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        CountDownLatch latch = new CountDownLatch(1);
        
        stream.endHandler(v -> {
            latch.countDown();
        });
        
        stream.handleEnd();
        
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Should receive end signal");
        
        System.err.println("=== TEST: testHandleEnd COMPLETED ===");
    }

    @Test
    void testHandleNotificationWithoutHandler() {
        System.err.println("=== TEST: testHandleNotificationWithoutHandler STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        // Should not throw exception when handling notification without handler
        assertDoesNotThrow(() -> stream.handleNotification("Test message"),
            "Should not throw when handling notification without handler");
        
        System.err.println("=== TEST: testHandleNotificationWithoutHandler COMPLETED ===");
    }

    @Test
    void testHandleErrorWithoutHandler() {
        System.err.println("=== TEST: testHandleErrorWithoutHandler STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        // Should not throw exception when handling error without exception handler
        assertDoesNotThrow(() -> stream.handleError(new RuntimeException("Test error")),
            "Should not throw when handling error without exception handler");
        
        System.err.println("=== TEST: testHandleErrorWithoutHandler COMPLETED ===");
    }

    @Test
    void testHandleEndWithoutHandler() {
        System.err.println("=== TEST: testHandleEndWithoutHandler STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        // Should not throw exception when handling end without end handler
        assertDoesNotThrow(() -> stream.handleEnd(),
            "Should not throw when handling end without end handler");
        
        System.err.println("=== TEST: testHandleEndWithoutHandler COMPLETED ===");
    }

    @Test
    void testFluentApi() {
        System.err.println("=== TEST: testFluentApi STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        // Test fluent API chaining
        assertNotNull(
            stream.handler(msg -> {})
                .exceptionHandler(err -> {})
                .endHandler(v -> {})
                .pause()
                .resume()
                .fetch(100),
            "Fluent API chaining should work"
        );
        
        System.err.println("=== TEST: testFluentApi COMPLETED ===");
    }
}
