package dev.mars.peegeeq.db.subscription;

import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Service for validating zero-subscription protection rules.
 * 
 * <p>This class provides the API for checking if writes to PUB_SUB topics
 * should be blocked when there are zero ACTIVE subscriptions.</p>
 * 
 * <p>All methods return Vert.x Future for composable asynchronous operations
 * following modern Vert.x 5.x patterns.</p>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-12
 * @version 1.0
 */
public class ZeroSubscriptionValidator {
    
    private static final Logger logger = LoggerFactory.getLogger(ZeroSubscriptionValidator.class);
    
    private final PgConnectionManager connectionManager;
    private final String serviceId;
    
    /**
     * Creates a new ZeroSubscriptionValidator.
     * 
     * @param connectionManager The connection manager for database access
     * @param serviceId The service ID for connection pool selection
     */
    public ZeroSubscriptionValidator(PgConnectionManager connectionManager, String serviceId) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager cannot be null");
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId cannot be null");
        logger.info("ZeroSubscriptionValidator initialized for service: {}", serviceId);
    }
    
    /**
     * Checks if a write to the specified topic should be allowed.
     * 
     * <p>This method checks the topic configuration and active subscription count
     * to determine if writes should be blocked based on zero-subscription protection rules.</p>
     * 
     * <p><strong>Behavior:</strong></p>
     * <ul>
     *   <li>QUEUE topics: Always allowed (returns true)</li>
     *   <li>PUB_SUB topics with block_writes_on_zero_subscriptions = FALSE: Always allowed</li>
     *   <li>PUB_SUB topics with block_writes_on_zero_subscriptions = TRUE and zero ACTIVE subscriptions: Blocked (returns false)</li>
     *   <li>Unconfigured topics: Always allowed (defaults to QUEUE semantics)</li>
     * </ul>
     * 
     * @param topic The topic to check
     * @return Future<Boolean> that completes with true if write is allowed, false if blocked
     */
    public Future<Boolean> isWriteAllowed(String topic) {
        Objects.requireNonNull(topic, "topic cannot be null");
        
        return connectionManager.withConnection(serviceId, connection -> {
            String sql = """
                SELECT t.semantics,
                       t.block_writes_on_zero_subscriptions,
                       COUNT(s.id) AS active_subscriptions
                FROM outbox_topics t
                LEFT JOIN outbox_topic_subscriptions s
                    ON s.topic = t.topic AND s.subscription_status = 'ACTIVE'
                WHERE t.topic = $1
                GROUP BY t.topic, t.semantics, t.block_writes_on_zero_subscriptions
                """;
            
            return connection.preparedQuery(sql)
                .execute(Tuple.of(topic))
                .map(rows -> {
                    if (rows.size() == 0) {
                        // Topic not configured, allow write (defaults to QUEUE semantics)
                        logger.debug("Topic '{}' not configured, allowing write (QUEUE default)", topic);
                        return true;
                    }
                    
                    Row row = rows.iterator().next();
                    String semantics = row.getString("semantics");
                    Boolean blockOnZero = row.getBoolean("block_writes_on_zero_subscriptions");
                    Integer activeCount = row.getInteger("active_subscriptions");
                    
                    // QUEUE topics always allow writes
                    if ("QUEUE".equals(semantics)) {
                        logger.debug("Topic '{}' is QUEUE, allowing write", topic);
                        return true;
                    }
                    
                    // PUB_SUB topics with blocking disabled always allow writes
                    if (!blockOnZero) {
                        logger.debug("Topic '{}' is PUB_SUB with blocking disabled, allowing write", topic);
                        return true;
                    }
                    
                    // PUB_SUB topics with blocking enabled and zero subscriptions block writes
                    if (activeCount == 0) {
                        logger.warn("EXPECTED BEHAVIOR: Blocking write to topic '{}' - zero ACTIVE subscriptions and block_writes_on_zero_subscriptions = TRUE", topic);
                        return false;
                    }
                    
                    // PUB_SUB topics with blocking enabled and active subscriptions allow writes
                    logger.debug("Topic '{}' is PUB_SUB with {} active subscriptions, allowing write", topic, activeCount);
                    return true;
                });
        });
    }
    
    /**
     * Validates that a write to the specified topic is allowed, throwing an exception if blocked.
     * 
     * <p>This is a convenience method that calls {@link #isWriteAllowed(String)} and throws
     * a {@link NoActiveSubscriptionsException} if the write is blocked.</p>
     * 
     * @param topic The topic to validate
     * @return Future<Void> that completes successfully if write is allowed, or fails with NoActiveSubscriptionsException if blocked
     */
    public Future<Void> validateWriteAllowed(String topic) {
        return isWriteAllowed(topic)
            .compose(allowed -> {
                if (!allowed) {
                    return Future.failedFuture(new NoActiveSubscriptionsException(
                        "Topic '" + topic + "' has zero ACTIVE subscriptions and blocks writes"));
                }
                return Future.succeededFuture();
            });
    }
    
    /**
     * Exception thrown when a write is blocked due to zero active subscriptions.
     */
    public static class NoActiveSubscriptionsException extends RuntimeException {
        public NoActiveSubscriptionsException(String message) {
            super(message);
        }
    }
}

