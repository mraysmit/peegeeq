package dev.mars.peegeeq.db.subscription;

import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Service for managing topic configurations.
 * 
 * <p>This class provides the API for creating and managing topic configurations
 * including semantics (QUEUE vs PUB_SUB), retention policies, and completion tracking modes.</p>
 * 
 * <p>All methods return Vert.x Future for composable asynchronous operations
 * following modern Vert.x 5.x patterns.</p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-12
 * @version 1.0
 */
public class TopicConfigService {
    
    private static final Logger logger = LoggerFactory.getLogger(TopicConfigService.class);
    
    private final PgConnectionManager connectionManager;
    private final String serviceId;
    
    /**
     * Creates a new TopicConfigService.
     * 
     * @param connectionManager The connection manager for database access
     * @param serviceId The service ID for connection pool selection
     */
    public TopicConfigService(PgConnectionManager connectionManager, String serviceId) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager cannot be null");
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId cannot be null");
        logger.info("TopicConfigService initialized for service: {}", serviceId);
    }
    
    /**
     * Creates a new topic configuration.
     * 
     * <p>If a topic configuration already exists, this method will update it.</p>
     * 
     * @param config The topic configuration
     * @return Future that completes when topic is created
     */
    public Future<Void> createTopic(TopicConfig config) {
        Objects.requireNonNull(config, "config cannot be null");
        
        logger.info("Creating topic configuration for '{}' with semantics: {}", 
                   config.getTopic(), config.getSemantics());
        
        return connectionManager.withConnection(serviceId, connection -> {
            String sql = """
                INSERT INTO outbox_topics (
                    topic, semantics, message_retention_hours,
                    zero_subscription_retention_hours, block_writes_on_zero_subscriptions,
                    completion_tracking_mode, created_at, updated_at
                ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                ON CONFLICT (topic) DO UPDATE SET
                    semantics = EXCLUDED.semantics,
                    message_retention_hours = EXCLUDED.message_retention_hours,
                    zero_subscription_retention_hours = EXCLUDED.zero_subscription_retention_hours,
                    block_writes_on_zero_subscriptions = EXCLUDED.block_writes_on_zero_subscriptions,
                    completion_tracking_mode = EXCLUDED.completion_tracking_mode,
                    updated_at = EXCLUDED.updated_at
                """;
            
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            Tuple params = Tuple.of(
                config.getTopic(),
                config.getSemantics().name(),
                config.getMessageRetentionHours(),
                config.getZeroSubscriptionRetentionHours(),
                config.isBlockWritesOnZeroSubscriptions(),
                config.getCompletionTrackingMode(),
                now,
                now
            );
            
            return connection.preparedQuery(sql)
                .execute(params)
                .onSuccess(result -> {
                    logger.info("Successfully created/updated topic configuration for '{}'",
                               config.getTopic());
                })
                .onFailure(error -> {
                    logger.error("Failed to create topic configuration for '{}': {}",
                                config.getTopic(), error.getMessage(), error);
                })
                .mapEmpty();
        });
    }
    
    /**
     * Updates an existing topic configuration.
     * 
     * @param config The updated topic configuration
     * @return Future that completes when topic is updated
     */
    public Future<Void> updateTopic(TopicConfig config) {
        Objects.requireNonNull(config, "config cannot be null");
        
        logger.info("Updating topic configuration for '{}'", config.getTopic());
        
        return connectionManager.withConnection(serviceId, connection -> {
            String sql = """
                UPDATE outbox_topics SET
                    semantics = $1,
                    message_retention_hours = $2,
                    zero_subscription_retention_hours = $3,
                    block_writes_on_zero_subscriptions = $4,
                    completion_tracking_mode = $5,
                    updated_at = $6
                WHERE topic = $7
                """;
            
            Tuple params = Tuple.of(
                config.getSemantics().name(),
                config.getMessageRetentionHours(),
                config.getZeroSubscriptionRetentionHours(),
                config.isBlockWritesOnZeroSubscriptions(),
                config.getCompletionTrackingMode(),
                OffsetDateTime.now(ZoneOffset.UTC),
                config.getTopic()
            );
            
            return connection.preparedQuery(sql)
                .execute(params)
                .compose(result -> {
                    if (result.rowCount() == 0) {
                        String msg = String.format("Topic configuration not found: '%s'", config.getTopic());
                        logger.error(msg);
                        return Future.failedFuture(new IllegalStateException(msg));
                    }
                    logger.info("Successfully updated topic configuration for '{}'", config.getTopic());
                    return Future.succeededFuture();
                });
        });
    }
    
    /**
     * Gets a topic configuration by name.
     * 
     * @param topic The topic name
     * @return Future containing the topic configuration, or null if not found
     */
    public Future<TopicConfig> getTopic(String topic) {
        Objects.requireNonNull(topic, "topic cannot be null");
        
        return connectionManager.withConnection(serviceId, connection -> {
            String sql = """
                SELECT topic, semantics, message_retention_hours,
                       zero_subscription_retention_hours, block_writes_on_zero_subscriptions,
                       completion_tracking_mode, created_at, updated_at
                FROM outbox_topics
                WHERE topic = $1
                """;
            
            return connection.preparedQuery(sql)
                .execute(Tuple.of(topic))
                .map(rows -> {
                    if (rows.size() == 0) {
                        logger.debug("Topic configuration not found: '{}'", topic);
                        return null;
                    }
                    return mapRowToTopicConfig(rows.iterator().next());
                });
        });
    }
    
    /**
     * Lists all topic configurations.
     * 
     * @return Future containing list of all topic configurations
     */
    public Future<List<TopicConfig>> listTopics() {
        return connectionManager.withConnection(serviceId, connection -> {
            String sql = """
                SELECT topic, semantics, message_retention_hours,
                       zero_subscription_retention_hours, block_writes_on_zero_subscriptions,
                       completion_tracking_mode, created_at, updated_at
                FROM outbox_topics
                ORDER BY topic
                """;
            
            return connection.preparedQuery(sql)
                .execute()
                .map(rows -> {
                    List<TopicConfig> configs = new ArrayList<>();
                    for (Row row : rows) {
                        configs.add(mapRowToTopicConfig(row));
                    }
                    logger.debug("Found {} topic configurations", configs.size());
                    return configs;
                });
        });
    }
    
    /**
     * Deletes a topic configuration.
     * 
     * <p>Note: This does not delete the topic's messages or subscriptions.
     * Use with caution.</p>
     * 
     * @param topic The topic name
     * @return Future that completes when topic is deleted
     */
    public Future<Void> deleteTopic(String topic) {
        Objects.requireNonNull(topic, "topic cannot be null");
        
        logger.info("Deleting topic configuration for '{}'", topic);
        
        return connectionManager.withConnection(serviceId, connection -> {
            String sql = "DELETE FROM outbox_topics WHERE topic = $1";
            
            return connection.preparedQuery(sql)
                .execute(Tuple.of(topic))
                .onSuccess(result -> {
                    if (result.rowCount() == 0) {
                        logger.warn("Topic configuration not found for deletion: '{}'", topic);
                    } else {
                        logger.info("Successfully deleted topic configuration for '{}'", topic);
                    }
                })
                .mapEmpty();
        });
    }
    
    /**
     * Checks if a topic exists.
     * 
     * @param topic The topic name
     * @return Future containing true if topic exists, false otherwise
     */
    public Future<Boolean> topicExists(String topic) {
        Objects.requireNonNull(topic, "topic cannot be null");
        
        return getTopic(topic).map(config -> config != null);
    }
    
    // Helper methods
    
    private TopicConfig mapRowToTopicConfig(Row row) {
        OffsetDateTime createdAt = row.get(OffsetDateTime.class, row.getColumnIndex("created_at"));
        OffsetDateTime updatedAt = row.get(OffsetDateTime.class, row.getColumnIndex("updated_at"));

        return TopicConfig.builder()
            .topic(row.getString("topic"))
            .semantics(TopicSemantics.valueOf(row.getString("semantics")))
            .messageRetentionHours(row.getInteger("message_retention_hours"))
            .zeroSubscriptionRetentionHours(row.getInteger("zero_subscription_retention_hours"))
            .blockWritesOnZeroSubscriptions(row.getBoolean("block_writes_on_zero_subscriptions"))
            .completionTrackingMode(row.getString("completion_tracking_mode"))
            .createdAt(createdAt != null ? createdAt.toInstant() : null)
            .updatedAt(updatedAt != null ? updatedAt.toInstant() : null)
            .build();
    }
}

