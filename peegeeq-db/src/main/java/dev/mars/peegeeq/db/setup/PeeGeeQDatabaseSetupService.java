package dev.mars.peegeeq.db.setup;

import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.setup.*;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class PeeGeeQDatabaseSetupService implements DatabaseSetupService {
    
    private final Map<String, DatabaseSetupResult> activeSetups = new ConcurrentHashMap<>();
    private final DatabaseTemplateManager templateManager = new DatabaseTemplateManager();
    private final SqlTemplateProcessor templateProcessor = new SqlTemplateProcessor();
    
    @Override
    public CompletableFuture<DatabaseSetupResult> createCompleteSetup(DatabaseSetupRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. Create database from template
                createDatabaseFromTemplate(request.getDatabaseConfig());
                
                // 2. Apply schema migrations
                applySchemaTemplates(request);
                
                // 3. Create PeeGeeQ configuration and manager
                PeeGeeQConfiguration config = createConfiguration(request.getDatabaseConfig());
                PeeGeeQManager manager = new PeeGeeQManager(config);
                manager.start();
                
                // 4. Create queues and event stores
                Map<String, QueueFactory> queueFactories = createQueueFactories(manager, request.getQueues());
                Map<String, EventStore<?>> eventStores = createEventStores(manager, request.getEventStores());
                
                DatabaseSetupResult result = new DatabaseSetupResult(
                    request.getSetupId(), queueFactories, eventStores, DatabaseSetupStatus.ACTIVE
                );
                
                activeSetups.put(request.getSetupId(), result);
                return result;
                
            } catch (Exception e) {
                throw new RuntimeException("Failed to create database setup: " + request.getSetupId(), e);
            }
        });
    }
    
    private void createDatabaseFromTemplate(DatabaseConfig dbConfig) throws Exception {
        String adminUrl = String.format("jdbc:postgresql://%s:%d/postgres", 
            dbConfig.getHost(), dbConfig.getPort());
        
        try (Connection adminConn = DriverManager.getConnection(adminUrl, 
                dbConfig.getUsername(), dbConfig.getPassword())) {
            
            templateManager.createDatabaseFromTemplate(
                adminConn,
                dbConfig.getDatabaseName(),
                dbConfig.getTemplateDatabase() != null ? dbConfig.getTemplateDatabase() : "template0",
                dbConfig.getEncoding(),
                Map.of()
            );
        }
    }
    
    private void applySchemaTemplates(DatabaseSetupRequest request) throws Exception {
        String dbUrl = String.format("jdbc:postgresql://%s:%d/%s", 
            request.getDatabaseConfig().getHost(),
            request.getDatabaseConfig().getPort(),
            request.getDatabaseConfig().getDatabaseName());
        
        try (Connection conn = DriverManager.getConnection(dbUrl, 
                request.getDatabaseConfig().getUsername(), 
                request.getDatabaseConfig().getPassword())) {
            
            // Apply base template
            templateProcessor.applyTemplate(conn, "peegeeq-template.sql", Map.of());
            
            // Create individual queue tables
            for (QueueConfig queueConfig : request.getQueues()) {
                Map<String, String> params = Map.of(
                    "queueName", queueConfig.getQueueName(),
                    "schema", request.getDatabaseConfig().getSchema()
                );
                templateProcessor.applyTemplate(conn, "create-queue-table.sql", params);
            }
            
            // Create event store tables
            for (EventStoreConfig eventStoreConfig : request.getEventStores()) {
                Map<String, String> params = Map.of(
                    "tableName", eventStoreConfig.getTableName(),
                    "schema", request.getDatabaseConfig().getSchema(),
                    "notificationPrefix", eventStoreConfig.getNotificationPrefix()
                );
                templateProcessor.applyTemplate(conn, "create-eventstore-table.sql", params);
            }
        }
    }
}