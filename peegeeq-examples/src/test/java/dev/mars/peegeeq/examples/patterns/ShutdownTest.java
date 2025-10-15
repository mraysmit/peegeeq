package dev.mars.peegeeq.examples.patterns;

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


import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * INFRASTRUCTURE TEST: Graceful Shutdown and Resource Cleanup Patterns
 *
 * ⚠️  NOTE: This test does NOT create or test any message queues.
 *
 * WHAT THIS TESTS:
 * - ExecutorService graceful shutdown patterns and timeouts
 * - Thread pool cleanup and resource management
 * - Forced shutdown handling for unresponsive tasks
 * - TestContainers lifecycle management and cleanup
 *
 * BUSINESS VALUE:
 * - Ensures proper resource cleanup prevents memory leaks
 * - Validates graceful shutdown patterns work correctly
 * - Provides confidence in production shutdown procedures
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class ShutdownTest {
    private static final Logger logger = LoggerFactory.getLogger(ShutdownTest.class);

    @Test
    @Timeout(10) // Test should complete within 10 seconds
    void testExecutorShutdownGracefully() {
        logger.info("Testing graceful executor shutdown");
        
        // Create a scheduled executor similar to what the demo uses
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(5);
        
        // Submit some tasks
        for (int i = 0; i < 10; i++) {
            final int taskId = i;
            executor.submit(() -> {
                try {
                    Thread.sleep(100); // Simulate work
                    logger.debug("Task {} completed", taskId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.debug("Task {} interrupted", taskId);
                }
            });
        }
        
        // Test our graceful shutdown method
        long startTime = System.currentTimeMillis();
        shutdownExecutorGracefully(executor, "test-executor");
        long shutdownTime = System.currentTimeMillis() - startTime;
        
        // Verify shutdown completed
        assertTrue(executor.isShutdown(), "Executor should be shut down");
        assertTrue(executor.isTerminated(), "Executor should be terminated");
        
        // Should complete reasonably quickly (within 6 seconds - 5s timeout + 1s buffer)
        assertTrue(shutdownTime < 6000, 
            "Shutdown took too long: " + shutdownTime + "ms");
        
        logger.info("Executor shutdown completed in {}ms", shutdownTime);
    }
    
    @Test
    @Timeout(15) // Test should complete within 15 seconds
    void testExecutorForcedShutdown() {
        logger.info("Testing forced executor shutdown");
        
        ExecutorService executor = Executors.newFixedThreadPool(3);
        
        // Submit long-running tasks that won't finish gracefully
        for (int i = 0; i < 5; i++) {
            final int taskId = i;
            executor.submit(() -> {
                try {
                    // Long-running task that should be interrupted
                    Thread.sleep(30000); // 30 seconds - longer than our timeout
                    logger.debug("Task {} completed normally (shouldn't happen)", taskId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.debug("Task {} was interrupted (expected)", taskId);
                }
            });
        }
        
        // Test forced shutdown
        long startTime = System.currentTimeMillis();
        shutdownExecutorGracefully(executor, "test-forced-executor");
        long shutdownTime = System.currentTimeMillis() - startTime;
        
        // Verify shutdown completed
        assertTrue(executor.isShutdown(), "Executor should be shut down");
        assertTrue(executor.isTerminated(), "Executor should be terminated");
        
        // Should complete within our timeout window (5s graceful + 5s forced + buffer)
        assertTrue(shutdownTime < 12000, 
            "Forced shutdown took too long: " + shutdownTime + "ms");
        
        logger.info("Forced executor shutdown completed in {}ms", shutdownTime);
    }

    @Test
    @Timeout(10) // Test should complete within 10 seconds
    void testManualContainerManagement() {
        logger.info("Testing manual container management");

        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
                .withDatabaseName("test_db")
                .withUsername("test_user")
                .withPassword("test_pass")
                .withReuse(false);

        try {
            postgres.start();
            logger.info("Container started: {}", postgres.getJdbcUrl());

            // Simulate some work
            Thread.sleep(1000);

        } catch (Exception e) {
            logger.error("Error in test", e);
        } finally {
            logger.info("Stopping container manually");
            postgres.stop();
            logger.info("Container stopped");
        }
    }

    /**
     * Copy of the graceful shutdown method from PeeGeeQSelfContainedDemo
     * to test it works correctly.
     */
    private static void shutdownExecutorGracefully(ExecutorService executor, String name) {
        logger.debug("Shutting down executor: {}", name);
        
        executor.shutdown(); // Disable new tasks from being submitted
        
        try {
            // Wait a while for existing tasks to terminate
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Executor {} did not terminate gracefully, forcing shutdown", name);
                executor.shutdownNow(); // Cancel currently executing tasks
                
                // Wait a while for tasks to respond to being cancelled
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.error("Executor {} did not terminate after forced shutdown", name);
                } else {
                    logger.debug("Executor {} terminated after forced shutdown", name);
                }
            } else {
                logger.debug("Executor {} terminated gracefully", name);
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted while shutting down executor {}", name);
            // (Re-)Cancel if current thread also interrupted
            executor.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }
}
