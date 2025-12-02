package dev.mars.peegeeq.pgqueue;

import io.vertx.core.Handler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test implementation that provides controlled execution of context handlers.
 * for testing PgNotificationStream functionality.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-08
 * @version 1.0
 */
public class TestVertx {
    
    private final List<Handler<Void>> pendingContextHandlers = new ArrayList<>();
    private final AtomicBoolean executeImmediately = new AtomicBoolean(true);
    
    /**
     * Creates a new TestVertx instance that executes handlers immediately by default.
     */
    public TestVertx() {
        // Default behavior: execute immediately
    }
    
    /**
     * Sets whether context handlers should be executed immediately or queued.
     * 
     * @param immediate true to execute immediately, false to queue for manual execution
     */
    public void setExecuteImmediately(boolean immediate) {
        executeImmediately.set(immediate);
    }
    
    /**
     * Executes all pending context handlers that were queued when executeImmediately was false.
     */
    public void executePendingHandlers() {
        synchronized (pendingContextHandlers) {
            List<Handler<Void>> handlersToExecute = new ArrayList<>(pendingContextHandlers);
            pendingContextHandlers.clear();
            
            for (Handler<Void> handler : handlersToExecute) {
                try {
                    handler.handle(null);
                } catch (Exception e) {
                    // Log but don't rethrow to avoid breaking tests
                    System.err.println("Error executing pending handler: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Gets the number of pending context handlers.
     * 
     * @return the number of handlers waiting to be executed
     */
    public int getPendingHandlerCount() {
        synchronized (pendingContextHandlers) {
            return pendingContextHandlers.size();
        }
    }
    
    /**
     * Clears all pending context handlers without executing them.
     */
    public void clearPendingHandlers() {
        synchronized (pendingContextHandlers) {
            pendingContextHandlers.clear();
        }
    }
    
    public TestVertx runOnContext(Handler<Void> action) {
        if (executeImmediately.get()) {
            // Execute immediately for simple test scenarios
            try {
                action.handle(null);
            } catch (Exception e) {
                // Log but don't rethrow to avoid breaking tests
                System.err.println("Error executing context handler: " + e.getMessage());
            }
        } else {
            // Queue for manual execution in complex test scenarios
            synchronized (pendingContextHandlers) {
                pendingContextHandlers.add(action);
            }
        }
        return this;
    }
    
    // Additional methods for test control

    public void close() {
        clearPendingHandlers();
    }

    public void close(Handler<Void> completionHandler) {
        clearPendingHandlers();
        if (completionHandler != null) {
            completionHandler.handle(null);
        }
    }
}
