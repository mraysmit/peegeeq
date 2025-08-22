package dev.mars.peegeeq.db.setup;

import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.setup.*;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PeeGeeQDatabaseSetupService implements DatabaseSetupService {

    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQDatabaseSetupService.class);

    private final Map<String, DatabaseSetupResult> activeSetups = new ConcurrentHashMap<>();
    private final Map<String, DatabaseConfig> setupDatabaseConfigs = new ConcurrentHashMap<>();
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
                setupDatabaseConfigs.put(request.getSetupId(), request.getDatabaseConfig());
                return result;
                
            } catch (Exception e) {
                throw new RuntimeException("Failed to create database setup: " + request.getSetupId(), e);
            }
        });
    }
    
    private void createDatabaseFromTemplate(DatabaseConfig dbConfig) throws Exception {
        // Ensure PostgreSQL driver is loaded
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("PostgreSQL driver not found on classpath", e);
        }

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
        // Ensure PostgreSQL driver is loaded
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("PostgreSQL driver not found on classpath", e);
        }

        String dbUrl = String.format("jdbc:postgresql://%s:%d/%s",
            request.getDatabaseConfig().getHost(),
            request.getDatabaseConfig().getPort(),
            request.getDatabaseConfig().getDatabaseName());

        try (Connection conn = DriverManager.getConnection(dbUrl,
                request.getDatabaseConfig().getUsername(),
                request.getDatabaseConfig().getPassword())) {
            
            // Apply base template
            logger.info("Applying base template: peegeeq-template.sql");
            templateProcessor.applyTemplate(conn, "peegeeq-template.sql", Map.of());
            logger.info("Base template applied successfully");
            
            // Create individual queue tables
            for (QueueConfig queueConfig : request.getQueues()) {
                logger.info("Creating queue table for: {}", queueConfig.getQueueName());
                Map<String, String> params = Map.of(
                    "queueName", queueConfig.getQueueName(),
                    "schema", request.getDatabaseConfig().getSchema()
                );
                templateProcessor.applyTemplate(conn, "create-queue-table.sql", params);
                logger.info("Queue table created successfully for: {}", queueConfig.getQueueName());
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

    @Override
    public CompletableFuture<Void> destroySetup(String setupId) {
        return CompletableFuture.runAsync(() -> {
            try {
                DatabaseSetupResult setup = activeSetups.remove(setupId);
                DatabaseConfig dbConfig = setupDatabaseConfigs.remove(setupId);
                if (setup == null) {
                    logger.info("Setup {} not found or already destroyed", setupId);
                    return; // Don't throw error for non-existent setup
                }

                // Close any active resources first
                if (setup.getQueueFactories() != null) {
                    setup.getQueueFactories().values().forEach(factory -> {
                        try {
                            factory.close();
                        } catch (Exception e) {
                            logger.warn("Failed to close queue factory", e);
                        }
                    });
                }

                if (setup.getEventStores() != null) {
                    setup.getEventStores().values().forEach(store -> {
                        try {
                            store.close();
                        } catch (Exception e) {
                            logger.warn("Failed to close event store", e);
                        }
                    });
                }

                // Drop the database if it's a test setup
                if (dbConfig != null && setupId.contains("test")) {
                    dropTestDatabase(dbConfig);
                }

            } catch (Exception e) {
                throw new RuntimeException("Failed to destroy setup: " + setupId, e);
            }
        });
    }

    private void dropTestDatabase(DatabaseConfig dbConfig) {
        try {
            String adminUrl = String.format("jdbc:postgresql://%s:%d/postgres",
                dbConfig.getHost(), dbConfig.getPort());

            try (Connection adminConn = DriverManager.getConnection(adminUrl,
                    dbConfig.getUsername(), dbConfig.getPassword())) {

                templateManager.dropDatabase(adminConn, dbConfig.getDatabaseName());
                logger.info("Test database {} dropped successfully", dbConfig.getDatabaseName());
            }
        } catch (Exception e) {
            logger.warn("Failed to drop test database: {}", dbConfig.getDatabaseName(), e);
        }
    }

    @Override
    public CompletableFuture<DatabaseSetupStatus> getSetupStatus(String setupId) {
        return CompletableFuture.supplyAsync(() -> {
            DatabaseSetupResult setup = activeSetups.get(setupId);
            if (setup == null) {
                throw new RuntimeException("Setup not found: " + setupId);
            }
            return setup.getStatus();
        });
    }

    @Override
    public CompletableFuture<DatabaseSetupResult> getSetupResult(String setupId) {
        return CompletableFuture.supplyAsync(() -> {
            DatabaseSetupResult setup = activeSetups.get(setupId);
            if (setup == null) {
                throw new RuntimeException("Setup not found: " + setupId);
            }
            return setup;
        });
    }

    @Override
    public CompletableFuture<Void> addQueue(String setupId, QueueConfig queueConfig) {
        return CompletableFuture.runAsync(() -> {
            try {
                DatabaseSetupResult setup = activeSetups.get(setupId);
                DatabaseConfig dbConfig = setupDatabaseConfigs.get(setupId);
                if (setup == null || dbConfig == null) {
                    throw new RuntimeException("Setup not found: " + setupId);
                }

                // Create queue table using SQL template with stored database config
                String dbUrl = String.format("jdbc:postgresql://%s:%d/%s",
                    dbConfig.getHost(), dbConfig.getPort(), dbConfig.getDatabaseName());

                try (Connection conn = DriverManager.getConnection(dbUrl,
                        dbConfig.getUsername(), dbConfig.getPassword())) {
                    Map<String, String> params = Map.of(
                        "queueName", queueConfig.getQueueName(),
                        "schema", dbConfig.getSchema()
                    );
                    templateProcessor.applyTemplate(conn, "create-queue-table.sql", params);
                }

            } catch (Exception e) {
                throw new RuntimeException("Failed to add queue to setup: " + setupId, e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> addEventStore(String setupId, EventStoreConfig eventStoreConfig) {
        return CompletableFuture.runAsync(() -> {
            try {
                DatabaseSetupResult setup = activeSetups.get(setupId);
                DatabaseConfig dbConfig = setupDatabaseConfigs.get(setupId);
                if (setup == null || dbConfig == null) {
                    throw new RuntimeException("Setup not found: " + setupId);
                }

                // Create event store table using SQL template with stored database config
                String dbUrl = String.format("jdbc:postgresql://%s:%d/%s",
                    dbConfig.getHost(), dbConfig.getPort(), dbConfig.getDatabaseName());

                try (Connection conn = DriverManager.getConnection(dbUrl,
                        dbConfig.getUsername(), dbConfig.getPassword())) {
                    Map<String, String> params = Map.of(
                        "tableName", eventStoreConfig.getTableName(),
                        "schema", dbConfig.getSchema(),
                        "notificationPrefix", eventStoreConfig.getNotificationPrefix()
                    );
                    templateProcessor.applyTemplate(conn, "create-eventstore-table.sql", params);
                }

            } catch (Exception e) {
                throw new RuntimeException("Failed to add event store to setup: " + setupId, e);
            }
        });
    }

    private PeeGeeQConfiguration createConfiguration(DatabaseConfig dbConfig) {
        // Override database connection properties with the provided config
        System.setProperty("peegeeq.database.host", dbConfig.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(dbConfig.getPort()));
        System.setProperty("peegeeq.database.name", dbConfig.getDatabaseName());
        System.setProperty("peegeeq.database.username", dbConfig.getUsername());
        System.setProperty("peegeeq.database.password", dbConfig.getPassword());
        System.setProperty("peegeeq.database.schema", dbConfig.getSchema());

        // Reload configuration to pick up the new properties
        return new PeeGeeQConfiguration("default");
    }

    private Map<String, QueueFactory> createQueueFactories(PeeGeeQManager manager, List<QueueConfig> queues) {
        Map<String, QueueFactory> factories = new HashMap<>();

        if (queues != null && !queues.isEmpty()) {
            // Get the queue factory provider from the manager
            var queueFactoryProvider = manager.getQueueFactoryProvider();
            var databaseService = manager.getDatabaseService();

            for (QueueConfig queueConfig : queues) {
                try {
                    // Create a queue factory for each queue using the default type (native)
                    String implementationType = queueFactoryProvider.getDefaultType();
                    QueueFactory factory = queueFactoryProvider.createFactory(implementationType, databaseService);
                    factories.put(queueConfig.getQueueName(), factory);

                    logger.info("Created {} queue factory for queue: {}", implementationType, queueConfig.getQueueName());
                } catch (Exception e) {
                    logger.error("Failed to create queue factory for queue: {}", queueConfig.getQueueName(), e);
                    // Continue with other queues rather than failing completely
                }
            }
        }

        return factories;
    }

    private Map<String, EventStore<?>> createEventStores(PeeGeeQManager manager, List<EventStoreConfig> eventStores) {
        Map<String, EventStore<?>> stores = new HashMap<>();
        // TODO: Create actual event stores
        // For now, return empty map
        return stores;
    }

    @Override
    public CompletableFuture<Set<String>> getAllActiveSetupIds() {
        return CompletableFuture.supplyAsync(() -> {
            return activeSetups.keySet();
        });
    }
}