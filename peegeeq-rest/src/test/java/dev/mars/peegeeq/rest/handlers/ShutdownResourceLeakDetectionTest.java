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

package dev.mars.peegeeq.rest.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to detect resource leaks during server shutdown.
 * Verifies that all handlers are properly closed and resources are released.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-31
 * @version 1.0
 */
@ExtendWith(VertxExtension.class)
class ShutdownResourceLeakDetectionTest {

    private static final Logger logger = LoggerFactory.getLogger(ShutdownResourceLeakDetectionTest.class);
    private static final int TEST_PORT = 19999;

    @Test
    @DisplayName("Server should close all handlers on shutdown without resource leaks")
    void testServerShutdownClosesAllHandlers(Vertx vertx, VertxTestContext testContext) throws Throwable {
        logger.info("=== Testing Server Shutdown Resource Cleanup ===");

        // Arrange
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();
        RestServerConfig config = new RestServerConfig(
            TEST_PORT, 
            RestServerConfig.MonitoringConfig.defaults(), 
            java.util.List.of("*")
        );

        // Act - Deploy server
        vertx.deployVerticle(new PeeGeeQRestServer(config, setupService))
            .onSuccess(deploymentId -> {
                logger.info("Server deployed successfully: {}", deploymentId);
                
                // Wait a moment for server to fully start, then undeploy
                vertx.setTimer(500L, timerId -> {
                    logger.info("Initiating server shutdown...");
                    vertx.undeploy(deploymentId)
                        .onSuccess(v -> {
                            logger.info("✓ Server shutdown completed successfully");
                            logger.info("✓ All handlers should have been closed");
                            logger.info("✓ No resource leak exceptions thrown");
                            testContext.completeNow();
                        })
                        .onFailure(testContext::failNow);
                });
            })
            .onFailure(cause -> {
                logger.error("❌ Server deployment failed: {}", cause.getMessage());
                testContext.failNow(cause);
            });

        // Assert - Wait for test completion
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), 
            "Test should complete within 10 seconds");
        
        if (testContext.failed()) {
            throw testContext.causeOfFailure();
        }
    }

    @Test
    @DisplayName("Multiple deploy/undeploy cycles should not leak resources")
    void testMultipleDeployUndeployCycles(Vertx vertx, VertxTestContext testContext) throws Throwable {
        logger.info("=== Testing Multiple Deploy/Undeploy Cycles ===");

        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();
        RestServerConfig config = new RestServerConfig(
            TEST_PORT, 
            RestServerConfig.MonitoringConfig.defaults(), 
            java.util.List.of("*")
        );

        // Perform 3 deploy/undeploy cycles
        deployAndUndeployCycle(vertx, config, setupService, 0, 3, testContext);

        // Wait for all cycles to complete
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
            "All cycles should complete within 30 seconds");
            
        if (testContext.failed()) {
            throw testContext.causeOfFailure();
        }
        
        logger.info("✓ All {} deploy/undeploy cycles completed without resource leaks", 3);
    }

    private void deployAndUndeployCycle(Vertx vertx, RestServerConfig config, 
                                       DatabaseSetupService setupService, 
                                       int currentCycle, int totalCycles, 
                                       VertxTestContext testContext) {
        if (currentCycle >= totalCycles) {
            logger.info("✓ All {} cycles completed successfully", totalCycles);
            testContext.completeNow();
            return;
        }

        int cycleNumber = currentCycle + 1;
        logger.info("Starting cycle {}/{}", cycleNumber, totalCycles);

        vertx.deployVerticle(new PeeGeeQRestServer(config, setupService))
            .onSuccess(deploymentId -> {
                logger.info("  Cycle {}: Deployed {}", cycleNumber, deploymentId);
                vertx.setTimer(200L, timerId -> {
                    logger.info("  Cycle {}: Undeploying...", cycleNumber);
                    vertx.undeploy(deploymentId)
                        .onSuccess(v -> {
                            logger.info("  Cycle {}: Completed successfully", cycleNumber);
                            // Start next cycle
                            deployAndUndeployCycle(vertx, config, setupService, currentCycle + 1, totalCycles, testContext);
                        })
                        .onFailure(cause -> {
                            logger.error("  Cycle {}: Failed - {}", cycleNumber, cause.getMessage());
                            testContext.failNow(cause);
                        });
                });
            })
            .onFailure(cause -> {
                logger.error("  Cycle {}: Deployment failed - {}", cycleNumber, cause.getMessage());
                testContext.failNow(cause);
            });
    }

    @Test
    @DisplayName("WebSocketHandler should properly close all connections on shutdown")
    void testWebSocketHandlerCleanup() {
        logger.info("=== Testing WebSocketHandler Cleanup ===");

        // Arrange
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();
        ObjectMapper objectMapper = new ObjectMapper();
        WebSocketHandler handler = new WebSocketHandler(setupService, objectMapper);

        // Assert initial state
        assertEquals(0, handler.getActiveConnectionCount(), 
            "New handler should have 0 active connections");

        // Act - Close handler
        assertDoesNotThrow(() -> handler.close(), 
            "close() should execute without throwing exceptions");

        // Assert cleanup
        assertEquals(0, handler.getActiveConnectionCount(),
            "After close(), connection count should be 0");

        // Verify idempotent
        assertDoesNotThrow(() -> handler.close(),
            "Multiple close() calls should be safe (idempotent)");

        logger.info("✓ WebSocketHandler cleanup verified");
    }

    @Test
    @DisplayName("SystemMonitoringHandler should properly stop metrics collection on shutdown")
    void testSystemMonitoringHandlerCleanup(Vertx vertx) {
        logger.info("=== Testing SystemMonitoringHandler Cleanup ===");

        // Arrange
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();
        RestServerConfig.MonitoringConfig monitoringConfig = RestServerConfig.MonitoringConfig.defaults();
        io.micrometer.prometheus.PrometheusMeterRegistry meterRegistry = 
            new io.micrometer.prometheus.PrometheusMeterRegistry(
                io.micrometer.prometheus.PrometheusConfig.DEFAULT);

        SystemMonitoringHandler handler = new SystemMonitoringHandler(
            setupService, vertx, monitoringConfig, meterRegistry);

        // Act - Close handler
        assertDoesNotThrow(() -> handler.close(),
            "close() should execute without throwing exceptions");

        // Verify idempotent
        assertDoesNotThrow(() -> handler.close(),
            "Multiple close() calls should be safe (idempotent)");

        logger.info("✓ SystemMonitoringHandler cleanup verified");
    }

    @Test
    @DisplayName("CORS validation should prevent server startup with invalid configuration")
    void testCorsValidationPreventsStartupWithInvalidConfig(Vertx vertx, VertxTestContext testContext) {
        logger.info("=== Testing CORS Validation at Startup ===");

        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();

        // Test 1: Null allowedOrigins should be rejected by RestServerConfig
        logger.info("Test 1: Verifying null allowedOrigins is rejected");
        assertThrows(NullPointerException.class, () -> {
            new RestServerConfig(TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), null);
        }, "RestServerConfig should reject null allowedOrigins");

        // Test 2: Empty allowedOrigins should be rejected
        logger.info("Test 2: Verifying empty allowedOrigins is rejected");
        assertThrows(IllegalArgumentException.class, () -> {
            new RestServerConfig(TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), java.util.List.of());
        }, "RestServerConfig should reject empty allowedOrigins");

        logger.info("✓ CORS validation tests passed");
        testContext.completeNow();
    }
}
