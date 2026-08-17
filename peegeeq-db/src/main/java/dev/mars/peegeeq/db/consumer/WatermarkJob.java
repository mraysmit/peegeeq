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
    private volatile Future<Void> inFlightRun = Future.succeededFuture();

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
        stopAsync().onFailure(error ->
                logger.error("WatermarkJob asynchronous stop failed: topic={}", topic, error));
    }

    /**
     * Fences new runs, cancels future scheduling, and waits for the current sweep
     * to settle before reporting that the job has stopped.
     *
     * @return future completing when no watermark sweep remains in flight
     */
    public synchronized Future<Void> stopAsync() {
        running = false;
        if (timerId >= 0) {
            vertx.cancelTimer(timerId);
            timerId = -1;
        }

        Future<Void> stopped = inFlightRun;
        return stopped
                .onSuccess(v -> logger.info(
                        "WatermarkJob stopped: topic={}, totalRuns={}, totalSwept={}",
                        topic, totalRunCount.getAcquire(), totalSwept.getAcquire()))
                .onFailure(error -> logger.error(
                        "WatermarkJob stopped after in-flight sweep failure: topic={}, totalRuns={}, totalSwept={}",
                        topic, totalRunCount.getAcquire(), totalSwept.getAcquire(), error));
    }

    public boolean isRunning() {
        return running;
    }

    public long getTotalRunCount() {
        return totalRunCount.getAcquire();
    }

    public long getTotalSwept() {
        return totalSwept.getAcquire();
    }

    /**
     * Runs one calculate-and-sweep pass. Exposed for testing.
     */
    public Future<Integer> runOnce() {
        return calculator.calculateAndSweep(topic);
    }

    private synchronized void runProcessing() {
        if (!running) {
            return;
        }
        if (!processingInProgress.compareAndSet(false, true)) {
            logger.debug("Watermark processing already in progress for topic={}, skipping", topic);
            return;
        }

        Future<Void> currentRun = calculator.calculateAndSweep(topic)
                .onSuccess(sweptCount -> {
                    totalRunCount.incrementAndGet();
                    totalSwept.addAndGet(sweptCount);
                    if (sweptCount > 0) {
                        logger.info("Watermark sweep #{}: topic={}, swept={}",
                                totalRunCount.getAcquire(), topic, sweptCount);
                    } else {
                        logger.debug("Watermark sweep #{}: topic={}, no messages swept",
                                totalRunCount.getAcquire(), topic);
                    }
                })
                .onFailure(throwable -> {
                    totalRunCount.incrementAndGet();
                    logger.error("Watermark sweep #{} failed: topic={}",
                            totalRunCount.getAcquire(), topic, throwable);
                })
                .eventually(() -> {
                    processingInProgress.set(false);
                    return Future.succeededFuture();
                })
                .mapEmpty();
        inFlightRun = currentRun;
        currentRun
                .onSuccess(ignored -> clearSettledRun(currentRun))
                .onFailure(error -> {
                    clearSettledRun(currentRun);
                    logger.debug("Recorded failed watermark run completion: topic={}", topic);
                });
    }

    private synchronized void clearSettledRun(Future<Void> settledRun) {
        if (inFlightRun == settledRun) {
            inFlightRun = Future.succeededFuture();
        }
    }
}
