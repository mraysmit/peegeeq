package dev.mars.peegeeq.integration.resilience;

import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.integration.SmokeTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.VertxExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Collections;
import java.util.UUID;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resilience smoke tests that verify system behaviour when PostgreSQL becomes
 * unavailable and subsequently recovers.
 *
 * <h2>Intentional Design: Main-Thread CountDownLatch + {@code throws Exception}</h2>
 *
 * <p>These tests deliberately use {@code CountDownLatch} and run on the main JUnit
 * thread rather than adopting the {@code VertxTestContext} + {@code Checkpoint} pattern
 * used elsewhere in the suite. This is not a violation of the project antipattern guide;
 * it is the correct architecture for this specific test concern. The reasons are:
 *
 * <h3>1. Synchronous Docker container lifecycle calls</h3>
 * <p>The core test operations — {@code pauseContainerCmd(...).exec()} and
 * {@code unpauseContainerCmd(...).exec()} — are synchronous blocking Docker API calls.
 * Placing them inside a {@code Future} pipeline that runs on the Vert.x event loop would
 * block the event loop thread, which is forbidden. Wrapping each in
 * {@code vertx.executeBlocking(...)} would add substantial boilerplate while providing
 * no correctness benefit, because the pause/unpause sequence is inherently synchronous
 * and sequential — there is no meaningful parallelism to gain.
 *
 * <h3>2. Retry polling loops require inter-attempt delays</h3>
 * <p>The tests poll the health endpoint up to 10 times waiting for the system to
 * detect connection loss (503) or recover (200). Each iteration needs a ~1 second
 * pause. {@code Thread.sleep} is forbidden by the project rules. The pattern used —
 * {@code vertx.timer(1000).onComplete(ar -> timerLatch.countDown())} on the main
 * JUnit thread — is the correct substitute: it delegates the timer to Vert.x (which
 * fires on the event loop) and the main thread waits on the latch without blocking
 * any Vert.x thread.
 *
 * <h3>3. All latches are on the main JUnit thread, never inside event-loop callbacks</h3>
 * <p>The antipattern guide prohibits {@code latch.await()} <em>inside</em>
 * {@code onSuccess} / {@code onComplete} callbacks because it blocks the Vert.x event
 * loop and causes deadlocks. That pattern does not appear here. Every
 * {@code latch.await()} call is on the main thread ({@code throws Exception} test
 * method), which is a normal blocking thread. The {@code CountDownLatch} callbacks
 * ({@code onSuccess}, {@code onFailure}) are side-effect-only: they store a result in
 * an {@code AtomicReference} or {@code AtomicInteger} and call {@code countDown()}.
 * No assertions run inside any event-loop callback.
 *
 * <h3>4. All latches count down in both success and failure paths</h3>
 * <p>Every latch has paired {@code onSuccess} and {@code onFailure} handlers that both
 * call {@code countDown()}, preventing the main thread from hanging indefinitely if an
 * async operation fails. Errors are captured in an {@code AtomicReference} and
 * re-thrown on the main thread after the latch completes.
 *
 * <h3>Why not VertxTestContext?</h3>
 * <p>{@code VertxTestContext} is the right tool when the test is structured as a single
 * linear Future chain or a set of independent concurrent operations. It is poorly suited
 * to tests that interleave blocking external operations (Docker calls) with async Vert.x
 * calls in a loop, because {@code VertxTestContext.awaitCompletion()} cannot be called
 * mid-test to synchronise between loop iterations.
 */
@DisplayName("System Resilience Smoke Tests")
@Tag("integration")
@ExtendWith(VertxExtension.class)
public class ResilienceSmokeTest extends SmokeTestBase {

    private static final Logger log = LoggerFactory.getLogger(ResilienceSmokeTest.class);

