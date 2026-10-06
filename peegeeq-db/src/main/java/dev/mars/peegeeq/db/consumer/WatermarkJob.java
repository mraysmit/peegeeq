package dev.mars.peegeeq.db.consumer;

import dev.mars.peegeeq.db.health.BackgroundTaskFailureTracker;
import dev.mars.peegeeq.db.health.HealthStatus;
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
 * <p>Sweep failures follow the shared background-task policy of
 * {@link BackgroundTaskFailureTracker}: the first consecutive failure is logged at WARN with its
 * stack, persistent failure escalates to a count-bearing ERROR summary, and a successful sweep
 * restores health. {@link #checkHealth()} exposes that state.
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
    private final BackgroundTaskFailureTracker failureTracker;

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
        this.failureTracker = new BackgroundTaskFailureTracker(
                "background-watermark-" + topic, "Watermark sweep for topic " + topic, logger);
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
     * <p>A sweep that fails while stop is waiting has already been recorded by the failure tracker
     * when it settled, so the returned Future still completes successfully. This matches
     * {@code DeadConsumerDetectionJob} and {@code ConsumerGroupRetryJob}, and keeps a background
     * sweep failure from failing the engine teardown that awaits this Future.
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
                .transform(ar -> {
                    logger.info(
                            "WatermarkJob stopped: topic={}, totalRuns={}, totalSwept={}, totalFailures={}",
                            topic, totalRunCount.getAcquire(), totalSwept.getAcquire(),
                            failureTracker.totalFailures());
                    return Future.<Void>succeededFuture();
                });
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

    public long getTotalFailures() {
        return failureTracker.totalFailures();
    }

    public Future<HealthStatus> checkHealth() {
        return failureTracker.check();
    }

    public String getHealthComponentName() {
        return failureTracker.component();
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

        // A synchronous throw or a null Future from the calculator is a sweep failure like any other.
        // Left unconverted it would escape this method with processingInProgress still set, and no
        // later run would ever start.
        Future<Integer> sweep;
        try {
            sweep = calculator.calculateAndSweep(topic);
            if (sweep == null) {
                sweep = Future.failedFuture(new IllegalStateException(
                        "Watermark calculator returned a null Future for topic " + topic));
            }
        } catch (RuntimeException error) {
            sweep = Future.failedFuture(error);
        }

        Future<Void> currentRun = sweep
                .onSuccess(sweptCount -> {
                    failureTracker.recordSuccess();
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
                    failureTracker.recordFailure(throwable);
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
