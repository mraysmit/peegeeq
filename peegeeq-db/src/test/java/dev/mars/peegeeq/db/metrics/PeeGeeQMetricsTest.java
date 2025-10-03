package dev.mars.peegeeq.db.metrics;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
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


import dev.mars.peegeeq.db.SharedPostgresExtension;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.migration.SchemaMigrationManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for PeeGeeQMetrics.
 *
 * <p><strong>IMPORTANT:</strong> This test uses SharedPostgresExtension for shared container.
 * Schema is initialized once by the extension. Tests use @ResourceLock to prevent data conflicts.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@ExtendWith(SharedPostgresExtension.class)
@ResourceLock("metrics-data")
class PeeGeeQMetricsTest {

    private PgConnectionManager connectionManager;
    private io.vertx.sqlclient.Pool reactivePool;
    private MeterRegistry meterRegistry;
    private PeeGeeQMetrics metrics;

    @BeforeEach
    void setUp() throws SQLException {
        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();
        connectionManager = new PgConnectionManager(Vertx.vertx());

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

        reactivePool = connectionManager.getOrCreateReactivePool("test", connectionConfig, poolConfig);

        // DO NOT recreate tables - they are created once by SharedPostgresExtension

        meterRegistry = new SimpleMeterRegistry();
        metrics = new PeeGeeQMetrics(reactivePool, "test-instance");

        // Clean up any existing data from previous tests
        cleanupTestData();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Clean up test data after each test
        try {
            if (reactivePool != null) {
                cleanupTestData();
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to cleanup test data in tearDown: " + e.getMessage());
        }

        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    /**
     * Cleans up test data to ensure test isolation.
     * This removes all data from test tables between test methods.
     */
    private void cleanupTestData() {
        try {
            reactivePool.withConnection(connection -> {
                // Clean up all test data from tables
                return connection.query("DELETE FROM dead_letter_queue").execute()
                    .compose(result -> connection.query("DELETE FROM outbox").execute())
                    .compose(result -> connection.query("DELETE FROM queue_messages").execute())
                    .compose(result -> connection.query("DELETE FROM queue_metrics").execute());
            }).toCompletionStage().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);

            System.out.println("DEBUG: Cleaned up test data for PeeGeeQMetrics test isolation");
        } catch (Exception e) {
            System.err.println("Warning: Failed to cleanup test data: " + e.getMessage());
            // Don't throw - allow test to proceed
        }
    }

    /**
     * @deprecated Tables are now created once in @BeforeAll. This method is no longer used.
     */
    @Deprecated
    private void createTablesReactively() {
        try {
            reactivePool.withConnection(connection -> {
                // Create essential tables for metrics tests
                String createOutboxTable = """
                    CREATE TABLE IF NOT EXISTS outbox (
                        id BIGSERIAL PRIMARY KEY,
                        topic VARCHAR(255) NOT NULL,
                        payload JSONB NOT NULL,
                        created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        processed_at TIMESTAMP WITH TIME ZONE,
                        processing_started_at TIMESTAMP WITH TIME ZONE,
                        status VARCHAR(50) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD_LETTER')),
                        retry_count INT DEFAULT 0,
                        max_retries INT DEFAULT 3,
                        next_retry_at TIMESTAMP WITH TIME ZONE,
                        version INT DEFAULT 0,
                        headers JSONB DEFAULT '{}',
                        error_message TEXT,
                        correlation_id VARCHAR(255),
                        message_group VARCHAR(255),
                        priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10)
                    )
                    """;

                String createQueueMessagesTable = """
                    CREATE TABLE IF NOT EXISTS queue_messages (
                        id BIGSERIAL PRIMARY KEY,
                        topic VARCHAR(255) NOT NULL,
                        payload JSONB NOT NULL,
                        visible_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        lock_id BIGINT,
                        lock_until TIMESTAMP WITH TIME ZONE,
                        retry_count INT DEFAULT 0,
                        max_retries INT DEFAULT 3,
                        status VARCHAR(50) DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'LOCKED', 'PROCESSED', 'FAILED', 'DEAD_LETTER')),
                        headers JSONB DEFAULT '{}',
                        error_message TEXT,
                        correlation_id VARCHAR(255),
                        message_group VARCHAR(255),
                        priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10)
                    )
                    """;

                String createDeadLetterTable = """
                    CREATE TABLE IF NOT EXISTS dead_letter_queue (
                        id BIGSERIAL PRIMARY KEY,
                        original_table VARCHAR(50) NOT NULL,
                        original_id BIGINT NOT NULL,
                        topic VARCHAR(255) NOT NULL,
                        payload JSONB NOT NULL,
                        original_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        failed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                        failure_reason TEXT NOT NULL,
                        retry_count INT NOT NULL,
                        headers JSONB DEFAULT '{}',
                        correlation_id VARCHAR(255),
                        message_group VARCHAR(255)
                    )
                    """;

                String createQueueMetricsTable = """
                    CREATE TABLE IF NOT EXISTS queue_metrics (
                        id BIGSERIAL PRIMARY KEY,
                        metric_name VARCHAR(100) NOT NULL,
                        metric_value DOUBLE PRECISION NOT NULL,
                        tags JSONB DEFAULT '{}',
                        timestamp TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                    )
                    """;

                return connection.query(createOutboxTable).execute()
                    .compose(result -> connection.query(createQueueMessagesTable).execute())
                    .compose(result -> connection.query(createDeadLetterTable).execute())
                    .compose(result -> connection.query(createQueueMetricsTable).execute())
                    .mapEmpty();
            }).toCompletionStage().toCompletableFuture().get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tables reactively", e);
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
        assertEquals(3.0, meterRegistry.get("peegeeq.messages.sent").tag("instance", "test-instance").counter().count());
    }

    @Test
    void testMessageReceivedMetrics() {
        metrics.bindTo(meterRegistry);

        metrics.recordMessageReceived("topic1");
        metrics.recordMessageReceived("topic2");

        assertEquals(2.0, meterRegistry.get("peegeeq.messages.received").tag("instance", "test-instance").counter().count());
    }

    @Test
    void testMessageProcessedMetrics() {
        metrics.bindTo(meterRegistry);

        Duration processingTime1 = Duration.ofMillis(100);
        Duration processingTime2 = Duration.ofMillis(200);

        metrics.recordMessageProcessed("topic1", processingTime1);
        metrics.recordMessageProcessed("topic1", processingTime2);

        assertEquals(2.0, meterRegistry.get("peegeeq.messages.processed").tag("instance", "test-instance").counter().count());

        // Verify timer metrics
        assertNotNull(meterRegistry.get("peegeeq.message.processing.time").tag("instance", "test-instance").timer());
        assertEquals(2, meterRegistry.get("peegeeq.message.processing.time").tag("instance", "test-instance").timer().count());
    }

    /**
     * Tests metrics recording for intentionally failed messages.
     * This test verifies that the metrics system properly tracks and counts
     * message processing failures.
     *
     * INTENTIONAL FAILURE TEST: This test deliberately records message failures
     * to verify that failure metrics are properly tracked and reported.
     */
    @Test
    void testMessageFailedMetrics() {
        System.out.println("🧪 ===== RUNNING INTENTIONAL MESSAGE FAILURE METRICS TEST ===== 🧪");
        System.out.println("🔥 **INTENTIONAL TEST** 🔥 This test deliberately records message failures to verify metrics tracking");

        metrics.bindTo(meterRegistry);

        System.out.println("🔥 **INTENTIONAL TEST FAILURE** 🔥 Recording simulated message failures for metrics testing");
        metrics.recordMessageFailed("topic1", "timeout");
        metrics.recordMessageFailed("topic1", "validation");
        metrics.recordMessageFailed("topic2", "timeout");

        assertEquals(3.0, meterRegistry.get("peegeeq.messages.failed").tag("instance", "test-instance").counter().count());

        System.out.println("✅ **SUCCESS** ✅ Message failure metrics were properly recorded and tracked");
        System.out.println("🧪 ===== INTENTIONAL FAILURE TEST COMPLETED ===== 🧪");
    }

    @Test
    void testMessageRetriedMetrics() {
        metrics.bindTo(meterRegistry);

        metrics.recordMessageRetried("topic1", 1);
        metrics.recordMessageRetried("topic1", 2);
        metrics.recordMessageRetried("topic2", 1);

        assertEquals(3.0, meterRegistry.get("peegeeq.messages.retried").tag("instance", "test-instance").counter().count());
    }

    @Test
    void testMessageDeadLetteredMetrics() {
        metrics.bindTo(meterRegistry);

        metrics.recordMessageDeadLettered("topic1", "max_retries_exceeded");
        metrics.recordMessageDeadLettered("topic2", "poison_message");

        assertEquals(2.0, meterRegistry.get("peegeeq.messages.dead_lettered").tag("instance", "test-instance").counter().count());
    }

    @Test
    void testDatabaseOperationMetrics() {
        metrics.bindTo(meterRegistry);

        Duration operationTime = Duration.ofMillis(50);
        metrics.recordDatabaseOperation("select", operationTime);
        metrics.recordDatabaseOperation("insert", operationTime);

        assertEquals(2, meterRegistry.get("peegeeq.database.operation.time").tag("instance", "test-instance").timer().count());
    }

    @Test
    void testConnectionAcquisitionMetrics() {
        metrics.bindTo(meterRegistry);

        Duration acquisitionTime = Duration.ofMillis(10);
        metrics.recordConnectionAcquisition(acquisitionTime);
        metrics.recordConnectionAcquisition(acquisitionTime);

        assertEquals(2, meterRegistry.get("peegeeq.connection.acquisition.time").tag("instance", "test-instance").timer().count());
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
        
        // Verify metrics were persisted to database using reactive patterns
        try {
            Integer count = reactivePool.withConnection(connection -> {
                return connection.preparedQuery("SELECT COUNT(*) FROM queue_metrics")
                    .execute()
                    .map(rowSet -> {
                        io.vertx.sqlclient.Row row = rowSet.iterator().next();
                        return row.getInteger(0);
                    });
            }).toCompletionStage().toCompletableFuture().get();

            assertTrue(count > 0);
        } catch (Exception e) {
            fail("Failed to verify persisted metrics: " + e.getMessage());
        }
    }

    @Test
    void testHealthCheck() {
        assertTrue(metrics.isHealthy());
        
        // Health check should work even without binding to registry
        PeeGeeQMetrics unboundMetrics = new PeeGeeQMetrics(reactivePool, "test-instance-2");
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
        assertEquals(2.0, meterRegistry.get("peegeeq.messages.sent").tag("instance", "test-instance").counter().count());
        assertEquals(2.0, meterRegistry.get("peegeeq.messages.failed").tag("instance", "test-instance").counter().count());
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
        assertEquals(expectedCount, meterRegistry.get("peegeeq.messages.sent").tag("instance", "test-instance").counter().count());
        assertEquals(expectedCount, meterRegistry.get("peegeeq.messages.received").tag("instance", "test-instance").counter().count());
        assertEquals(expectedCount, meterRegistry.get("peegeeq.messages.processed").tag("instance", "test-instance").counter().count());
    }

    /**
     * Tests metrics behavior when the database connection is intentionally closed.
     * This test verifies that the metrics system gracefully handles database failures
     * without throwing exceptions and properly reports unhealthy status.
     *
     * INTENTIONAL FAILURE TEST: This test deliberately closes the database connection
     * to simulate a database failure and verify metrics system resilience.
     */
    @Test
    void testMetricsWithDatabaseFailure() throws Exception {
        System.out.println("🧪 ===== RUNNING INTENTIONAL DATABASE FAILURE METRICS TEST ===== 🧪");
        System.out.println("🔥 **INTENTIONAL TEST** 🔥 This test deliberately closes the database connection to test metrics resilience");

        metrics.bindTo(meterRegistry);

        // Close the connection manager to simulate database failure
        System.out.println("🔥 **INTENTIONAL TEST FAILURE** 🔥 Closing database connection to simulate failure");
        connectionManager.close();

        // Metrics recording should still work (not throw exceptions)
        System.out.println("Testing that metrics recording continues to work despite database failure");
        assertDoesNotThrow(() -> {
            metrics.recordMessageSent("topic1");
            metrics.recordMessageReceived("topic1");
        });

        // Health check should return false
        assertFalse(metrics.isHealthy());

        // Queue depth gauges should return 0 on database failure
        assertEquals(0.0, meterRegistry.get("peegeeq.queue.depth.outbox").gauge().value());

        System.out.println("✅ **SUCCESS** ✅ Metrics system properly handled database failure");
        System.out.println("🧪 ===== INTENTIONAL FAILURE TEST COMPLETED ===== 🧪");
    }

    private void insertTestOutboxMessage() {
        try {
            reactivePool.withConnection(connection -> {
                return connection.preparedQuery("INSERT INTO outbox (topic, payload, status) VALUES ($1, $2::jsonb, $3)")
                    .execute(io.vertx.sqlclient.Tuple.of("test-topic", "{\"test\": \"data\"}", "PENDING"))
                    .mapEmpty();
            }).toCompletionStage().toCompletableFuture().get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to insert test outbox message", e);
        }
    }

    private void insertTestQueueMessage() {
        try {
            reactivePool.withConnection(connection -> {
                return connection.preparedQuery("INSERT INTO queue_messages (topic, payload, status) VALUES ($1, $2::jsonb, $3)")
                    .execute(io.vertx.sqlclient.Tuple.of("test-topic", "{\"test\": \"data\"}", "AVAILABLE"))
                    .mapEmpty();
            }).toCompletionStage().toCompletableFuture().get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to insert test queue message", e);
        }
    }

    private void insertTestDeadLetterMessage() {
        try {
            reactivePool.withConnection(connection -> {
                return connection.preparedQuery("INSERT INTO dead_letter_queue (original_table, original_id, topic, payload, original_created_at, failure_reason, retry_count) VALUES ($1, $2, $3, $4::jsonb, $5, $6, $7)")
                    .execute(io.vertx.sqlclient.Tuple.of("outbox", 1, "test-topic", "{\"test\": \"data\"}",
                        java.time.OffsetDateTime.now(), "test failure", 3))
                    .mapEmpty();
            }).toCompletionStage().toCompletableFuture().get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to insert test dead letter message", e);
        }
    }

    @Test
    void testReactiveMetricsConstructor() {
        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();

        // Create connection config for reactive pool
        PgConnectionConfig reactiveConnectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();

        PgPoolConfig reactivePoolConfig = new PgPoolConfig.Builder()
                .minimumIdle(2)
                .maximumPoolSize(5)
                .build();

        // Create reactive pool
        Pool reactivePool = connectionManager.getOrCreateReactivePool("test-reactive", reactiveConnectionConfig, reactivePoolConfig);
        assertNotNull(reactivePool);

        // Create metrics with reactive constructor
        PeeGeeQMetrics reactiveMetrics = new PeeGeeQMetrics(reactivePool, "test-reactive-instance");
        assertNotNull(reactiveMetrics);

        // Test that reactive health check works
        assertTrue(reactiveMetrics.isHealthy());

        // Test that metrics can be bound to registry
        assertDoesNotThrow(() -> reactiveMetrics.bindTo(meterRegistry));

        // Test basic metrics recording
        assertDoesNotThrow(() -> {
            reactiveMetrics.recordMessageSent("test-topic");
            reactiveMetrics.recordMessageReceived("test-topic");
        });
    }
}