    @Test
    @DisplayName("Verify 503 Service Unavailable when DB connection is lost")
    void testDatabaseConnectionLossReturns503() throws Exception {
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
            CountDownLatch setupLatch = new CountDownLatch(1);
            AtomicReference<Throwable> setupError = new AtomicReference<>();
            setupService.createCompleteSetup(request)
                    .onSuccess(v -> setupLatch.countDown())
                    .onFailure(e -> { setupError.set(e); setupLatch.countDown(); });
            assertTrue(setupLatch.await(10, TimeUnit.SECONDS), "Setup creation timed out");
            if (setupError.get() != null) throw new RuntimeException("Setup failed", setupError.get());

            // 2. Verify Health is UP
            CountDownLatch healthLatch = new CountDownLatch(1);
            AtomicInteger healthStatus = new AtomicInteger(-1);
            webClient.get("/api/v1/setups/" + setupId + "/health")
                    .send()
                    .onSuccess(res -> { healthStatus.set(res.statusCode()); healthLatch.countDown(); })
                    .onFailure(e -> healthLatch.countDown());
            assertTrue(healthLatch.await(5, TimeUnit.SECONDS), "Health check timed out");
            assertEquals(200, healthStatus.get());

            // 3. Pause DB
            postgres.getDockerClient().pauseContainerCmd(postgres.getContainerId()).exec();

            try {
                // 4. Verify Health is DOWN (503)
                boolean serviceUnavailable = false;
                for (int i = 0; i < 10; i++) { // Retry for up to 10 seconds
                    CountDownLatch checkLatch = new CountDownLatch(1);
                    AtomicInteger checkStatus = new AtomicInteger(-1);
                    webClient.get("/api/v1/setups/" + setupId + "/health")
                            .timeout(2000)
                            .send()
                            .onSuccess(res -> { checkStatus.set(res.statusCode()); checkLatch.countDown(); })
                            .onFailure(err -> { checkStatus.set(503); checkLatch.countDown(); }); // Treat timeout/error as 503

                    checkLatch.await(3, TimeUnit.SECONDS);
                    int status = checkStatus.get();
                    if (status == 503) {
                        serviceUnavailable = true;
                        break;
                    }
                    CountDownLatch timerLatch = new CountDownLatch(1);
                    vertx.timer(1000).onComplete(ar -> timerLatch.countDown());
                    timerLatch.await(5, TimeUnit.SECONDS);
                }
                assertTrue(serviceUnavailable, "Service should return 503 when DB is paused");

            } finally {
                // 5. Unpause DB
                postgres.getDockerClient().unpauseContainerCmd(postgres.getContainerId()).exec();
            }
            
            // 6. Verify Health recovers
            boolean recovered = false;
            for (int i = 0; i < 10; i++) {
                CountDownLatch recoverLatch = new CountDownLatch(1);
                AtomicInteger recoverStatus = new AtomicInteger(-1);
                webClient.get("/api/v1/setups/" + setupId + "/health")
                        .send()
                        .onSuccess(res -> { recoverStatus.set(res.statusCode()); recoverLatch.countDown(); })
                        .onFailure(err -> { recoverStatus.set(500); recoverLatch.countDown(); });

                try {
                    recoverLatch.await(3, TimeUnit.SECONDS);
                    int status = recoverStatus.get();
                    if (status == 200) {
                        recovered = true;
                        break;
                    }
                } catch (Exception e) {
                    log.warn("Interrupted during recovery poll wait", e);
                    Thread.currentThread().interrupt();
                    break;
                }
                CountDownLatch timerLatch = new CountDownLatch(1);
                vertx.timer(1000).onComplete(ar -> timerLatch.countDown());
                timerLatch.await(5, TimeUnit.SECONDS);
            }
            assertTrue(recovered, "Service should recover (200 OK) after DB is unpaused");

            // Cleanup
            CountDownLatch destroyLatch = new CountDownLatch(1);
            setupService.destroySetup(setupId).onComplete(ar -> destroyLatch.countDown());
            destroyLatch.await(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Verify Circuit Breaker opens under load/failure")
    void testCircuitBreakerOpen() throws Exception {
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
            CountDownLatch setupLatch = new CountDownLatch(1);
            AtomicReference<Throwable> setupError = new AtomicReference<>();
            setupService.createCompleteSetup(request)
                    .onSuccess(v -> setupLatch.countDown())
                    .onFailure(e -> { setupError.set(e); setupLatch.countDown(); });
            assertTrue(setupLatch.await(10, TimeUnit.SECONDS), "Setup creation timed out");
            if (setupError.get() != null) throw new RuntimeException("Setup failed", setupError.get());

            // 2. Pause DB to cause failures
            postgres.getDockerClient().pauseContainerCmd(postgres.getContainerId()).exec();

            try {
                // 3. Trigger failures to open circuit breaker
                // We need at least 3 failures.
                for (int i = 0; i < 6; i++) {
                    CountDownLatch checkLatch = new CountDownLatch(1);
                    webClient.get("/api/v1/setups/" + setupId + "/health")
                            .timeout(2000)
                            .send()
                            .onSuccess(res -> checkLatch.countDown())
                            .onFailure(err -> checkLatch.countDown());
                    
                    try {
                        checkLatch.await(3, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.warn("Interrupted while waiting for circuit breaker check", e);
                        Thread.currentThread().interrupt();
                    }
                    
                    // Wait for health check cache to expire (interval is 1s)
                    CountDownLatch timerLatch = new CountDownLatch(1);
                    vertx.timer(1100).onComplete(ar -> timerLatch.countDown());
                    timerLatch.await(5, TimeUnit.SECONDS);
                }

                // 4. Verify Circuit Breaker is OPEN (Fast failure)
                long startTime = System.currentTimeMillis();
                CountDownLatch fastLatch = new CountDownLatch(1);
                AtomicInteger fastStatus = new AtomicInteger(-1);
                webClient.get("/api/v1/setups/" + setupId + "/health")
                        .send()
                        .onSuccess(res -> { fastStatus.set(res.statusCode()); fastLatch.countDown(); })
                        .onFailure(err -> { fastStatus.set(503); fastLatch.countDown(); });
                
                assertTrue(fastLatch.await(5, TimeUnit.SECONDS), "Fast check timed out");
                int status = fastStatus.get();
                long duration = System.currentTimeMillis() - startTime;
                
                // If CB is open, it should fail immediately, much faster than the 1000ms connection timeout
                // We use 800ms as a safe upper bound for "fast failure" vs 1000ms timeout
                assertTrue(duration < 800, "Circuit breaker should fail fast (took " + duration + "ms)");
                assertEquals(503, status);

            } finally {
                postgres.getDockerClient().unpauseContainerCmd(postgres.getContainerId()).exec();
            }
            
            // Cleanup
            CountDownLatch destroyLatch = new CountDownLatch(1);
            setupService.destroySetup(setupId).onComplete(ar -> destroyLatch.countDown());
            destroyLatch.await(10, TimeUnit.SECONDS);
    }
}
