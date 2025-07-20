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


import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.concurrent.CompletableFuture;

/**
 * PostgreSQL implementation of ConnectionProvider.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
/**
 * PostgreSQL implementation of ConnectionProvider.
 * This class wraps the existing PgClientFactory and PgConnectionManager
 * to provide a clean interface for connection management.
 */
public class PgConnectionProvider implements dev.mars.peegeeq.api.database.ConnectionProvider {
    
    private static final Logger logger = LoggerFactory.getLogger(PgConnectionProvider.class);
    
    private final PgClientFactory clientFactory;
    private final PgConnectionManager connectionManager;
    
    public PgConnectionProvider(PgClientFactory clientFactory) {
        this.clientFactory = clientFactory;
        this.connectionManager = clientFactory.getConnectionManager();
        logger.info("Initialized PgConnectionProvider");
    }
    
    @Override
    public DataSource getDataSource(String clientId) {
        try {
            return connectionManager.getDataSource(clientId);
        } catch (Exception e) {
            logger.error("Failed to get data source for client: {}", clientId, e);
            throw new IllegalArgumentException("Client not found: " + clientId, e);
        }
    }
    
    @Override
    public Connection getConnection(String clientId) throws Exception {
        DataSource dataSource = getDataSource(clientId);
        return dataSource.getConnection();
    }
    
    @Override
    public CompletableFuture<Connection> getConnectionAsync(String clientId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return getConnection(clientId);
            } catch (Exception e) {
                logger.error("Failed to get connection asynchronously for client: {}", clientId, e);
                throw new RuntimeException("Failed to get connection for client: " + clientId, e);
            }
        });
    }
    
    @Override
    public boolean hasClient(String clientId) {
        try {
            connectionManager.getDataSource(clientId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    public boolean isHealthy() {
        try {
            return connectionManager.isHealthy();
        } catch (Exception e) {
            logger.warn("Health check failed", e);
            return false;
        }
    }
    
    @Override
    public boolean isClientHealthy(String clientId) {
        try {
            if (!hasClient(clientId)) {
                return false;
            }
            
            DataSource dataSource = getDataSource(clientId);
            try (Connection connection = dataSource.getConnection()) {
                return connection.isValid(5); // 5 second timeout
            }
        } catch (Exception e) {
            logger.warn("Health check failed for client: {}", clientId, e);
            return false;
        }
    }
    
    @Override
    public void close() throws Exception {
        logger.info("Closing PgConnectionProvider");
        if (clientFactory != null) {
            clientFactory.close();
        }
    }
}
