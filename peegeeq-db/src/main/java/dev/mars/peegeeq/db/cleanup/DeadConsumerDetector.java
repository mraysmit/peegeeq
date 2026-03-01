package dev.mars.peegeeq.db.cleanup;

import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Service for detecting and marking dead consumer group subscriptions.
 * 
 * <p>This class provides the API for detecting subscriptions that have exceeded
 * their heartbeat timeout and marking them as DEAD.</p>
 * 
 * <p>Dead Detection Logic:</p>
 * <ul>
 *   <li>Subscription is ACTIVE or PAUSED</li>
 *   <li>last_heartbeat_at + heartbeat_timeout_seconds < NOW()</li>
 *   <li>Marked as DEAD automatically</li>
 * </ul>
 * 
 * <p>All methods return Vert.x Future for composable asynchronous operations
 * following modern Vert.x 5.x patterns.</p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-12
 * @version 1.0
 */
public class DeadConsumerDetector {
    
    private static final Logger logger = LoggerFactory.getLogger(DeadConsumerDetector.class);
    
    private final PgConnectionManager connectionManager;
    private final String serviceId;

    /**
     * Detailed information about a single subscription that was marked DEAD.
     *
     * @param topic           The topic the subscription belongs to
     * @param groupName       The consumer group name
     * @param lastHeartbeat   When the consumer last sent a heartbeat
     * @param timeoutSeconds  The configured heartbeat timeout
     * @param overdueDuration How long past the timeout the heartbeat is
     */
    public record DeadSubscriptionInfo(
            String topic,
            String groupName,
            OffsetDateTime lastHeartbeat,
            int timeoutSeconds,
            Duration overdueDuration
    ) {
        /**
         * How long ago the heartbeat was last received, relative to now.
         */
        public Duration silenceDuration() {
            return Duration.between(lastHeartbeat, OffsetDateTime.now(ZoneOffset.UTC));
        }
    }

    /**
     * Result of a detection run, containing structured data about what was found
     * and the state of the subscription landscape.
     *
     * @param deadSubscriptions    Subscriptions that were marked DEAD in this run
     * @param totalActiveChecked   Total ACTIVE/PAUSED subscriptions that were evaluated
     * @param totalTopicsAffected  Number of distinct topics that had dead consumers
     * @param detectionTimeMs      How long the detection query took in milliseconds
     */
    public record DetectionResult(
            List<DeadSubscriptionInfo> deadSubscriptions,
            long totalActiveChecked,
            int totalTopicsAffected,
            long detectionTimeMs
    ) {
        /** Number of subscriptions marked DEAD in this run. */
        public int deadCount() {
            return deadSubscriptions.size();
        }

        /** True if any subscriptions were marked DEAD. */
        public boolean hasDeadSubscriptions() {
            return !deadSubscriptions.isEmpty();
        }

        /** Returns the distinct topics that had dead subscriptions. */
        public List<String> affectedTopics() {
            return deadSubscriptions.stream()
                    .map(DeadSubscriptionInfo::topic)
                    .distinct()
                    .toList();
        }
    }

    /**
     * Per-topic statistics about messages blocked by dead consumers.
     *
     * @param topic              The topic name
     * @param groupName          The dead consumer group name
     * @param blockedPending     Number of PENDING messages this dead group is blocking
     * @param blockedProcessing  Number of PROCESSING messages this dead group is blocking
     * @param totalBlocked       Total messages blocked (pending + processing)
     * @param oldestBlockedAge   Age of the oldest blocked message, or null if none
     */
    public record BlockedMessageStats(
            String topic,
            String groupName,
            long blockedPending,
            long blockedProcessing,
            long totalBlocked,
            Duration oldestBlockedAge
    ) {}

