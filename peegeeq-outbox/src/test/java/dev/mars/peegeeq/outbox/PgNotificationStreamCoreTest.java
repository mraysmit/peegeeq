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
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
@ExtendWith(VertxExtension.class)
public class PgNotificationStreamCoreTest {

    private static final Logger logger = LoggerFactory.getLogger(PgNotificationStreamCoreTest.class);

    private Vertx vertx;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp(Vertx vertx) {
        logger.info("=== PgNotificationStreamCoreTest SETUP STARTED ===");
        this.vertx = vertx;
        objectMapper = new ObjectMapper();
        logger.info("=== PgNotificationStreamCoreTest SETUP COMPLETED ===");
    }

    @AfterEach
    void tearDown() {
        logger.info("=== PgNotificationStreamCoreTest TEARDOWN STARTED ===");
        logger.info("=== PgNotificationStreamCoreTest TEARDOWN COMPLETED ===");
    }

    @Test
    void testStreamCreation() {
        logger.info("=== TEST: testStreamCreation STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        assertNotNull(stream, "Stream should be created");
        
        logger.info("=== TEST: testStreamCreation COMPLETED ===");
    }

    @Test
    void testSetDataHandler() {
        logger.info("=== TEST: testSetDataHandler STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        Handler<String> handler = message -> {
            // Handler logic
        };
        
        assertNotNull(stream.handler(handler), "Setting handler should return stream");
        
        logger.info("=== TEST: testSetDataHandler COMPLETED ===");
    }

    @Test
    void testSetExceptionHandler() {
        logger.info("=== TEST: testSetExceptionHandler STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        Handler<Throwable> exceptionHandler = error -> {
            // Exception handler logic
        };
        
        assertNotNull(stream.exceptionHandler(exceptionHandler), "Setting exception handler should return stream");
        
        logger.info("=== TEST: testSetExceptionHandler COMPLETED ===");
    }

    @Test
    void testSetEndHandler() {
        logger.info("=== TEST: testSetEndHandler STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        Handler<Void> endHandler = v -> {
            // End handler logic
        };
        
        assertNotNull(stream.endHandler(endHandler), "Setting end handler should return stream");
        
        logger.info("=== TEST: testSetEndHandler COMPLETED ===");
    }

    @Test
    void testPauseStream() {
        logger.info("=== TEST: testPauseStream STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        assertNotNull(stream.pause(), "Pause should return stream");
        
        logger.info("=== TEST: testPauseStream COMPLETED ===");
    }

    @Test
    void testResumeStream() {
        logger.info("=== TEST: testResumeStream STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        assertNotNull(stream.resume(), "Resume should return stream");
        
        logger.info("=== TEST: testResumeStream COMPLETED ===");
    }

    @Test
    void testFetch() {
        logger.info("=== TEST: testFetch STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        assertNotNull(stream.fetch(10), "Fetch should return stream");
        
        logger.info("=== TEST: testFetch COMPLETED ===");
    }

    @Test
    void testHandleNotification(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testHandleNotification STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        Checkpoint messageCheckpoint = testContext.checkpoint();
        AtomicReference<String> receivedMessage = new AtomicReference<>();
        
        stream.handler(message -> {
            receivedMessage.set(message);
            messageCheckpoint.flag();
        });
        
        String testMessage = "Test notification";
        stream.handleNotification(testMessage);
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Should receive notification");
        assertEquals(testMessage, receivedMessage.get(), "Should receive correct message");
        
        logger.info("=== TEST: testHandleNotification COMPLETED ===");
    }

    @Test
    void testHandleNotificationWhenPaused() throws Exception {
        logger.info("=== TEST: testHandleNotificationWhenPaused STARTED ===");
        
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
        vertx.timer(1000).await();
        
        // Should not have received the message
        assertEquals(0, messageCount.get(), "Should not receive messages while paused");
        
        logger.info("=== TEST: testHandleNotificationWhenPaused COMPLETED ===");
    }

    @Test
    void testHandleNotificationAfterResume(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testHandleNotificationAfterResume STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        Checkpoint messageCheckpoint = testContext.checkpoint();
        AtomicReference<String> receivedMessage = new AtomicReference<>();
        
        stream.handler(message -> {
            receivedMessage.set(message);
            messageCheckpoint.flag();
        });
        
        // Pause then resume
        stream.pause();
        stream.resume();
        
        // Send notification after resume
        String testMessage = "Message after resume";
        stream.handleNotification(testMessage);
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Should receive notification after resume");
        assertEquals(testMessage, receivedMessage.get(), "Should receive correct message");
        
        logger.info("=== TEST: testHandleNotificationAfterResume COMPLETED ===");
    }

    @Test
    void testHandleMultipleNotifications(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testHandleMultipleNotifications STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        int notificationCount = 5;
        Checkpoint messageCheckpoint = testContext.checkpoint(notificationCount);
        AtomicInteger receivedCount = new AtomicInteger(0);
        
        stream.handler(message -> {
            receivedCount.incrementAndGet();
            messageCheckpoint.flag();
        });
        
        // Send multiple notifications
        for (int i = 0; i < notificationCount; i++) {
            stream.handleNotification("Notification " + i);
        }
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Should receive all notifications");
        assertEquals(notificationCount, receivedCount.get(), "Should receive correct number of notifications");
        
        logger.info("=== TEST: testHandleMultipleNotifications COMPLETED ===");
    }

    @Test
    void testHandleError(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testHandleError STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        Checkpoint errorCheckpoint = testContext.checkpoint();
        AtomicReference<Throwable> receivedError = new AtomicReference<>();
        
        stream.exceptionHandler(error -> {
            receivedError.set(error);
            errorCheckpoint.flag();
        });
        
        RuntimeException testError = new RuntimeException("Test error");
        stream.handleError(testError);
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Should receive error");
        assertEquals(testError, receivedError.get(), "Should receive correct error");
        
        logger.info("=== TEST: testHandleError COMPLETED ===");
    }

    @Test
    void testHandleEnd(VertxTestContext testContext) throws Exception {
        logger.info("=== TEST: testHandleEnd STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        Checkpoint endCheckpoint = testContext.checkpoint();
        
        stream.endHandler(v -> {
            endCheckpoint.flag();
        });
        
        stream.handleEnd();
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Should receive end signal");
        
        logger.info("=== TEST: testHandleEnd COMPLETED ===");
    }

    @Test
    void testHandleNotificationWithoutHandler() {
        logger.info("=== TEST: testHandleNotificationWithoutHandler STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        // Should not throw exception when handling notification without handler
        assertDoesNotThrow(() -> stream.handleNotification("Test message"),
            "Should not throw when handling notification without handler");
        
        logger.info("=== TEST: testHandleNotificationWithoutHandler COMPLETED ===");
    }

    @Test
    void testHandleErrorWithoutHandler() {
        logger.info("=== TEST: testHandleErrorWithoutHandler STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        // Should not throw exception when handling error without exception handler
        assertDoesNotThrow(() -> stream.handleError(new RuntimeException("Test error")),
            "Should not throw when handling error without exception handler");
        
        logger.info("=== TEST: testHandleErrorWithoutHandler COMPLETED ===");
    }

    @Test
    void testHandleEndWithoutHandler() {
        logger.info("=== TEST: testHandleEndWithoutHandler STARTED ===");
        
        PgNotificationStream<String> stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
        
        // Should not throw exception when handling end without end handler
        assertDoesNotThrow(() -> stream.handleEnd(),
            "Should not throw when handling end without end handler");
        
        logger.info("=== TEST: testHandleEndWithoutHandler COMPLETED ===");
    }

    @Test
    void testFluentApi() {
        logger.info("=== TEST: testFluentApi STARTED ===");
        
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
        
        logger.info("=== TEST: testFluentApi COMPLETED ===");
    }
}
