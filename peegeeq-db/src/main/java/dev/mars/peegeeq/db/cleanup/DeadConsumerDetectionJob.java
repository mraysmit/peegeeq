package dev.mars.peegeeq.db.cleanup;

import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector.BlockedMessageStats;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector.DeadSubscriptionInfo;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector.DetectionResult;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector.SubscriptionSummary;
import dev.mars.peegeeq.db.cleanup.DeadConsumerGroupCleanup.CleanupResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Scheduled job that periodically runs dead consumer detection with full
 * operational logging.
 *
 * <p>This job wraps {@link DeadConsumerDetector} with a Vert.x periodic timer,
 * automatically detecting and marking subscriptions as DEAD when their heartbeat
 * timeout is exceeded. Each run produces detailed log output suitable for
 * production operations and incident response.</p>
 *
 * <h3>Logging Levels:</h3>
 * <ul>
 *   <li>{@code INFO}  — Run summary on every cycle (healthy or not)</li>
 *   <li>{@code WARN}  — Per-dead-consumer detail, blocked message counts, overdue durations</li>
 *   <li>{@code ERROR} — Detection failures, query errors</li>
 *   <li>{@code DEBUG} — Detailed per-topic breakdowns, timing, skip-if-running guard</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * DeadConsumerDetector detector = new DeadConsumerDetector(connectionManager, "peegeeq-main");
 * DeadConsumerGroupCleanup cleanup = new DeadConsumerGroupCleanup(connectionManager, "peegeeq-main");
 * DeadConsumerDetectionJob job = new DeadConsumerDetectionJob(vertx, detector, cleanup);
 * job.start();           // Starts periodic detection + cleanup
 * // ...
 * job.stop();            // Stops the periodic timer
 * }</pre>
 *
 * <h3>When a dead consumer is detected:</h3>
 * <ol>
 *   <li>Subscription status is set to DEAD by {@link DeadConsumerDetector}</li>
 *   <li>{@link DeadConsumerGroupCleanup} decrements {@code required_consumer_groups} for
 *       PENDING/PROCESSING messages owned by the dead group</li>
 *   <li>Orphaned tracking rows are removed from {@code outbox_consumer_groups}</li>
 *   <li>Messages where all remaining consumers have completed are auto-completed</li>
 *   <li>Auto-completed messages become eligible for normal cleanup/deletion</li>
 * </ol>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-01
 * @version 2.0
 */
public class DeadConsumerDetectionJob {

    private static final Logger logger = LoggerFactory.getLogger(DeadConsumerDetectionJob.class);

    /** Default detection interval: 60 seconds */
    public static final long DEFAULT_DETECTION_INTERVAL_MS = 60_000L;

    private final Vertx vertx;
    private final DeadConsumerDetector detector;
    private final DeadConsumerGroupCleanup cleanup;
    private final long detectionIntervalMs;

    private volatile long timerId = -1;
    private volatile boolean running = false;
    private volatile boolean detectionInProgress = false;

    // Trace context for the lifecycle of this job (start → stop)
    private volatile TraceCtx lifecycleTrace;

    // Cumulative stats across the lifetime of this job
    private final AtomicLong totalRunCount = new AtomicLong(0);
    private final AtomicLong totalDeadDetected = new AtomicLong(0);
    private final AtomicLong totalRunTimeMs = new AtomicLong(0);
    private final AtomicLong consecutiveFailures = new AtomicLong(0);
    private final AtomicLong totalFailures = new AtomicLong(0);
    private final AtomicLong totalMessagesDecremented = new AtomicLong(0);
    private final AtomicLong totalOrphanRowsRemoved = new AtomicLong(0);
    private final AtomicLong totalMessagesAutoCompleted = new AtomicLong(0);
    private final AtomicLong totalCleanupFailures = new AtomicLong(0);

