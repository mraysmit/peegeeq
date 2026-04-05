package dev.mars.peegeeq.db.metrics;

import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector;
import dev.mars.peegeeq.db.cleanup.DeadConsumerDetector.SubscriptionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prometheus metrics for consumer group subscription health.
 *
 * <p>Registers gauges that reflect the current state of consumer group subscriptions
 * (active, paused, dead, cancelled) as scraped from the database via
 * {@link DeadConsumerDetector#getSubscriptionSummary()}.</p>
 *
 * <p>Call {@link #refresh()} periodically (e.g. from a Vert.x timer or after each
 * detection run) to update the gauge values from the database.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-05
 * @version 1.0
 */
public class ConsumerGroupMetrics implements MeterBinder {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupMetrics.class);

    private final DeadConsumerDetector detector;

    private final AtomicLong activeCount = new AtomicLong(0);
    private final AtomicLong pausedCount = new AtomicLong(0);
    private final AtomicLong deadCount = new AtomicLong(0);
    private final AtomicLong cancelledCount = new AtomicLong(0);
    private final AtomicLong totalCount = new AtomicLong(0);
    private final AtomicLong topicCount = new AtomicLong(0);

    public ConsumerGroupMetrics(DeadConsumerDetector detector) {
        this.detector = Objects.requireNonNull(detector, "detector cannot be null");
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("peegeeq.subscriptions.active", activeCount::get)
                .description("Number of ACTIVE consumer group subscriptions")
                .register(registry);

        Gauge.builder("peegeeq.subscriptions.paused", pausedCount::get)
                .description("Number of PAUSED consumer group subscriptions")
                .register(registry);

        Gauge.builder("peegeeq.subscriptions.dead", deadCount::get)
                .description("Number of DEAD consumer group subscriptions")
                .register(registry);

        Gauge.builder("peegeeq.subscriptions.cancelled", cancelledCount::get)
                .description("Number of CANCELLED consumer group subscriptions")
                .register(registry);

        Gauge.builder("peegeeq.subscriptions.total", totalCount::get)
                .description("Total number of consumer group subscriptions across all statuses")
                .register(registry);

        Gauge.builder("peegeeq.subscriptions.topics", topicCount::get)
                .description("Number of distinct topics with consumer group subscriptions")
                .register(registry);

        logger.info("Consumer group metrics registered");
    }

    /**
     * Refreshes all gauge values from the database.
     *
     * @return Future that completes when the refresh is done
     */
    public Future<Void> refresh() {
        return detector.getSubscriptionSummary()
                .map(summary -> {
                    activeCount.set(summary.activeCount());
                    pausedCount.set(summary.pausedCount());
                    deadCount.set(summary.deadCount());
                    cancelledCount.set(summary.cancelledCount());
                    totalCount.set(summary.totalCount());
                    topicCount.set(summary.topicCount());
                    logger.debug("Consumer group metrics refreshed: active={}, paused={}, dead={}, cancelled={}, total={}, topics={}",
                            summary.activeCount(), summary.pausedCount(), summary.deadCount(),
                            summary.cancelledCount(), summary.totalCount(), summary.topicCount());
                    return (Void) null;
                });
    }
}
