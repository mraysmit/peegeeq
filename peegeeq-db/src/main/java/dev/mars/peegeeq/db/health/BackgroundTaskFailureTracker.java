package dev.mars.peegeeq.db.health;

import io.vertx.core.Future;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared failure logging and health-state policy for periodic background tasks.
 *
 * <p>The first consecutive failure retains the complete stack trace at WARN. Persistent
 * failures escalate to a count-bearing ERROR summary at the threshold and at a bounded
 * interval thereafter. A successful run resets the consecutive count and restores health.
 */
public final class BackgroundTaskFailureTracker implements HealthCheck {

    public static final long DEFAULT_ESCALATION_THRESHOLD = 3;
    public static final long DEFAULT_SUMMARY_INTERVAL = 3;

    private final String component;
    private final String taskDescription;
    private final Logger logger;
    private final long escalationThreshold;
    private final long summaryInterval;
    private final AtomicLong consecutiveFailures = new AtomicLong();
    private final AtomicLong totalFailures = new AtomicLong();

    private volatile String lastFailureType;
    private volatile String lastFailureMessage;

    public BackgroundTaskFailureTracker(String component, String taskDescription, Logger logger) {
        this(component, taskDescription, logger,
                DEFAULT_ESCALATION_THRESHOLD, DEFAULT_SUMMARY_INTERVAL);
    }

    BackgroundTaskFailureTracker(String component, String taskDescription, Logger logger,
                                 long escalationThreshold, long summaryInterval) {
        this.component = requireText(component, "component");
        this.taskDescription = requireText(taskDescription, "taskDescription");
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        if (escalationThreshold < 2) {
            throw new IllegalArgumentException("escalationThreshold must be at least 2");
        }
        if (summaryInterval < 1) {
            throw new IllegalArgumentException("summaryInterval must be positive");
        }
        this.escalationThreshold = escalationThreshold;
        this.summaryInterval = summaryInterval;
    }

    public synchronized void recordSuccess() {
        long recoveredFailures = consecutiveFailures.getAndSet(0);
        if (recoveredFailures > 0) {
            logger.info("{} recovered after {} consecutive failures", taskDescription, recoveredFailures);
        }
        lastFailureType = null;
        lastFailureMessage = null;
    }

    public synchronized void recordFailure(Throwable failure) {
        Objects.requireNonNull(failure, "failure cannot be null");

        long consecutive = consecutiveFailures.incrementAndGet();
        long total = totalFailures.incrementAndGet();
        lastFailureType = failure.getClass().getName();
        lastFailureMessage = failureSummary(failure);

        if (consecutive == 1) {
            logger.warn("{} failed (first failure): {}", taskDescription, lastFailureMessage, failure);
        } else if (consecutive >= escalationThreshold
                && (consecutive == escalationThreshold || consecutive % summaryInterval == 0)) {
            logger.error("{} is still failing ({} consecutive failures, {} total failures): {}",
                    taskDescription, consecutive, total, lastFailureMessage);
        }
    }

    public String component() {
        return component;
    }

    public long consecutiveFailures() {
        return consecutiveFailures.get();
    }

    public long totalFailures() {
        return totalFailures.get();
    }

    public synchronized HealthStatus currentStatus() {
        long consecutive = consecutiveFailures.get();
        long total = totalFailures.get();
        Map<String, Object> details = healthDetails(consecutive, total);

        if (consecutive == 0) {
            return HealthStatus.healthy(component, details);
        }

        String message = taskDescription + " has failed " + consecutive
                + " consecutive time(s): " + lastFailureMessage;
        if (consecutive < escalationThreshold) {
            return HealthStatus.degraded(component, message, details);
        }
        return HealthStatus.unhealthy(component, message, details);
    }

    @Override
    public Future<HealthStatus> check() {
        return Future.succeededFuture(currentStatus());
    }

    private Map<String, Object> healthDetails(long consecutive, long total) {
        if (consecutive == 0) {
            return Map.of(
                    "consecutiveFailures", consecutive,
                    "totalFailures", total);
        }
        return Map.of(
                "consecutiveFailures", consecutive,
                "totalFailures", total,
                "lastFailureType", lastFailureType,
                "lastFailureMessage", lastFailureMessage);
    }

    private static String failureSummary(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }
}
