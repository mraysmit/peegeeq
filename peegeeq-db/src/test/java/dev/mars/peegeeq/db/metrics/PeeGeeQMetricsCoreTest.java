package dev.mars.peegeeq.db.metrics;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE tests for PeeGeeQMetrics using TestContainers.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
public class PeeGeeQMetricsCoreTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQMetricsCoreTest.class);

    private PgConnectionManager connectionManager;
    private Pool pool;
    private PeeGeeQMetrics metrics;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        connectionManager = new PgConnectionManager(manager.getVertx());
        
        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).shared(false).idleTimeout(Duration.ofSeconds(2)).connectionTimeout(Duration.ofSeconds(5)).build();
        pool = connectionManager.getOrCreateReactivePool("test-metrics", connectionConfig, poolConfig);
        
        metrics = new PeeGeeQMetrics(pool, "test-instance");
        meterRegistry = new SimpleMeterRegistry();
        metrics.bindTo(meterRegistry);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            connectionManager.close()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    @Test
    void testPeeGeeQMetricsCreation() {
        assertNotNull(metrics);
    }

    @Test
    void testBindTo() {
        // Verify counters are registered
        Counter messagesSent = meterRegistry.find("peegeeq.messages.sent").counter();
        assertNotNull(messagesSent);

        Counter messagesReceived = meterRegistry.find("peegeeq.messages.received").counter();
        assertNotNull(messagesReceived);

        Counter messagesProcessed = meterRegistry.find("peegeeq.messages.processed").counter();
        assertNotNull(messagesProcessed);

        Counter messagesFailed = meterRegistry.find("peegeeq.messages.failed").counter();
        assertNotNull(messagesFailed);

        Counter messagesDeadLettered = meterRegistry.find("peegeeq.messages.dead_lettered").counter();
        assertNotNull(messagesDeadLettered);

        // peegeeq.messages.retried and peegeeq.database.operation.time are deliberately
        // NOT among the registered meters (deleted 2026-08-09, never fed) — their absence
        // is pinned by deadMetersAreNotFabricated.

        // Verify timers are registered
        Timer messageProcessingTime = meterRegistry.find("peegeeq.message.processing.time").timer();
        assertNotNull(messageProcessingTime);

        Timer connectionAcquisitionTime = meterRegistry.find("peegeeq.connection.acquisition.time").timer();
        assertNotNull(connectionAcquisitionTime);

        // Verify gauges are registered. The peegeeq.connection.pool.* gauges are deliberately
        // NOT among them (deleted 2026-08-09, never fed) — their absence is pinned by
        // connectionPoolGaugesAreNotFabricated.
        Gauge outboxQueueDepth = meterRegistry.find("peegeeq.queue.depth.outbox").gauge();
        assertNotNull(outboxQueueDepth);

        Gauge nativeQueueDepth = meterRegistry.find("peegeeq.queue.depth.native").gauge();
        assertNotNull(nativeQueueDepth);

        Gauge deadLetterQueueDepth = meterRegistry.find("peegeeq.queue.depth.dead_letter").gauge();
        assertNotNull(deadLetterQueueDepth);
    }

    @Test
    void testRecordMessageSent() {
        Counter counter = meterRegistry.find("peegeeq.messages.sent").counter();
        double before = counter.count();

        metrics.recordMessageSent("test-topic");

        double after = counter.count();
        assertEquals(before + 1, after);
    }

    @Test
    void testRecordMessageSentWithDuration() {
        Counter counter = meterRegistry.find("peegeeq.messages.sent").counter();
        double before = counter.count();

        metrics.recordMessageSent("test-topic", 100);

        double after = counter.count();
        assertEquals(before + 1, after);

        // Verify timing was recorded
        Timer timer = meterRegistry.find("peegeeq.message.send.time").tag("topic", "test-topic").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    void testRecordMessageReceived() {
        Counter counter = meterRegistry.find("peegeeq.messages.received").counter();
        double before = counter.count();

        metrics.recordMessageReceived("test-topic");

        double after = counter.count();
        assertEquals(before + 1, after);
    }

    @Test
    void testRecordMessageProcessed() {
        Counter counter = meterRegistry.find("peegeeq.messages.processed").counter();
        double before = counter.count();

        metrics.recordMessageProcessed("test-topic", java.time.Duration.ofMillis(100));

        double after = counter.count();
        assertEquals(before + 1, after);
    }

    @Test
    void testRecordMessageFailed() {
        Counter counter = meterRegistry.find("peegeeq.messages.failed").counter();
        double before = counter.count();

        metrics.recordMessageFailed("test-topic", "test-error");

        double after = counter.count();
        assertEquals(before + 1, after);
    }

    @Test
    void testRecordMessageDeadLettered() {
        Counter counter = meterRegistry.find("peegeeq.messages.dead_lettered").counter();
        double before = counter.count();

        metrics.recordMessageDeadLettered("test-topic", "test-reason");

        double after = counter.count();
        assertEquals(before + 1, after);
    }

    @Test
    void testRecordConnectionAcquisition() {
        Timer timer = meterRegistry.find("peegeeq.connection.acquisition.time").timer();
        long before = timer.count();

        metrics.recordConnectionAcquisition(java.time.Duration.ofMillis(25));

        long after = timer.count();
        assertEquals(before + 1, after);
    }

    /**
     * Regression lock (metrics-stack review backlog, 2026-08-09): the retried counter and
     * the database-operation timer were deleted because no production code ever fed them —
     * both permanently reported 0. Re-registering either without a real feed would
     * reintroduce the fabrication, so their ABSENCE is asserted.
     */
    @Test
    void deadMetersAreNotFabricated() {
        assertNull(meterRegistry.find("peegeeq.messages.retried").counter(),
            "peegeeq.messages.retried was a never-fed, permanently-zero counter; do not re-register it without a real feed");
        assertNull(meterRegistry.find("peegeeq.database.operation.time").timer(),
            "peegeeq.database.operation.time was a never-fed, permanently-zero timer; do not re-register it without a real feed");
    }

    /**
     * Regression lock (metrics-stack remediation, 2026-08-09): the three
     * peegeeq.connection.pool.* gauges were deleted because nothing ever fed them — they
     * permanently reported 0, a fabricated healthy signal. Re-registering them without a real
     * feed would reintroduce the fabrication, so their ABSENCE is asserted.
     */
    @Test
    void connectionPoolGaugesAreNotFabricated() {
        org.junit.jupiter.api.Assertions.assertNull(
            meterRegistry.find("peegeeq.connection.pool.active").gauge(),
            "peegeeq.connection.pool.active was a never-fed, permanently-zero gauge; do not re-register it without a real feed");
        org.junit.jupiter.api.Assertions.assertNull(
            meterRegistry.find("peegeeq.connection.pool.idle").gauge(),
            "peegeeq.connection.pool.idle was a never-fed, permanently-zero gauge; do not re-register it without a real feed");
        org.junit.jupiter.api.Assertions.assertNull(
            meterRegistry.find("peegeeq.connection.pool.pending").gauge(),
            "peegeeq.connection.pool.pending was a never-fed, permanently-zero gauge; do not re-register it without a real feed");
    }

    // testRecordTimer, testIncrementCounter, the three testRecordGauge tests and
    // testGetAllMetrics were deleted 2026-08-09 with the generic pass-through metrics
    // surface itself (zero production callers, metrics-stack review backlog).

    @Test
    void testIsHealthy(VertxTestContext testContext) {
        metrics.isHealthy()
            .onComplete(testContext.succeeding(healthy -> testContext.verify(() -> {
                    assertNotNull(healthy);
                    testContext.completeNow();
                })));
    }

    // testPersistMetrics deleted 2026-08-09 with persistMetrics itself.

    // \u2500\u2500 Processing-time percentiles (telemetry G1 \u2014 Phase T.1) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    @Test
    void testProcessingTimePercentilesFromRecordedDurations() {
        // 100 durations of 1..100 ms: the percentiles must exist, sit within
        // the recorded range in order, and count every sample.
        for (int ms = 1; ms <= 100; ms++) {
            metrics.recordMessageProcessed("percentile-topic", Duration.ofMillis(ms));
        }

        var percentiles = metrics.getProcessingTimePercentiles("percentile-topic");

        assertNotNull(percentiles, "Recorded durations must yield a distribution");
        assertEquals(100, percentiles.sampleCount());
        assertTrue(percentiles.p50Ms() > 0, "p50 must be positive");
        assertTrue(percentiles.p50Ms() <= percentiles.p95Ms(), "p50 must not exceed p95");
        assertTrue(percentiles.p95Ms() <= percentiles.p99Ms(), "p95 must not exceed p99");
        // Micrometer percentile histograms are approximate; bound loosely
        // around the true values (p50=50, p99=99) rather than pinning them.
        assertTrue(percentiles.p50Ms() >= 25 && percentiles.p50Ms() <= 75,
            "p50 " + percentiles.p50Ms() + " must approximate the median of 1..100 ms");
        assertTrue(percentiles.p99Ms() <= 200,
            "p99 " + percentiles.p99Ms() + " must stay near the recorded range");
        assertTrue(percentiles.meanMs() > 0, "mean must be positive");
    }

    @Test
    void testProcessingTimePercentilesAbsentForUnrecordedTopic() {
        // "No data" is null, never zeroed values \u2014 a 0 ms tail is a claim.
        assertNull(metrics.getProcessingTimePercentiles("never-recorded-topic"));
    }

    @Test
    void testProcessingTimePercentilesAbsentWithoutRegistry() {
        // Dependency failure mode: metrics created but bindTo() never called.
        PeeGeeQMetrics unbound = new PeeGeeQMetrics(pool, "unbound-instance");

        assertNull(unbound.getProcessingTimePercentiles("any-topic"));
    }

    // ── Delivery-latency percentiles (telemetry G2 — Phase T.2) ─────────────

    @Test
    void testDeliveryLatencyPercentilesFromRecordedLatencies() {
        for (int ms = 1; ms <= 100; ms++) {
            metrics.recordMessageDeliveryLatency("delivery-topic", "native", Duration.ofMillis(ms));
        }

        var percentiles = metrics.getDeliveryLatencyPercentiles("delivery-topic");

        assertNotNull(percentiles, "Recorded latencies must yield a distribution");
        assertEquals(100, percentiles.sampleCount());
        assertTrue(percentiles.p50Ms() <= percentiles.p95Ms(), "p50 must not exceed p95");
        assertTrue(percentiles.p95Ms() <= percentiles.p99Ms(), "p95 must not exceed p99");
    }

    @Test
    void testDeliveryLatencyPercentilesAbsentForUnrecordedTopic() {
        assertNull(metrics.getDeliveryLatencyPercentiles("never-recorded-topic"));
    }

    @Test
    void testDeliveryLatencyDistinctFromProcessingTime() {
        // The two distributions answer different questions (G1 handler time vs
        // G2 enqueue→claim); one topic's recordings must not cross-feed.
        metrics.recordMessageProcessed("distinct-topic", Duration.ofMillis(5));
        metrics.recordMessageDeliveryLatency("distinct-topic", "outbox", Duration.ofMillis(700));

        var processing = metrics.getProcessingTimePercentiles("distinct-topic");
        var delivery = metrics.getDeliveryLatencyPercentiles("distinct-topic");

        assertNotNull(processing);
        assertNotNull(delivery);
        assertEquals(1, processing.sampleCount());
        assertEquals(1, delivery.sampleCount());
        assertTrue(processing.p99Ms() < delivery.p50Ms(),
            "the 5 ms processing sample must not absorb the 700 ms delivery sample");
    }

    @Test
    void testProcessingTimePercentilesScopedPerTopic() {
        // Distributions are per queue: topic B's recording must not leak into
        // topic A's distribution (the per-queue contract of telemetry G1).
        metrics.recordMessageProcessed("topic-a", Duration.ofMillis(10));
        metrics.recordMessageProcessed("topic-b", Duration.ofMillis(500));

        var a = metrics.getProcessingTimePercentiles("topic-a");
        var b = metrics.getProcessingTimePercentiles("topic-b");

        assertNotNull(a);
        assertNotNull(b);
        assertEquals(1, a.sampleCount());
        assertEquals(1, b.sampleCount());
        assertTrue(a.p99Ms() < b.p50Ms(),
            "topic-a (10 ms) must not absorb topic-b's 500 ms sample");
    }

}