    /**
     * Creates a new DeadConsumerDetector.
     * 
     * @param connectionManager The connection manager for database access
     * @param serviceId The service ID for connection pool selection
     */
    public DeadConsumerDetector(PgConnectionManager connectionManager, String serviceId) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager cannot be null");
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId cannot be null");
        logger.info("DeadConsumerDetector initialized for service: {}", serviceId);
    }
    
    /**
     * Detects and marks dead subscriptions for a specific topic.
     * 
     * <p>This method marks subscriptions as DEAD when:</p>
     * <ul>
     *   <li>subscription_status IN ('ACTIVE', 'PAUSED')</li>
     *   <li>last_heartbeat_at + heartbeat_timeout_seconds < NOW()</li>
     * </ul>
     * 
     * @param topic The topic to check for dead subscriptions
     * @return Future containing the number of subscriptions marked as DEAD
     */
    public Future<Integer> detectDeadSubscriptions(String topic) {
        Objects.requireNonNull(topic, "topic cannot be null");
        
        logger.debug("Detecting dead subscriptions for topic='{}'", topic);
        
        return connectionManager.withConnection(serviceId, connection -> {
            String sql = """
                UPDATE outbox_topic_subscriptions
                SET subscription_status = 'DEAD',
                    last_active_at = $1
                WHERE topic = $2
                  AND subscription_status IN ('ACTIVE', 'PAUSED')
                  AND last_heartbeat_at + (heartbeat_timeout_seconds || ' seconds')::INTERVAL < $1
                RETURNING id, group_name, last_heartbeat_at, heartbeat_timeout_seconds
                """;

            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            Tuple params = Tuple.of(now, topic);
            
            return connection.preparedQuery(sql)
                    .execute(params)
                    .map(rows -> {
                        int markedDead = rows.rowCount();
                        if (markedDead > 0) {
                            logger.warn("Marked {} subscriptions as DEAD for topic='{}'", markedDead, topic);
                            for (var row : rows) {
                                String groupName = row.getString("group_name");
                                OffsetDateTime lastHeartbeat = row.getOffsetDateTime("last_heartbeat_at");
                                Integer timeout = row.getInteger("heartbeat_timeout_seconds");
                                logger.warn("  - group='{}', last_heartbeat={}, timeout={}s",
                                        groupName, lastHeartbeat, timeout);
                            }
                        } else {
                            logger.debug("No dead subscriptions detected for topic='{}'", topic);
                        }
                        return markedDead;
                    });
        });
    }
    
    /**
     * Detects and marks dead subscriptions for all topics, returning full structured results.
     * 
     * <p>This is the primary detection method that returns a {@link DetectionResult}
     * containing detailed information about each dead subscription, the total number
     * of active subscriptions evaluated, and timing information.</p>
     * 
     * @return Future containing the full detection result
     */
    public Future<DetectionResult> detectAllDeadSubscriptionsWithDetails() {
        logger.debug("Detecting dead subscriptions for all topics");
        
        return connectionManager.withConnection(serviceId, connection -> {
            long startTime = System.currentTimeMillis();

            // First count how many active/paused subscriptions exist (the population we're checking)
            String countSql = """
                SELECT COUNT(*) AS active_count
                FROM outbox_topic_subscriptions
                WHERE subscription_status IN ('ACTIVE', 'PAUSED')
                """;

            return connection.preparedQuery(countSql)
                    .execute()
                    .compose(countRows -> {
                        long activeCount = countRows.iterator().next().getLong("active_count");

                        // Now mark dead subscriptions
                        String sql = """
                            UPDATE outbox_topic_subscriptions
                            SET subscription_status = 'DEAD',
                                last_active_at = $1
                            WHERE subscription_status IN ('ACTIVE', 'PAUSED')
                              AND last_heartbeat_at + (heartbeat_timeout_seconds || ' seconds')::INTERVAL < $1
                            RETURNING id, topic, group_name, last_heartbeat_at, heartbeat_timeout_seconds
                            """;

                        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                        Tuple params = Tuple.of(now);

                        return connection.preparedQuery(sql)
                                .execute(params)
                                .map(rows -> {
                                    long elapsed = System.currentTimeMillis() - startTime;
                                    List<DeadSubscriptionInfo> deadList = new ArrayList<>();
                                    
                                    for (Row row : rows) {
                                        String topic = row.getString("topic");
                                        String groupName = row.getString("group_name");
                                        OffsetDateTime lastHeartbeat = row.getOffsetDateTime("last_heartbeat_at");
                                        int timeout = row.getInteger("heartbeat_timeout_seconds");
                                        
                                        OffsetDateTime deadlineAt = lastHeartbeat.plusSeconds(timeout);
                                        Duration overdue = Duration.between(deadlineAt, now);

                                        deadList.add(new DeadSubscriptionInfo(
                                                topic, groupName, lastHeartbeat, timeout, overdue));
                                    }

                                    int topicsAffected = (int) deadList.stream()
                                            .map(DeadSubscriptionInfo::topic)
                                            .distinct()
                                            .count();

                                    return new DetectionResult(
                                            Collections.unmodifiableList(deadList),
                                            activeCount,
                                            topicsAffected,
                                            elapsed);
                                });
                    });
        });
    }

    /**
     * Detects and marks dead subscriptions for all topics.
     * 
     * <p>This method iterates through all configured topics and detects dead subscriptions
     * for each topic.</p>
     * 
     * @return Future containing the total number of subscriptions marked as DEAD across all topics
     */
    public Future<Integer> detectAllDeadSubscriptions() {
        return detectAllDeadSubscriptionsWithDetails()
                .map(result -> {
                    if (result.hasDeadSubscriptions()) {
                        logger.warn("Marked {} subscriptions as DEAD across all topics", result.deadCount());
                        for (DeadSubscriptionInfo info : result.deadSubscriptions()) {
                            logger.warn("  - topic='{}', group='{}', last_heartbeat={}, timeout={}s",
                                    info.topic(), info.groupName(), info.lastHeartbeat(), info.timeoutSeconds());
                        }
                    } else {
                        logger.debug("No dead subscriptions detected across all topics");
                    }
                    return result.deadCount();
                });
    }
    
    /**
     * Counts the number of dead subscriptions for a specific topic.
     * 
     * <p>This method is useful for monitoring and metrics collection.</p>
     * 
     * @param topic The topic to count
     * @return Future containing the count of DEAD subscriptions
     */
    public Future<Long> countDeadSubscriptions(String topic) {
        Objects.requireNonNull(topic, "topic cannot be null");
        
        return connectionManager.withConnection(serviceId, connection -> {
            String sql = """
                SELECT COUNT(*) AS dead_count
                FROM outbox_topic_subscriptions
                WHERE topic = $1
                  AND subscription_status = 'DEAD'
                """;

            Tuple params = Tuple.of(topic);
            
            return connection.preparedQuery(sql)
                    .execute(params)
                    .map(rows -> {
                        if (rows.size() == 0) {
                            return 0L;
                        }
                        Long count = rows.iterator().next().getLong("dead_count");
                        logger.debug("Found {} DEAD subscriptions for topic='{}'", count, topic);
                        return count;
                    });
        });
    }
    
    /**
     * Counts the number of subscriptions that will be marked as dead on next detection run.
     * 
     * <p>This method is useful for monitoring and alerting before subscriptions are marked as DEAD.</p>
     * 
     * @param topic The topic to check
     * @return Future containing the count of subscriptions that will be marked as DEAD
     */
    public Future<Long> countEligibleForDeadDetection(String topic) {
        Objects.requireNonNull(topic, "topic cannot be null");
        
        return connectionManager.withConnection(serviceId, connection -> {
            String sql = """
                SELECT COUNT(*) AS eligible_count
                FROM outbox_topic_subscriptions
                WHERE topic = $1
                  AND subscription_status IN ('ACTIVE', 'PAUSED')
                  AND last_heartbeat_at + (heartbeat_timeout_seconds || ' seconds')::INTERVAL < $2
                """;

            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            Tuple params = Tuple.of(topic, now);
            
            return connection.preparedQuery(sql)
                    .execute(params)
                    .map(rows -> {
                        if (rows.size() == 0) {
                            return 0L;
                        }
                        Long count = rows.iterator().next().getLong("eligible_count");
                        if (count > 0) {
                            logger.warn("Found {} subscriptions eligible for dead detection for topic='{}'", count, topic);
                        } else {
                            logger.debug("No subscriptions eligible for dead detection for topic='{}'", topic);
                        }
                        return count;
                    });
        });
    }

    /**
     * Queries the number of messages blocked by each DEAD consumer group.
     *
     * <p>For each DEAD subscription, this counts how many PENDING and PROCESSING messages
     * in the outbox still require that group (i.e. the group has not completed them and
     * the message's {@code required_consumer_groups} has not been decremented).</p>
     *
     * <p>This is an expensive diagnostic query — it scans outbox messages. It should be
     * called after detection, not on every heartbeat check.</p>
     *
     * @return Future containing a list of {@link BlockedMessageStats}, one per dead group per topic
     */
    public Future<List<BlockedMessageStats>> getBlockedMessageStats() {
        return connectionManager.withConnection(serviceId, connection -> {
            // For each DEAD subscription, count PENDING/PROCESSING messages for that topic
            // that the dead group has NOT completed (i.e. no COMPLETED row in outbox_consumer_groups)
            String sql = """
                SELECT
                    s.topic,
                    s.group_name,
                    COALESCE(SUM(CASE WHEN o.status = 'PENDING' THEN 1 ELSE 0 END), 0) AS blocked_pending,
                    COALESCE(SUM(CASE WHEN o.status = 'PROCESSING' THEN 1 ELSE 0 END), 0) AS blocked_processing,
                    COUNT(o.id) AS total_blocked,
                    MIN(o.created_at) AS oldest_blocked_at
                FROM outbox_topic_subscriptions s
                LEFT JOIN outbox o
                    ON o.topic = s.topic
                    AND o.status IN ('PENDING', 'PROCESSING')
                    AND o.required_consumer_groups > 0
                    AND NOT EXISTS (
                        SELECT 1 FROM outbox_consumer_groups cg
                        WHERE cg.message_id = o.id
                          AND cg.group_name = s.group_name
                          AND cg.status = 'COMPLETED'
                    )
                WHERE s.subscription_status = 'DEAD'
                GROUP BY s.topic, s.group_name
                ORDER BY total_blocked DESC
                """;

            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

            return connection.preparedQuery(sql)
                    .execute()
                    .map(rows -> {
                        List<BlockedMessageStats> stats = new ArrayList<>();
                        for (Row row : rows) {
                            OffsetDateTime oldestAt = row.getOffsetDateTime("oldest_blocked_at");
                            Duration oldestAge = oldestAt != null
                                    ? Duration.between(oldestAt, now)
                                    : null;

                            stats.add(new BlockedMessageStats(
                                    row.getString("topic"),
                                    row.getString("group_name"),
                                    row.getLong("blocked_pending"),
                                    row.getLong("blocked_processing"),
                                    row.getLong("total_blocked"),
                                    oldestAge));
                        }
                        return Collections.unmodifiableList(stats);
                    });
        });
    }

    /**
     * Returns a summary snapshot of all subscription statuses across all topics.
     *
     * @return Future containing a {@link SubscriptionSummary}
     */
    public Future<SubscriptionSummary> getSubscriptionSummary() {
        return connectionManager.withConnection(serviceId, connection -> {
            String sql = """
                SELECT
                    COUNT(*) FILTER (WHERE subscription_status = 'ACTIVE') AS active_count,
                    COUNT(*) FILTER (WHERE subscription_status = 'PAUSED') AS paused_count,
                    COUNT(*) FILTER (WHERE subscription_status = 'DEAD') AS dead_count,
                    COUNT(*) FILTER (WHERE subscription_status = 'CANCELLED') AS cancelled_count,
                    COUNT(DISTINCT topic) AS topic_count,
                    COUNT(*) AS total_count
                FROM outbox_topic_subscriptions
                """;

            return connection.preparedQuery(sql)
                    .execute()
                    .map(rows -> {
                        Row row = rows.iterator().next();
                        return new SubscriptionSummary(
                                row.getLong("active_count"),
                                row.getLong("paused_count"),
                                row.getLong("dead_count"),
                                row.getLong("cancelled_count"),
                                row.getLong("topic_count"),
                                row.getLong("total_count"));
                    });
        });
    }

    /**
     * Summary of subscription statuses across all topics.
     *
     * @param activeCount    Number of ACTIVE subscriptions
     * @param pausedCount    Number of PAUSED subscriptions
     * @param deadCount      Number of DEAD subscriptions
     * @param cancelledCount Number of CANCELLED subscriptions
     * @param topicCount     Number of distinct topics with subscriptions
     * @param totalCount     Total subscriptions across all statuses
     */
    public record SubscriptionSummary(
            long activeCount,
            long pausedCount,
            long deadCount,
            long cancelledCount,
            long topicCount,
            long totalCount
    ) {
        /** True if there are any DEAD subscriptions. */
        public boolean hasDeadSubscriptions() {
            return deadCount > 0;
        }
    }
}

