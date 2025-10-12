package dev.mars.peegeeq.db.setup;

import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.setup.*;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Optional import for bitemporal event store factory (if available on classpath)
// This allows the service to create actual event stores when the bitemporal module is present

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

    // Reuse Vert.x instance when running inside Vert.x; otherwise create a new one lazily
    private final io.vertx.core.Vertx vertx;

    // Dedicated worker executor to ensure setup runs off the event-loop (no Vert.x context)
    private final java.util.concurrent.ExecutorService setupExecutor = java.util.concurrent.Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "peegeeq-setup-worker");
        t.setDaemon(true);
        return t;
    });

    private final DatabaseTemplateManager templateManager;
    private final SqlTemplateProcessor templateProcessor = new SqlTemplateProcessor();

    public PeeGeeQDatabaseSetupService() {
        // If we're inside a Vert.x context, reuse the owning Vertx instance; otherwise create a new one
        var ctx = io.vertx.core.Vertx.currentContext();
        this.vertx = (ctx != null) ? ctx.owner() : io.vertx.core.Vertx.vertx();
        this.templateManager = new DatabaseTemplateManager(vertx);
    }
    
    @Override
    public CompletableFuture<DatabaseSetupResult> createCompleteSetup(DatabaseSetupRequest request) {
        // Offload the entire setup sequence to a worker thread to avoid running any
        // potentially blocking operations on the Vert.x event loop thread
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. Create database from template
                createDatabaseFromTemplate(request.getDatabaseConfig());

                // 2. Apply schema migrations
                applySchemaTemplates(request);

                // 3. Create PeeGeeQ configuration and manager
                PeeGeeQConfiguration config = createConfiguration(request.getDatabaseConfig());
                PeeGeeQManager manager = new PeeGeeQManager(config);
                // Start reactively and wait for completion on the worker thread
                manager.startReactive().toCompletionStage().toCompletableFuture().get();

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
                return result;

            } catch (Exception e) {
                // Check if this is a database creation conflict (expected in concurrent scenarios)
                if (isDatabaseCreationConflict(e)) {
                    logger.debug("🚫 EXPECTED: Database creation conflict for setup: {} (concurrent test scenario)",
                               request.getSetupId());
                    throw new DatabaseCreationConflictException("Database creation conflict: " + request.getSetupId());
                }

                // For other exceptions, provide more context but still throw with stack trace
                logger.error("Failed to create database setup: {} - {}", request.getSetupId(), e.getMessage());

                // Clean up any partially created resources
                try {
                    destroySetup(request.getSetupId()).get();
                } catch (Exception cleanupException) {
                    logger.warn("Failed to cleanup after setup creation failure", cleanupException);
                }

                throw new RuntimeException("Failed to create database setup: " + request.getSetupId(), e);
            }
        }, setupExecutor);
    }
    
    private void createDatabaseFromTemplate(DatabaseConfig dbConfig) throws Exception {
        templateManager.createDatabaseFromTemplate(
            dbConfig.getHost(),
            dbConfig.getPort(),
            dbConfig.getUsername(),
            dbConfig.getPassword(),
            dbConfig.getDatabaseName(),
            dbConfig.getTemplateDatabase() != null ? dbConfig.getTemplateDatabase() : "template0",
            dbConfig.getEncoding(),
            Map.of()
        ).toCompletionStage().toCompletableFuture().get();
    }
    
    private void applySchemaTemplates(DatabaseSetupRequest request) throws Exception {
        // Create a temporary reactive pool for schema template application
        io.vertx.pgclient.PgConnectOptions connectOptions = new io.vertx.pgclient.PgConnectOptions()
            .setHost(request.getDatabaseConfig().getHost())
            .setPort(request.getDatabaseConfig().getPort())
            .setDatabase(request.getDatabaseConfig().getDatabaseName())
            .setUser(request.getDatabaseConfig().getUsername())
            .setPassword(request.getDatabaseConfig().getPassword());

        io.vertx.sqlclient.Pool tempPool = io.vertx.pgclient.PgBuilder.pool()
            .with(new io.vertx.sqlclient.PoolOptions().setMaxSize(1))
            .connectingTo(connectOptions)
            .using(vertx)
            .build();

        try {
            tempPool.withConnection(connection -> {
                // Apply base template; on permission errors, fall back to minimal core schema (no extensions)
                logger.info("Applying base template: peegeeq-template.sql");
                return templateProcessor.applyTemplateReactive(connection, "peegeeq-template.sql", Map.of())
                    .onSuccess(v -> logger.info("Base template applied successfully"))
                    .map(Boolean.TRUE)
                    .recover(err -> {
                        if (isExtensionPermissionError(err)) {
                            logger.warn("Base template application failed due to extension permissions. Falling back to minimal core schema without extensions: {}", err.getMessage());
                            return applyMinimalCoreSchemaReactive(connection, request.getDatabaseConfig().getSchema()).map(Boolean.FALSE);
                        }
                        return io.vertx.core.Future.failedFuture(err);
                    })
                    .compose(baseApplied -> {
                        // Only create per-queue tables if base template (with templates) was applied
                        if (Boolean.TRUE.equals(baseApplied)) {
                            io.vertx.core.Future<Void> queueChain = io.vertx.core.Future.succeededFuture();
                            for (QueueConfig queueConfig : request.getQueues()) {
                                queueChain = queueChain.compose(v2 -> {
                                    logger.info("Creating queue table for: {}", queueConfig.getQueueName());
                                    Map<String, String> params = Map.of(
                                        "queueName", queueConfig.getQueueName(),
                                        "schema", request.getDatabaseConfig().getSchema()
                                    );
                                    return templateProcessor.applyTemplateReactive(connection, "create-queue-table.sql", params)
                                        .onSuccess(v3 -> logger.info("Queue table created successfully for: {}", queueConfig.getQueueName()));
                                });
                            }
                            return queueChain.map(baseApplied);
                        } else {
                            logger.info("Skipping per-queue table creation because minimal core schema fallback was used");
                            return io.vertx.core.Future.succeededFuture(baseApplied);
                        }
                    })
                    .compose(baseApplied -> {
                        // Only create event store tables if base template was applied
                        if (Boolean.TRUE.equals(baseApplied)) {
                            io.vertx.core.Future<Void> eventStoreChain = io.vertx.core.Future.succeededFuture();
                            for (EventStoreConfig eventStoreConfig : request.getEventStores()) {
                                eventStoreChain = eventStoreChain.compose(v2 -> {
                                    Map<String, String> params = Map.of(
                                        "tableName", eventStoreConfig.getTableName(),
                                        "schema", request.getDatabaseConfig().getSchema(),
                                        "notificationPrefix", eventStoreConfig.getNotificationPrefix()
                                    );
                                    return templateProcessor.applyTemplateReactive(connection, "create-eventstore-table.sql", params);
                                });
                            }
                            return eventStoreChain;
                        } else {
                            logger.info("Skipping event store table creation because minimal core schema fallback was used");
                            return io.vertx.core.Future.succeededFuture();
                        }
                    })
                    .compose(v -> {
                        // Ensure core operational tables always exist (idempotent)
                        logger.info("Ensuring core operational tables exist (queue_messages, outbox, dead_letter_queue)");
                        return applyMinimalCoreSchemaReactive(connection, request.getDatabaseConfig().getSchema());
                    });
            })
            .toCompletionStage().toCompletableFuture().get();
        } finally {
            tempPool.close();
        }
    }

    private boolean isExtensionPermissionError(Throwable throwable) {
        if (throwable == null) return false;
        String msg = String.valueOf(throwable.getMessage());
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("create extension") ||
               lower.contains("uuid-ossp") ||
               lower.contains("pg_stat_statements") ||
               lower.contains("must be superuser") ||
               lower.contains("permission denied");
    }

    private io.vertx.core.Future<Void> applyMinimalCoreSchemaReactive(io.vertx.sqlclient.SqlConnection connection, String schema) {
        // Minimal schema: schemas + core tables used by native/outbox/health-checks; no extensions required
        String createSchemas = """
            CREATE SCHEMA IF NOT EXISTS peegeeq;
            CREATE SCHEMA IF NOT EXISTS bitemporal;
            """;
        String createQueueMessages =
            "CREATE TABLE IF NOT EXISTS " + schema + ".queue_messages (\n" +
            "    id BIGSERIAL PRIMARY KEY,\n" +
            "    topic VARCHAR(255) NOT NULL,\n" +
            "    payload JSONB NOT NULL,\n" +
            "    visible_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),\n" +
            "    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),\n" +
            "    lock_id BIGINT,\n" +
            "    lock_until TIMESTAMP WITH TIME ZONE,\n" +
            "    retry_count INT DEFAULT 0,\n" +
            "    max_retries INT DEFAULT 3,\n" +
            "    status VARCHAR(50) DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'LOCKED', 'PROCESSED', 'FAILED', 'DEAD_LETTER')),\n" +
            "    headers JSONB DEFAULT '{}',\n" +
            "    error_message TEXT,\n" +
            "    correlation_id VARCHAR(255),\n" +
            "    message_group VARCHAR(255),\n" +
            "    priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10)\n" +
            ");";
        String createOutbox =
            "CREATE TABLE IF NOT EXISTS " + schema + ".outbox (\n" +
            "    id BIGSERIAL PRIMARY KEY,\n" +
            "    topic VARCHAR(255) NOT NULL,\n" +
            "    payload JSONB NOT NULL,\n" +
            "    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),\n" +
            "    processed_at TIMESTAMP WITH TIME ZONE,\n" +
            "    processing_started_at TIMESTAMP WITH TIME ZONE,\n" +
            "    status VARCHAR(50) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD_LETTER')),\n" +
            "    retry_count INT DEFAULT 0,\n" +
            "    max_retries INT DEFAULT 3,\n" +
            "    next_retry_at TIMESTAMP WITH TIME ZONE,\n" +
            "    version INT DEFAULT 0,\n" +
            "    headers JSONB DEFAULT '{}',\n" +
            "    error_message TEXT,\n" +
            "    correlation_id VARCHAR(255),\n" +
            "    message_group VARCHAR(255),\n" +
            "    priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10)\n" +
            ");";
        String createDeadLetter =
            "CREATE TABLE IF NOT EXISTS " + schema + ".dead_letter_queue (\n" +
            "    id BIGSERIAL PRIMARY KEY,\n" +
            "    original_table VARCHAR(50) NOT NULL,\n" +
            "    original_id BIGINT NOT NULL,\n" +
            "    topic VARCHAR(255) NOT NULL,\n" +
            "    payload JSONB NOT NULL,\n" +
            "    original_created_at TIMESTAMP WITH TIME ZONE NOT NULL,\n" +
            "    failed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),\n" +
            "    failure_reason TEXT NOT NULL,\n" +
            "    retry_count INT NOT NULL,\n" +
            "    headers JSONB DEFAULT '{}',\n" +
            "    correlation_id VARCHAR(255),\n" +
            "    message_group VARCHAR(255)\n" +
            ");";

        return connection.query(createSchemas).execute()
            .compose(rs -> connection.query(createQueueMessages).execute())
            .compose(rs -> connection.query(createOutbox).execute())
            .compose(rs -> connection.query(createDeadLetter).execute())
            .mapEmpty();
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
            templateManager.dropDatabaseFromAdmin(
                dbConfig.getHost(),
                dbConfig.getPort(),
                dbConfig.getUsername(),
                dbConfig.getPassword(),
                dbConfig.getDatabaseName()
            )
            .onSuccess(v -> logger.info("Test database {} dropped successfully", dbConfig.getDatabaseName()))
            .onFailure(error -> {
                if (error.getMessage().contains("is being accessed by other users")) {
                    logger.warn("Database {} is still being accessed by other users, will retry cleanup later",
                        dbConfig.getDatabaseName());
                    // In a production system, you might want to schedule a retry
                } else {
                    logger.warn("Failed to drop test database: {} - {}", dbConfig.getDatabaseName(), error.getMessage());
                }
            })
            .toCompletionStage().toCompletableFuture().get();
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
        DatabaseSetupResult setup = activeSetups.get(setupId);
        DatabaseConfig dbConfig = setupDatabaseConfigs.get(setupId);
        if (setup == null || dbConfig == null) {
            logger.debug("🚫 Setup not found: {} (expected for test scenarios)", setupId);
            return CompletableFuture.failedFuture(new SetupNotFoundException("Setup not found: " + setupId));
        }

        // Create queue table using reactive SQL template with stored database config
        io.vertx.pgclient.PgConnectOptions connectOptions = new io.vertx.pgclient.PgConnectOptions()
            .setHost(dbConfig.getHost())
            .setPort(dbConfig.getPort())
            .setDatabase(dbConfig.getDatabaseName())
            .setUser(dbConfig.getUsername())
            .setPassword(dbConfig.getPassword());

        io.vertx.sqlclient.Pool tempPool = io.vertx.pgclient.PgBuilder.pool()
            .with(new io.vertx.sqlclient.PoolOptions().setMaxSize(1))
            .connectingTo(connectOptions)
            .using(vertx)
            .build();

        return tempPool.withConnection(connection -> {
            Map<String, String> params = Map.of(
                "queueName", queueConfig.getQueueName(),
                "schema", dbConfig.getSchema()
            );
            return templateProcessor.applyTemplateReactive(connection, "create-queue-table.sql", params);
        })
        .onComplete(ar -> tempPool.close())
        .toCompletionStage().toCompletableFuture()
        .handle((result, error) -> {
            if (error != null) {
                throw new RuntimeException("Failed to add queue to setup: " + setupId, error);
            }
            return result;
        });
    }

    @Override
    public CompletableFuture<Void> addEventStore(String setupId, EventStoreConfig eventStoreConfig) {
        DatabaseSetupResult setup = activeSetups.get(setupId);
        DatabaseConfig dbConfig = setupDatabaseConfigs.get(setupId);
        if (setup == null || dbConfig == null) {
            logger.debug("🚫 Setup not found: {} (expected for test scenarios)", setupId);
            return CompletableFuture.failedFuture(new SetupNotFoundException("Setup not found: " + setupId));
        }

        // Create event store table using reactive SQL template with stored database config
        io.vertx.pgclient.PgConnectOptions connectOptions = new io.vertx.pgclient.PgConnectOptions()
            .setHost(dbConfig.getHost())
            .setPort(dbConfig.getPort())
            .setDatabase(dbConfig.getDatabaseName())
            .setUser(dbConfig.getUsername())
            .setPassword(dbConfig.getPassword());

        io.vertx.sqlclient.Pool tempPool = io.vertx.pgclient.PgBuilder.pool()
            .with(new io.vertx.sqlclient.PoolOptions().setMaxSize(1))
            .connectingTo(connectOptions)
            .using(vertx)
            .build();

        return tempPool.withConnection(connection -> {
            Map<String, String> params = Map.of(
                "tableName", eventStoreConfig.getTableName(),
                "schema", dbConfig.getSchema(),
                "notificationPrefix", eventStoreConfig.getNotificationPrefix()
            );
            return templateProcessor.applyTemplateReactive(connection, "create-eventstore-table.sql", params);
        })
        .onComplete(ar -> tempPool.close())
        .toCompletionStage().toCompletableFuture()
        .handle((result, error) -> {
            if (error != null) {
                throw new RuntimeException("Failed to add event store to setup: " + setupId, error);
            }
            return result;
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

        if (eventStores != null && !eventStores.isEmpty()) {
            // Try to create event stores using reflection to avoid hard dependency on bitemporal module
            try {
                // Check if BiTemporalEventStoreFactory is available on classpath
                Class<?> factoryClass = Class.forName("dev.mars.peegeeq.bitemporal.BiTemporalEventStoreFactory");
                Object factory = factoryClass.getConstructor(PeeGeeQManager.class).newInstance(manager);

                logger.info("BiTemporalEventStoreFactory available, creating {} event store(s)", eventStores.size());

                for (EventStoreConfig eventStoreConfig : eventStores) {
                    try {
                        // Create event store for Object type (most flexible)
                        var createMethod = factoryClass.getMethod("createObjectEventStore");
                        EventStore<?> eventStore = (EventStore<?>) createMethod.invoke(factory);
                        stores.put(eventStoreConfig.getEventStoreName(), eventStore);

                        logger.info("Created bi-temporal event store for: {}", eventStoreConfig.getEventStoreName());
                    } catch (Exception e) {
                        logger.error("Failed to create event store for: {}", eventStoreConfig.getEventStoreName(), e);
                        // Continue with other event stores rather than failing completely
                    }
                }
            } catch (ClassNotFoundException e) {
                logger.info("BiTemporalEventStoreFactory not available on classpath. " +
                    "Event stores will not be created. This is expected when running without peegeeq-bitemporal module.");
            } catch (Exception e) {
                logger.error("Failed to create event store factory", e);
            }
        }

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