package dev.mars.peegeeq.db.metrics;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

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

    private PgConnectionManager connectionManager;
    private Pool pool;
    private PeeGeeQMetrics metrics;
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
        pool = connectionManager.getOrCreateReactivePool("test-metrics", connectionConfig, poolConfig);
        
        metrics = new PeeGeeQMetrics(pool, "test-instance");
        meterRegistry = new SimpleMeterRegistry();
        metrics.bindTo(meterRegistry);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectionManager != null) {
            connectionManager.close();
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
    void testRecordTestTimer() {
        java.util.Map<String, String> tags = new java.util.HashMap<>();
        tags.put("custom", "value");
        metrics.recordTestTimer("test.timer", 100L, "test-profile", "test-name", tags);
        // Verify no exception thrown
    }

    @Test
    void testRecordTestCounter() {
        java.util.Map<String, String> tags = new java.util.HashMap<>();
        tags.put("custom", "value");
        metrics.recordTestCounter("test.counter", "test-profile", "test-name", tags);
        // Verify no exception thrown
    }

    @Test
    void testRecordTestGauge() {
        java.util.Map<String, String> tags = new java.util.HashMap<>();
        tags.put("custom", "value");
        metrics.recordTestGauge("test.gauge", 42.0, "test-profile", "test-name", tags);
        // Verify no exception thrown
    }

    @Test
    void testRecordPerformanceTestExecution() {
        java.util.Map<String, Object> additionalMetrics = new java.util.HashMap<>();
        additionalMetrics.put("custom", "value");
        metrics.recordPerformanceTestExecution("test-name", "test-profile", 1000L, true, 100.0, additionalMetrics);
        // Verify no exception thrown
    }

    @Test
    void testGetAllMetrics() {
        java.util.Map<String, Object> allMetrics = metrics.getAllMetrics();
        assertNotNull(allMetrics);
    }

    @Test
    void testIsHealthyReactive() throws Exception {
        Boolean healthy = metrics.isHealthyReactive()
            .toCompletionStage().toCompletableFuture().get();
        assertNotNull(healthy);
    }

    @Test
    void testPersistMetrics() {
        metrics.persistMetrics(meterRegistry);
        // Verify no exception thrown
    }

}

