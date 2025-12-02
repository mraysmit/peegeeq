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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage tests for PgNotificationStream.
 * Simple tests to exercise all methods and achieve 100% coverage.
 */
@Tag(TestCategories.CORE)
public class PgNotificationStreamCoverageTest {

    private Vertx vertx;
    private PgNotificationStream<String> stream;
    private ObjectMapper objectMapper;

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
    void testConstructor() {
        assertNotNull(stream, "Stream should be created successfully");
    }

    @Test
    void testExceptionHandler() {
        AtomicBoolean handlerCalled = new AtomicBoolean(false);
        
        Handler<Throwable> exceptionHandler = throwable -> handlerCalled.set(true);
        
        var result = stream.exceptionHandler(exceptionHandler);
        
        assertNotNull(result, "Should return stream for chaining");
        assertEquals(stream, result, "Should return same instance for chaining");
    }

    @Test
    void testDataHandler() {
        AtomicInteger messageCount = new AtomicInteger(0);
        
        Handler<String> dataHandler = message -> messageCount.incrementAndGet();
        
        var result = stream.handler(dataHandler);
        
        assertNotNull(result, "Should return stream for chaining");
        assertEquals(stream, result, "Should return same instance for chaining");
    }

    @Test
    void testPause() {
        var result = stream.pause();
        
        assertNotNull(result, "Should return stream for chaining");
        assertEquals(stream, result, "Should return same instance for chaining");
    }

    @Test
    void testResume() {
        var result = stream.resume();
        
        assertNotNull(result, "Should return stream for chaining");
        assertEquals(stream, result, "Should return same instance for chaining");
    }

    @Test
    void testPauseAndResume() {
        stream.pause();
        var result = stream.resume();
        
        assertNotNull(result, "Should return stream for chaining");
        assertEquals(stream, result, "Should return same instance for chaining");
    }

    @Test
    void testEndHandler() {
        AtomicBoolean endCalled = new AtomicBoolean(false);
        
        Handler<Void> endHandler = v -> endCalled.set(true);
        
        var result = stream.endHandler(endHandler);
        
        assertNotNull(result, "Should return stream for chaining");
        assertEquals(stream, result, "Should return same instance for chaining");
    }

    @Test
    void testFetch() {
        var result = stream.fetch(10);
        
        assertNotNull(result, "Should return stream for chaining");
        assertEquals(stream, result, "Should return same instance for chaining");
    }

    @Test
    void testChainedCalls() {
        AtomicBoolean exceptionCalled = new AtomicBoolean(false);
        AtomicInteger dataCount = new AtomicInteger(0);
        AtomicBoolean endCalled = new AtomicBoolean(false);
        
        var result = stream
            .exceptionHandler(t -> exceptionCalled.set(true))
            .handler(msg -> dataCount.incrementAndGet())
            .endHandler(v -> endCalled.set(true))
            .pause()
            .fetch(5)
            .resume();
        
        assertNotNull(result, "Should return stream for chaining");
        assertEquals(stream, result, "Should return same instance for chaining");
    }

    @Test
    void testNullHandlers() {
        // Should not throw when setting handlers to null
        assertDoesNotThrow(() -> {
            stream.exceptionHandler(null);
            stream.handler(null);
            stream.endHandler(null);
        }, "Setting handlers to null should not throw");
    }
}
