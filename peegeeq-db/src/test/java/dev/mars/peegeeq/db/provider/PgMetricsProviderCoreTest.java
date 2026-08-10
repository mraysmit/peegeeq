package dev.mars.peegeeq.db.provider;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE tests for PgMetricsProvider using TestContainers.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
public class PgMetricsProviderCoreTest extends BaseIntegrationTest {

    private PgConnectionManager connectionManager;
    private Pool pool;
    private PeeGeeQMetrics metrics;
    private PgMetricsProvider metricsProvider;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() throws Exception {
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
        pool = connectionManager.getOrCreateReactivePool("test-metrics-provider", connectionConfig, poolConfig);
        
        metrics = new PeeGeeQMetrics(pool, "test-instance");
        meterRegistry = new SimpleMeterRegistry();
        metrics.bindTo(meterRegistry);
        
        metricsProvider = new PgMetricsProvider(metrics);
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
    void testPgMetricsProviderCreation() {
        assertNotNull(metricsProvider);
    }

    @Test
    void testRecordMessageSent() {
        metricsProvider.recordMessageSent("test-topic");
        // Verify no exception thrown
    }

    @Test
    void testRecordMessageReceived() {
        metricsProvider.recordMessageReceived("test-topic");
        // Verify no exception thrown
    }

    @Test
    void testRecordMessageProcessed() {
        metricsProvider.recordMessageProcessed("test-topic", Duration.ofMillis(100));
        // Verify no exception thrown
    }

    @Test
    void testRecordMessageFailed() {
        metricsProvider.recordMessageFailed("test-topic", "test error");
        // Verify no exception thrown
    }

    @Test
    void testRecordMessageDeadLettered() {
        metricsProvider.recordMessageDeadLettered("test-topic", "max retries exceeded");
        // Verify no exception thrown
    }

    // The incrementCounter/recordGauge/recordTimer/getAllMetrics tests were deleted
    // 2026-08-09 with the generic pass-through metrics surface itself (zero production
    // callers, metrics-stack review backlog).

    @Test
    void testGetInstanceId() {
        String instanceId = metricsProvider.getInstanceId();
        assertEquals("test-instance", instanceId);
    }

    // ── Processing-time percentiles (telemetry G1 — Phase T.1) ──────────────

    @Test
    void testGetProcessingTimePercentilesDelegates() {
        metricsProvider.recordMessageProcessed("delegate-topic", Duration.ofMillis(42));

        var percentiles = metricsProvider.getProcessingTimePercentiles("delegate-topic");

        assertNotNull(percentiles, "The provider must surface the wrapped metrics' distribution");
        assertEquals(1, percentiles.sampleCount());
        assertTrue(percentiles.p50Ms() > 0);
    }

    @Test
    void testGetProcessingTimePercentilesNullForUnrecordedTopic() {
        assertNull(metricsProvider.getProcessingTimePercentiles("never-recorded"));
    }

    @Test
    void testDeliveryLatencyDelegates() {
        metricsProvider.recordMessageDeliveryLatency("delegate-topic", "native", Duration.ofMillis(30));

        var percentiles = metricsProvider.getDeliveryLatencyPercentiles("delegate-topic");

        assertNotNull(percentiles, "The provider must surface the wrapped metrics' distribution");
        assertEquals(1, percentiles.sampleCount());
    }

    @Test
    void testGetDeliveryLatencyPercentilesNullForUnrecordedTopic() {
        assertNull(metricsProvider.getDeliveryLatencyPercentiles("never-recorded"));
    }
}

