package dev.mars.peegeeq.db.lifecycle;

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to demonstrate the event-driven lifecycle management in PeeGeeQManager.
 * This test shows how components can subscribe to lifecycle events for coordinated startup.
 */
public class EventDrivenLifecycleTest {
    
    private static final Logger logger = LoggerFactory.getLogger(EventDrivenLifecycleTest.class);
    
    @Test
    public void testEventDrivenLifecycleAPI() throws Exception {
        logger.info("=== Testing Event-Driven Lifecycle API ===");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        PeeGeeQManager manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        // List to capture lifecycle events
        List<String> lifecycleEvents = new ArrayList<>();

        try {
            // Subscribe to lifecycle events
            manager.getVertx().eventBus().consumer("peegeeq.lifecycle", message -> {
                JsonObject eventData = (JsonObject) message.body();
                String event = eventData.getString("event");
                lifecycleEvents.add(event);

                logger.info("📡 Lifecycle Event Received: {} at {}",
                    event, eventData.getString("timestamp"));
            });

            logger.info("🚀 Testing reactive lifecycle API...");

            // Test that startReactive method exists and returns a Future
            Future<Void> startFuture = manager.startReactive();
            assertNotNull(startFuture, "startReactive should return a Future");

            logger.info("✅ Reactive lifecycle API test completed");
            logger.info("🎯 Event-driven lifecycle API test completed successfully!");

        } finally {
            // Clean up
            try {
                manager.close();
                logger.info("🧹 PeeGeeQ Manager closed successfully");
            } catch (Exception e) {
                logger.warn("Error during cleanup", e);
            }
        }
    }
    
    @Test
    public void testReactiveHealthCheckStartup() throws Exception {
        logger.info("=== Testing Reactive Health Check Startup (Unit Test) ===");

        // This test demonstrates the reactive API without requiring database connectivity
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        PeeGeeQManager manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        try {
            logger.info("🚀 Testing reactive startup API...");

            // Verify health check manager is not running initially
            assertFalse(manager.getHealthCheckManager().isRunning(),
                "Health check manager should not be running initially");

            // Test that startReactive method exists and returns a Future
            Future<Void> startFuture = manager.startReactive();
            assertNotNull(startFuture, "startReactive should return a Future");

            logger.info("✅ Reactive API test completed - startReactive() method available");
            logger.info("🎯 Reactive health check startup API test completed successfully!");

        } finally {
            // Clean up
            try {
                manager.close();
                logger.info("🧹 PeeGeeQ Manager closed successfully");
            } catch (Exception e) {
                logger.warn("Error during cleanup", e);
            }
        }
    }
}
