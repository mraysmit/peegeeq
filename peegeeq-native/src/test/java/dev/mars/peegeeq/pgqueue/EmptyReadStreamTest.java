package dev.mars.peegeeq.pgqueue;

import io.vertx.core.Handler;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
        Handler<Throwable> handler = Mockito.mock(Handler.class);
        
        // Act
        EmptyReadStream<String> result = (EmptyReadStream<String>) stream.exceptionHandler(handler);
        
        // Assert
        assertSame(stream, result);
        // No way to verify the handler was set since it's not used in this implementation
    }
    
    @Test
    void testHandler() {
        // Arrange
        EmptyReadStream<String> stream = new EmptyReadStream<>();
        Handler<String> handler = Mockito.mock(Handler.class);
        
        // Act
        EmptyReadStream<String> result = (EmptyReadStream<String>) stream.handler(handler);
        
        // Assert
        assertSame(stream, result);
        // No data is emitted, so the handler should never be called
        verifyNoInteractions(handler);
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
        Handler<Void> endHandler = Mockito.mock(Handler.class);
        
        // Act
        stream.endHandler(endHandler);
        
        // Assert
        verify(endHandler).handle(null);
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