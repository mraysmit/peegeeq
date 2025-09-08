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


import io.vertx.core.Handler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The EmptyReadStreamTest class is testing the EmptyReadStream class, which is a "null object" implementation of Vert.x's ReadStream interface. This implementation provides a placeholder stream that doesn't emit any items and is used when no actual stream is available.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * The EmptyReadStreamTest class is testing the EmptyReadStream class, which is a "null object" implementation of Vert.x's ReadStream interface. This implementation provides a placeholder stream that doesn't emit any items and is used when no actual stream is available.
 * Specifically, the test verifies that:
 * Method Chaining Works: All methods (exceptionHandler, handler, pause, resume, endHandler, fetch) return the stream itself, allowing for method chaining.
 * No Data Emission: The data handler is never called since this is an empty stream that doesn't emit any items (verified in testHandler() with verifyNoInteractions(handler)).
 * End Handler Behavior: The end handler is immediately called when set, signaling that the stream is already at its end (verified in testEndHandlerIsCalled()).
 * Null Handler Safety: The implementation safely handles null handlers without throwing exceptions (verified in testEndHandlerWithNull()).
 * No-op Operations: The pause, resume, and fetch operations don't affect the stream's behavior since it's empty.
 * This implementation follows the "null object" pattern, providing a non-null implementation of an interface that performs no actions but maintains the expected interface contract. It's a useful pattern when you need to provide a default implementation that does nothing but still conforms to the expected interface.
 */
public class EmptyReadStreamTest {

    @Test
    void testExceptionHandler() {
        // Arrange
        EmptyReadStream<String> stream = new EmptyReadStream<>();
        AtomicBoolean handlerCalled = new AtomicBoolean(false);
        Handler<Throwable> handler = throwable -> handlerCalled.set(true);

        // Act
        EmptyReadStream<String> result = (EmptyReadStream<String>) stream.exceptionHandler(handler);

        // Assert
        assertSame(stream, result);
        // No way to verify the handler was set since it's not used in this implementation
        // The handler should not be called for an empty stream
        assertFalse(handlerCalled.get(), "Exception handler should not be called for empty stream");
    }
    
    @Test
    void testHandler() {
        // Arrange
        EmptyReadStream<String> stream = new EmptyReadStream<>();
        AtomicInteger callCount = new AtomicInteger(0);
        Handler<String> handler = data -> callCount.incrementAndGet();

        // Act
        EmptyReadStream<String> result = (EmptyReadStream<String>) stream.handler(handler);

        // Assert
        assertSame(stream, result);
        // No data is emitted, so the handler should never be called
        assertEquals(0, callCount.get(), "Data handler should never be called for empty stream");
    }
    
    @Test
    void testPause() {
        // Arrange
        EmptyReadStream<String> stream = new EmptyReadStream<>();
        
        // Act
        EmptyReadStream<String> result = (EmptyReadStream<String>) stream.pause();
        
        // Assert
        assertSame(stream, result);
    }
    
    @Test
    void testResume() {
        // Arrange
        EmptyReadStream<String> stream = new EmptyReadStream<>();
        
        // Act
        EmptyReadStream<String> result = (EmptyReadStream<String>) stream.resume();
        
        // Assert
        assertSame(stream, result);
    }
    
    @Test
    void testEndHandlerWithNull() {
        // Arrange
        EmptyReadStream<String> stream = new EmptyReadStream<>();
        
        // Act
        EmptyReadStream<String> result = (EmptyReadStream<String>) stream.endHandler(null);
        
        // Assert
        assertSame(stream, result);
        // No exception should be thrown
    }
    
    @Test
    void testEndHandlerIsCalled() {
        // Arrange
        EmptyReadStream<String> stream = new EmptyReadStream<>();
        AtomicBoolean endHandlerCalled = new AtomicBoolean(false);
        Handler<Void> endHandler = voidValue -> endHandlerCalled.set(true);

        // Act
        stream.endHandler(endHandler);

        // Assert
        assertTrue(endHandlerCalled.get(), "End handler should be called immediately for empty stream");
    }
    
    @Test
    void testFetch() {
        // Arrange
        EmptyReadStream<String> stream = new EmptyReadStream<>();
        
        // Act
        EmptyReadStream<String> result = (EmptyReadStream<String>) stream.fetch(10);
        
        // Assert
        assertSame(stream, result);
    }
}