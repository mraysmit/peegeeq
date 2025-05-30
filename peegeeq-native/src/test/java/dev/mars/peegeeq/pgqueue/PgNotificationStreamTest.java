package dev.mars.peegeeq.pgqueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the PgNotificationStream class.
 */
@ExtendWith(MockitoExtension.class)
public class PgNotificationStreamTest {

    @Mock
    private Vertx vertx;

    private ObjectMapper objectMapper;

    private PgNotificationStream<String> stream;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        stream = new PgNotificationStream<>(vertx, String.class, objectMapper);
    }

    @AfterEach
    void tearDown() {
        // No resources to clean up
    }

    @Test
    void testExceptionHandler() {
        // Arrange
        Handler<Throwable> handler = mock(Handler.class);

        // Act
        PgNotificationStream<String> result = (PgNotificationStream<String>) stream.exceptionHandler(handler);

        // Assert
        assertSame(stream, result);
    }

    @Test
    void testHandler() {
        // Arrange
        Handler<String> handler = mock(Handler.class);

        // Act
        PgNotificationStream<String> result = (PgNotificationStream<String>) stream.handler(handler);

        // Assert
        assertSame(stream, result);
    }

    @Test
    void testPause() {
        // Act
        PgNotificationStream<String> result = (PgNotificationStream<String>) stream.pause();

        // Assert
        assertSame(stream, result);

        // Verify that the stream is paused by sending a notification and checking that the handler is not called
        Handler<String> handler = mock(Handler.class);
        stream.handler(handler);

        stream.handleNotification("test");

        verifyNoInteractions(handler);
    }

    @Test
    void testResume() {
        // Arrange
        Handler<String> handler = mock(Handler.class);
        stream.handler(handler);
        stream.pause();

        // Act
        PgNotificationStream<String> result = (PgNotificationStream<String>) stream.resume();

        // Assert
        assertSame(stream, result);

        // Verify that the stream is resumed by sending a notification and checking that the handler is called
        doAnswer(invocation -> {
            Handler<Void> contextHandler = invocation.getArgument(0);
            contextHandler.handle(null);
            return null;
        }).when(vertx).runOnContext(any());

        stream.handleNotification("test");

        verify(handler).handle("test");
    }

    @Test
    void testEndHandler() {
        // Arrange
        Handler<Void> handler = mock(Handler.class);

        // Act
        PgNotificationStream<String> result = (PgNotificationStream<String>) stream.endHandler(handler);

        // Assert
        assertSame(stream, result);
    }

    @Test
    void testFetch() {
        // Act
        PgNotificationStream<String> result = (PgNotificationStream<String>) stream.fetch(10);

        // Assert
        assertSame(stream, result);
    }

    @Test
    void testHandleNotification() {
        // Arrange
        Handler<String> handler = mock(Handler.class);
        stream.handler(handler);

        doAnswer(invocation -> {
            Handler<Void> contextHandler = invocation.getArgument(0);
            contextHandler.handle(null);
            return null;
        }).when(vertx).runOnContext(any());

        // Act
        stream.handleNotification("test");

        // Assert
        verify(handler).handle("test");
    }

    @Test
    void testHandleNotificationWhenPaused() {
        // Arrange
        Handler<String> handler = mock(Handler.class);
        stream.handler(handler);
        stream.pause();

        // Act
        stream.handleNotification("test");

        // Assert
        verifyNoInteractions(handler);
    }

    @Test
    void testHandleError() {
        // Arrange
        Handler<Throwable> handler = mock(Handler.class);
        stream.exceptionHandler(handler);

        doAnswer(invocation -> {
            Handler<Void> contextHandler = invocation.getArgument(0);
            contextHandler.handle(null);
            return null;
        }).when(vertx).runOnContext(any());

        Exception error = new RuntimeException("Test error");

        // Act
        stream.handleError(error);

        // Assert
        verify(handler).handle(error);
    }

    @Test
    void testHandleEnd() {
        // Arrange
        Handler<Void> handler = mock(Handler.class);
        stream.endHandler(handler);

        doAnswer(invocation -> {
            Handler<Void> contextHandler = invocation.getArgument(0);
            contextHandler.handle(null);
            return null;
        }).when(vertx).runOnContext(any());

        // Act
        stream.handleEnd();

        // Assert
        verify(handler).handle(null);
    }
}
