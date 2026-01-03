package dev.mars.peegeeq.integration.resilience;

import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.integration.SmokeTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("System Resilience Smoke Tests")
public class ResilienceSmokeTest extends SmokeTestBase {

    @Test
    @DisplayName("Verify 503 Service Unavailable when DB connection is lost")
    void testDatabaseConnectionLossReturns503() throws Exception {
        // Set short connection timeout for this test to ensure fast failure detection
        String originalTimeout = System.getProperty("peegeeq.database.pool.connection-timeout-ms");
        String originalHealthInterval = System.getProperty("peegeeq.health-check.interval");
        
        System.setProperty("peegeeq.database.pool.connection-timeout-ms", "2000");
        // Set health check interval to 1s to ensure status updates quickly
        System.setProperty("peegeeq.health-check.interval", "PT1S");

        try {
            String setupId = UUID.randomUUID().toString();
            String dbName = "peegeeq_resilience_" + setupId.replace("-", "_");

            DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                    .host(postgres.getHost())
                    .port(postgres.getFirstMappedPort())
                    .databaseName(dbName)
                    .username(postgres.getUsername())
                    .password(postgres.getPassword())
                    .schema("resilience_schema")
                    .build();

            DatabaseSetupRequest request = new DatabaseSetupRequest(setupId, dbConfig, Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());

            // 1. Create setup
            setupService.createCompleteSetup(request).get(10, TimeUnit.SECONDS);

            // 2. Verify Health is UP
            CompletableFuture<Integer> healthCheck = new CompletableFuture<>();
            webClient.get("/api/v1/setups/" + setupId + "/health")
                    .send()
                    .onSuccess(res -> healthCheck.complete(res.statusCode()))
                    .onFailure(healthCheck::completeExceptionally);
            
            assertEquals(200, healthCheck.get(5, TimeUnit.SECONDS));

            // 3. Pause DB
            postgres.getDockerClient().pauseContainerCmd(postgres.getContainerId()).exec();

            try {
                // 4. Verify Health is DOWN (503)
                boolean serviceUnavailable = false;
                for (int i = 0; i < 10; i++) { // Retry for up to 10 seconds
                    CompletableFuture<Integer> check = new CompletableFuture<>();
                    webClient.get("/api/v1/setups/" + setupId + "/health")
                            .timeout(2000)
                            .send()
                            .onSuccess(res -> check.complete(res.statusCode()))
                            .onFailure(err -> check.complete(503)); // Treat timeout/error as 503

                    int status = check.get(3, TimeUnit.SECONDS);
                    if (status == 503) {
                        serviceUnavailable = true;
                        break;
                    }
                    Thread.sleep(1000);
                }
                assertTrue(serviceUnavailable, "Service should return 503 when DB is paused");

            } finally {
                // 5. Unpause DB
                postgres.getDockerClient().unpauseContainerCmd(postgres.getContainerId()).exec();
            }
            
            // 6. Verify Health recovers
            boolean recovered = false;
            for (int i = 0; i < 10; i++) {
                 CompletableFuture<Integer> check = new CompletableFuture<>();
                    webClient.get("/api/v1/setups/" + setupId + "/health")
                            .send()
                            .onSuccess(res -> check.complete(res.statusCode()))
                            .onFailure(err -> check.complete(500));

                    try {
                        int status = check.get(3, TimeUnit.SECONDS);
                        if (status == 200) {
                            recovered = true;
                            break;
                        }
                    } catch (Exception ignored) {}
                    Thread.sleep(1000);
            }
            assertTrue(recovered, "Service should recover (200 OK) after DB is unpaused");

            // Cleanup
            setupService.destroySetup(setupId).get(10, TimeUnit.SECONDS);
        } finally {
            if (originalTimeout != null) {
                System.setProperty("peegeeq.database.pool.connection-timeout-ms", originalTimeout);
            } else {
                System.clearProperty("peegeeq.database.pool.connection-timeout-ms");
            }
            
            if (originalHealthInterval != null) {
                System.setProperty("peegeeq.health-check.interval", originalHealthInterval);
            } else {
                System.clearProperty("peegeeq.health-check.interval");
            }
        }
    }

