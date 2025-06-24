package dev.mars.peegeeq.db.metrics;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Comprehensive metrics collection for PeeGeeQ message queue system.
 * Provides metrics for message processing, queue depth, connection pools, and performance.
 */
public class PeeGeeQMetrics implements MeterBinder {
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQMetrics.class);

    private final DataSource dataSource;
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

    public PeeGeeQMetrics(DataSource dataSource, String instanceId) {
        this.dataSource = dataSource;
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
        messagesSent.increment();
        Counter.builder("peegeeq.messages.sent.by.topic")
            .tag("instance", instanceId)
            .tag("topic", topic)
            .register(registry)
            .increment();
    }

    public void recordMessageReceived(String topic) {
        messagesReceived.increment();
        Counter.builder("peegeeq.messages.received.by.topic")
            .tag("instance", instanceId)
            .tag("topic", topic)
            .register(registry)
            .increment();
    }

    public void recordMessageProcessed(String topic, Duration processingTime) {
        messagesProcessed.increment();
        Counter.builder("peegeeq.messages.processed.by.topic")
            .tag("instance", instanceId)
            .tag("topic", topic)
            .register(registry)
            .increment();

        messageProcessingTime.record(processingTime);
        Timer.builder("peegeeq.message.processing.time.by.topic")
            .tag("instance", instanceId)
            .tag("topic", topic)
            .register(registry)
            .record(processingTime);
    }

    public void recordMessageFailed(String topic, String errorType) {
        messagesFailed.increment();
        Counter.builder("peegeeq.messages.failed.by.topic")
            .tag("instance", instanceId)
            .tag("topic", topic)
            .tag("error_type", errorType)
            .register(registry)
            .increment();
    }

    public void recordMessageRetried(String topic, int retryCount) {
        messagesRetried.increment();
        Counter.builder("peegeeq.messages.retried.by.topic")
            .tag("instance", instanceId)
            .tag("topic", topic)
            .tag("retry_count", String.valueOf(retryCount))
            .register(registry)
            .increment();
    }

    public void recordMessageDeadLettered(String topic, String reason) {
        messagesDeadLettered.increment();
        Counter.builder("peegeeq.messages.dead_lettered.by.topic")
            .tag("instance", instanceId)
            .tag("topic", topic)
            .tag("reason", reason)
            .register(registry)
            .increment();
    }

    // Database operation metrics
    public void recordDatabaseOperation(String operation, Duration duration) {
        databaseOperationTime.record(duration);
        Timer.builder("peegeeq.database.operation.time.by.operation")
            .tag("instance", instanceId)
            .tag("operation", operation)
            .register(registry)
            .record(duration);
    }

    public void recordConnectionAcquisition(Duration duration) {
        connectionAcquisitionTime.record(duration);
    }

    // Connection pool metrics
    public void updateConnectionPoolMetrics(int active, int idle, int pending) {
        activeConnections.set(active);
        idleConnections.set(idle);
        pendingConnections.set(pending);
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
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            logger.warn("Failed to execute count query: {}", sql, e);
            return 0;
        }
    }

    /**
     * Records metrics to database for historical analysis.
     */
    public void persistMetrics(MeterRegistry registry) {
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
            logger.warn("Failed to persist metrics to database", e);
        }
    }

    private void persistCounter(PreparedStatement stmt, String name, Counter counter) throws SQLException {
        stmt.setString(1, name);
        stmt.setDouble(2, counter.count());
        stmt.setString(3, "{}"); // Simplified - in real implementation, serialize tags
        stmt.addBatch();
    }

    /**
     * Health check metrics.
     */
    public boolean isHealthy() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(5); // 5 second timeout
        } catch (SQLException e) {
            logger.warn("Health check failed", e);
            return false;
        }
    }

    /**
     * Performance metrics summary.
     */
    public MetricsSummary getSummary() {
        return new MetricsSummary(
            messagesSent.count(),
            messagesReceived.count(),
            messagesProcessed.count(),
            messagesFailed.count(),
            getOutboxQueueDepth(),
            getNativeQueueDepth(),
            getDeadLetterQueueDepth(),
            activeConnections.get(),
            idleConnections.get()
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
