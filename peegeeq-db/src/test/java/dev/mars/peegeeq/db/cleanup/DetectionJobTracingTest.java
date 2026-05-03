package dev.mars.peegeeq.db.cleanup;

import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector.DeadSubscriptionInfo;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector.DetectionResult;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector.SubscriptionSummary;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for tracing propagation in DeadConsumerDetectionJob.
 *
 * <p>Verifies that trace context is correctly propagated through the
 * entire detection→cleanup→summary compose chain, ensuring all log
 * operations include traceId/spanId in MDC.</p>
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
public class DetectionJobTracingTest {

    private Vertx vertx;
    private PgConnectionManager stubConnectionManager;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        stubConnectionManager = new PgConnectionManager(vertx);
        TraceContextUtil.clearTraceMDC();
    }

    @AfterEach
    void tearDown() {
        TraceContextUtil.clearTraceMDC();
        if (stubConnectionManager != null) {
            stubConnectionManager.close();
        }
        if (vertx != null) {
            vertx.close();
        }
    }

    @Test
    void runDetection_createsTraceForEachRun(VertxTestContext testContext) {
        DeadConsumerDetector stubDetector = new StubDetector(
                new DetectionResult(List.of(), 0, 0, 0),
                new SubscriptionSummary(0, 0, 0, 0, 0, 0));
        DeadConsumerGroupCleanup stubCleanup = new StubCleanup();
        DeadConsumerDetectionJob job = new DeadConsumerDetectionJob(
                vertx, stubDetector, stubCleanup, 60_000);

        // Chain two sequential detection runs
        job.runDetectionOnce()
                .compose(firstResult -> {
                    assertEquals(0, firstResult, "Healthy run should return 0 dead consumers");
                    return job.runDetectionOnce();
                })
                .onSuccess(secondResult -> testContext.verify(() -> {
                    assertEquals(0, secondResult, "Second healthy run should also return 0");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void runDetection_mdcCleanAfterCompletion(VertxTestContext testContext) {
        DeadConsumerDetector stubDetector = new StubDetector(
                new DetectionResult(List.of(), 0, 0, 0),
                new SubscriptionSummary(0, 0, 0, 0, 0, 0));
        DeadConsumerGroupCleanup stubCleanup = new StubCleanup();
        DeadConsumerDetectionJob job = new DeadConsumerDetectionJob(
                vertx, stubDetector, stubCleanup, 60_000);

        job.runDetectionOnce()
                .onSuccess(result -> testContext.verify(() -> {
                    assertNull(MDC.get("traceId"), "traceId should not leak into MDC after detection");
                    assertNull(MDC.get("spanId"), "spanId should not leak into MDC after detection");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void runDetection_withDeadConsumers_tracesPropagatedThroughCleanup(VertxTestContext testContext) {
        DetectionResult resultWithDead = new DetectionResult(
                List.of(new DeadSubscriptionInfo(
                        "test-topic", "dead-group",
                        OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5),
                        30, Duration.ofMinutes(4))),
                1, 1, 50);
        SubscriptionSummary summary = new SubscriptionSummary(1, 0, 1, 0, 1, 2);

        DeadConsumerDetector stubDetector = new StubDetector(resultWithDead, summary);
        StubCleanup stubCleanup = new StubCleanup();
        DeadConsumerDetectionJob job = new DeadConsumerDetectionJob(
                vertx, stubDetector, stubCleanup, 60_000);

        job.runDetectionOnce()
                .onSuccess(deadCount -> testContext.verify(() -> {
                    assertEquals(1, deadCount, "Should detect 1 dead consumer");
                    assertTrue(stubCleanup.wasCleanupCalled(), "Cleanup should have been called");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void runDetection_failurePreservesTraceInErrorLog(VertxTestContext testContext) {
        DeadConsumerDetector failingDetector = new StubDetector(true);
        DeadConsumerGroupCleanup stubCleanup = new StubCleanup();
        DeadConsumerDetectionJob job = new DeadConsumerDetectionJob(
                vertx, failingDetector, stubCleanup, 60_000);

        job.runDetectionOnceWithDetails()
                .onSuccess(result -> testContext.failNow(new AssertionError("Expected failure from failing detector")))
                .onFailure(throwable -> testContext.verify(() -> {
                    assertTrue(throwable.getMessage().contains("Simulated detection failure"));
                    assertNull(MDC.get("traceId"), "traceId should not leak after failed detection");
                    testContext.completeNow();
                }));
    }

    @Test
    void start_stop_lifecycle_cleansUpTrace() {
        DeadConsumerDetector stubDetector = new StubDetector(
                new DetectionResult(List.of(), 0, 0, 0),
                new SubscriptionSummary(0, 0, 0, 0, 0, 0));
        DeadConsumerGroupCleanup stubCleanup = new StubCleanup();

        DeadConsumerDetectionJob job = new DeadConsumerDetectionJob(
                vertx, stubDetector, stubCleanup, 60_000);

        job.start();
        assertTrue(job.isRunning());

        job.stop();
        assertFalse(job.isRunning());

        assertNull(MDC.get("traceId"));
    }

    // ---- Stubs ----

    private class StubDetector extends DeadConsumerDetector {
        private final DetectionResult result;
        private final SubscriptionSummary summary;
        private final boolean shouldFail;

        StubDetector(DetectionResult result, SubscriptionSummary summary) {
            super(stubConnectionManager, "stub");
            this.result = result;
            this.summary = summary;
            this.shouldFail = false;
        }

        StubDetector(boolean shouldFail) {
            super(stubConnectionManager, "stub");
            this.result = null;
            this.summary = null;
            this.shouldFail = shouldFail;
        }

        @Override
        public Future<DetectionResult> detectAllDeadSubscriptionsWithDetails() {
            if (shouldFail) {
                return Future.failedFuture(new RuntimeException("Simulated detection failure"));
            }
            return Future.succeededFuture(result);
        }

        @Override
        public Future<List<BlockedMessageStats>> getBlockedMessageStats() {
            return Future.succeededFuture(Collections.emptyList());
        }

        @Override
        public Future<SubscriptionSummary> getSubscriptionSummary() {
            return Future.succeededFuture(summary);
        }
    }

    private class StubCleanup extends DeadConsumerGroupCleanup {
        private volatile boolean cleanupCalled = false;

        StubCleanup() {
            super(stubConnectionManager, "stub");
        }

        @Override
        public Future<List<CleanupResult>> cleanupAllDeadGroups() {
            cleanupCalled = true;
            return Future.succeededFuture(Collections.emptyList());
        }

        boolean wasCleanupCalled() {
            return cleanupCalled;
        }
    }
}
