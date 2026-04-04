package dev.mars.peegeeq.db.consumer;

import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scheduled job that periodically processes failed consumer group messages
 * for retry or dead-letter-queue movement.
 *
 * <p>This job wraps {@link ConsumerGroupRetryService} with a Vert.x periodic timer,
 * automatically retrying FAILED messages that haven't exhausted their retry budget
 * and moving exhausted messages to the dead letter queue.</p>
 *
 * <p>Follows the same lifecycle and operational pattern as
 * {@link dev.mars.peegeeq.db.cleanup.DeadConsumerDetectionJob}.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-01
 * @version 1.0
 */
public class ConsumerGroupRetryJob {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupRetryJob.class);

    /** Default retry interval: 30 seconds */
    public static final long DEFAULT_RETRY_INTERVAL_MS = 30_000L;

    private final Vertx vertx;
    private final ConsumerGroupRetryService retryService;
    private final long retryIntervalMs;

    private volatile long timerId = -1;
    private volatile boolean running = false;
    private final AtomicBoolean processingInProgress = new AtomicBoolean(false);

    private volatile TraceCtx lifecycleTrace;

    // Cumulative stats
    private final AtomicLong totalRunCount = new AtomicLong(0);
    private final AtomicLong totalRetried = new AtomicLong(0);
    private final AtomicLong totalMovedToDlq = new AtomicLong(0);
    private final AtomicLong totalFailures = new AtomicLong(0);

    /**
     * Creates a new ConsumerGroupRetryJob with the default retry interval.
     *
     * @param vertx The Vert.x instance for timer scheduling
     * @param retryService The retry service
     */
    public ConsumerGroupRetryJob(Vertx vertx, ConsumerGroupRetryService retryService) {
        this(vertx, retryService, DEFAULT_RETRY_INTERVAL_MS);
    }

    /**
     * Creates a new ConsumerGroupRetryJob with a custom retry interval.
     *
     * @param vertx The Vert.x instance for timer scheduling
     * @param retryService The retry service
     * @param retryIntervalMs The interval between retry scans in milliseconds
     */
    public ConsumerGroupRetryJob(Vertx vertx, ConsumerGroupRetryService retryService, long retryIntervalMs) {
        this.vertx = Objects.requireNonNull(vertx, "vertx cannot be null");
        this.retryService = Objects.requireNonNull(retryService, "retryService cannot be null");
        if (retryIntervalMs <= 0) {
            throw new IllegalArgumentException("retryIntervalMs must be positive");
        }
        this.retryIntervalMs = retryIntervalMs;
        logger.info("ConsumerGroupRetryJob created: interval={}ms ({}s)",
                retryIntervalMs, retryIntervalMs / 1000);
    }

    /**
     * Starts the periodic retry job.
     *
     * @throws IllegalStateException if the job is already running
     */
    public void start() {
        if (running) {
            throw new IllegalStateException("ConsumerGroupRetryJob is already running");
        }

        lifecycleTrace = TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(lifecycleTrace)) {
            logger.info("Starting ConsumerGroupRetryJob: interval={}ms", retryIntervalMs);
        }
        running = true;

        // Run once immediately
        runProcessing();

        // Schedule periodic runs
        timerId = vertx.setPeriodic(retryIntervalMs, id -> runProcessing());

        try (var scope = TraceContextUtil.mdcScope(lifecycleTrace)) {
            logger.info("ConsumerGroupRetryJob started: timerId={}", timerId);
        }
    }

    /**
     * Stops the periodic retry job.
     */
    public void stop() {
        if (!running) {
            logger.debug("ConsumerGroupRetryJob is not running, nothing to stop");
            return;
        }

        TraceCtx stopTrace = lifecycleTrace != null ? lifecycleTrace : TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(stopTrace)) {
            logger.info("Stopping ConsumerGroupRetryJob: timerId={}, totalRuns={}, totalRetried={}, " +
                            "totalMovedToDlq={}, totalFailures={}",
                    timerId, totalRunCount.get(), totalRetried.get(),
                    totalMovedToDlq.get(), totalFailures.get());
        }

        running = false;

        if (timerId >= 0) {
            vertx.cancelTimer(timerId);
            timerId = -1;
        }

        try (var scope = TraceContextUtil.mdcScope(stopTrace)) {
            logger.info("ConsumerGroupRetryJob stopped");
        }
        lifecycleTrace = null;
    }

    public boolean isRunning() {
        return running;
    }

    public long getTotalRunCount() {
        return totalRunCount.get();
    }

    public long getTotalRetried() {
        return totalRetried.get();
    }

    public long getTotalMovedToDlq() {
        return totalMovedToDlq.get();
    }

    public long getTotalFailures() {
        return totalFailures.get();
    }

    /**
     * Runs a single processing pass (for testing).
     *
     * @return Future containing the result
     */
    public Future<ConsumerGroupRetryService.RetryResult> runOnce() {
        return retryService.processFailedMessages();
    }

    private void runProcessing() {
        if (!running) {
            return;
        }

        if (!processingInProgress.compareAndSet(false, true)) {
            logger.debug("Retry processing already in progress, skipping");
            return;
        }

        TraceCtx trace = TraceCtx.createNew();
        long startMs = System.currentTimeMillis();

        retryService.processFailedMessages()
                .onSuccess(result -> {
                    totalRunCount.incrementAndGet();
                    totalRetried.addAndGet(result.retriedCount());
                    totalMovedToDlq.addAndGet(result.dlqCount());

                    long durationMs = System.currentTimeMillis() - startMs;
                    try (var scope = TraceContextUtil.mdcScope(trace)) {
                        if (result.retriedCount() > 0 || result.dlqCount() > 0) {
                            logger.info("Retry scan #{}: retried={}, movedToDlq={} ({}ms)",
                                    totalRunCount.get(), result.retriedCount(), result.dlqCount(), durationMs);
                        } else {
                            logger.debug("Retry scan #{}: no actions needed ({}ms)",
                                    totalRunCount.get(), durationMs);
                        }
                    }
                })
                .onFailure(throwable -> {
                    totalRunCount.incrementAndGet();
                    totalFailures.incrementAndGet();
                    try (var scope = TraceContextUtil.mdcScope(trace)) {
                        logger.error("Retry scan #{} failed ({}ms)",
                                totalRunCount.get(), System.currentTimeMillis() - startMs, throwable);
                    }
                })
                .eventually(() -> {
                    processingInProgress.set(false);
                    return Future.succeededFuture();
                });
    }
}
