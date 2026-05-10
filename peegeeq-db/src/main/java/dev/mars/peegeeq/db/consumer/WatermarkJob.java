package dev.mars.peegeeq.db.consumer;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Periodic job that calculates and advances watermarks, then sweeps completed
 * messages for a given topic. Follows the same lifecycle pattern as
 * {@link ConsumerGroupRetryJob}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-12
 * @version 1.0
 */
public class WatermarkJob {

    private static final Logger logger = LoggerFactory.getLogger(WatermarkJob.class);

    public static final long DEFAULT_INTERVAL_MS = 60_000L;

    private final Vertx vertx;
    private final WatermarkCalculator calculator;
    private final String topic;
    private final long intervalMs;

    private volatile long timerId = -1;
    private volatile boolean running = false;
    private final AtomicBoolean processingInProgress = new AtomicBoolean(false);
    private final AtomicLong totalRunCount = new AtomicLong(0);
    private final AtomicLong totalSwept = new AtomicLong(0);

    public WatermarkJob(Vertx vertx, WatermarkCalculator calculator, String topic) {
        this(vertx, calculator, topic, DEFAULT_INTERVAL_MS);
    }

    public WatermarkJob(Vertx vertx, WatermarkCalculator calculator, String topic, long intervalMs) {
        this.vertx = Objects.requireNonNull(vertx, "vertx cannot be null");
        this.calculator = Objects.requireNonNull(calculator, "calculator cannot be null");
        this.topic = Objects.requireNonNull(topic, "topic cannot be null");
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("intervalMs must be positive");
        }
        this.intervalMs = intervalMs;
    }

    public void start() {
        if (running) {
            throw new IllegalStateException("WatermarkJob is already running");
        }
        running = true;
        runProcessing();
        timerId = vertx.setPeriodic(intervalMs, id -> runProcessing());
        logger.info("WatermarkJob started: topic={}, interval={}ms, timerId={}", topic, intervalMs, timerId);
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (timerId >= 0) {
            vertx.cancelTimer(timerId);
            timerId = -1;
        }
        logger.info("WatermarkJob stopped: topic={}, totalRuns={}, totalSwept={}", topic, totalRunCount.get(), totalSwept.get());
    }

    public boolean isRunning() {
        return running;
    }

    public long getTotalRunCount() {
        return totalRunCount.get();
    }

    public long getTotalSwept() {
        return totalSwept.get();
    }

    /**
     * Runs one calculate-and-sweep pass. Exposed for testing.
     */
    public Future<Integer> runOnce() {
        return calculator.calculateAndSweep(topic);
    }

    private void runProcessing() {
        if (!running) {
            return;
        }
        if (!processingInProgress.compareAndSet(false, true)) {
            logger.debug("Watermark processing already in progress for topic={}, skipping", topic);
            return;
        }

        calculator.calculateAndSweep(topic)
                .onSuccess(sweptCount -> {
                    totalRunCount.incrementAndGet();
                    totalSwept.addAndGet(sweptCount);
                    if (sweptCount > 0) {
                        logger.info("Watermark sweep #{}: topic={}, swept={}", totalRunCount.get(), topic, sweptCount);
                    } else {
                        logger.debug("Watermark sweep #{}: topic={}, no messages swept", totalRunCount.get(), topic);
                    }
                })
                .onFailure(throwable -> {
                    totalRunCount.incrementAndGet();
                    logger.error("Watermark sweep #{} failed: topic={}", totalRunCount.get(), topic, throwable);
                })
                .eventually(() -> {
                    processingInProgress.set(false);
                    return Future.succeededFuture();
                });
    }
}
