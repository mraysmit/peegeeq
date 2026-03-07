package dev.mars.peegeeq.db.cleanup;

import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Service for cleaning up completed messages from the outbox table.
 * 
 * <p>This class provides the API for deleting messages that have been processed
 * by all required consumer groups, following the reference counting completion tracking mode.</p>
 * 
 * <p>Cleanup Logic:</p>
 * <ul>
 *   <li>QUEUE topics: Delete after message_retention_hours</li>
 *   <li>PUB_SUB topics: Delete when completed_consumer_groups >= required_consumer_groups</li>
 *   <li>Zero subscriptions: Delete after zero_subscription_retention_hours</li>
 * </ul>
 * 
 * <p>All methods return Vert.x Future for composable asynchronous operations
 * following modern Vert.x 5.x patterns.</p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-12
 * @version 1.0
 */
public class CleanupService {
    
    private static final Logger logger = LoggerFactory.getLogger(CleanupService.class);
    
    private final PgConnectionManager connectionManager;
    private final String serviceId;
    
    /**
     * Creates a new CleanupService.
     * 
     * @param connectionManager The connection manager for database access
     * @param serviceId The service ID for connection pool selection
     */
    public CleanupService(PgConnectionManager connectionManager, String serviceId) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager cannot be null");
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId cannot be null");
        logger.info("CleanupService initialized for service: {}", serviceId);
    }
    
    /**
     * Deletes completed messages for a specific topic.
     * 
     * <p>This method deletes messages where:</p>
     * <ul>
     *   <li>status = 'COMPLETED'</li>
     *   <li>completed_consumer_groups >= required_consumer_groups (all groups finished)</li>
    *   <li>For required_consumer_groups &gt; 0: processed_at older than message_retention_hours</li>
    *   <li>For required_consumer_groups = 0: created_at older than zero_subscription_retention_hours</li>
     * </ul>
     * 
     * <p>For messages with required_consumer_groups = 0 (zero subscriptions),
     * uses zero_subscription_retention_hours instead.</p>
     * 
     * @param topic The topic to clean up
     * @param batchSize Maximum number of messages to delete in one batch
     * @return Future containing the number of messages deleted
     */
    public Future<Integer> cleanupCompletedMessages(String topic, int batchSize) {
        Objects.requireNonNull(topic, "topic cannot be null");
        
        if (batchSize <= 0) {
            return Future.failedFuture(new IllegalArgumentException("batchSize must be positive"));
        }
        
        logger.debug("Cleaning up completed messages for topic='{}', batchSize={}", topic, batchSize);
        
        return connectionManager.withConnection(serviceId, connection -> {
            String sql = """
                DELETE FROM outbox
                WHERE id IN (
                    SELECT o.id
                    FROM outbox o
                    LEFT JOIN outbox_topics t ON t.topic = o.topic
                    WHERE o.topic = $1
                      AND o.status = 'COMPLETED'
                      AND (
                          (
                              o.required_consumer_groups = 0
                              AND o.created_at < NOW() - (COALESCE(t.zero_subscription_retention_hours, 24) * INTERVAL '1 hour')
                          )
                          OR
                          (
                              o.required_consumer_groups > 0
                              AND o.processed_at IS NOT NULL
                              AND o.processed_at < NOW() - (COALESCE(t.message_retention_hours, 24) * INTERVAL '1 hour')
                          )
                      )
                      AND (
                          -- QUEUE: status='COMPLETED' is sufficient (required_consumer_groups=1 for backward compatibility)
                          COALESCE(t.semantics, 'QUEUE') = 'QUEUE'
                          OR
                          -- PUB_SUB: all groups completed
                          (COALESCE(t.semantics, 'QUEUE') = 'PUB_SUB' AND o.completed_consumer_groups >= o.required_consumer_groups)
                      )
                    ORDER BY COALESCE(o.processed_at, o.created_at) ASC
                    LIMIT $2
                )
                """;

            Tuple params = Tuple.of(topic, batchSize);
            
            logger.debug("Cleanup SQL: topic='{}', batchSize={}", topic, batchSize);

            return connection.preparedQuery(sql)
                    .execute(params)
                    .map(result -> {
                        int deletedCount = result.rowCount();
                        if (deletedCount > 0) {
                            logger.info("Deleted {} completed messages for topic='{}'", deletedCount, topic);
                        } else {
                            logger.debug("No messages deleted for topic='{}'", topic);
                        }
                        return deletedCount;
                    });
        });
    }
    
    /**
     * Deletes completed messages for all topics.
     * 
    * <p>This method iterates through all known topics (configured topics plus topics with
    * completed outbox rows) and deletes completed messages for each topic.</p>
     * 
     * @param batchSize Maximum number of messages to delete per topic in one batch
     * @return Future containing the total number of messages deleted across all topics
     */
    public Future<Integer> cleanupAllTopics(int batchSize) {
        if (batchSize <= 0) {
            return Future.failedFuture(new IllegalArgumentException("batchSize must be positive"));
        }
        
        logger.debug("Cleaning up completed messages for all topics, batchSize={}", batchSize);
        
        return fetchTopicsForCleanup()
                .compose(topics -> {
                    Future<Integer> totalDeleted = Future.succeededFuture(0);
                    for (String topic : topics) {
                        totalDeleted = totalDeleted.compose(currentTotal ->
                                cleanupCompletedMessages(topic, batchSize)
                                        .map(deleted -> currentTotal + deleted)
                        );
                    }
                    return totalDeleted;
                })
                .map(totalDeleted -> {
                    if (totalDeleted > 0) {
                        logger.info("Deleted {} completed messages across all topics", totalDeleted);
                    } else {
                        logger.debug("No completed messages to delete across all topics");
                    }
                    return totalDeleted;
                });
    }
    
    /**
     * Counts the number of completed messages eligible for cleanup for a specific topic.
     * 
     * <p>This method is useful for monitoring and metrics collection.</p>
     * 
     * @param topic The topic to count
     * @return Future containing the count of messages eligible for cleanup
     */
    public Future<Long> countEligibleForCleanup(String topic) {
        Objects.requireNonNull(topic, "topic cannot be null");
        
        return connectionManager.withConnection(serviceId, connection -> {
            String sql = """
                SELECT COUNT(*) AS eligible_count
                FROM outbox o
                LEFT JOIN outbox_topics t ON t.topic = o.topic
                WHERE o.topic = $1
                  AND o.status = 'COMPLETED'
                  AND (
                      (
                          o.required_consumer_groups = 0
                          AND o.created_at < NOW() - (COALESCE(t.zero_subscription_retention_hours, 24) * INTERVAL '1 hour')
                      )
                      OR
                      (
                          o.required_consumer_groups > 0
                          AND o.processed_at IS NOT NULL
                          AND o.processed_at < NOW() - (COALESCE(t.message_retention_hours, 24) * INTERVAL '1 hour')
                      )
                  )
                  AND (
                      -- QUEUE: status='COMPLETED' is sufficient (required_consumer_groups=1 for backward compatibility)
                      COALESCE(t.semantics, 'QUEUE') = 'QUEUE'
                      OR
                      -- PUB_SUB: all groups completed
                      (COALESCE(t.semantics, 'QUEUE') = 'PUB_SUB' AND o.completed_consumer_groups >= o.required_consumer_groups)
                  )
                """;

            Tuple params = Tuple.of(topic);
            
            return connection.preparedQuery(sql)
                    .execute(params)
                    .map(rows -> {
                        if (rows.size() == 0) {
                            return 0L;
                        }
                        Long count = rows.iterator().next().getLong("eligible_count");
                        logger.debug("Found {} messages eligible for cleanup for topic='{}'", count, topic);
                        return count;
                    });
        });
    }

    private Future<List<String>> fetchTopicsForCleanup() {
        return connectionManager.withConnection(serviceId, connection -> {
            String sql = """
                SELECT topic
                FROM (
                    SELECT topic FROM outbox_topics
                    UNION
                    SELECT DISTINCT topic FROM outbox WHERE status = 'COMPLETED'
                ) topics
                ORDER BY topic
                """;

            return connection.preparedQuery(sql)
                    .execute()
                    .map(rows -> {
                        List<String> topics = new ArrayList<>();
                        for (Row row : rows) {
                            topics.add(row.getString("topic"));
                        }
                        logger.debug("Found {} topic(s) to evaluate for cleanup", topics.size());
                        return topics;
                    });
        });
    }
}

