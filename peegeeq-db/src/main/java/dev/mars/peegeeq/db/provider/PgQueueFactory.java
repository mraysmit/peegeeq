package dev.mars.peegeeq.db.provider;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import dev.mars.peegeeq.api.DatabaseService;
import dev.mars.peegeeq.api.MessageConsumer;
import dev.mars.peegeeq.api.MessageProducer;
import dev.mars.peegeeq.api.QueueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base PostgreSQL implementation of QueueFactory.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * Base PostgreSQL implementation of QueueFactory.
 * This abstract class provides common functionality for PostgreSQL-based
 * queue factories and can be extended by specific implementations.
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
    public abstract String getImplementationType();
    
    @Override
    public boolean isHealthy() {
        if (closed) {
            return false;
        }
        
        try {
            return databaseService.isHealthy();
        } catch (Exception e) {
            logger.warn("Health check failed for queue factory", e);
            return false;
        }
    }
    
    @Override
    public void close() throws Exception {
        if (closed) {
            logger.debug("Queue factory already closed");
            return;
        }
        
        logger.info("Closing PgQueueFactory");
        closed = true;
        
        try {
            closeImplementationSpecificResources();
        } catch (Exception e) {
            logger.error("Failed to close implementation-specific resources", e);
            throw e;
        }
        
        logger.info("PgQueueFactory closed successfully");
    }
    
    /**
     * Closes implementation-specific resources.
     * Subclasses should override this method to clean up their specific resources.
     * 
     * @throws Exception if cleanup fails
     */
    protected abstract void closeImplementationSpecificResources() throws Exception;
    
    /**
     * Checks if the factory is closed.
     * 
     * @return true if closed, false otherwise
     */
    protected boolean isClosed() {
        return closed;
    }
    
    /**
     * Validates that the factory is not closed before performing operations.
     * 
     * @throws IllegalStateException if the factory is closed
     */
    protected void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("Queue factory is closed");
        }
    }
    
    /**
     * Gets the database service used by this factory.
     * 
     * @return The database service
     */
    protected DatabaseService getDatabaseService() {
        return databaseService;
    }
}
