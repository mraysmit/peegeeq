package dev.mars.peegeeq.db.provider;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base PostgreSQL implementation of QueueFactory.
 * This abstract class provides common functionality for PostgreSQL-based
 * queue factories and can be extended by specific implementations.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-21
 * @version 1.0
 */
public abstract class PgQueueFactory implements QueueFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(PgQueueFactory.class);
    
    protected final DatabaseService databaseService;
    protected volatile boolean closed = false;
    
    protected PgQueueFactory(DatabaseService databaseService) {
        this.databaseService = databaseService;
        logger.info("Initialized PgQueueFactory with implementation type: {}", getImplementationType());
    }
    
    @Override
    public abstract <T> MessageProducer<T> createProducer(String topic, Class<T> payloadType);
    
    @Override
    public abstract <T> MessageConsumer<T> createConsumer(String topic, Class<T> payloadType);
    
    @Override
    public abstract <T> ConsumerGroup<T> createConsumerGroup(String groupName, String topic, Class<T> payloadType);
    
    @Override
    public abstract String getImplementationType();
    
    @Override
    public io.vertx.core.Future<Boolean> isHealthy() {
        return io.vertx.core.Future.succeededFuture(!closed && databaseService != null);
    }
    
    @Override
    public Future<Void> close() {
        if (!closed) {
            closed = true;
            logger.info("Closing PgQueueFactory of type: {}", getImplementationType());
            return closeResources().onFailure(e -> logger.error("Error closing PgQueueFactory resources", e));
        }
        return io.vertx.core.Future.succeededFuture();
    }
    
    /**
     * Close implementation-specific resources.
     * Called by close() method.
     */
    protected abstract Future<Void> closeResources();
    
    /**
     * Check if the factory is closed and throw an exception if it is.
     * @throws IllegalStateException if the factory is closed
     */
    protected void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("QueueFactory is closed");
        }
    }
    
    /**
     * Get the database service used by this factory.
     * @return the database service
     */
    protected DatabaseService getDatabaseService() {
        return databaseService;
    }
}