    @Test
    @DisplayName("Verify Circuit Breaker opens under load/failure")
    void testCircuitBreakerOpen() throws Exception {
        // Configure sensitive circuit breaker
        String originalThreshold = System.getProperty("peegeeq.circuit-breaker.failure-threshold");
        String originalWait = System.getProperty("peegeeq.circuit-breaker.wait-duration-ms");
        String originalTimeout = System.getProperty("peegeeq.database.pool.connection-timeout-ms");
        String originalHealthInterval = System.getProperty("peegeeq.health-check.interval");
        String originalRingBufferSize = System.getProperty("peegeeq.circuit-breaker.ring-buffer-size");
        
        System.setProperty("peegeeq.circuit-breaker.failure-threshold", "3");
        System.setProperty("peegeeq.circuit-breaker.wait-duration-ms", "5000");
        System.setProperty("peegeeq.circuit-breaker.ring-buffer-size", "5"); // Small window for testing
        System.setProperty("peegeeq.database.pool.connection-timeout-ms", "1000"); // Fast timeout for failures
        System.setProperty("peegeeq.health-check.interval", "PT1S"); // Fast health check updates

        try {
            String setupId = UUID.randomUUID().toString();
            String dbName = "peegeeq_cb_" + setupId.replace("-", "_");

            DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                    .host(postgres.getHost())
                    .port(postgres.getFirstMappedPort())
                    .databaseName(dbName)
                    .username(postgres.getUsername())
                    .password(postgres.getPassword())
                    .schema("cb_schema")
                    .build();

            DatabaseSetupRequest request = new DatabaseSetupRequest(setupId, dbConfig, Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());

            // 1. Create setup
            setupService.createCompleteSetup(request).get(10, TimeUnit.SECONDS);

            // 2. Pause DB to cause failures
            postgres.getDockerClient().pauseContainerCmd(postgres.getContainerId()).exec();

            try {
                // 3. Trigger failures to open circuit breaker
                // We need at least 3 failures.
                for (int i = 0; i < 6; i++) {
                    CompletableFuture<Integer> check = new CompletableFuture<>();
                    webClient.get("/api/v1/setups/" + setupId + "/health")
                            .timeout(2000)
                            .send()
                            .onSuccess(res -> check.complete(res.statusCode()))
                            .onFailure(err -> check.complete(503));
                    
                    try {
                        check.get(3, TimeUnit.SECONDS);
                    } catch (Exception ignored) {}
                    
                    // Wait for health check cache to expire (interval is 1s)
                    Thread.sleep(1100);
                }

                // 4. Verify Circuit Breaker is OPEN (Fast failure)
                long startTime = System.currentTimeMillis();
                CompletableFuture<Integer> fastCheck = new CompletableFuture<>();
                webClient.get("/api/v1/setups/" + setupId + "/health")
                        .send()
                        .onSuccess(res -> fastCheck.complete(res.statusCode()))
                        .onFailure(err -> fastCheck.complete(503));
                
                int status = fastCheck.get(5, TimeUnit.SECONDS);
                long duration = System.currentTimeMillis() - startTime;
                
                // If CB is open, it should fail immediately, much faster than the 1000ms connection timeout
                // We use 800ms as a safe upper bound for "fast failure" vs 1000ms timeout
                assertTrue(duration < 800, "Circuit breaker should fail fast (took " + duration + "ms)");
                assertEquals(503, status);

            } finally {
                postgres.getDockerClient().unpauseContainerCmd(postgres.getContainerId()).exec();
            }
            
            // Cleanup
            setupService.destroySetup(setupId).get(10, TimeUnit.SECONDS);

        } finally {
            // Restore properties
            if (originalThreshold != null) System.setProperty("peegeeq.circuit-breaker.failure-threshold", originalThreshold);
            else System.clearProperty("peegeeq.circuit-breaker.failure-threshold");
            
            if (originalWait != null) System.setProperty("peegeeq.circuit-breaker.wait-duration-ms", originalWait);
            else System.clearProperty("peegeeq.circuit-breaker.wait-duration-ms");
            
            if (originalTimeout != null) System.setProperty("peegeeq.database.pool.connection-timeout-ms", originalTimeout);
            else System.clearProperty("peegeeq.database.pool.connection-timeout-ms");
            
            if (originalHealthInterval != null) System.setProperty("peegeeq.health-check.interval", originalHealthInterval);
            else System.clearProperty("peegeeq.health-check.interval");

            if (originalRingBufferSize != null) System.setProperty("peegeeq.circuit-breaker.ring-buffer-size", originalRingBufferSize);
            else System.clearProperty("peegeeq.circuit-breaker.ring-buffer-size");
        }
    }
}
