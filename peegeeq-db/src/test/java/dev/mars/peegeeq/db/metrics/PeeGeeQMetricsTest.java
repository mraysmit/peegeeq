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


import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.db.SharedPostgresTestExtension;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.vertx.core.Future;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for PeeGeeQMetrics.
 *
 * <p><strong>IMPORTANT:</strong> This test uses SharedPostgresTestExtension for shared container.
 * Schema is initialized once by the extension. Tests use @ResourceLock to prevent data conflicts.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith({SharedPostgresTestExtension.class, VertxExtension.class})
@ResourceLock(value = "dead-letter-queue-database", mode = org.junit.jupiter.api.parallel.ResourceAccessMode.READ_WRITE)
@Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
class PeeGeeQMetricsTest {

    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQMetricsTest.class);

    private PgConnectionManager connectionManager;
    private io.vertx.sqlclient.Pool reactivePool;
    private MeterRegistry meterRegistry;
    private PeeGeeQMetrics metrics;
    private boolean poolClosedIntentionally = false;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) {
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();
        connectionManager = new PgConnectionManager(vertx);

        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(3)
                .shared(false)
                .idleTimeout(Duration.ofSeconds(2))
                .connectionTimeout(Duration.ofSeconds(5))
                .build();

        reactivePool = connectionManager.getOrCreateReactivePool("test", connectionConfig, poolConfig);

        // DO NOT recreate tables - they are created once by SharedPostgresTestExtension
        meterRegistry = new SimpleMeterRegistry();
        metrics = new PeeGeeQMetrics(reactivePool, "test-instance");

        cleanupTestData()
            .onFailure(e -> logger.warn("Failed to cleanup test data in setUp: {}", e.getMessage()))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        Future<Void> cleanup = (reactivePool != null && !poolClosedIntentionally)
            ? cleanupTestData()
                .onFailure(e -> logger.warn("Failed to cleanup test data in tearDown: {}", e.getMessage()))
            : Future.succeededFuture();

        cleanup
            .eventually(() -> connectionManager != null
                ? connectionManager.close()
                    .onFailure(e -> logger.debug("Connection manager close (may already be closed): {}", e.getMessage()))
                : Future.succeededFuture())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    /**
     * Cleans up test data to ensure test isolation.
     * Scoped to only this test class's data (topic='test-topic') to avoid
     * interfering with concurrent test classes that also use these tables.
     */
    private Future<Void> cleanupTestData() {
        return reactivePool.withConnection(connection ->
            connection.query("DELETE FROM dead_letter_queue WHERE topic = 'test-topic'").execute()
                .compose(result -> connection.query("DELETE FROM outbox WHERE topic = 'test-topic'").execute())
                .compose(result -> connection.query("DELETE FROM queue_messages WHERE topic = 'test-topic'").execute())
                .compose(result -> connection.query("DELETE FROM queue_metrics").execute())
                .mapEmpty()
        ).onSuccess(v -> logger.debug("Cleaned up test data for PeeGeeQMetrics test isolation"))
         .mapEmpty();
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
        logger.info("===== RUNNING INTENTIONAL MESSAGE FAILURE METRICS TEST =====");
        logger.info("INTENTIONAL TEST: This test deliberately records message failures to verify metrics tracking");

        metrics.bindTo(meterRegistry);

        logger.info("INTENTIONAL TEST FAILURE: Recording simulated message failures for metrics testing");
        metrics.recordMessageFailed("topic1", "timeout");
        metrics.recordMessageFailed("topic1", "validation");
        metrics.recordMessageFailed("topic2", "timeout");

        assertEquals(3.0, meterRegistry.get("peegeeq.messages.failed").tag("instance", "test-instance").counter().count());

        logger.info("SUCCESS: Message failure metrics were properly recorded and tracked");
        logger.info("===== INTENTIONAL FAILURE TEST COMPLETED =====");
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
    void testQueueDepthGauges(VertxTestContext testContext) {
        metrics.bindTo(meterRegistry);
        insertTestOutboxMessage()
            .compose(v -> insertTestQueueMessage())
            .compose(v -> insertTestDeadLetterMessage())
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                assertTrue(meterRegistry.get("peegeeq.queue.depth.outbox").gauge().value() >= 0);
                assertTrue(meterRegistry.get("peegeeq.queue.depth.native").gauge().value() >= 0);
                assertTrue(meterRegistry.get("peegeeq.queue.depth.dead_letter").gauge().value() >= 0);
                testContext.completeNow();
            })));
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
    void testMetricsPersistence(VertxTestContext testContext) {
        metrics.bindTo(meterRegistry);
        metrics.recordMessageSent("topic1");
        metrics.recordMessageReceived("topic1");
        metrics.recordMessageProcessed("topic1", Duration.ofMillis(100));

        metrics.persistMetrics(meterRegistry)
            .compose(v -> reactivePool.withConnection(connection ->
                connection.preparedQuery("SELECT COUNT(*) FROM queue_metrics")
                    .execute()
                    .map(rowSet -> rowSet.iterator().next().getInteger(0))
            ))
            .onComplete(testContext.succeeding(count -> testContext.verify(() -> {
                assertTrue(count > 0);
                testContext.completeNow();
            })));
    }

    @Test
    void testHealthCheck(VertxTestContext testContext) {
        metrics.isHealthy()
            .compose(healthy -> {
                assertTrue(healthy);
                PeeGeeQMetrics unboundMetrics = new PeeGeeQMetrics(reactivePool, "test-instance-2");
                return unboundMetrics.isHealthy();
            })
            .onComplete(testContext.succeeding(healthy -> testContext.verify(() -> {
                assertTrue(healthy);
                testContext.completeNow();
            })));
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
    void testMetricsWithDatabaseFailure(VertxTestContext testContext) {
        logger.warn("===== INTENTIONAL WARN TEST ===== The next WARN log ('Reactive health check failed') is EXPECTED  this test deliberately closes the DB connection to verify metrics resilience");

        metrics.bindTo(meterRegistry);

        poolClosedIntentionally = true;
        connectionManager.close()
            .compose(v -> {
                logger.info("Testing that metrics recording continues to work despite database failure");
                assertDoesNotThrow(() -> {
                    metrics.recordMessageSent("topic1");
                    metrics.recordMessageReceived("topic1");
                });
                return metrics.isHealthy();
            })
            .onComplete(testContext.succeeding(healthy -> testContext.verify(() -> {
                assertFalse(healthy);
                assertEquals(0.0, meterRegistry.get("peegeeq.queue.depth.outbox").gauge().value());
                logger.info("SUCCESS: Metrics system properly handled database failure");
                logger.info("===== INTENTIONAL FAILURE TEST COMPLETED =====");
                testContext.completeNow();
            })));
    }

    private Future<Void> insertTestOutboxMessage() {
        return reactivePool.withConnection(connection ->
            connection.preparedQuery("INSERT INTO outbox (topic, payload, status) VALUES ($1, $2::jsonb, $3)")
                .execute(io.vertx.sqlclient.Tuple.of("test-topic", "{\"test\": \"data\"}", "PENDING"))
                .mapEmpty()
        );
    }

    private Future<Void> insertTestQueueMessage() {
        return reactivePool.withConnection(connection ->
            connection.preparedQuery("INSERT INTO queue_messages (topic, payload, status) VALUES ($1, $2::jsonb, $3)")
                .execute(io.vertx.sqlclient.Tuple.of("test-topic", "{\"test\": \"data\"}", "AVAILABLE"))
                .mapEmpty()
        );
    }

    private Future<Void> insertTestDeadLetterMessage() {
        return reactivePool.withConnection(connection ->
            connection.preparedQuery("INSERT INTO dead_letter_queue (original_table, original_id, topic, payload, original_created_at, failure_reason, retry_count) VALUES ($1, $2, $3, $4::jsonb, $5, $6, $7)")
                .execute(io.vertx.sqlclient.Tuple.of("outbox", 1, "test-topic", "{\"test\": \"data\"}",
                    java.time.OffsetDateTime.now(), "test failure", 3))
                .mapEmpty()
        );
    }

    @Test
    void testReactiveMetricsConstructor(VertxTestContext testContext) {
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        PgConnectionConfig reactiveConnectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .build();

        PgPoolConfig reactivePoolConfig = new PgPoolConfig.Builder()
                .maxSize(3)
                .shared(false)
                .idleTimeout(Duration.ofSeconds(2))
                .connectionTimeout(Duration.ofSeconds(5))
                .build();

        Pool localPool = connectionManager.getOrCreateReactivePool("test-reactive", reactiveConnectionConfig, reactivePoolConfig);
        assertNotNull(localPool);

        PeeGeeQMetrics reactiveMetrics = new PeeGeeQMetrics(localPool, "test-reactive-instance");
        assertNotNull(reactiveMetrics);

        reactiveMetrics.isHealthy()
            .onComplete(testContext.succeeding(healthy -> testContext.verify(() -> {
                assertTrue(healthy);
                assertDoesNotThrow(() -> reactiveMetrics.bindTo(meterRegistry));
                assertDoesNotThrow(() -> {
                    reactiveMetrics.recordMessageSent("test-topic");
                    reactiveMetrics.recordMessageReceived("test-topic");
                });
                testContext.completeNow();
            })));
    }
}
