package dev.mars.peegeeq.db.cleanup;

import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Future;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
     * Detects and marks dead subscriptions for all topics.
     * 
     * <p>This method iterates through all configured topics and detects dead subscriptions
     * for each topic.</p>
     * 
     * @return Future containing the total number of subscriptions marked as DEAD across all topics
     */
    public Future<Integer> detectAllDeadSubscriptions() {
        logger.debug("Detecting dead subscriptions for all topics");
        
        return connectionManager.withConnection(serviceId, connection -> {
            // Mark all dead subscriptions across all topics in one query
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
                        int markedDead = rows.rowCount();
                        if (markedDead > 0) {
                            logger.warn("Marked {} subscriptions as DEAD across all topics", markedDead);
                            for (var row : rows) {
                                String topic = row.getString("topic");
                                String groupName = row.getString("group_name");
                                OffsetDateTime lastHeartbeat = row.getOffsetDateTime("last_heartbeat_at");
                                Integer timeout = row.getInteger("heartbeat_timeout_seconds");
                                logger.warn("  - topic='{}', group='{}', last_heartbeat={}, timeout={}s",
                                        topic, groupName, lastHeartbeat, timeout);
                            }
                        } else {
                            logger.debug("No dead subscriptions detected across all topics");
                        }
                        return markedDead;
                    });
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
}

