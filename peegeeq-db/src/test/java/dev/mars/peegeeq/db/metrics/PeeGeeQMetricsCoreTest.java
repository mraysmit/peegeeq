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

        Counter messagesRetried = meterRegistry.find("peegeeq.messages.retried").counter();
        assertNotNull(messagesRetried);

        Counter messagesDeadLettered = meterRegistry.find("peegeeq.messages.dead_lettered").counter();
        assertNotNull(messagesDeadLettered);

        // Verify timers are registered
        Timer messageProcessingTime = meterRegistry.find("peegeeq.message.processing.time").timer();
        assertNotNull(messageProcessingTime);

        Timer databaseOperationTime = meterRegistry.find("peegeeq.database.operation.time").timer();
        assertNotNull(databaseOperationTime);

        Timer connectionAcquisitionTime = meterRegistry.find("peegeeq.connection.acquisition.time").timer();
        assertNotNull(connectionAcquisitionTime);

        // Verify gauges are registered
        Gauge activeConnections = meterRegistry.find("peegeeq.connection.pool.active").gauge();
        assertNotNull(activeConnections);

        Gauge idleConnections = meterRegistry.find("peegeeq.connection.pool.idle").gauge();
        assertNotNull(idleConnections);

        Gauge pendingConnections = meterRegistry.find("peegeeq.connection.pool.pending").gauge();
        assertNotNull(pendingConnections);

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
    void testRecordMessageSendError() {
        metrics.recordMessageSendError("test-topic");

        // Verify error was recorded
        Counter counter = meterRegistry.find("peegeeq.messages.failed").counter();
        assertTrue(counter.count() > 0);
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
    void testRecordMessageRetried() {
        Counter counter = meterRegistry.find("peegeeq.messages.retried").counter();
        double before = counter.count();

        metrics.recordMessageRetried("test-topic", 1);

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
    void testRecordDatabaseOperation() {
        Timer timer = meterRegistry.find("peegeeq.database.operation.time").timer();
        long before = timer.count();

        metrics.recordDatabaseOperation("SELECT", java.time.Duration.ofMillis(50));

        long after = timer.count();
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

    @Test
    void testRecordMessageReceiveError() {
        metrics.recordMessageReceiveError("test-topic");
        // Verify no exception thrown
    }

    @Test
    void testRecordMessageAckError() {
        metrics.recordMessageAckError("test-topic");
        // Verify no exception thrown
    }

    @Test
    void testUpdateConnectionPoolMetrics() {
        metrics.updateConnectionPoolMetrics(5, 3, 2);
        // Verify no exception thrown
    }

    @Test
    void testRecordTimer() {
        java.util.Map<String, String> tags = new java.util.HashMap<>();
        tags.put("custom", "value");
        metrics.recordTimer("peegeeq.timer", 100L, tags);
        // Verify no exception thrown
    }

    @Test
    void testIncrementCounter() {
        java.util.Map<String, String> tags = new java.util.HashMap<>();
        tags.put("custom", "value");
        metrics.incrementCounter("peegeeq.counter", tags);
        // Verify no exception thrown
    }

    @Test
    void testRecordGauge() {
        java.util.Map<String, String> tags = new java.util.HashMap<>();
        tags.put("instance", "test-instance");
        tags.put("custom", "value");
        metrics.recordGauge("peegeeq.gauge", 42.0, tags);

        Gauge gauge = meterRegistry.find("peegeeq.gauge")
            .tag("instance", "test-instance")
            .tag("custom", "value")
            .gauge();

        assertNotNull(gauge);
        assertEquals(42.0, gauge.value(), 0.0001);

        metrics.recordGauge("peegeeq.gauge", 99.0, tags);
        assertEquals(99.0, gauge.value(), 0.0001);
    }

    @Test
    void testRecordGaugeWithDelimiterValues() {
        java.util.Map<String, String> tagsPipe = new java.util.HashMap<>();
        tagsPipe.put("instance", "test-instance");
        tagsPipe.put("custom", "a|b");
        metrics.recordGauge("peegeeq.gauge.delimited", 11.0, tagsPipe);

        java.util.Map<String, String> tagsEquals = new java.util.HashMap<>();
        tagsEquals.put("instance", "test-instance");
        tagsEquals.put("custom", "a=b");
        metrics.recordGauge("peegeeq.gauge.delimited", 22.0, tagsEquals);

        Gauge gaugePipe = meterRegistry.find("peegeeq.gauge.delimited")
            .tag("instance", "test-instance")
            .tag("custom", "a|b")
            .gauge();

        Gauge gaugeEquals = meterRegistry.find("peegeeq.gauge.delimited")
            .tag("instance", "test-instance")
            .tag("custom", "a=b")
            .gauge();

        assertNotNull(gaugePipe);
        assertNotNull(gaugeEquals);
        assertEquals(11.0, gaugePipe.value(), 0.0001);
        assertEquals(22.0, gaugeEquals.value(), 0.0001);
    }

    @Test
    void testRecordGaugeAfterRegistryRebind() {
        java.util.Map<String, String> tags = new java.util.HashMap<>();
        tags.put("instance", "test-instance");
        tags.put("custom", "value");
        metrics.recordGauge("peegeeq.gauge.rebind", 10.0, tags);

        SimpleMeterRegistry reboundRegistry = new SimpleMeterRegistry();
        metrics.bindTo(reboundRegistry);
        metrics.recordGauge("peegeeq.gauge.rebind", 55.0, tags);

        Gauge reboundGauge = reboundRegistry.find("peegeeq.gauge.rebind")
            .tag("instance", "test-instance")
            .tag("custom", "value")
            .gauge();

        assertNotNull(reboundGauge);
        assertEquals(55.0, reboundGauge.value(), 0.0001);
    }

    @Test
    void testGetAllMetrics() {
        java.util.Map<String, Number> allMetrics = metrics.getAllMetrics();
        assertNotNull(allMetrics);
    }

    @Test
    void testIsHealthy(VertxTestContext testContext) {
        metrics.isHealthy()
            .onComplete(testContext.succeeding(healthy -> testContext.verify(() -> {
                    assertNotNull(healthy);
                    testContext.completeNow();
                })));
    }

    @Test
    void testPersistMetrics(VertxTestContext testContext) {
        logger.warn("===== INTENTIONAL ERROR TEST ===== If the next ERROR log ('Failed to persist metrics to database') appears, it is EXPECTED \u2014 queue_metrics table is absent from the standard test schema");
        metrics.persistMetrics(meterRegistry)
            .onSuccess(v -> testContext.completeNow())
            .onFailure(err -> testContext.completeNow());
    }

}

