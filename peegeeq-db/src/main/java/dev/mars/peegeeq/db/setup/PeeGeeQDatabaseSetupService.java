package dev.mars.peegeeq.db.setup;

import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.setup.*;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import io.vertx.core.Future;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
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

    /**
     * Custom exception for setup-related errors that doesn't generate stack traces
     * for expected error conditions like "setup not found".
     * This prevents confusing stack traces in logs for normal error scenarios.
     */
    public static class SetupNotFoundException extends RuntimeException {
        public SetupNotFoundException(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            // Don't fill in stack trace for expected setup errors
            return this;
        }
    }

    /**
     * Custom exception for database creation conflicts that doesn't generate stack traces
     * for expected race conditions in concurrent test scenarios.
     */
    public static class DatabaseCreationConflictException extends RuntimeException {
        public DatabaseCreationConflictException(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            // Don't fill in stack trace for expected database conflicts
            return this;
        }
    }

    private final Map<String, DatabaseSetupResult> activeSetups = new ConcurrentHashMap<>();
    private final Map<String, DatabaseConfig> setupDatabaseConfigs = new ConcurrentHashMap<>();
    private final Map<String, PeeGeeQManager> activeManagers = new ConcurrentHashMap<>();
    private final DatabaseTemplateManager templateManager = new DatabaseTemplateManager();
    private final SqlTemplateProcessor templateProcessor = new SqlTemplateProcessor();
    
    @Override
    public CompletableFuture<DatabaseSetupResult> createCompleteSetup(DatabaseSetupRequest request) {
        try {
            // 1. Create database from template
            createDatabaseFromTemplate(request.getDatabaseConfig());

            // 2. Apply schema migrations
            applySchemaTemplates(request);

            // 3. Create PeeGeeQ configuration and manager
            PeeGeeQConfiguration config = createConfiguration(request.getDatabaseConfig());
            PeeGeeQManager manager = new PeeGeeQManager(config);
            manager.start();

            // Register queue factory implementations with the manager's provider
            registerAvailableQueueFactories(manager);

            // 4. Create queues and event stores
            Map<String, QueueFactory> queueFactories = createQueueFactories(manager, request.getQueues());
            Map<String, EventStore<?>> eventStores = createEventStores(manager, request.getEventStores());

            DatabaseSetupResult result = new DatabaseSetupResult(
                request.getSetupId(), queueFactories, eventStores, DatabaseSetupStatus.ACTIVE
            );

            activeSetups.put(request.getSetupId(), result);
            setupDatabaseConfigs.put(request.getSetupId(), request.getDatabaseConfig());
            activeManagers.put(request.getSetupId(), manager);
            return CompletableFuture.completedFuture(result);

        } catch (Exception e) {
            // Check if this is a database creation conflict (expected in concurrent scenarios)
            if (isDatabaseCreationConflict(e)) {
                logger.debug("🚫 EXPECTED: Database creation conflict for setup: {} (concurrent test scenario)",
                           request.getSetupId());
                return CompletableFuture.failedFuture(new DatabaseCreationConflictException("Database creation conflict: " + request.getSetupId()));
            }

            // For other exceptions, provide more context but still throw with stack trace
            logger.error("Failed to create database setup: {} - {}", request.getSetupId(), e.getMessage());

            // Clean up any partially created resources
            try {
                destroySetup(request.getSetupId()).get();
            } catch (Exception cleanupException) {
                logger.warn("Failed to cleanup after setup creation failure", cleanupException);
            }

            return CompletableFuture.failedFuture(new RuntimeException("Failed to create database setup: " + request.getSetupId(), e));
        }
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
        try {
            DatabaseSetupResult setup = activeSetups.remove(setupId);
            DatabaseConfig dbConfig = setupDatabaseConfigs.remove(setupId);
            PeeGeeQManager manager = activeManagers.remove(setupId);

            if (setup == null) {
                logger.info("Setup {} not found or already destroyed", setupId);
                return CompletableFuture.completedFuture(null); // Don't throw error for non-existent setup
            }

            // CRITICAL: Stop the PeeGeeQManager first to stop all background threads
            if (manager != null) {
                try {
                    logger.info("Stopping PeeGeeQManager for setup: {}", setupId);
                    manager.stop();
                    logger.info("PeeGeeQManager stopped successfully for setup: {}", setupId);
                } catch (Exception e) {
                    logger.error("Failed to stop PeeGeeQManager for setup: " + setupId, e);
                }
            }

            // Close any active resources
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

            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new RuntimeException("Failed to destroy setup: " + setupId, e));
        }
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
        } catch (SQLException e) {
            if (e.getMessage().contains("is being accessed by other users")) {
                logger.warn("Database {} is still being accessed by other users, will retry cleanup later",
                    dbConfig.getDatabaseName());
                // In a production system, you might want to schedule a retry
            } else {
                logger.warn("Failed to drop test database: {} - {}", dbConfig.getDatabaseName(), e.getMessage());
            }
        } catch (Exception e) {
            logger.warn("Failed to drop test database: {}", dbConfig.getDatabaseName(), e);
        }
    }

    @Override
    public CompletableFuture<DatabaseSetupStatus> getSetupStatus(String setupId) {
        DatabaseSetupResult setup = activeSetups.get(setupId);
        if (setup == null) {
            logger.debug("🚫 Setup not found: {} (expected for test scenarios)", setupId);
            return CompletableFuture.failedFuture(new SetupNotFoundException("Setup not found: " + setupId));
        }
        return CompletableFuture.completedFuture(setup.getStatus());
    }

    @Override
    public CompletableFuture<DatabaseSetupResult> getSetupResult(String setupId) {
        DatabaseSetupResult setup = activeSetups.get(setupId);
        if (setup == null) {
            logger.debug("🚫 Setup not found: {} (expected for test scenarios)", setupId);
            return CompletableFuture.failedFuture(new SetupNotFoundException("Setup not found: " + setupId));
        }
        return CompletableFuture.completedFuture(setup);
    }

    @Override
    public CompletableFuture<Void> addQueue(String setupId, QueueConfig queueConfig) {
        try {
            DatabaseSetupResult setup = activeSetups.get(setupId);
            DatabaseConfig dbConfig = setupDatabaseConfigs.get(setupId);
            if (setup == null || dbConfig == null) {
                logger.debug("🚫 Setup not found: {} (expected for test scenarios)", setupId);
                return CompletableFuture.failedFuture(new SetupNotFoundException("Setup not found: " + setupId));
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

            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new RuntimeException("Failed to add queue to setup: " + setupId, e));
        }
    }

    @Override
    public CompletableFuture<Void> addEventStore(String setupId, EventStoreConfig eventStoreConfig) {
        try {
            DatabaseSetupResult setup = activeSetups.get(setupId);
            DatabaseConfig dbConfig = setupDatabaseConfigs.get(setupId);
            if (setup == null || dbConfig == null) {
                logger.debug("🚫 Setup not found: {} (expected for test scenarios)", setupId);
                return CompletableFuture.failedFuture(new SetupNotFoundException("Setup not found: " + setupId));
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

            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new RuntimeException("Failed to add event store to setup: " + setupId, e));
        }
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

            // Check if any queue factory implementations are available
            var bestAvailableType = queueFactoryProvider.getBestAvailableType();
            if (bestAvailableType.isEmpty()) {
                logger.info("No queue factory implementations available. Skipping queue factory creation. " +
                    "This is expected when running without peegeeq-native or peegeeq-outbox modules.");
                return factories;
            }

            String implementationType = bestAvailableType.get();
            logger.info("Using {} queue factory implementation for {} queue(s)", implementationType, queues.size());

            for (QueueConfig queueConfig : queues) {
                try {
                    // Create a queue factory for each queue using the available type
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

    /**
     * Check if the exception is a database creation conflict (expected in concurrent scenarios).
     */
    private boolean isDatabaseCreationConflict(Exception e) {
        if (e == null) return false;

        // Check the exception message for database conflict indicators
        String message = e.getMessage();
        if (message != null) {
            if (message.contains("duplicate key value violates unique constraint") ||
                message.contains("pg_database_datname_index") ||
                (message.contains("database") && message.contains("already exists"))) {
                return true;
            }
        }

        // Check nested causes (this is where PostgreSQL exceptions usually are)
        Throwable cause = e.getCause();
        while (cause != null) {
            String causeMessage = cause.getMessage();
            if (causeMessage != null) {
                if (causeMessage.contains("duplicate key value violates unique constraint") ||
                    causeMessage.contains("pg_database_datname_index") ||
                    (causeMessage.contains("database") && causeMessage.contains("already exists"))) {
                    return true;
                }
            }
            cause = cause.getCause();
        }

        return false;
    }

    private Map<String, EventStore<?>> createEventStores(PeeGeeQManager manager, List<EventStoreConfig> eventStores) {
        Map<String, EventStore<?>> stores = new HashMap<>();
        // TODO: Create actual event stores
        // For now, return empty map
        return stores;
    }

    /**
     * Registers available queue factory implementations with the manager's factory provider.
     * This is a hook method that can be overridden by subclasses to register specific
     * factory implementations based on their available dependencies.
     */
    protected void registerAvailableQueueFactories(PeeGeeQManager manager) {
        // Base implementation does nothing - subclasses should override this
        // to register the factory implementations they have dependencies for
        logger.debug("Base registerAvailableQueueFactories called - no factories registered");
    }





    @Override
    public CompletableFuture<Set<String>> getAllActiveSetupIds() {
        return CompletableFuture.completedFuture(activeSetups.keySet());
    }
}