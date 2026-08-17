package dev.mars.peegeeq.db.consumer;

import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@Tag(TestCategories.CORE)
class WatermarkJobLifecycleTest {

    @Test
    void stopDoesNotReturnBeforeInFlightSweepSettles(Vertx vertx, VertxTestContext testContext) {
        PgConnectionManager connectionManager = new PgConnectionManager(vertx, null);
        ControlledWatermarkCalculator calculator = new ControlledWatermarkCalculator(connectionManager);
        WatermarkJob job = new WatermarkJob(vertx, calculator, "lifecycle-topic", 60_000L);

        job.start();
        assertTrue(calculator.started(), "The immediate watermark sweep should be in flight");

        Future<Void> stopFuture = job.stopAsync();
        assertFalse(stopFuture.isComplete(), "stopAsync() should wait for the in-flight sweep");
        calculator.complete(1);

        stopFuture
                .map(ignored -> {
                    assertEquals(1L, job.getTotalRunCount(),
                            "The settled in-flight sweep should be included before stop completes");
                    assertEquals(1L, job.getTotalSwept());
                    assertFalse(job.isRunning());
                    return (Void) null;
                })
                .eventually(connectionManager::close)
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void stopDoesNotReplayAnAlreadyObservedSweepFailure(Vertx vertx, VertxTestContext testContext) {
        PgConnectionManager connectionManager = new PgConnectionManager(vertx, null);
        ControlledWatermarkCalculator calculator = new ControlledWatermarkCalculator(connectionManager);
        WatermarkJob job = new WatermarkJob(vertx, calculator, "failed-lifecycle-topic", 60_000L);

        job.start();
        calculator.fail(new IllegalStateException("injected transient sweep failure"));

        job.stopAsync()
                .map(ignored -> {
                    assertEquals(1L, job.getTotalRunCount());
                    assertEquals(0L, job.getTotalSwept());
                    assertFalse(job.isRunning());
                    return (Void) null;
                })
                .eventually(connectionManager::close)
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    private static final class ControlledWatermarkCalculator extends WatermarkCalculator {
        private final Promise<Integer> sweep = Promise.promise();
        private boolean started;

        private ControlledWatermarkCalculator(PgConnectionManager connectionManager) {
            super(connectionManager, "unused-service");
        }

        @Override
        public Future<Integer> calculateAndSweep(String topic) {
            started = true;
            return sweep.future();
        }

        private boolean started() {
            return started;
        }

        private void complete(int swept) {
            sweep.complete(swept);
        }

        private void fail(Throwable failure) {
            sweep.fail(failure);
        }
    }
}