    /**
     * Creates a new DeadConsumerDetectionJob with the default detection interval.
     *
     * @param vertx The Vert.x instance for timer scheduling
     * @param detector The dead consumer detector service
     * @param cleanup The dead consumer group cleanup service
     */
    public DeadConsumerDetectionJob(Vertx vertx, DeadConsumerDetector detector,
                                    DeadConsumerGroupCleanup cleanup) {
        this(vertx, detector, cleanup, DEFAULT_DETECTION_INTERVAL_MS);
    }

    /**
     * Creates a new DeadConsumerDetectionJob with a custom detection interval.
     *
     * @param vertx The Vert.x instance for timer scheduling
     * @param detector The dead consumer detector service
     * @param cleanup The dead consumer group cleanup service
     * @param detectionIntervalMs The interval between detection runs in milliseconds
     */
    public DeadConsumerDetectionJob(Vertx vertx, DeadConsumerDetector detector,
                                    DeadConsumerGroupCleanup cleanup, long detectionIntervalMs) {
        this.vertx = Objects.requireNonNull(vertx, "vertx cannot be null");
        this.detector = Objects.requireNonNull(detector, "detector cannot be null");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup cannot be null");
        if (detectionIntervalMs <= 0) {
            throw new IllegalArgumentException("detectionIntervalMs must be positive");
        }
        this.detectionIntervalMs = detectionIntervalMs;
        logger.info("DeadConsumerDetectionJob created: interval={}ms ({}s), cleanup=enabled",
                detectionIntervalMs, detectionIntervalMs / 1000);
    }

    /**
     * Starts the periodic dead consumer detection job.
     *
     * <p>The job runs immediately on start and then repeats at the configured interval.</p>
     *
     * @throws IllegalStateException if the job is already running
     */
    public void start() {
        if (running) {
            throw new IllegalStateException("DeadConsumerDetectionJob is already running");
        }

        lifecycleTrace = TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(lifecycleTrace)) {
            logger.info("Starting DeadConsumerDetectionJob: interval={}ms, timer will fire every {}s",
                    detectionIntervalMs, detectionIntervalMs / 1000);
        }
        running = true;

        // Run once immediately
        runDetection();

        // Schedule periodic runs
        timerId = vertx.setPeriodic(detectionIntervalMs, id -> runDetection());

