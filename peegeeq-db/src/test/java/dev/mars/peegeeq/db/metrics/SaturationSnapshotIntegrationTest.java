package dev.mars.peegeeq.db.metrics;

/*
 * Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
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

import dev.mars.peegeeq.api.metrics.SetupSaturationSnapshot;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.PeeGeeQManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the core-owned saturation snapshot (telemetry §4 gap G3 — metrics-stack remediation
 * step 1, 2026-08-09).
 *
 * <p>The architecture rule under test: the MANAGER produces the event-loop lag measurement
 * unconditionally, by sampling its OWN Vert.x — the loop that carries this setup's queue work.
 * No collector causes the measurement; the REST layer only reads
 * {@code getSaturationSnapshotForSetup}. The previous implementation sampled the REST server's
 * loop inside the REST handler, which measured the wrong loop and ceased to exist when the
 * REST layer was down.
 *
 * <p>Classification: INTEGRATION — the manager's start path touches the database
 * (TestContainers via {@link BaseIntegrationTest}).
 */
class SaturationSnapshotIntegrationTest extends BaseIntegrationTest {

    private static final long POLL_INTERVAL_MS = 250;
    private static final long POLL_DEADLINE_MS = 10_000;

    /**
     * After start, the snapshot carries a real event-loop lag measurement: the sampler runs as
     * a property of the manager running. Polled rather than slept — the first sample exists
     * only after the first 500 ms tick, and a fixed delay is the "strategic delay" antipattern.
     */
    @Test
    void snapshotCarriesEventLoopLagOnceSampled(Vertx vertx, VertxTestContext testContext) {
        pollForLag(vertx, System.currentTimeMillis() + POLL_DEADLINE_MS)
            .onSuccess(lag -> testContext.verify(() -> {
                assertTrue(lag.maxMs() >= 0.0, "maxMs must be a real measurement: " + lag);
                assertTrue(lag.latestMs() >= 0.0, "latestMs must be a real measurement: " + lag);
                assertTrue(lag.sampleCount() >= 1, "at least one sample must back the figures: " + lag);
                assertEquals(60, lag.windowSeconds(), "window length is part of the contract");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * Reading the snapshot is IDEMPOTENT: a second read still carries the measurement, with at
     * least as much evidence. A read-and-reset accessor would fail this — the first reader
     * would steal the window from every other collector, and the REST layer must stay
     * stateless and idempotent.
     */
    @Test
    void snapshotReadsAreIdempotent(Vertx vertx, VertxTestContext testContext) {
        pollForLag(vertx, System.currentTimeMillis() + POLL_DEADLINE_MS)
            .onSuccess(first -> testContext.verify(() -> {
                SetupSaturationSnapshot.Window second =
                    manager.getSaturationSnapshot().eventLoopLag();
                assertNotNull(second, "the read must not consume the window");
                assertTrue(second.sampleCount() >= first.sampleCount(),
                    "a later read cannot carry LESS evidence: first=" + first + " second=" + second);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * The pool-acquisition canary produces a real acquire-wait sample (remediation step 3):
     * a timed {@code getConnection()} against the manager's own pool, so the figure is a
     * measured wait, not the permanently-zero gauge this replaces. First canary fires at 5 s,
     * so the deadline is longer than the lag poll's.
     */
    @Test
    void snapshotCarriesPoolAcquireWaitOnceCanaryRuns(Vertx vertx, VertxTestContext testContext) {
        pollFor(vertx, System.currentTimeMillis() + ACQUIRE_POLL_DEADLINE_MS,
                "pool-acquisition canary", s -> s.poolAcquireWait())
            .onSuccess(acquire -> testContext.verify(() -> {
                assertTrue(acquire.maxMs() >= 0.0, "maxMs must be a real measurement: " + acquire);
                assertTrue(acquire.sampleCount() >= 1,
                    "at least one canary must back the figures: " + acquire);
                assertEquals(60, acquire.windowSeconds(), "window length is part of the contract");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * A manager that was never started reports ABSENCE, not zero: nothing has sampled, so a
     * zeroed value would claim a perfectly healthy resource on the evidence of nothing.
     */
    @Test
    void unstartedManagerReportsAbsenceNotZero(VertxTestContext testContext) {
        PeeGeeQManager unstarted = new PeeGeeQManager(configuration, new SimpleMeterRegistry());
        SetupSaturationSnapshot snapshot = unstarted.getSaturationSnapshot();
        // The construction already allocated a pool and a Vert.x, so the manager must be
        // closed properly regardless of the assertion outcome.
        unstarted.closeReactive()
            .onSuccess(v -> testContext.verify(() -> {
                assertNotNull(snapshot, "the snapshot object itself always exists");
                assertNull(snapshot.eventLoopLag(),
                    "an unsampled lag must be absent, never a fabricated zero: " + snapshot.eventLoopLag());
                assertNull(snapshot.poolAcquireWait(),
                    "an unsampled acquire-wait must be absent, never a fabricated zero: "
                        + snapshot.poolAcquireWait());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    private static final long ACQUIRE_POLL_DEADLINE_MS = 20_000;

    private Future<SetupSaturationSnapshot.Window> pollForLag(Vertx vertx, long deadline) {
        return pollFor(vertx, deadline, "event-loop lag sampler", s -> s.eventLoopLag());
    }

    /** Recursive vertx.timer poll — no blocking waits, deadline-bounded. */
    private Future<SetupSaturationSnapshot.Window> pollFor(
            Vertx vertx, long deadline, String what,
            java.util.function.Function<SetupSaturationSnapshot, SetupSaturationSnapshot.Window> extract) {
        SetupSaturationSnapshot.Window window = extract.apply(manager.getSaturationSnapshot());
        if (window != null) {
            return Future.succeededFuture(window);
        }
        if (System.currentTimeMillis() >= deadline) {
            return Future.failedFuture(new AssertionError(
                "The manager's " + what + " produced no measurement before the deadline"));
        }
        return vertx.timer(POLL_INTERVAL_MS).compose(t -> pollFor(vertx, deadline, what, extract));
    }
}
