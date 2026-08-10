package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.rest.support.ControllableSetupService;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the /sse/metrics cadence contract (metrics-stack remediation step 6, 2026-08-09):
 * <b>jitter is a one-off PHASE offset, never an addition to the period.</b>
 *
 * <p>The defect this locks out: the stream computed
 * {@code intervalMs = interval * 1000 + jitter} and passed THAT as the {@code setPeriodic}
 * period, so a random jitter draw permanently slowed every connection — measured live at
 * 0.52 Hz against a 1 Hz request. The fix passes jitter as {@code setPeriodic}'s initial
 * delay and keeps the period at exactly what the client asked for.
 *
 * <p><b>Why this pin is deterministic where a naive one is flaky:</b> the handler is
 * constructed directly with {@code jitterMs = 5000} — five times the requested 1 s interval.
 * Gaps BETWEEN periodic events are then ~1 s under the fix and ~6 s under the period bug, so
 * a 3.5 s threshold sits wide of both and cannot be crossed by scheduler noise. The first
 * event (the immediate initial send) and the first periodic gap (which legitimately carries
 * the phase offset) are excluded from the assertion.
 *
 * <p>Classification: CORE — {@link ControllableSetupService#defaults()} reports zero setups,
 * so the collection is JVM-only; no database, no containers.
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
class SseMetricsCadencePinTest {

    private static final Logger logger = LoggerFactory.getLogger(SseMetricsCadencePinTest.class);

    /** Wide of both worlds: fixed ~1000 ms, period-bug ~6000 ms. */
    private static final long MAX_PERIODIC_GAP_MS = 3_500;
    private static final int EVENTS_NEEDED = 4; // initial + 3 periodic → 2 pure-period gaps

    @Test
    void jitterOffsetsThePhaseNotThePeriod(Vertx vertx, VertxTestContext testContext) {
        RestServerConfig.MonitoringConfig config = new RestServerConfig.MonitoringConfig(
            1000,   // maxConnections
            10,     // maxConnectionsPerIp
            1,      // defaultIntervalSeconds
            1,      // minIntervalSeconds
            60,     // maxIntervalSeconds
            300_000, // idleTimeoutMs
            5_000,  // cacheTtlMs
            5_000   // jitterMs — 5x the interval, so a period-added jitter CANNOT hide
        );
        SystemMonitoringHandler handler = new SystemMonitoringHandler(
            ControllableSetupService.defaults(), vertx, config, new SimpleMeterRegistry());

        Router router = Router.router(vertx);
        router.get("/sse/metrics").handler(handler::handleSSEMetrics);

        List<Long> metricsEventNanos = new ArrayList<>();

        vertx.createHttpServer().requestHandler(router).listen(0)
            .onSuccess(server -> {
                int port = server.actualPort();
                HttpClient client = vertx.createHttpClient();
                client.request(HttpMethod.GET, port, "localhost", "/sse/metrics?interval=1")
                    .compose(req -> req.send())
                    .onSuccess(resp -> {
                        StringBuilder buffer = new StringBuilder();
                        resp.handler(chunk -> {
                            buffer.append(chunk.toString());
                            int idx;
                            while ((idx = buffer.indexOf("\n\n")) != -1) {
                                String rawEvent = buffer.substring(0, idx);
                                buffer.delete(0, idx + 2);
                                if (!rawEvent.contains("event: metrics")) {
                                    continue;
                                }
                                metricsEventNanos.add(System.nanoTime());
                                if (metricsEventNanos.size() < EVENTS_NEEDED) {
                                    continue;
                                }
                                testContext.verify(() -> {
                                    // Events: [0] immediate initial send, [1] first periodic
                                    // tick (carries the phase offset — excluded), [2],[3]
                                    // pure-period ticks. Assert the two pure-period gaps.
                                    for (int i = 2; i < EVENTS_NEEDED; i++) {
                                        long gapMs = (metricsEventNanos.get(i)
                                            - metricsEventNanos.get(i - 1)) / 1_000_000L;
                                        logger.info("periodic gap {}: {} ms", i - 1, gapMs);
                                        assertTrue(gapMs <= MAX_PERIODIC_GAP_MS,
                                            "Periodic gap of " + gapMs + " ms against a requested"
                                                + " 1000 ms interval — jitter is inflating the"
                                                + " PERIOD again (the 0.52 Hz defect). It must be"
                                                + " an initial-delay phase offset only.");
                                    }
                                    client.close();
                                    handler.close();
                                    server.close();
                                    testContext.completeNow();
                                });
                            }
                        });
                    })
                    .onFailure(testContext::failNow);
            })
            .onFailure(testContext::failNow);
    }
}
