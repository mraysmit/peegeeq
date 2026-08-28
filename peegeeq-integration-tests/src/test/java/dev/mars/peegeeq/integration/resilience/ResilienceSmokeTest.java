package dev.mars.peegeeq.integration.resilience;

import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.integration.SmokeTestBase;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.junit5.Timeout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resilience smoke tests that verify system behaviour when PostgreSQL becomes
 * unavailable and subsequently recovers.
 *
 * <p>Docker pause and unpause calls run on a Vert.x worker. Health transitions
 * are observed through bounded Future polling, and the test context owns the
 * terminal success or failure signal.</p>
 */
@DisplayName("System Resilience Smoke Tests")
@Tag("integration")
@ExtendWith(VertxExtension.class)
public class ResilienceSmokeTest extends SmokeTestBase {

    private static final int HEALTH_POLL_ATTEMPTS = 10;
    private static final long HEALTH_POLL_DELAY_MS = 1_000;

    @Test
    @DisplayName("Verify 503 Service Unavailable when DB connection is lost")
    void testDatabaseConnectionLossReturns503(Vertx vertx, VertxTestContext testContext) {
        String setupId = UUID.randomUUID().toString();
        DatabaseSetupRequest request = setupRequest(
                setupId,
                "peegeeq_resilience_" + setupId.replace("-", "_"),
                "resilience_schema");

        setupService.createCompleteSetup(request)
                .compose(v -> expectHealthStatus(setupId, 200, 500))
                .compose(v -> pauseDatabase(vertx)
                        .compose(ignored -> pollHealthUntil(
                                vertx, setupId, 503, 503, HEALTH_POLL_ATTEMPTS,
                                "Service should return 503 when DB is paused"))
                        .eventually(() -> unpauseDatabase(vertx)))
                .compose(v -> pollHealthUntil(
                        vertx, setupId, 200, 500, HEALTH_POLL_ATTEMPTS,
                        "Service should recover with 200 after DB is unpaused"))
                .eventually(() -> setupService.destroySetup(setupId))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
    @DisplayName("Verify Circuit Breaker opens under load/failure")
    @ExpectedErrorLog(
            logger = "io.vertx.ext.web.handler.LoggerHandler",
            message = "127.0.0.1 - - [",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.NONE,
            minOccurrences = 5,
            maxOccurrences = 13)
    void testCircuitBreakerOpen(Vertx vertx, VertxTestContext testContext) {
        String setupId = UUID.randomUUID().toString();
        DatabaseSetupRequest request = setupRequest(
                setupId,
                "peegeeq_cb_" + setupId.replace("-", "_"),
                "cb_schema");

        setupService.createCompleteSetup(request)
                .compose(v -> pauseDatabase(vertx)
                        .compose(ignored -> triggerCircuitBreakerChecks(vertx, setupId, 12))
                        .compose(ignored -> verifyFastFailure(setupId))
                        .eventually(() -> unpauseDatabase(vertx)))
                .eventually(() -> setupService.destroySetup(setupId))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    private DatabaseSetupRequest setupRequest(String setupId, String databaseName, String schema) {
        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .databaseName(databaseName)
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema(schema)
                .build();

        return new DatabaseSetupRequest(
                setupId,
                dbConfig,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyMap());
    }

    private Future<Void> pauseDatabase(Vertx vertx) {
        return vertx.<Void>executeBlocking(() -> {
            postgres.getDockerClient().pauseContainerCmd(postgres.getContainerId()).exec();
            return null;
        });
    }

    private Future<Void> unpauseDatabase(Vertx vertx) {
        return vertx.<Void>executeBlocking(() -> {
            postgres.getDockerClient().unpauseContainerCmd(postgres.getContainerId()).exec();
            return null;
        });
    }

    private Future<Void> expectHealthStatus(String setupId, int expectedStatus, int failureStatus) {
        return healthStatus(setupId, failureStatus).compose(status -> {
            if (status == expectedStatus) {
                return Future.succeededFuture();
            }
            return Future.failedFuture(new AssertionError(
                    "Expected health status " + expectedStatus + " but received " + status));
        });
    }

    private Future<Integer> pollHealthUntil(
            Vertx vertx,
            String setupId,
            int expectedStatus,
            int failureStatus,
            int attemptsRemaining,
            String failureMessage) {
        return healthStatus(setupId, failureStatus).compose(status -> {
            if (status == expectedStatus) {
                return Future.succeededFuture(status);
            }
            if (attemptsRemaining <= 1) {
                return Future.failedFuture(new AssertionError(
                        failureMessage + "; last status was " + status));
            }
            return vertx.timer(HEALTH_POLL_DELAY_MS)
                    .compose(ignored -> pollHealthUntil(
                            vertx,
                            setupId,
                            expectedStatus,
                            failureStatus,
                            attemptsRemaining - 1,
                            failureMessage));
        });
    }

    private Future<Void> triggerCircuitBreakerChecks(Vertx vertx, String setupId, int checksRemaining) {
        if (checksRemaining == 0) {
            return Future.succeededFuture();
        }
        return healthStatus(setupId, 503)
                .compose(status -> vertx.timer(1_100))
                .compose(ignored -> triggerCircuitBreakerChecks(vertx, setupId, checksRemaining - 1));
    }

    private Future<Void> verifyFastFailure(String setupId) {
        long startNanos = System.nanoTime();
        return healthStatus(setupId, 503).compose(status -> {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            assertTrue(durationMs < 800,
                    "Circuit breaker should fail fast (took " + durationMs + "ms)");
            assertEquals(503, status);
            return Future.succeededFuture();
        });
    }

    private Future<Integer> healthStatus(String setupId, int failureStatus) {
        Promise<Integer> result = Promise.promise();
        webClient.get("/api/v1/setups/" + setupId + "/health")
                .timeout(2_000)
                .send()
                .onSuccess(response -> result.complete(response.statusCode()))
                .onFailure(error -> result.complete(failureStatus));
        return result.future();
    }
}
