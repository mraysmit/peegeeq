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


import dev.mars.peegeeq.api.database.MetricsProvider;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;

import java.time.Duration;

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
public class PeeGeeQMetrics implements MeterBinder, MetricsProvider {
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQMetrics.class);

    private final Pool reactivePool;
    private final String instanceId;
    private MeterRegistry registry;

    // Cached queue depth values refreshed periodically by refreshDepthCache()
    private volatile double cachedOutboxDepth = 0.0;
    private volatile double cachedNativeDepth = 0.0;
    private volatile double cachedDeadLetterDepth = 0.0;

    // Shutdown coordination prevents database operations during closeReactive()
    private volatile boolean closing = false;

    // Counters
    private Counter messagesSent;
    private Counter messagesReceived;
    private Counter messagesProcessed;
    private Counter messagesFailed;
    private Counter messagesDeadLettered;

    // Timers
    // messagesRetried and databaseOperationTime were DELETED 2026-08-09 (metrics-stack
    // review backlog): no production code ever fed either — the retried counter and the
    // database-operation timer permanently reported 0, fabricated healthy signals. Native
    // retry accounting lives in the retry_count column; re-add a meter only together with
    // the production call that feeds it.
    private Timer messageProcessingTime;
    private Timer connectionAcquisitionTime;

    // Gauges
    // The three peegeeq.connection.pool.* gauges that lived here were DELETED by the
    // metrics-stack remediation (2026-08-09): no production code ever fed them, so they
    // permanently reported 0 — a fabricated healthy signal. Client-side acquire-wait is now
    // MEASURED by the manager's acquisition canary (recordConnectionAcquisition + the
    // saturation snapshot); the server-side connection breakdown comes from pg_stat_activity.
    /**
     * Constructor using reactive Pool for Vert.x 5.x patterns.
     * This is the only constructor - pure Vert.x reactive implementation.
     */
    public PeeGeeQMetrics(Pool reactivePool, String instanceId) {
        this.reactivePool = reactivePool;
        this.instanceId = instanceId;
    }

    /**
     * Marks this metrics instance as closing to fail-fast any in-flight timer operations.
     * Called by PeeGeeQManager.closeReactive() to prevent database queries after shutdown begins.
     */
    public void markClosing() {
        this.closing = true;
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

        messagesDeadLettered = Counter.builder("peegeeq.messages.dead_lettered")
            .description("Total number of messages sent to dead letter queue")
            .tag("instance", instanceId)
            .register(registry);

        // Processing time metrics
        messageProcessingTime = Timer.builder("peegeeq.message.processing.time")
            .description("Time taken to process messages")
            .tag("instance", instanceId)
            .register(registry);

        connectionAcquisitionTime = Timer.builder("peegeeq.connection.acquisition.time")
            .description("Time taken to acquire database connections")
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

    // Message processing metrics - MetricsProvider interface implementations
    @Override
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

    @Override
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

    // recordMessageReceived(String,long), recordMessageAcknowledged and the
    // recordMessageSendError/ReceiveError/AckError wrappers were DELETED 2026-08-09
    // (metrics-stack review backlog): zero production callers — the first two had zero
    // callers anywhere, including tests.
    @Override
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
            // publishPercentiles makes the per-topic timer carry a client-side
            // histogram, so /stats can report p50/p95/p99 per queue (telemetry
            // G1). The config binds at the timer's FIRST registration; later
            // register() calls with the same name+tags return that meter.
            Timer.builder("peegeeq.message.processing.time.by.topic")
                .tag("instance", instanceId)
                .tag("topic", topic)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(processingTime);
        }
    }

    /**
     * Records a message's delivery latency — enqueue to claim, measured on the
     * database clock inside the claim statement (telemetry G2). Tagged by
     * implementation type: the delivery mechanism IS the native-vs-outbox
     * difference this exists to measure.
     */
    @Override
    public void recordMessageDeliveryLatency(String topic, String implementationType, Duration latency) {
        if (registry != null) {
            Timer.builder("peegeeq.message.delivery.latency.by.topic")
                .tag("instance", instanceId)
                .tag("topic", topic)
                .tag("implementation", implementationType)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(latency);
        }
    }

    /**
     * The processing-time distribution recorded for a topic, or null when
     * nothing has been recorded (no registry bound, no timer yet, or zero
     * samples). Reads the same per-topic timer recordMessageProcessed feeds —
     * one source, so /stats cannot disagree with the recorded metrics.
     */
    @Override
    public dev.mars.peegeeq.api.messaging.DurationPercentiles getProcessingTimePercentiles(String topic) {
        return percentilesFor("peegeeq.message.processing.time.by.topic", topic);
    }

    /**
     * The delivery-latency distribution recorded for a topic (telemetry G2),
     * same null-means-no-data contract as the processing-time query.
     */
    @Override
    public dev.mars.peegeeq.api.messaging.DurationPercentiles getDeliveryLatencyPercentiles(String topic) {
        return percentilesFor("peegeeq.message.delivery.latency.by.topic", topic);
    }

    /** Snapshot a per-topic timer's percentile histogram; null when absent. */
    private dev.mars.peegeeq.api.messaging.DurationPercentiles percentilesFor(String meterName, String topic) {
        if (registry == null) {
            return null;
        }
        Timer timer = registry.find(meterName)
            .tag("instance", instanceId)
            .tag("topic", topic)
            .timer();
        if (timer == null || timer.count() == 0) {
            return null;
        }
        double p50 = -1.0;
        double p95 = -1.0;
        double p99 = -1.0;
        for (io.micrometer.core.instrument.distribution.ValueAtPercentile v
                : timer.takeSnapshot().percentileValues()) {
            if (v.percentile() == 0.5) {
                p50 = v.value(java.util.concurrent.TimeUnit.MILLISECONDS);
            } else if (v.percentile() == 0.95) {
                p95 = v.value(java.util.concurrent.TimeUnit.MILLISECONDS);
            } else if (v.percentile() == 0.99) {
                p99 = v.value(java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        }
        if (p50 < 0 || p95 < 0 || p99 < 0) {
            // The timer exists but carries no percentile histogram (registered
            // by an older code path). Absence, not fabricated zeroes.
            return null;
        }
        return new dev.mars.peegeeq.api.messaging.DurationPercentiles(
            timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS), p50, p95, p99, timer.count());
    }

    @Override
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

    @Override
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

    public void recordConnectionAcquisition(Duration duration) {
        if (connectionAcquisitionTime != null) {
            connectionAcquisitionTime.record(duration);
        }
    }

    // The generic pass-through surface (incrementCounter, recordTimer, recordGauge with its
    // dynamic-gauge machinery, getAllMetrics) was DELETED 2026-08-09 (metrics-stack review
    // backlog): zero production callers — a speculative API that let anything register
    // unnamed meters outside the defined contract. TestSupportMetrics (peegeeq-test-support)
    // is an independent class and keeps its own same-named methods.

    @Override
    public String getInstanceId() {
        return instanceId;
    }

    // Queue depth synchronous reads from cache for gauges and MetricsProvider
    private double getOutboxQueueDepth() {
        return cachedOutboxDepth;
    }

    private double getNativeQueueDepth() {
        return cachedNativeDepth;
    }

    private double getDeadLetterQueueDepth() {
        return cachedDeadLetterDepth;
    }

    /**
     * Refreshes cached queue depth values by querying the database asynchronously.
     * Call this periodically (e.g. from a Vert.x timer) to keep gauge/summary values current.
     *
     * @return Future that completes when all depth caches have been updated
     */
    public Future<Void> refreshDepthCache() {
        if (reactivePool == null || closing) {
            return Future.succeededFuture();
        }

        Future<Double> outbox = executeCountQuery(
            "SELECT COUNT(*) FROM outbox WHERE status IN ('PENDING', 'PROCESSING')");
        Future<Double> native_ = executeCountQuery(
            "SELECT COUNT(*) FROM queue_messages WHERE status = 'AVAILABLE'");
        Future<Double> deadLetter = executeCountQuery(
            "SELECT COUNT(*) FROM dead_letter_queue");

        return Future.all(outbox, native_, deadLetter).map(cf -> {
            cachedOutboxDepth = cf.resultAt(0);
            cachedNativeDepth = cf.resultAt(1);
            cachedDeadLetterDepth = cf.resultAt(2);
            return null;
        });
    }

    /**
     * Executes a count query reactively using Vert.x Pool.
     */
    private Future<Double> executeCountQuery(String sql) {
        if (reactivePool == null || closing) {
            return Future.succeededFuture(0.0);
        }

        return reactivePool.withConnection(connection -> {
            // Double-check closing flag inside connection callback
            if (closing) {
                return Future.succeededFuture(0.0);
            }
            return connection.preparedQuery(sql).execute()
                .map(rowSet -> {
                    if (rowSet.iterator().hasNext()) {
                        return (double) rowSet.iterator().next().getLong(0);
                    }
                    return 0.0;
                });
        });
    }

    // persistMetrics/persistCounter were DELETED by the metrics-stack remediation
    // (2026-08-09): they INSERTed four counters into queue_metrics on a timer, a table no
    // production code ever read — and one absent from setup-service-provisioned schemas, so
    // the write failed every interval forever. Write-only persistence is dead weight; it
    // returns only with a real reader.


    /**
     * Health check using Vert.x Pool.
     * Returns a Future for non-blocking health checks.
     */
    public Future<Boolean> isHealthy() {
        if (reactivePool == null) {
            return Future.failedFuture(new IllegalStateException("No reactive pool available"));
        }

        return reactivePool.withConnection(connection -> {
            // Simple query to test connection health
            return connection.preparedQuery("SELECT 1").execute()
                .map(rowSet -> true);
        }).transform(ar -> {
            if (ar.failed()) {
                logger.warn("Reactive health check failed", ar.cause());
                return Future.succeededFuture(false);
            }
            return Future.succeededFuture(ar.result());
        });
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
            cachedOutboxDepth,
            cachedNativeDepth,
            cachedDeadLetterDepth
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

        public MetricsSummary(double messagesSent, double messagesReceived, double messagesProcessed,
                            double messagesFailed, double outboxQueueDepth, double nativeQueueDepth,
                            double deadLetterQueueDepth) {
            this.messagesSent = messagesSent;
            this.messagesReceived = messagesReceived;
            this.messagesProcessed = messagesProcessed;
            this.messagesFailed = messagesFailed;
            this.outboxQueueDepth = outboxQueueDepth;
            this.nativeQueueDepth = nativeQueueDepth;
            this.deadLetterQueueDepth = deadLetterQueueDepth;
        }

        // Getters
        public double getMessagesSent() { return messagesSent; }
        public double getMessagesReceived() { return messagesReceived; }
        public double getMessagesProcessed() { return messagesProcessed; }
        public double getMessagesFailed() { return messagesFailed; }
        public double getOutboxQueueDepth() { return outboxQueueDepth; }
        public double getNativeQueueDepth() { return nativeQueueDepth; }
        public double getDeadLetterQueueDepth() { return deadLetterQueueDepth; }

        public double getSuccessRate() {
            double total = messagesProcessed + messagesFailed;
            return total > 0 ? (messagesProcessed / total) * 100 : 0;
        }
    }


}
