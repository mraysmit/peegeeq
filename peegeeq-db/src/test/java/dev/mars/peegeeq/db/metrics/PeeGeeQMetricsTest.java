package dev.mars.peegeeq.db.metrics;

import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.migration.SchemaMigrationManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for PeeGeeQMetrics.
 */
@Testcontainers
class PeeGeeQMetricsTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("metrics_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    private PgConnectionManager connectionManager;
    private DataSource dataSource;
    private MeterRegistry meterRegistry;
    private PeeGeeQMetrics metrics;

    @BeforeEach
    void setUp() throws SQLException {
        connectionManager = new PgConnectionManager();
        
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .minimumIdle(2)
                .maximumPoolSize(5)
                .build();

        dataSource = connectionManager.getOrCreateDataSource("test", connectionConfig, poolConfig);
        
        // Apply migrations to create necessary tables
        SchemaMigrationManager migrationManager = new SchemaMigrationManager(dataSource);
        migrationManager.migrate();
        
        meterRegistry = new SimpleMeterRegistry();
        metrics = new PeeGeeQMetrics(dataSource, "test-instance");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    @Test
    void testMetricsInitialization() {
        assertNotNull(metrics);
        
        // Test binding to meter registry
        assertDoesNotThrow(() -> metrics.bindTo(meterRegistry));
        
        // Verify meters are registered
        assertFalse(meterRegistry.getMeters().isEmpty());
    }

    @Test
    void testMessageSentMetrics() {
        metrics.bindTo(meterRegistry);
        
        // Record some message sent events
        metrics.recordMessageSent("topic1");
        metrics.recordMessageSent("topic1");
        metrics.recordMessageSent("topic2");
        
        // Verify counter exists and has correct value
        assertEquals(3.0, meterRegistry.counter("peegeeq.messages.sent").count());
    }

    @Test
    void testMessageReceivedMetrics() {
        metrics.bindTo(meterRegistry);
        
        metrics.recordMessageReceived("topic1");
        metrics.recordMessageReceived("topic2");
        
        assertEquals(2.0, meterRegistry.counter("peegeeq.messages.received").count());
    }

    @Test
    void testMessageProcessedMetrics() {
        metrics.bindTo(meterRegistry);
        
        Duration processingTime1 = Duration.ofMillis(100);
        Duration processingTime2 = Duration.ofMillis(200);
        
        metrics.recordMessageProcessed("topic1", processingTime1);
        metrics.recordMessageProcessed("topic1", processingTime2);
        
        assertEquals(2.0, meterRegistry.counter("peegeeq.messages.processed").count());
        
        // Verify timer metrics
        assertNotNull(meterRegistry.timer("peegeeq.message.processing.time"));
        assertEquals(2, meterRegistry.timer("peegeeq.message.processing.time").count());
    }

    @Test
    void testMessageFailedMetrics() {
        metrics.bindTo(meterRegistry);
        
        metrics.recordMessageFailed("topic1", "timeout");
        metrics.recordMessageFailed("topic1", "validation");
        metrics.recordMessageFailed("topic2", "timeout");
        
        assertEquals(3.0, meterRegistry.counter("peegeeq.messages.failed").count());
    }

    @Test
    void testMessageRetriedMetrics() {
        metrics.bindTo(meterRegistry);
        
        metrics.recordMessageRetried("topic1", 1);
        metrics.recordMessageRetried("topic1", 2);
        metrics.recordMessageRetried("topic2", 1);
        
        assertEquals(3.0, meterRegistry.counter("peegeeq.messages.retried").count());
    }

    @Test
    void testMessageDeadLetteredMetrics() {
        metrics.bindTo(meterRegistry);
        
        metrics.recordMessageDeadLettered("topic1", "max_retries_exceeded");
        metrics.recordMessageDeadLettered("topic2", "poison_message");
        
        assertEquals(2.0, meterRegistry.counter("peegeeq.messages.dead_lettered").count());
    }

    @Test
    void testDatabaseOperationMetrics() {
        metrics.bindTo(meterRegistry);
        
        Duration operationTime = Duration.ofMillis(50);
        metrics.recordDatabaseOperation("select", operationTime);
        metrics.recordDatabaseOperation("insert", operationTime);
        
        assertEquals(2, meterRegistry.timer("peegeeq.database.operation.time").count());
    }

    @Test
    void testConnectionAcquisitionMetrics() {
        metrics.bindTo(meterRegistry);
        
        Duration acquisitionTime = Duration.ofMillis(10);
        metrics.recordConnectionAcquisition(acquisitionTime);
        metrics.recordConnectionAcquisition(acquisitionTime);
        
        assertEquals(2, meterRegistry.timer("peegeeq.connection.acquisition.time").count());
    }

    @Test
    void testConnectionPoolMetrics() {
        metrics.bindTo(meterRegistry);
        
        metrics.updateConnectionPoolMetrics(3, 2, 1);
        
        assertEquals(3.0, meterRegistry.get("peegeeq.connection.pool.active").gauge().value());
        assertEquals(2.0, meterRegistry.get("peegeeq.connection.pool.idle").gauge().value());
        assertEquals(1.0, meterRegistry.get("peegeeq.connection.pool.pending").gauge().value());
    }

    @Test
    void testQueueDepthGauges() throws SQLException {
        metrics.bindTo(meterRegistry);
        
        // Insert test data to verify queue depth calculations
        insertTestOutboxMessage();
        insertTestQueueMessage();
        insertTestDeadLetterMessage();
        
        // Queue depth gauges should reflect the test data
        assertTrue(meterRegistry.get("peegeeq.queue.depth.outbox").gauge().value() >= 0);
        assertTrue(meterRegistry.get("peegeeq.queue.depth.native").gauge().value() >= 0);
        assertTrue(meterRegistry.get("peegeeq.queue.depth.dead_letter").gauge().value() >= 0);
    }

    @Test
    void testMetricsSummary() {
        metrics.bindTo(meterRegistry);
        
        // Record various metrics
        metrics.recordMessageSent("topic1");
        metrics.recordMessageReceived("topic1");
        metrics.recordMessageProcessed("topic1", Duration.ofMillis(100));
        metrics.recordMessageFailed("topic1", "error");
        
        PeeGeeQMetrics.MetricsSummary summary = metrics.getSummary();
        
        assertNotNull(summary);
        assertEquals(1.0, summary.getMessagesSent());
        assertEquals(1.0, summary.getMessagesReceived());
        assertEquals(1.0, summary.getMessagesProcessed());
        assertEquals(1.0, summary.getMessagesFailed());
        
        // Test success rate calculation
        assertEquals(50.0, summary.getSuccessRate()); // 1 success out of 2 total (1 success + 1 failure)
    }

    @Test
    void testSuccessRateCalculation() {
        metrics.bindTo(meterRegistry);
        
        // Test with no messages
        PeeGeeQMetrics.MetricsSummary emptySummary = metrics.getSummary();
        assertEquals(0.0, emptySummary.getSuccessRate());
        
        // Test with only successful messages
        metrics.recordMessageProcessed("topic1", Duration.ofMillis(100));
        metrics.recordMessageProcessed("topic1", Duration.ofMillis(100));
        
        PeeGeeQMetrics.MetricsSummary successSummary = metrics.getSummary();
        assertEquals(100.0, successSummary.getSuccessRate());
        
        // Test with mixed success/failure
        metrics.recordMessageFailed("topic1", "error");
        
        PeeGeeQMetrics.MetricsSummary mixedSummary = metrics.getSummary();
        assertEquals(66.67, mixedSummary.getSuccessRate(), 0.01); // 2 success out of 3 total
    }

    @Test
    void testMetricsPersistence() {
        metrics.bindTo(meterRegistry);
        
        // Record some metrics
        metrics.recordMessageSent("topic1");
        metrics.recordMessageReceived("topic1");
        metrics.recordMessageProcessed("topic1", Duration.ofMillis(100));
        
        // Test metrics persistence
        assertDoesNotThrow(() -> metrics.persistMetrics(meterRegistry));
        
        // Verify metrics were persisted to database
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM queue_metrics");
             var rs = stmt.executeQuery()) {
            
            assertTrue(rs.next());
            assertTrue(rs.getInt(1) > 0);
        } catch (SQLException e) {
            fail("Failed to verify persisted metrics: " + e.getMessage());
        }
    }

    @Test
    void testHealthCheck() {
        assertTrue(metrics.isHealthy());
        
        // Health check should work even without binding to registry
        PeeGeeQMetrics unboundMetrics = new PeeGeeQMetrics(dataSource, "test-instance-2");
        assertTrue(unboundMetrics.isHealthy());
    }

    @Test
    void testMetricsWithTags() {
        metrics.bindTo(meterRegistry);
        
        // Record metrics with different topics (which become tags)
        metrics.recordMessageSent("topic1");
        metrics.recordMessageSent("topic2");
        metrics.recordMessageFailed("topic1", "timeout");
        metrics.recordMessageFailed("topic2", "validation");
        
        // Verify that metrics are properly tagged
        assertEquals(2.0, meterRegistry.counter("peegeeq.messages.sent").count());
        assertEquals(2.0, meterRegistry.counter("peegeeq.messages.failed").count());
    }

    @Test
    void testConcurrentMetricsRecording() throws InterruptedException {
        metrics.bindTo(meterRegistry);
        
        int threadCount = 10;
        int operationsPerThread = 100;
        Thread[] threads = new Thread[threadCount];
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    metrics.recordMessageSent("topic" + threadId);
                    metrics.recordMessageReceived("topic" + threadId);
                    metrics.recordMessageProcessed("topic" + threadId, Duration.ofMillis(10));
                }
            });
        }
        
        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }
        
        // Verify final counts
        double expectedCount = threadCount * operationsPerThread;
        assertEquals(expectedCount, meterRegistry.counter("peegeeq.messages.sent").count());
        assertEquals(expectedCount, meterRegistry.counter("peegeeq.messages.received").count());
        assertEquals(expectedCount, meterRegistry.counter("peegeeq.messages.processed").count());
    }

    @Test
    void testMetricsWithDatabaseFailure() throws Exception {
        metrics.bindTo(meterRegistry);
        
        // Close the connection manager to simulate database failure
        connectionManager.close();
        
        // Metrics recording should still work (not throw exceptions)
        assertDoesNotThrow(() -> {
            metrics.recordMessageSent("topic1");
            metrics.recordMessageReceived("topic1");
        });
        
        // Health check should return false
        assertFalse(metrics.isHealthy());
        
        // Queue depth gauges should return 0 on database failure
        assertEquals(0.0, meterRegistry.get("peegeeq.queue.depth.outbox").gauge().value());
    }

    private void insertTestOutboxMessage() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO outbox (topic, payload, status) VALUES (?, ?::jsonb, ?)")) {
            
            stmt.setString(1, "test-topic");
            stmt.setString(2, "{\"test\": \"data\"}");
            stmt.setString(3, "PENDING");
            stmt.executeUpdate();
        }
    }

    private void insertTestQueueMessage() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO queue_messages (topic, payload, status) VALUES (?, ?::jsonb, ?)")) {
            
            stmt.setString(1, "test-topic");
            stmt.setString(2, "{\"test\": \"data\"}");
            stmt.setString(3, "AVAILABLE");
            stmt.executeUpdate();
        }
    }

    private void insertTestDeadLetterMessage() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "INSERT INTO dead_letter_queue (original_table, original_id, topic, payload, original_created_at, failure_reason, retry_count) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)")) {
            
            stmt.setString(1, "outbox");
            stmt.setLong(2, 1);
            stmt.setString(3, "test-topic");
            stmt.setString(4, "{\"test\": \"data\"}");
            stmt.setTimestamp(5, new java.sql.Timestamp(System.currentTimeMillis()));
            stmt.setString(6, "test failure");
            stmt.setInt(7, 3);
            stmt.executeUpdate();
        }
    }
}
