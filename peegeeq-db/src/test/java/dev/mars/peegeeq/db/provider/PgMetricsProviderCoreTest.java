package dev.mars.peegeeq.db.provider;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.metrics.PeeGeeQMetrics;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

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
        
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();
        pool = connectionManager.getOrCreateReactivePool("test-metrics-provider", connectionConfig, poolConfig);
        
        metrics = new PeeGeeQMetrics(pool, "test-instance");
        meterRegistry = new SimpleMeterRegistry();
        metrics.bindTo(meterRegistry);
        
        metricsProvider = new PgMetricsProvider(metrics);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    @Test
    void testPgMetricsProviderCreation() {
        assertNotNull(metricsProvider);
    }

    @Test
    void testRecordMessageSentSuccess() {
        metricsProvider.recordMessageSent("test-topic", true, Duration.ofMillis(100));
        // Verify no exception thrown
    }

    @Test
    void testRecordMessageSentFailure() {
        metricsProvider.recordMessageSent("test-topic", false, Duration.ofMillis(100));
        // Verify no exception thrown
    }

    @Test
    void testRecordMessageReceivedSuccess() {
        metricsProvider.recordMessageReceived("test-topic", true, Duration.ofMillis(50));
        // Verify no exception thrown
    }

    @Test
    void testRecordMessageReceivedFailure() {
        metricsProvider.recordMessageReceived("test-topic", false, Duration.ofMillis(50));
        // Verify no exception thrown
    }

    @Test
    void testRecordMessageAcknowledgedSuccess() {
        metricsProvider.recordMessageAcknowledged("test-topic", true, Duration.ofMillis(25));
        // Verify no exception thrown
    }

    @Test
    void testRecordMessageAcknowledgedFailure() {
        metricsProvider.recordMessageAcknowledged("test-topic", false, Duration.ofMillis(25));
        // Verify no exception thrown
    }

    @Test
    void testIncrementCounter() {
        Map<String, String> tags = new HashMap<>();
        tags.put("topic", "test-topic");
        tags.put("status", "success");
        
        metricsProvider.incrementCounter("test.counter", tags);
        // Verify no exception thrown
    }

    @Test
    void testIncrementCounterEmptyTags() {
        metricsProvider.incrementCounter("test.counter", new HashMap<>());
        // Verify no exception thrown
    }

    @Test
    void testRecordGauge() {
        metricsProvider.recordGauge("test.gauge", 42.0, new HashMap<>());
        // Verify no exception thrown
    }

    @Test
    void testRecordTimer() {
        metricsProvider.recordTimer("test.timer", Duration.ofMillis(100), new HashMap<>());
        // Verify no exception thrown
    }

    @Test
    void testGetInstanceId() {
        String instanceId = metricsProvider.getInstanceId();
        assertEquals("test-instance", instanceId);
    }

    @Test
    void testGetPeeGeeQMetrics() {
        PeeGeeQMetrics underlyingMetrics = metricsProvider.getPeeGeeQMetrics();
        assertNotNull(underlyingMetrics);
        assertSame(metrics, underlyingMetrics);
    }

    @Test
    void testGetQueueDepth() {
        long depth = metricsProvider.getQueueDepth("test-topic");
        assertTrue(depth >= 0);
    }

    @Test
    void testGetAllMetrics() {
        Map<String, Object> allMetrics = metricsProvider.getAllMetrics();
        assertNotNull(allMetrics);
    }
}

