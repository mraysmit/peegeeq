package dev.mars.peegeeq.db.cleanup;

import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector.DetectionResult;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector.SubscriptionSummary;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
class DeadConsumerDetectionJobFailureHealthCoreTest {

    private Vertx vertx;
    private PgConnectionManager connectionManager;
    private ControlledDetector detector;
    private DeadConsumerDetectionJob job;

    @BeforeEach
    void setUp(Vertx vertx) {
        this.vertx = vertx;
        connectionManager = new PgConnectionManager(vertx);
        detector = new ControlledDetector(connectionManager);
        job = new DeadConsumerDetectionJob(
                vertx, detector, new NoOpCleanup(connectionManager), 60_000L);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        Future.<Void>succeededFuture()
                .compose(v -> job != null ? job.stop() : Future.succeededFuture())
                .compose(v -> connectionManager != null ? connectionManager.close() : Future.succeededFuture())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void failureHealthDegradesAndThenRecovers(VertxTestContext testContext) {
        Promise<DetectionResult> gate = Promise.promise();
        detector.deferNextRun(gate);

        job.start();
        gate.fail(new RuntimeException("forced detection failure"));

        awaitCondition(() -> job.getTotalFailures() == 1, System.currentTimeMillis() + 2_000)
                .compose(v -> job.checkHealth())
                .compose(status -> {
                    assertTrue(status.isDegraded(), "First detection failure should degrade health");
                    assertEquals(1L, status.getDetails().get("consecutiveFailures"));
                    return job.stop();
                })
                .compose(v -> {
                    job.start();
                    return awaitCondition(() -> job.getTotalRunCount() >= 2,
                            System.currentTimeMillis() + 2_000);
                })
                .compose(v -> job.checkHealth())
                .compose(status -> {
                    assertTrue(status.isHealthy(), "A successful detection run should restore health");
                    return job.stop();
                })
                .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }

    private Future<Void> awaitCondition(BooleanSupplier condition, long deadlineMs) {
        if (condition.getAsBoolean()) {
            return Future.succeededFuture();
        }
        if (System.currentTimeMillis() >= deadlineMs) {
            return Future.failedFuture(new AssertionError("Condition was not met before deadline"));
        }
        return vertx.timer(10).compose(ignored -> awaitCondition(condition, deadlineMs));
    }

    private static final class ControlledDetector extends DeadConsumerDetector {
        private Promise<DetectionResult> deferred;

        private ControlledDetector(PgConnectionManager connectionManager) {
            super(connectionManager, "failure-health-test");
        }

        void deferNextRun(Promise<DetectionResult> deferred) {
            this.deferred = deferred;
        }

        @Override
        public Future<DetectionResult> detectAllDeadSubscriptionsWithDetails() {
            Promise<DetectionResult> current = deferred;
            deferred = null;
            if (current != null) {
                return current.future();
            }
            return Future.succeededFuture(new DetectionResult(List.of(), 0, 0, 0));
        }

        @Override
        public Future<SubscriptionSummary> getSubscriptionSummary() {
            return Future.succeededFuture(new SubscriptionSummary(0, 0, 0, 0, 0, 0));
        }
    }

    private static final class NoOpCleanup extends DeadConsumerGroupCleanup {
        private NoOpCleanup(PgConnectionManager connectionManager) {
            super(connectionManager, "failure-health-test");
        }

        @Override
        public Future<List<CleanupResult>> cleanupAllDeadGroups() {
            return Future.succeededFuture(Collections.emptyList());
        }
    }
}