        try (var scope = TraceContextUtil.mdcScope(lifecycleTrace)) {
            logger.info("DeadConsumerDetectionJob started: timerId={}", timerId);
        }
    }

    /**
     * Stops the periodic dead consumer detection job.
     */
    public void stop() {
        if (!running) {
            logger.debug("DeadConsumerDetectionJob is not running, nothing to stop");
            return;
        }

        TraceCtx stopTrace = lifecycleTrace != null ? lifecycleTrace : TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(stopTrace)) {
            logger.info("Stopping DeadConsumerDetectionJob: timerId={}, totalRuns={}, totalDeadDetected={}, " +
                            "totalFailures={}, totalMessagesDecremented={}, totalOrphanRowsRemoved={}, " +
                            "totalMessagesAutoCompleted={}, totalCleanupFailures={}, avgRunTimeMs={}",
                    timerId, totalRunCount.get(), totalDeadDetected.get(), totalFailures.get(),
                    totalMessagesDecremented.get(), totalOrphanRowsRemoved.get(),
                    totalMessagesAutoCompleted.get(), totalCleanupFailures.get(),
                    totalRunCount.get() > 0 ? totalRunTimeMs.get() / totalRunCount.get() : 0);
        }

        if (timerId >= 0) {
            vertx.cancelTimer(timerId);
            timerId = -1;
        }

        running = false;
        try (var scope = TraceContextUtil.mdcScope(stopTrace)) {
            logger.info("DeadConsumerDetectionJob stopped");
        }
        lifecycleTrace = null;
    }

    /**
     * Returns whether the job is currently running.
     *
     * @return true if the job is running
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the total number of detection runs completed (success or failure).
     */
    public long getTotalRunCount() {
        return totalRunCount.get();
    }

    /**
     * Returns the total number of dead subscriptions detected across all runs.
     */
    public long getTotalDeadDetected() {
        return totalDeadDetected.get();
    }

    /**
     * Returns the total number of failed detection runs.
     */
    public long getTotalFailures() {
        return totalFailures.get();
    }

    /**
     * Triggers a single detection run manually (useful for testing).
     *
     * @return Future containing the number of subscriptions marked as DEAD
     */
    public Future<Integer> runDetectionOnce() {
        return detector.detectAllDeadSubscriptions();
    }

    /**
     * Triggers a single detection run manually with full detailed results.
     *
     * @return Future containing the full {@link DetectionResult}
     */
    public Future<DetectionResult> runDetectionOnceWithDetails() {
        return detector.detectAllDeadSubscriptionsWithDetails();
    }

    /**
     * Internal method that runs detection and produces detailed operational logs.
     */
    private void runDetection() {
        if (detectionInProgress) {
            logger.debug("Detection run skipped — previous run still in progress");
            return;
        }

        detectionInProgress = true;
        long runNumber = totalRunCount.incrementAndGet();
        long overallStartMs = System.currentTimeMillis();
        TraceCtx trace = TraceCtx.createNew();

        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.debug("Detection run #{} starting", runNumber);
        }

        detector.detectAllDeadSubscriptionsWithDetails()
                .compose(result -> {
                    long detectionMs = System.currentTimeMillis() - overallStartMs;
                    consecutiveFailures.set(0);

                    if (result.hasDeadSubscriptions()) {
                        totalDeadDetected.addAndGet(result.deadCount());
                        logDeadConsumersDetected(runNumber, result, detectionMs);

                        // Query blocked message stats for newly dead + any previously dead consumers
                        return detector.getBlockedMessageStats()
                                .compose(blockedStats -> {
                                    logBlockedMessageStats(blockedStats);

                                    // Run cleanup for all dead groups (newly detected + previously dead)
                                    logger.info("  Starting cleanup for dead consumer groups...");
                                    return cleanup.cleanupAllDeadGroups();
                                })
                                .compose(cleanupResults -> {
                                    logCleanupResults(runNumber, cleanupResults);
                                    return detector.getSubscriptionSummary();
                                })
                                .map(summary -> {
                                    logSubscriptionSummary(runNumber, summary, System.currentTimeMillis() - overallStartMs);
                                    return result.deadCount();
                                });
                    } else {
                        // No dead consumers — still log a periodic health summary
                        return detector.getSubscriptionSummary()
                                .map(summary -> {
                                    logHealthyRun(runNumber, result, summary, detectionMs);
                                    return 0;
                                });
                    }
                })
                .onSuccess(count -> {
                    long elapsed = System.currentTimeMillis() - overallStartMs;
                    totalRunTimeMs.addAndGet(elapsed);
                    detectionInProgress = false;
                })
                .onFailure(error -> {
                    long elapsed = System.currentTimeMillis() - overallStartMs;
                    totalRunTimeMs.addAndGet(elapsed);
                    long failures = consecutiveFailures.incrementAndGet();
                    totalFailures.incrementAndGet();
                    detectionInProgress = false;

                    try (var scope = TraceContextUtil.mdcScope(trace)) {
                        if (failures >= 3) {
                            logger.error("Detection run #{} FAILED ({} consecutive failures, {}ms): {}",
                                    runNumber, failures, elapsed, error.getMessage(), error);
                        } else {
                            logger.error("Detection run #{} failed ({}ms): {}",
                                    runNumber, elapsed, error.getMessage(), error);
                        }
                    }
                });
    }

    /**
     * Logs cleanup results after dead consumer group message cleanup.
     */
    private void logCleanupResults(long runNumber, List<CleanupResult> cleanupResults) {
        if (cleanupResults.isEmpty()) {
            logger.debug("  Run #{}: No dead consumer groups required cleanup", runNumber);
            return;
        }

        int totalDecremented = cleanupResults.stream().mapToInt(CleanupResult::messagesDecremented).sum();
        int totalOrphans = cleanupResults.stream().mapToInt(CleanupResult::orphanRowsRemoved).sum();
        int totalAutoCompleted = cleanupResults.stream().mapToInt(CleanupResult::messagesAutoCompleted).sum();

        // Update cumulative stats
        totalMessagesDecremented.addAndGet(totalDecremented);
        totalOrphanRowsRemoved.addAndGet(totalOrphans);
        totalMessagesAutoCompleted.addAndGet(totalAutoCompleted);

        if (totalDecremented > 0 || totalOrphans > 0 || totalAutoCompleted > 0) {
            logger.warn("  === Cleanup Run #{} Summary: {} group(s) processed ===", runNumber, cleanupResults.size());
            logger.warn("    Messages decremented (required_consumer_groups -= 1): {}", totalDecremented);
            logger.warn("    Orphaned tracking rows removed: {}", totalOrphans);
            logger.warn("    Messages auto-completed (now eligible for deletion): {}", totalAutoCompleted);

            // Per-group breakdown
            for (CleanupResult cr : cleanupResults) {
                if (cr.hadWork()) {
                    logger.warn("    group='{}' on topic='{}': decremented={}, orphans={}, auto-completed={}",
                            cr.groupName(), cr.topic(),
                            cr.messagesDecremented(), cr.orphanRowsRemoved(), cr.messagesAutoCompleted());
                }
            }

            if (totalAutoCompleted > 0) {
                logger.info("  {} message(s) are now eligible for cleanup after dead consumer removal",
                        totalAutoCompleted);
            }
        } else {
            logger.debug("  Cleanup ran for {} group(s) but no work was needed (already cleaned)",
                    cleanupResults.size());
        }

        // Track cleanup failures (results with zero work where we expected work)
        long failedCleanups = cleanupResults.stream()
                .filter(cr -> !cr.hadWork())
                .count();
        if (failedCleanups > 0 && cleanupResults.size() > failedCleanups) {
            // Some succeeded, some didn't — might indicate partial failure
            logger.debug("  {} of {} cleanup(s) had no work (may already be cleaned or failed)",
                    failedCleanups, cleanupResults.size());
        }
    }

    /**
     * Logs detailed information about dead consumers that were just detected.
     */
    private void logDeadConsumersDetected(long runNumber, DetectionResult result, long detectionMs) {
        logger.warn("=== Detection Run #{}: {} DEAD consumer(s) detected in {}ms " +
                        "(checked {} active subscriptions across {} topic(s)) ===",
                runNumber, result.deadCount(), detectionMs,
                result.totalActiveChecked(), result.totalTopicsAffected());

        // Group by topic for readable output
        Map<String, List<DeadSubscriptionInfo>> byTopic = result.deadSubscriptions().stream()
                .collect(Collectors.groupingBy(DeadSubscriptionInfo::topic));

        for (Map.Entry<String, List<DeadSubscriptionInfo>> entry : byTopic.entrySet()) {
            String topic = entry.getKey();
            List<DeadSubscriptionInfo> deadInTopic = entry.getValue();
            logger.warn("  Topic '{}': {} dead consumer(s):", topic, deadInTopic.size());

            for (DeadSubscriptionInfo info : deadInTopic) {
                logger.warn("    - group='{}': last_heartbeat={}, timeout={}s, " +
                                "overdue={}, silent_for={}",
                        info.groupName(),
                        info.lastHeartbeat(),
                        info.timeoutSeconds(),
                        formatDuration(info.overdueDuration()),
                        formatDuration(info.silenceDuration()));
            }
        }
    }

    /**
     * Logs the impact of dead consumers — how many messages they are blocking.
     */
    private void logBlockedMessageStats(List<BlockedMessageStats> statsList) {
        if (statsList.isEmpty()) {
            logger.info("  No messages currently blocked by dead consumers");
            return;
        }

        long totalBlocked = statsList.stream()
                .mapToLong(BlockedMessageStats::totalBlocked)
                .sum();

        logger.warn("  === Blocked Message Impact: {} total message(s) blocked by dead consumers ===",
                totalBlocked);

        for (BlockedMessageStats stats : statsList) {
            if (stats.totalBlocked() == 0) {
                logger.debug("    topic='{}', group='{}': no messages blocked", stats.topic(), stats.groupName());
                continue;
            }

            String ageStr = stats.oldestBlockedAge() != null
                    ? formatDuration(stats.oldestBlockedAge())
                    : "n/a";

            logger.warn("    topic='{}', group='{}': {} blocked ({} pending, {} processing), " +
                            "oldest_blocked_age={}",
                    stats.topic(),
                    stats.groupName(),
                    stats.totalBlocked(),
                    stats.blockedPending(),
                    stats.blockedProcessing(),
                    ageStr);
        }

        // Highlight critical situations
        for (BlockedMessageStats stats : statsList) {
            if (stats.totalBlocked() > 1000) {
                logger.error("  CRITICAL: Dead consumer group='{}' on topic='{}' is blocking {} messages! " +
                                "These messages cannot be cleaned up until required_consumer_groups is decremented.",
                        stats.groupName(), stats.topic(), stats.totalBlocked());
            }
            if (stats.oldestBlockedAge() != null && stats.oldestBlockedAge().toHours() > 24) {
                logger.error("  CRITICAL: Dead consumer group='{}' on topic='{}' has been blocking " +
                                "messages for {} — oldest blocked message is {} old.",
                        stats.groupName(), stats.topic(),
                        formatDuration(stats.oldestBlockedAge()),
                        formatDuration(stats.oldestBlockedAge()));
            }
        }
    }

    /**
     * Logs the overall subscription landscape summary after a run that found dead consumers.
     */
    private void logSubscriptionSummary(long runNumber, SubscriptionSummary summary, long totalMs) {
        logger.warn("  Subscription landscape: {} active, {} paused, {} dead, {} cancelled " +
                        "({} total across {} topic(s)) — run #{} completed in {}ms",
                summary.activeCount(), summary.pausedCount(),
                summary.deadCount(), summary.cancelledCount(),
                summary.totalCount(), summary.topicCount(),
                runNumber, totalMs);
    }

    /**
     * Logs a concise summary for a healthy run where no dead consumers were found.
     */
    private void logHealthyRun(long runNumber, DetectionResult result,
                               SubscriptionSummary summary, long detectionMs) {
        if (summary.hasDeadSubscriptions()) {
            // No NEW dead consumers, but there are previously-dead ones still present
            logger.info("Detection run #{}: no new dead consumers ({}ms). " +
                            "Landscape: {} active, {} paused, {} dead (pre-existing), {} cancelled " +
                            "({} total across {} topic(s))",
                    runNumber, detectionMs,
                    summary.activeCount(), summary.pausedCount(),
                    summary.deadCount(), summary.cancelledCount(),
                    summary.totalCount(), summary.topicCount());
        } else {
            // Completely healthy
            logger.info("Detection run #{}: all healthy ({}ms). " +
                            "{} active, {} paused subscriptions across {} topic(s)",
                    runNumber, detectionMs,
                    summary.activeCount(), summary.pausedCount(),
                    summary.topicCount());
        }
    }

    /**
     * Formats a Duration into a human-readable string like "2h 15m 30s" or "45s".
     */
    static String formatDuration(Duration duration) {
        if (duration == null) {
            return "n/a";
        }
        long totalSeconds = duration.getSeconds();
        if (totalSeconds < 0) {
            return "0s";
        }

        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        sb.append(seconds).append("s");
        return sb.toString().trim();
    }
}
