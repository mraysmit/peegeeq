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


import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Comprehensive metrics collection for PeeGeeQ message queue system.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class PeeGeeQMetrics implements MeterBinder {
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQMetrics.class);

    private final DataSource dataSource;
    private final Pool reactivePool;
    private final String instanceId;
    private MeterRegistry registry;

    // Counters
    private Counter messagesSent;
    private Counter messagesReceived;
    private Counter messagesProcessed;
    private Counter messagesFailed;
    private Counter messagesRetried;
    private Counter messagesDeadLettered;

    // Timers
    private Timer messageProcessingTime;
    private Timer databaseOperationTime;
    private Timer connectionAcquisitionTime;

    // Gauges
    private final AtomicLong activeConnections = new AtomicLong(0);
    private final AtomicLong idleConnections = new AtomicLong(0);
    private final AtomicLong pendingConnections = new AtomicLong(0);

    /**
     * Legacy constructor using DataSource.
     * @deprecated Use PeeGeeQMetrics(Pool, String) for reactive patterns
     */
    @Deprecated
    public PeeGeeQMetrics(DataSource dataSource, String instanceId) {
        this.dataSource = dataSource;
        this.reactivePool = null;
        this.instanceId = instanceId;
    }

    /**
     * Modern reactive constructor using Vert.x Pool.
     * This is the preferred constructor for Vert.x 5.x reactive patterns.
     */
    public PeeGeeQMetrics(Pool reactivePool, String instanceId) {
        this.dataSource = null;
        this.reactivePool = reactivePool;
        this.instanceId = instanceId;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        this.registry = registry;
        // Message processing counters
        messagesSent = Counter.builder("peegeeq.messages.sent")
            .description("Total number of messages sent to queues")
            .tag("instance", instanceId)
            .register(registry);

        messagesReceived = Counter.builder("peegeeq.messages.received")
            .description("Total number of messages received from queues")
            .tag("instance", instanceId)
            .register(registry);

        messagesProcessed = Counter.builder("peegeeq.messages.processed")
            .description("Total number of messages successfully processed")
            .tag("instance", instanceId)
            .register(registry);

        messagesFailed = Counter.builder("peegeeq.messages.failed")
            .description("Total number of messages that failed processing")
            .tag("instance", instanceId)
            .register(registry);

        messagesRetried = Counter.builder("peegeeq.messages.retried")
            .description("Total number of message retry attempts")
            .tag("instance", instanceId)
            .register(registry);

        messagesDeadLettered = Counter.builder("peegeeq.messages.dead_lettered")
            .description("Total number of messages sent to dead letter queue")
            .tag("instance", instanceId)
            .register(registry);

        // Processing time metrics
        messageProcessingTime = Timer.builder("peegeeq.message.processing.time")
            .description("Time taken to process messages")
            .tag("instance", instanceId)
            .register(registry);

        databaseOperationTime = Timer.builder("peegeeq.database.operation.time")
            .description("Time taken for database operations")
            .tag("instance", instanceId)
            .register(registry);

        connectionAcquisitionTime = Timer.builder("peegeeq.connection.acquisition.time")
            .description("Time taken to acquire database connections")
            .tag("instance", instanceId)
            .register(registry);

        // Connection pool gauges
        Gauge.builder("peegeeq.connection.pool.active", activeConnections::get)
            .description("Number of active database connections")
            .tag("instance", instanceId)
            .register(registry);

        Gauge.builder("peegeeq.connection.pool.idle", idleConnections::get)
            .description("Number of idle database connections")
            .tag("instance", instanceId)
            .register(registry);

        Gauge.builder("peegeeq.connection.pool.pending", pendingConnections::get)
            .description("Number of pending connection requests")
            .tag("instance", instanceId)
            .register(registry);

        // Queue depth gauges
        Gauge.builder("peegeeq.queue.depth.outbox", this::getOutboxQueueDepth)
            .description("Number of pending messages in outbox")
            .tag("instance", instanceId)
            .register(registry);

        Gauge.builder("peegeeq.queue.depth.native", this::getNativeQueueDepth)
            .description("Number of available messages in native queue")
            .tag("instance", instanceId)
            .register(registry);

        Gauge.builder("peegeeq.queue.depth.dead_letter", this::getDeadLetterQueueDepth)
            .description("Number of messages in dead letter queue")
            .tag("instance", instanceId)
            .register(registry);

        logger.info("PeeGeeQ metrics registered for instance: {}", instanceId);
    }

    // Message processing metrics
    public void recordMessageSent(String topic) {
        if (messagesSent != null) {
            messagesSent.increment();
        }
        if (registry != null) {
            Counter.builder("peegeeq.messages.sent.by.topic")
                .tag("instance", instanceId)
                .tag("topic", topic)
                .register(registry)
                .increment();
        }
    }

    public void recordMessageSent(String topic, long durationMs) {
        recordMessageSent(topic);
        // Record timing if needed
        if (registry != null) {
            Timer.builder("peegeeq.message.send.time")
                .tag("instance", instanceId)
                .tag("topic", topic)
                .register(registry)
                .record(Duration.ofMillis(durationMs));
        }
    }

    public void recordMessageSendError(String topic) {
        recordMessageFailed(topic, "send_error");
    }

    public void recordMessageReceived(String topic) {
        if (messagesReceived != null) {
            messagesReceived.increment();
        }
        if (registry != null) {
            Counter.builder("peegeeq.messages.received.by.topic")
                .tag("instance", instanceId)
                .tag("topic", topic)
                .register(registry)
                .increment();
        }
    }

    public void recordMessageReceived(String topic, long durationMs) {
        recordMessageReceived(topic);
        // Record timing if needed
        if (registry != null) {
            Timer.builder("peegeeq.message.receive.time")
                .tag("instance", instanceId)
                .tag("topic", topic)
                .register(registry)
                .record(Duration.ofMillis(durationMs));
        }
    }

    public void recordMessageReceiveError(String topic) {
        recordMessageFailed(topic, "receive_error");
    }

    public void recordMessageProcessed(String topic, Duration processingTime) {
        if (messagesProcessed != null) {
            messagesProcessed.increment();
        }
        if (registry != null) {
            Counter.builder("peegeeq.messages.processed.by.topic")
                .tag("instance", instanceId)
                .tag("topic", topic)
                .register(registry)
                .increment();
        }

        if (messageProcessingTime != null) {
            messageProcessingTime.record(processingTime);
        }
        if (registry != null) {
            Timer.builder("peegeeq.message.processing.time.by.topic")
                .tag("instance", instanceId)
                .tag("topic", topic)
                .register(registry)
                .record(processingTime);
        }
    }

    public void recordMessageFailed(String topic, String errorType) {
        if (messagesFailed != null) {
            messagesFailed.increment();
        }
        if (registry != null) {
            Counter.builder("peegeeq.messages.failed.by.topic")
                .tag("instance", instanceId)
                .tag("topic", topic)
                .tag("error_type", errorType)
                .register(registry)
                .increment();
        }
    }

    public void recordMessageRetried(String topic, int retryCount) {
        if (messagesRetried != null) {
            messagesRetried.increment();
        }
        if (registry != null) {
            Counter.builder("peegeeq.messages.retried.by.topic")
                .tag("instance", instanceId)
                .tag("topic", topic)
                .tag("retry_count", String.valueOf(retryCount))
                .register(registry)
                .increment();
        }
    }

    public void recordMessageDeadLettered(String topic, String reason) {
        if (messagesDeadLettered != null) {
            messagesDeadLettered.increment();
        }
        if (registry != null) {
            Counter.builder("peegeeq.messages.dead_lettered.by.topic")
                .tag("instance", instanceId)
                .tag("topic", topic)
                .tag("reason", reason)
                .register(registry)
                .increment();
        }
    }

    public void recordMessageAcknowledged(String topic, long durationMs) {
        // Record acknowledgment as a successful processing
        recordMessageProcessed(topic, Duration.ofMillis(durationMs));

        if (registry != null) {
            Timer.builder("peegeeq.message.ack.time")
                .tag("instance", instanceId)
                .tag("topic", topic)
                .register(registry)
                .record(Duration.ofMillis(durationMs));
        }
    }

    public void recordMessageAckError(String topic) {
        recordMessageFailed(topic, "ack_error");
    }

    // Database operation metrics
    public void recordDatabaseOperation(String operation, Duration duration) {
        if (databaseOperationTime != null) {
            databaseOperationTime.record(duration);
        }
        if (registry != null) {
            Timer.builder("peegeeq.database.operation.time.by.operation")
                .tag("instance", instanceId)
                .tag("operation", operation)
                .register(registry)
                .record(duration);
        }
    }

    public void recordConnectionAcquisition(Duration duration) {
        if (connectionAcquisitionTime != null) {
            connectionAcquisitionTime.record(duration);
        }
    }

    // Connection pool metrics
    public void updateConnectionPoolMetrics(int active, int idle, int pending) {
        if (activeConnections != null) {
            activeConnections.set(active);
        }
        if (idleConnections != null) {
            idleConnections.set(idle);
        }
        if (pendingConnections != null) {
            pendingConnections.set(pending);
        }
    }

    // Generic metrics methods for provider interface
    public void incrementCounter(String name, Map<String, String> tags) {
        if (registry != null) {
            Counter.Builder builder = Counter.builder(name)
                .tag("instance", instanceId);

            if (tags != null) {
                tags.forEach(builder::tag);
            }

            builder.register(registry).increment();
        }
    }

    public void recordTimer(String name, long durationMs, Map<String, String> tags) {
        if (registry != null) {
            Timer.Builder builder = Timer.builder(name)
                .tag("instance", instanceId);

            if (tags != null) {
                tags.forEach(builder::tag);
            }

            builder.register(registry).record(Duration.ofMillis(durationMs));
        }
    }

    public void recordGauge(String name, double value, Map<String, String> tags) {
        if (registry != null) {
            var builder = Gauge.builder(name, () -> value)
                .tag("instance", instanceId);

            if (tags != null) {
                tags.forEach(builder::tag);
            }

            builder.register(registry);
        }
    }

    public long getQueueDepth(String topic) {
        // For now, return native queue depth - this could be enhanced to be topic-specific
        return (long) getNativeQueueDepth();
    }

    // Parameterized test support methods

    /**
     * Record a test-specific timer metric with profile information.
     * This method is specifically designed for parameterized performance tests.
     *
     * @param name metric name (will be prefixed with 'peegeeq.test.')
     * @param durationMs duration in milliseconds
     * @param profileName the performance profile name
     * @param testName the test name
     * @param additionalTags additional tags to include
     */
    public void recordTestTimer(String name, long durationMs, String profileName,
                               String testName, Map<String, String> additionalTags) {
        Map<String, String> tags = createTestTags(profileName, testName, additionalTags);
        recordTimer("peegeeq.test." + name, durationMs, tags);
    }

    /**
     * Record a test-specific counter metric with profile information.
     * This method is specifically designed for parameterized performance tests.
     *
     * @param name metric name (will be prefixed with 'peegeeq.test.')
     * @param profileName the performance profile name
     * @param testName the test name
     * @param additionalTags additional tags to include
     */
    public void recordTestCounter(String name, String profileName, String testName,
                                 Map<String, String> additionalTags) {
        Map<String, String> tags = createTestTags(profileName, testName, additionalTags);
        incrementCounter("peegeeq.test." + name, tags);
    }

    /**
     * Record a test-specific gauge metric with profile information.
     * This method is specifically designed for parameterized performance tests.
     *
     * @param name metric name (will be prefixed with 'peegeeq.test.')
     * @param value the gauge value
     * @param profileName the performance profile name
     * @param testName the test name
     * @param additionalTags additional tags to include
     */
    public void recordTestGauge(String name, double value, String profileName,
                               String testName, Map<String, String> additionalTags) {
        Map<String, String> tags = createTestTags(profileName, testName, additionalTags);
        recordGauge("peegeeq.test." + name, value, tags);
    }

    /**
     * Record performance test execution metrics.
     * This is a convenience method for recording common performance test metrics.
     *
     * @param testName the test name
     * @param profileName the performance profile name
     * @param durationMs execution duration in milliseconds
     * @param success whether the test was successful
     * @param throughput throughput in operations per second (optional, use 0 if not applicable)
     * @param additionalMetrics additional metrics to record
     */
    public void recordPerformanceTestExecution(String testName, String profileName,
                                             long durationMs, boolean success,
                                             double throughput, Map<String, Object> additionalMetrics) {
        Map<String, String> baseTags = Map.of(
            "test_name", testName,
            "success", String.valueOf(success)
        );

        // Record execution time
        recordTestTimer("execution.time", durationMs, profileName, testName, baseTags);

        // Record success/failure counters
        if (success) {
            recordTestCounter("success", profileName, testName, baseTags);
        } else {
            recordTestCounter("failure", profileName, testName, baseTags);
        }

        // Record throughput if provided
        if (throughput > 0) {
            recordTestGauge("throughput", throughput, profileName, testName, baseTags);
        }

        // Record additional metrics
        if (additionalMetrics != null) {
            for (Map.Entry<String, Object> entry : additionalMetrics.entrySet()) {
                String metricName = entry.getKey();
                Object value = entry.getValue();

                if (value instanceof Number) {
                    double numericValue = ((Number) value).doubleValue();
                    recordTestGauge(metricName, numericValue, profileName, testName, baseTags);
                }
            }
        }
    }

    /**
     * Create standardized tags for test metrics.
     *
     * @param profileName the performance profile name
     * @param testName the test name
     * @param additionalTags additional tags to include
     * @return combined tags map
     */
    private Map<String, String> createTestTags(String profileName, String testName,
                                              Map<String, String> additionalTags) {
        Map<String, String> tags = new HashMap<>();
        tags.put("profile", profileName);
        tags.put("test_name", testName);
        tags.put("test_instance", instanceId);

        if (additionalTags != null) {
            tags.putAll(additionalTags);
        }

        return tags;
    }

    public Map<String, Object> getAllMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        if (messagesSent != null) {
            metrics.put("messages_sent", messagesSent.count());
        }
        if (messagesReceived != null) {
            metrics.put("messages_received", messagesReceived.count());
        }
        if (messagesProcessed != null) {
            metrics.put("messages_processed", messagesProcessed.count());
        }
        if (messagesFailed != null) {
            metrics.put("messages_failed", messagesFailed.count());
        }

        metrics.put("outbox_queue_depth", getOutboxQueueDepth());
        metrics.put("native_queue_depth", getNativeQueueDepth());
        metrics.put("dead_letter_queue_depth", getDeadLetterQueueDepth());

        if (activeConnections != null) {
            metrics.put("active_connections", activeConnections.get());
        }
        if (idleConnections != null) {
            metrics.put("idle_connections", idleConnections.get());
        }

        return metrics;
    }

    // Queue depth calculations
    private double getOutboxQueueDepth() {
        return executeCountQuery("SELECT COUNT(*) FROM outbox WHERE status IN ('PENDING', 'PROCESSING')");
    }

    private double getNativeQueueDepth() {
        return executeCountQuery("SELECT COUNT(*) FROM queue_messages WHERE status = 'AVAILABLE'");
    }

    private double getDeadLetterQueueDepth() {
        return executeCountQuery("SELECT COUNT(*) FROM dead_letter_queue");
    }

    private double executeCountQuery(String sql) {
        if (reactivePool != null) {
            // Use reactive approach - block on the result for compatibility with synchronous interface
            try {
                return executeCountQueryReactive(sql).toCompletionStage().toCompletableFuture().get();
            } catch (Exception e) {
                logger.warn("Failed to execute reactive count query: {}", sql, e);
                return 0;
            }
        } else {
            // Use legacy JDBC approach
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {

                return rs.next() ? rs.getLong(1) : 0;
            } catch (SQLException e) {
                logger.warn("Failed to execute count query: {}", sql, e);
                return 0;
            }
        }
    }

    /**
     * Reactive version of executeCountQuery using Vert.x Pool.
     * This method returns a Future for non-blocking database operations.
     */
    private Future<Double> executeCountQueryReactive(String sql) {
        if (reactivePool == null) {
            return Future.failedFuture(new IllegalStateException("No reactive pool available"));
        }

        return reactivePool.withConnection(connection -> {
            return connection.preparedQuery(sql).execute()
                .map(rowSet -> {
                    if (rowSet.iterator().hasNext()) {
                        return (double) rowSet.iterator().next().getLong(0);
                    }
                    return 0.0;
                });
        }).recover(throwable -> {
            logger.warn("Failed to execute reactive count query: {}", sql, throwable);
            return Future.succeededFuture(0.0);
        });
    }

    /**
     * Records metrics to database for historical analysis.
     */
    public void persistMetrics(MeterRegistry registry) {
        if (reactivePool != null) {
            // Use reactive approach - block on the result for compatibility with synchronous interface
            try {
                persistMetricsReactive(registry).toCompletionStage().toCompletableFuture().get();
            } catch (Exception e) {
                // Check if this is a connection error during shutdown (expected during cleanup)
                String errorMsg = e.getMessage();
                boolean isConnectionError = errorMsg != null &&
                    (errorMsg.contains("Connection refused") ||
                     errorMsg.contains("connection may have been lost") ||
                     errorMsg.contains("underlying connection"));

                if (isConnectionError) {
                    logger.debug("Failed to persist metrics due to connection issue (expected during shutdown): {}", errorMsg);
                } else {
                    logger.warn("Failed to persist metrics to database using reactive approach", e);
                }
            }
        } else {
            // Use legacy JDBC approach
            String sql = "INSERT INTO queue_metrics (metric_name, metric_value, tags) VALUES (?, ?, ?::jsonb)";

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                // Persist key metrics
                persistCounter(stmt, "messages_sent", messagesSent);
                persistCounter(stmt, "messages_received", messagesReceived);
                persistCounter(stmt, "messages_processed", messagesProcessed);
                persistCounter(stmt, "messages_failed", messagesFailed);

                stmt.executeBatch();
                logger.debug("Persisted metrics to database");

            } catch (SQLException e) {
                // Check if this is a connection error during shutdown
                String errorMsg = e.getMessage();
                boolean isConnectionError = errorMsg != null &&
                    (errorMsg.contains("Connection refused") ||
                     errorMsg.contains("connection may have been lost") ||
                     errorMsg.contains("underlying connection"));

                if (isConnectionError) {
                    logger.debug("Failed to persist metrics due to connection issue (expected during shutdown): {}", errorMsg);
                } else {
                    logger.warn("Failed to persist metrics to database", e);
                }
            }
        }
    }

    private void persistCounter(PreparedStatement stmt, String name, Counter counter) throws SQLException {
        stmt.setString(1, name);
        stmt.setDouble(2, counter.count());
        stmt.setString(3, "{}"); // Simplified - in real implementation, serialize tags
        stmt.addBatch();
    }

    /**
     * Reactive version of persistMetrics using Vert.x Pool.
     * This method returns a Future for non-blocking database operations.
     */
    private Future<Void> persistMetricsReactive(MeterRegistry registry) {
        if (reactivePool == null) {
            return Future.failedFuture(new IllegalStateException("No reactive pool available"));
        }

        return reactivePool.withTransaction(connection -> {
            // Persist key metrics using reactive patterns
            Future<Void> future = Future.succeededFuture();

            if (messagesSent != null) {
                future = future.compose(v -> persistCounterReactive(connection, "messages_sent", messagesSent));
            }
            if (messagesReceived != null) {
                future = future.compose(v -> persistCounterReactive(connection, "messages_received", messagesReceived));
            }
            if (messagesProcessed != null) {
                future = future.compose(v -> persistCounterReactive(connection, "messages_processed", messagesProcessed));
            }
            if (messagesFailed != null) {
                future = future.compose(v -> persistCounterReactive(connection, "messages_failed", messagesFailed));
            }

            return future.onSuccess(v -> logger.debug("Persisted metrics to database using reactive patterns"));
        }).recover(throwable -> {
            // Check if this is a connection error during shutdown (expected during cleanup)
            String errorMsg = throwable.getMessage();
            boolean isConnectionError = errorMsg != null &&
                (errorMsg.contains("Connection refused") ||
                 errorMsg.contains("connection may have been lost") ||
                 errorMsg.contains("underlying connection"));

            if (isConnectionError) {
                logger.debug("Failed to persist metrics due to connection issue (expected during shutdown): {}", errorMsg);
            } else {
                logger.warn("Failed to persist metrics to database using reactive patterns", throwable);
            }
            return Future.succeededFuture();
        });
    }

    /**
     * Reactive version of persistCounter using Vert.x SqlConnection.
     */
    private Future<Void> persistCounterReactive(io.vertx.sqlclient.SqlConnection connection, String name, Counter counter) {
        String sql = "INSERT INTO queue_metrics (metric_name, metric_value, tags) VALUES ($1, $2, $3::jsonb)";
        return connection.preparedQuery(sql)
            .execute(io.vertx.sqlclient.Tuple.of(name, counter.count(), "{}"))
            .mapEmpty();
    }

    /**
     * Health check metrics.
     */
    public boolean isHealthy() {
        if (reactivePool != null) {
            // Use reactive approach - block on the result for compatibility with synchronous interface
            try {
                return isHealthyReactive().toCompletionStage().toCompletableFuture().get();
            } catch (Exception e) {
                logger.warn("Reactive health check failed", e);
                return false;
            }
        } else {
            // Use legacy JDBC approach
            try (Connection conn = dataSource.getConnection()) {
                return conn.isValid(5); // 5 second timeout
            } catch (SQLException e) {
                logger.warn("Health check failed", e);
                return false;
            }
        }
    }

    /**
     * Reactive version of health check using Vert.x Pool.
     * This method returns a Future for non-blocking health checks.
     */
    public Future<Boolean> isHealthyReactive() {
        if (reactivePool == null) {
            return Future.failedFuture(new IllegalStateException("No reactive pool available"));
        }

        return reactivePool.withConnection(connection -> {
            // Simple query to test connection health
            return connection.preparedQuery("SELECT 1").execute()
                .map(rowSet -> true);
        }).recover(throwable -> {
            logger.warn("Reactive health check failed", throwable);
            return Future.succeededFuture(false);
        });
    }

    /**
     * Gets the instance ID for this metrics instance.
     *
     * @return The instance ID
     */
    public String getInstanceId() {
        return instanceId;
    }

    /**
     * Performance metrics summary.
     */
    public MetricsSummary getSummary() {
        return new MetricsSummary(
            messagesSent != null ? messagesSent.count() : 0.0,
            messagesReceived != null ? messagesReceived.count() : 0.0,
            messagesProcessed != null ? messagesProcessed.count() : 0.0,
            messagesFailed != null ? messagesFailed.count() : 0.0,
            getOutboxQueueDepth(),
            getNativeQueueDepth(),
            getDeadLetterQueueDepth(),
            activeConnections != null ? activeConnections.get() : 0L,
            idleConnections != null ? idleConnections.get() : 0L
        );
    }

    /**
     * Metrics summary data class.
     */
    public static class MetricsSummary {
        private final double messagesSent;
        private final double messagesReceived;
        private final double messagesProcessed;
        private final double messagesFailed;
        private final double outboxQueueDepth;
        private final double nativeQueueDepth;
        private final double deadLetterQueueDepth;
        private final long activeConnections;
        private final long idleConnections;

        public MetricsSummary(double messagesSent, double messagesReceived, double messagesProcessed,
                            double messagesFailed, double outboxQueueDepth, double nativeQueueDepth,
                            double deadLetterQueueDepth, long activeConnections, long idleConnections) {
            this.messagesSent = messagesSent;
            this.messagesReceived = messagesReceived;
            this.messagesProcessed = messagesProcessed;
            this.messagesFailed = messagesFailed;
            this.outboxQueueDepth = outboxQueueDepth;
            this.nativeQueueDepth = nativeQueueDepth;
            this.deadLetterQueueDepth = deadLetterQueueDepth;
            this.activeConnections = activeConnections;
            this.idleConnections = idleConnections;
        }

        // Getters
        public double getMessagesSent() { return messagesSent; }
        public double getMessagesReceived() { return messagesReceived; }
        public double getMessagesProcessed() { return messagesProcessed; }
        public double getMessagesFailed() { return messagesFailed; }
        public double getOutboxQueueDepth() { return outboxQueueDepth; }
        public double getNativeQueueDepth() { return nativeQueueDepth; }
        public double getDeadLetterQueueDepth() { return deadLetterQueueDepth; }
        public long getActiveConnections() { return activeConnections; }
        public long getIdleConnections() { return idleConnections; }

        public double getSuccessRate() {
            double total = messagesProcessed + messagesFailed;
            return total > 0 ? (messagesProcessed / total) * 100 : 0;
        }
    }


}
