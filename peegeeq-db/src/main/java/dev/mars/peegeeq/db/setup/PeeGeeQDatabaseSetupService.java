package dev.mars.peegeeq.db.setup;

import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.EventStoreFactory;
import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.setup.*;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.info.PeeGeeQInfoCodes;

public class PeeGeeQDatabaseSetupService implements DatabaseSetupService {

    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQDatabaseSetupService.class);

    private final Optional<Function<PeeGeeQManager, EventStoreFactory>> eventStoreFactoryProvider;

    // Factory registrations to apply during setup
    private final List<Consumer<QueueFactoryRegistrar>> factoryRegistrations = new ArrayList<>();

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
     * Custom exception for database creation conflicts that doesn't generate stack
     * traces
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

    // Reuse Vert.x instance when running inside Vert.x; otherwise create a new one
    // lazily
    private final Vertx vertx;

    // Dedicated worker executor to ensure setup runs off the event-loop (no Vert.x
    // context)
    private final ExecutorService setupExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "peegeeq-setup-worker");
        t.setDaemon(true);
        return t;
    });

    private final DatabaseTemplateManager templateManager;
    private final SqlTemplateProcessor templateProcessor = new SqlTemplateProcessor();

    /**
     * Creates a new database setup service without EventStore support.
     * Use this constructor when EventStore functionality is not needed.
     */
    public PeeGeeQDatabaseSetupService() {
        this((Function<PeeGeeQManager, EventStoreFactory>) null);
    }

    /**
     * Creates a new database setup service with optional EventStore support.
     * The factory provider function allows late binding of the EventStoreFactory,
     * which is necessary because the factory typically depends on the
     * PeeGeeQManager
     * that is created during setup.
     * 
     * @param eventStoreFactoryProvider Function that creates an EventStoreFactory
     *                                  given a PeeGeeQManager,
     *                                  or null if EventStore support is not needed
     */
    public PeeGeeQDatabaseSetupService(Function<PeeGeeQManager, EventStoreFactory> eventStoreFactoryProvider) {
        this.eventStoreFactoryProvider = Optional.ofNullable(eventStoreFactoryProvider);

        // If we're inside a Vert.x context, reuse the owning Vertx instance; otherwise
        // create a new one
        var ctx = Vertx.currentContext();
        this.vertx = (ctx != null) ? ctx.owner() : Vertx.vertx();
        this.templateManager = new DatabaseTemplateManager(vertx);

        if (this.eventStoreFactoryProvider.isPresent()) {
            logger.info("Database setup service initialized with EventStore factory provider");
        } else {
            logger.info("Database setup service initialized without EventStore support");
        }
    }

    /**
     * Adds a factory registration callback that will be invoked during setup.
     * This allows implementation modules to register their factories without
     * creating direct dependencies.
     *
     * @param registration A consumer that registers a factory with the registrar
     */
    @Override
    public void addFactoryRegistration(Consumer<QueueFactoryRegistrar> registration) {
        factoryRegistrations.add(registration);
        logger.debug("Added factory registration callback, total registrations: {}", factoryRegistrations.size());
    }

    @Override
    public CompletableFuture<DatabaseSetupResult> createCompleteSetup(DatabaseSetupRequest request) {
        logger.debug("START createCompleteSetup for setupId={}", request.getSetupId());

        // Validate schema parameter BEFORE creating database
        String schema = request.getDatabaseConfig().getSchema();
        if (schema == null || schema.isBlank()) {
            logger.error("Schema parameter is required and cannot be null or blank");
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Schema parameter is required and cannot be null or blank"));
        }

        // Validate schema name (prevent SQL injection)
        if (!schema.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            logger.error("Invalid schema name: {} - must start with letter or underscore, followed by alphanumeric or underscore", schema);
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Invalid schema name: " + schema +
                    " - must start with letter or underscore, followed by alphanumeric or underscore"));
        }

        // Prevent reserved schema names
        if (schema.startsWith("pg_") || schema.equals("information_schema")) {
            logger.error("Reserved schema name: {} - cannot use PostgreSQL system schemas", schema);
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Reserved schema name: " + schema +
                    " - cannot use PostgreSQL system schemas (pg_*, information_schema)"));
        }

        logger.info("Schema validation passed: {}", schema);

        // Offload the entire setup sequence to a worker thread to avoid running any
        // potentially blocking operations on the Vert.x event loop thread
        return CompletableFuture.supplyAsync(() -> {
            logger.debug("STEP 1: Creating database from template for setupId={}", request.getSetupId());
            try {
                // 1. Create database from template
                createDatabaseFromTemplate(request.getDatabaseConfig());
                logger.debug("STEP 1 COMPLETE: Database created");
                return request;
            } catch (Exception e) {
                logger.error("STEP 1 FAILED for setupId={}", request.getSetupId(), e);
                throw new RuntimeException(e);
            }
        }, setupExecutor)
                .exceptionally(ex -> {
                    logger.error("EXCEPTION in supplyAsync for setupId={}", request.getSetupId(), ex);
                    throw new RuntimeException("Database creation failed", ex);
                })
                .thenCompose(req -> {
                    logger.debug("STEP 2: Apply schema templates async");
                    // 2. Apply schema migrations asynchronously
                    return applySchemaTemplatesAsync(req);
                })
                .thenCompose(req -> {
                    logger.debug("STEP 3: Create manager and start");
                    // 3. Create PeeGeeQ configuration and manager (use setupId as profile)
                    PeeGeeQConfiguration config = createConfiguration(req.getDatabaseConfig(), req.getSetupId());
                    PeeGeeQManager manager = new PeeGeeQManager(config);
                    // Start reactively - DO NOT block with .get()
                    return manager.startReactive().toCompletionStage().thenApply(v -> {
                        // Store manager for later steps
                        logger.debug("STEP 3 COMPLETE: Manager started");
                        return new Object[] { req, manager };
                    });
                })
                .thenCompose(arr -> {
                    logger.debug("STEP 3.5: Validate database infrastructure");
                    DatabaseSetupRequest req = (DatabaseSetupRequest) arr[0];
                    PeeGeeQManager manager = (PeeGeeQManager) arr[1];
                    // Validate that all required tables exist in the schema
                    return validateDatabaseInfrastructure(req.getDatabaseConfig())
                            .thenApply(v -> arr);
                })
                .thenApply(arr -> {
                    logger.info("STEP 4: Create queues and event stores");
                    DatabaseSetupRequest req = (DatabaseSetupRequest) arr[0];
                    PeeGeeQManager manager = (PeeGeeQManager) arr[1];

                    try {

                        // Register queue factory implementations with the manager's provider
                        registerAvailableQueueFactories(manager);

                        // Create event store factory from provider if available
                        Optional<EventStoreFactory> eventStoreFactory = eventStoreFactoryProvider
                                .map(provider -> provider.apply(manager));
                        if (eventStoreFactory.isPresent()) {
                            registerEventStoreFactory(manager, eventStoreFactory.get());
                        }

                        // 4. Create queues and event stores
                        Map<String, QueueFactory> queueFactories = createQueueFactories(manager, req.getQueues());
                        Map<String, EventStore<?>> eventStores = createEventStores(manager, req.getEventStores(),
                                eventStoreFactory);

                        DatabaseSetupResult result = new DatabaseSetupResult(
                                req.getSetupId(), queueFactories, eventStores, DatabaseSetupStatus.ACTIVE);

                        activeSetups.put(req.getSetupId(), result);
                        setupDatabaseConfigs.put(req.getSetupId(), req.getDatabaseConfig());
                        activeManagers.put(req.getSetupId(), manager);

                        logger.debug("COMPLETE: createCompleteSetup finished for setupId={}", req.getSetupId());
                        return result;

                    } catch (Exception e) {
                        // Check if this is a database creation conflict (expected in concurrent
                        // scenarios)
                        if (isDatabaseCreationConflict(e)) {
                            logger.debug(
                                    "EXPECTED: Database creation conflict for setup: {} (concurrent test scenario)",
                                    req.getSetupId());
                            throw new DatabaseCreationConflictException(
                                    "Database creation conflict: " + req.getSetupId());
                        }

                        // For other exceptions, provide more context but still throw with stack trace
                        logger.error("Failed to create database setup: {} - {}", req.getSetupId(), e.getMessage());

                        // Clean up any partially created resources
                        try {
                            destroySetup(req.getSetupId()).get();
                        } catch (Exception cleanupException) {
                            logger.error("Failed to clean up after setup failure: {}", req.getSetupId(),
                                    cleanupException);
                        }

                        throw new RuntimeException("Failed to create database setup: " + req.getSetupId(), e);
                    }
                });
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
                Map.of()).toCompletionStage().toCompletableFuture().get();
    }

    /**
     * Apply schema templates asynchronously without blocking.
     * Returns CompletableFuture for proper async chaining.
     *
     * NOTE: Schema validation is performed in createCompleteSetup() before this method is called.
     */
    private CompletableFuture<DatabaseSetupRequest> applySchemaTemplatesAsync(DatabaseSetupRequest request) {
        // Create a temporary reactive pool for schema template application
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(request.getDatabaseConfig().getHost())
                .setPort(request.getDatabaseConfig().getPort())
                .setDatabase(request.getDatabaseConfig().getDatabaseName())
                .setUser(request.getDatabaseConfig().getUsername())
                .setPassword(request.getDatabaseConfig().getPassword());

        Pool tempPool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(1))
                .connectingTo(connectOptions)
                .using(vertx)
                .build();

        return tempPool.withConnection(connection -> {
            // Apply base template; on permission errors, fall back to minimal core schema
            // (no extensions)
            // NOTE: PostgreSQL will emit NOTICE messages for "DROP TABLE IF NOT EXISTS"
            // when tables don't exist.
            // These appear as WARN in Vert.x logs but are expected and harmless during
            // fresh database setup.
            logger.info(
                    "[{}] Applying base template (PostgreSQL NOTICE messages about non-existent tables are expected during fresh setup)",
                    PeeGeeQInfoCodes.SCHEMA_SETUP_STARTED);

            // Build the complete Future chain that MUST complete before connection is
            // released
            Map<String, String> baseParams = Map.of("schema", request.getDatabaseConfig().getSchema());
            return templateProcessor.applyTemplateReactive(connection, "base", baseParams)
                    .onSuccess(v -> logger.debug("Base template SQL executed"))
                    .compose(v -> {
                        // CRITICAL: Verify that the templates were actually created
                        // CREATE EXTENSION IF NOT EXISTS will "succeed" even when lacking permissions,
                        // but the template tables won't be created without the extensions
                        logger.debug("Verifying templates exist in " + request.getDatabaseConfig().getSchema()
                                + " schema...");
                        return verifyTemplatesExist(connection, request.getDatabaseConfig().getSchema());
                    })
                    .onSuccess(v -> logger.info("[{}] Base template verified - all required templates exist",
                            PeeGeeQInfoCodes.INFRASTRUCTURE_READY))
                    .map(Boolean.TRUE)
                    .recover(err -> {
                        if (isExtensionPermissionError(err) || err.getMessage().contains("Templates not found")) {
                            logger.warn(
                                    "Base template verification failed. Falling back to minimal core schema without extensions: {}",
                                    err.getMessage());
                            return applyMinimalCoreSchemaReactive(connection, request.getDatabaseConfig().getSchema())
                                    .map(Boolean.FALSE);
                        }
                        return Future.failedFuture(err);
                    })
                    .compose(baseApplied -> {
                        // Only create per-queue tables if base template (with templates) was applied
                        if (Boolean.TRUE.equals(baseApplied)) {
                            Future<Void> queueChain = Future.succeededFuture();
                            for (QueueConfig queueConfig : request.getQueues()) {
                                queueChain = queueChain.compose(v2 -> {
                                    logger.info("[{}] Creating queue table: {}", PeeGeeQInfoCodes.TABLE_CREATED,
                                            queueConfig.getQueueName());
                                    Map<String, String> params = Map.of(
                                            "queueName", queueConfig.getQueueName(),
                                            "schema", request.getDatabaseConfig().getSchema());
                                    return templateProcessor.applyTemplateReactive(connection, "queue", params)
                                            .onSuccess(v3 -> logger.info("[{}] Queue table created: {}",
                                                    PeeGeeQInfoCodes.QUEUE_CREATED, queueConfig.getQueueName()));
                                });
                            }
                            return queueChain.map(baseApplied);
                        } else {
                            // CRITICAL: If queues were requested but template failed, this is an error!
                            if (!request.getQueues().isEmpty()) {
                                String errorMsg = String.format(
                                        "Cannot create queues [%s] because database template creation failed due to insufficient permissions. "
                                                +
                                                "Queue tables require peegeeq.queue_template which could not be created. "
                                                +
                                                "Grant CREATE EXTENSION permission to the database user or use outbox pattern instead.",
                                        request.getQueues().stream()
                                                .map(QueueConfig::getQueueName)
                                                .collect(Collectors.joining(", ")));
                                logger.error(errorMsg);
                                return Future.failedFuture(new IllegalStateException(errorMsg));
                            }
                            logger.info("No queues requested, minimal core schema is sufficient");
                            return Future.succeededFuture(baseApplied);
                        }
                    })
                    .compose(baseApplied -> {
                        // First, ensure core operational tables always exist (idempotent)
                        // Do this FIRST before event stores so we can return success/failure properly
                        logger.info(
                                "[{}] Ensuring core operational tables exist (queue_messages, outbox, dead_letter_queue)",
                                PeeGeeQInfoCodes.TABLE_CREATED);
                        return applyMinimalCoreSchemaReactive(connection, request.getDatabaseConfig().getSchema())
                                .map(v -> baseApplied); // Pass through baseApplied flag
                    })
                    .compose(baseApplied -> {
                        // Only create event store tables if base template was applied
                        logger.debug("EVENT STORE COMPOSE: baseApplied={}, eventStoreCount={}", baseApplied,
                                request.getEventStores().size());
                        if (Boolean.TRUE.equals(baseApplied)) {
                            logger.debug("BASE APPLIED TRUE - creating {} event stores",
                                    request.getEventStores().size());
                            // Create all event store tables in parallel
                            List<Future<Void>> eventStoreFutures = new ArrayList<>();
                            for (EventStoreConfig config : request.getEventStores()) {
                                logger.debug("Adding event store {} to futures list", config.getTableName());
                                logger.info("[{}] Creating event store table: {} in schema: {}",
                                        PeeGeeQInfoCodes.TABLE_CREATED, config.getTableName(),
                                        request.getDatabaseConfig().getSchema());
                                Map<String, String> params = Map.of(
                                        "tableName", config.getTableName(),
                                        "schema", request.getDatabaseConfig().getSchema(),
                                        "notificationPrefix", config.getNotificationPrefix());
                                Future<Void> future = templateProcessor
                                        .applyTemplateReactive(connection, "eventstore", params)
                                        .onSuccess(v3 -> logger.info("[{}] Event store table created: {}",
                                                PeeGeeQInfoCodes.EVENT_STORE_CREATED, config.getTableName()))
                                        .onFailure(err -> logger.error("Failed to create event store table {}: {}",
                                                config.getTableName(), err.getMessage(), err));
                                eventStoreFutures.add(future);
                            }
                            // Wait for ALL event stores to be created
                            logger.debug("Waiting for {} event store futures", eventStoreFutures.size());
                            return Future.all(eventStoreFutures)
                                    .onSuccess(v -> logger.info("[{}] All event stores created successfully",
                                            PeeGeeQInfoCodes.INFRASTRUCTURE_READY))
                                    .onFailure(err -> logger.error("Event store creation failed", err))
                                    .mapEmpty();
                        } else {
                            // CRITICAL: If event stores were requested but template failed, this is an
                            // error!
                            if (!request.getEventStores().isEmpty()) {
                                String errorMsg = String.format(
                                        "Cannot create event stores [%s] because database template creation failed due to insufficient permissions. "
                                                +
                                                "Event store tables require bitemporal.event_store_template which could not be created. "
                                                +
                                                "Grant CREATE EXTENSION permission to the database user or remove event store configuration.",
                                        request.getEventStores().stream()
                                                .map(EventStoreConfig::getEventStoreName)
                                                .collect(Collectors.joining(", ")));
                                logger.error(errorMsg);
                                return Future.failedFuture(new IllegalStateException(errorMsg));
                            }
                            logger.info("No event stores requested, minimal core schema is sufficient");
                            return Future.succeededFuture();
                        }
                    })
                    .onSuccess(v -> logger.debug("All schema templates applied successfully"))
                    .onFailure(err -> logger.error("Schema template application failed: {}", err.getMessage()));
        })
                .toCompletionStage()
                .toCompletableFuture()
                .whenComplete((result, error) -> {
                    // Always close the pool after async operations complete (or fail)
                    tempPool.close();
                    if (error != null) {
                        logger.error("applySchemaTemplatesAsync failed", error);
                    } else {
                        logger.debug("applySchemaTemplatesAsync completed");
                    }
                })
                .thenApply(v -> request); // Return the request for chaining
    }

    private Future<Void> verifyTemplatesExist(SqlConnection connection, String schema) {
        String checkTemplatesSQL = String.format(
                """
                        SELECT
                            current_database() as db_name,
                            EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = '%s' AND table_name = 'queue_template') as queue_exists,
                            EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = '%s' AND table_name = 'event_store_template') as event_store_exists
                        """,
                schema, schema);

        return connection.query(checkTemplatesSQL).execute()
                .compose(rowSet -> {
                    if (rowSet.size() == 0) {
                        return Future.failedFuture(
                                new IllegalStateException("Templates not found: Failed to verify template existence"));
                    }
                    var row = rowSet.iterator().next();
                    String dbName = row.getString("db_name");
                    boolean queueExists = row.getBoolean("queue_exists");
                    boolean eventStoreExists = row.getBoolean("event_store_exists");

                    if (!queueExists || !eventStoreExists) {
                        String msg = String.format(
                                "Templates not found in database %s: queue_template=%b, event_store_template=%b. " +
                                        "This usually means CREATE EXTENSION failed silently due to insufficient permissions.",
                                dbName, queueExists, eventStoreExists);
                        logger.error(msg);
                        return Future.failedFuture(new IllegalStateException(msg));
                    }

                    logger.debug(
                            "Template verification successful in database {}: queue_template and event_store_template both exist",
                            dbName);
                    return Future.succeededFuture();
                });
    }

    private boolean isExtensionPermissionError(Throwable throwable) {
        if (throwable == null)
            return false;
        String msg = String.valueOf(throwable.getMessage());
        if (msg == null)
            return false;
        String lower = msg.toLowerCase();
        return lower.contains("create extension") ||
                lower.contains("uuid-ossp") ||
                lower.contains("pg_stat_statements") ||
                lower.contains("must be superuser") ||
                lower.contains("permission denied");
    }

    private Future<Void> applyMinimalCoreSchemaReactive(SqlConnection connection, String schema) {
        // Core tables used by native/outbox/health-checks
        // Note: Schema is already created by base template (03-schemas.sql)
        String createQueueMessages = "CREATE TABLE IF NOT EXISTS " + schema + ".queue_messages (\n" +
                "    id BIGSERIAL PRIMARY KEY,\n" +
                "    topic VARCHAR(255) NOT NULL,\n" +
                "    payload JSONB NOT NULL,\n" +
                "    visible_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),\n" +
                "    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),\n" +
                "    lock_id BIGINT,\n" +
                "    lock_until TIMESTAMP WITH TIME ZONE,\n" +
                "    retry_count INT DEFAULT 0,\n" +
                "    max_retries INT DEFAULT 3,\n" +
                "    status VARCHAR(50) DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'LOCKED', 'PROCESSED', 'FAILED', 'DEAD_LETTER')),\n"
                +
                "    headers JSONB DEFAULT '{}',\n" +
                "    error_message TEXT,\n" +
                "    correlation_id VARCHAR(255),\n" +
                "    message_group VARCHAR(255),\n" +
                "    priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10)\n" +
                ");";
        String createOutbox = "CREATE TABLE IF NOT EXISTS " + schema + ".outbox (\n" +
                "    id BIGSERIAL PRIMARY KEY,\n" +
                "    topic VARCHAR(255) NOT NULL,\n" +
                "    payload JSONB NOT NULL,\n" +
                "    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),\n" +
                "    processed_at TIMESTAMP WITH TIME ZONE,\n" +
                "    processing_started_at TIMESTAMP WITH TIME ZONE,\n" +
                "    status VARCHAR(50) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD_LETTER')),\n"
                +
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
        String createDeadLetter = "CREATE TABLE IF NOT EXISTS " + schema + ".dead_letter_queue (\n" +
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

        return connection.query(createQueueMessages).execute()
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
                    dbConfig.getDatabaseName())
                    .onSuccess(v -> logger.info("Test database {} dropped successfully", dbConfig.getDatabaseName()))
                    .onFailure(error -> {
                        if (error.getMessage().contains("is being accessed by other users")) {
                            logger.warn("Database {} is still being accessed by other users, will retry cleanup later",
                                    dbConfig.getDatabaseName());
                            // In a production system, you might want to schedule a retry
                        } else {
                            logger.warn("Failed to drop test database: {} - {}", dbConfig.getDatabaseName(),
                                    error.getMessage());
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
            logger.debug("Setup not found: {} (expected for test scenarios)", setupId);
            return CompletableFuture.failedFuture(new SetupNotFoundException("Setup not found: " + setupId));
        }
        return CompletableFuture.completedFuture(setup.getStatus());
    }

    @Override
    public CompletableFuture<DatabaseSetupResult> getSetupResult(String setupId) {
        DatabaseSetupResult setup = activeSetups.get(setupId);
        if (setup == null) {
            logger.debug("Setup not found: {} (expected for test scenarios)", setupId);
            return CompletableFuture.failedFuture(new SetupNotFoundException("Setup not found: " + setupId));
        }
        return CompletableFuture.completedFuture(setup);
    }

    @Override
    public CompletableFuture<Void> addQueue(String setupId, QueueConfig queueConfig) {
        DatabaseSetupResult setup = activeSetups.get(setupId);
        DatabaseConfig dbConfig = setupDatabaseConfigs.get(setupId);
        PeeGeeQManager manager = activeManagers.get(setupId);

        if (setup == null || dbConfig == null || manager == null) {
            logger.debug("Setup not found: {} (expected for test scenarios)", setupId);
            return CompletableFuture.failedFuture(new SetupNotFoundException("Setup not found: " + setupId));
        }

        // Create queue table using reactive SQL template with stored database config
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(dbConfig.getHost())
                .setPort(dbConfig.getPort())
                .setDatabase(dbConfig.getDatabaseName())
                .setUser(dbConfig.getUsername())
                .setPassword(dbConfig.getPassword());

        Pool tempPool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(1))
                .connectingTo(connectOptions)
                .using(vertx)
                .build();

        return tempPool.withConnection(connection -> {
            Map<String, String> params = Map.of(
                    "queueName", queueConfig.getQueueName(),
                    "schema", dbConfig.getSchema());
            return templateProcessor.applyTemplateReactive(connection, "queue", params);
        })
                .onComplete(ar -> tempPool.close())
                .toCompletionStage().toCompletableFuture()
                .thenApply(result -> {
                    // After table is created, create and register the QueueFactory
                    logger.info("Creating queue factory for queue: {} in setup: {}",
                               queueConfig.getQueueName(), setupId);

                    // Create a single queue factory for the new queue
                    Map<String, QueueFactory> newFactories = createQueueFactories(manager, List.of(queueConfig));

                    if (!newFactories.isEmpty()) {
                        // Add the new factory to the existing setup's queue factories
                        QueueFactory newFactory = newFactories.get(queueConfig.getQueueName());
                        if (newFactory != null) {
                            setup.getQueueFactories().put(queueConfig.getQueueName(), newFactory);
                            logger.info("✅ Added queue factory for '{}' to setup '{}'. Total factories: {}",
                                       queueConfig.getQueueName(), setupId, setup.getQueueFactories().size());
                        } else {
                            logger.warn("Queue factory was not created for queue: {}", queueConfig.getQueueName());
                            throw new RuntimeException("Queue factory was not created for queue: " + queueConfig.getQueueName());
                        }
                    } else {
                        logger.warn("No queue factories were created for queue: {}", queueConfig.getQueueName());
                        throw new RuntimeException("No queue factories were created for queue: " + queueConfig.getQueueName());
                    }
                    return null;
                });
    }

    @Override
    public CompletableFuture<Void> addEventStore(String setupId, EventStoreConfig eventStoreConfig) {
        DatabaseSetupResult setup = activeSetups.get(setupId);
        DatabaseConfig dbConfig = setupDatabaseConfigs.get(setupId);
        if (setup == null || dbConfig == null) {
            logger.debug("Setup not found: {} (expected for test scenarios)", setupId);
            return CompletableFuture.failedFuture(new SetupNotFoundException("Setup not found: " + setupId));
        }

        // Create event store table using reactive SQL template with stored database
        // config
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(dbConfig.getHost())
                .setPort(dbConfig.getPort())
                .setDatabase(dbConfig.getDatabaseName())
                .setUser(dbConfig.getUsername())
                .setPassword(dbConfig.getPassword());

        Pool tempPool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(1))
                .connectingTo(connectOptions)
                .using(vertx)
                .build();

        return tempPool.withConnection(connection -> {
            Map<String, String> params = Map.of(
                    "tableName", eventStoreConfig.getTableName(),
                    "schema", dbConfig.getSchema(),
                    "notificationPrefix", eventStoreConfig.getNotificationPrefix());
            return templateProcessor.applyTemplateReactive(connection, "eventstore", params);
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

    private PeeGeeQConfiguration createConfiguration(DatabaseConfig dbConfig, String setupId) {
        logger.info("Creating PeeGeeQConfiguration with setupId as profile: {}", setupId);

        // CRITICAL: Use the programmatic constructor to avoid System.setProperty()
        // pollution
        // which causes concurrent setups to interfere with each other's database
        // connections.
        // The old approach using System.setProperty() created shared mutable global
        // state that
        // would cause test 4's manager to read test 5's database name when creating new
        // connections.
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(
                setupId, // Use setupId as profile
                dbConfig.getHost(),
                dbConfig.getPort(),
                dbConfig.getDatabaseName(),
                dbConfig.getUsername(),
                dbConfig.getPassword(),
                dbConfig.getSchema());

        logger.info("Created PeeGeeQConfiguration - profile will be: {}", config.getProfile());
        return config;
    }

    private Map<String, QueueFactory> createQueueFactories(PeeGeeQManager manager, List<QueueConfig> queues) {
        Map<String, QueueFactory> factories = new HashMap<>();

        logger.info("createQueueFactories called with queues: {}", queues != null ? queues.size() : "null");

        if (queues != null && !queues.isEmpty()) {
            // Get the queue factory provider from the manager
            var queueFactoryProvider = manager.getQueueFactoryProvider();
            var databaseService = manager.getDatabaseService();
            var configuration = manager.getConfiguration();

            // Check if any queue factory implementations are available
            var bestAvailableType = queueFactoryProvider.getBestAvailableType();
            if (bestAvailableType.isEmpty()) {
                logger.info("No queue factory implementations available. Skipping queue factory creation. " +
                        "This is expected when running without peegeeq-native or peegeeq-outbox modules.");
                return factories;
            }

            String implementationType = bestAvailableType.get();
            logger.info("Using {} queue factory implementation for {} queue(s)", implementationType, queues.size());

            // Prepare configuration map with PeeGeeQConfiguration
            Map<String, Object> factoryConfig = new HashMap<>();
            factoryConfig.put("peeGeeQConfiguration", configuration);

            for (QueueConfig queueConfig : queues) {
                try {
                    // Create a queue factory for each queue using the available type WITH configuration
                    QueueFactory factory = queueFactoryProvider.createFactory(implementationType, databaseService, factoryConfig);
                    factories.put(queueConfig.getQueueName(), factory);

                    logger.info("Created {} queue factory for queue: {} with schema: {}", implementationType,
                            queueConfig.getQueueName(), configuration.getDatabaseConfig().getSchema());
                } catch (Exception e) {
                    logger.error("Failed to create queue factory for queue: {}", queueConfig.getQueueName(), e);
                    // Continue with other queues rather than failing completely
                }
            }
        }

        return factories;
    }

    /**
     * Check if the exception is a database creation conflict (expected in
     * concurrent scenarios).
     */
    private boolean isDatabaseCreationConflict(Exception e) {
        if (e == null)
            return false;

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

    private Map<String, EventStore<?>> createEventStores(PeeGeeQManager manager, List<EventStoreConfig> eventStores,
            Optional<EventStoreFactory> eventStoreFactory) {
        Map<String, EventStore<?>> stores = new HashMap<>();

        if (eventStores != null && !eventStores.isEmpty()) {
            if (eventStoreFactory.isPresent()) {
                EventStoreFactory factory = eventStoreFactory.get();
                logger.info("{} available, creating {} event store(s)",
                        factory.getFactoryName(), eventStores.size());

                // Get the database schema from the manager's configuration to construct fully
                // qualified table names
                String schema = manager.getConfiguration().getDatabaseConfig().getSchema();

                for (EventStoreConfig eventStoreConfig : eventStores) {
                    try {
                        // Construct fully qualified table name (schema.tableName) for database
                        // operations
                        // This ensures queries work correctly regardless of PostgreSQL search_path
                        // settings
                        String fullyQualifiedTableName = schema + "." + eventStoreConfig.getTableName();

                        // Create event store for Object type (most flexible) with fully qualified table
                        // name
                        EventStore<?> eventStore = factory.createEventStore(Object.class, fullyQualifiedTableName);
                        stores.put(eventStoreConfig.getEventStoreName(), eventStore);

                        logger.info("Created event store '{}' using fully qualified table '{}'",
                                eventStoreConfig.getEventStoreName(),
                                fullyQualifiedTableName);
                    } catch (Exception e) {
                        logger.error("Failed to create event store for: {}", eventStoreConfig.getEventStoreName(), e);
                        // Continue with other event stores rather than failing completely
                    }
                }
            } else {
                logger.info("No EventStoreFactory configured. " +
                        "Event stores will not be created. " +
                        "To enable EventStore support, inject an EventStoreFactory when creating PeeGeeQDatabaseSetupService.");
            }
        }

        return stores;
    }

    /**
     * Registers available queue factory implementations with the manager's factory
     * provider.
     * This method applies any factory registrations that were added via
     * addFactoryRegistration().
     * Subclasses can override to add additional factory implementations.
     */
    protected void registerAvailableQueueFactories(PeeGeeQManager manager) {
        if (factoryRegistrations.isEmpty()) {
            logger.debug("No factory registrations configured - no factories will be registered");
            return;
        }

        QueueFactoryRegistrar registrar = manager.getQueueFactoryRegistrar();
        logger.info("Applying {} factory registration(s) to manager", factoryRegistrations.size());

        for (Consumer<QueueFactoryRegistrar> registration : factoryRegistrations) {
            try {
                registration.accept(registrar);
            } catch (Exception e) {
                logger.error("Failed to apply factory registration: {}", e.getMessage(), e);
            }
        }

        logger.info("Factory registrations applied. Available types: {}",
                manager.getQueueFactoryProvider().getSupportedTypes());
    }

    /**
     * Hook method for registering an event store factory with the manager.
     * Subclasses can override to customize factory registration.
     * 
     * @param manager The PeeGeeQ manager
     * @param factory The event store factory to register
     */
    protected void registerEventStoreFactory(PeeGeeQManager manager, EventStoreFactory factory) {
        // Base implementation does nothing - EventStore instances are created directly
        // Subclasses can override if they need custom registration logic
        logger.debug("Event store factory registered: {}", factory.getFactoryName());
    }

    @Override
    public CompletableFuture<Set<String>> getAllActiveSetupIds() {
        return CompletableFuture.completedFuture(activeSetups.keySet());
    }

    // ========== ServiceProvider implementation ==========

    @Override
    public dev.mars.peegeeq.api.subscription.SubscriptionService getSubscriptionServiceForSetup(String setupId) {
        PeeGeeQManager manager = activeManagers.get(setupId);
        if (manager == null) {
            logger.debug("Manager not found for setupId: {}", setupId);
            return null;
        }
        return manager.createSubscriptionService();
    }

    @Override
    public dev.mars.peegeeq.api.deadletter.DeadLetterService getDeadLetterServiceForSetup(String setupId) {
        PeeGeeQManager manager = activeManagers.get(setupId);
        if (manager == null) {
            logger.debug("Manager not found for setupId: {}", setupId);
            return null;
        }
        return manager.getDeadLetterQueueManager();
    }

    @Override
    public dev.mars.peegeeq.api.health.HealthService getHealthServiceForSetup(String setupId) {
        PeeGeeQManager manager = activeManagers.get(setupId);
        if (manager == null) {
            logger.debug("Manager not found for setupId: {}", setupId);
            return null;
        }
        return manager.getHealthCheckManager();
    }

    /**
     * Validates that all required database infrastructure exists in the schema.
     * This method connects directly to the database and checks for required tables.
     *
     * @param dbConfig The database configuration
     * @return CompletableFuture that completes when validation is done
     */
    private CompletableFuture<Void> validateDatabaseInfrastructure(DatabaseConfig dbConfig) {
        logger.info("Validating database infrastructure for database={}, schema={}",
                dbConfig.getDatabaseName(), dbConfig.getSchema());

        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(dbConfig.getHost())
                .setPort(dbConfig.getPort())
                .setDatabase(dbConfig.getDatabaseName())
                .setUser(dbConfig.getUsername())
                .setPassword(dbConfig.getPassword());

        Pool validationPool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(1))
                .connectingTo(connectOptions)
                .using(vertx)
                .build();

        CompletableFuture<Void> validationFuture = new CompletableFuture<>();

        validationPool.withConnection(conn -> {
            // Check what tables exist in the schema
            String checkTablesSQL = String.format(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = '%s' ORDER BY table_name",
                    dbConfig.getSchema());

            return conn.query(checkTablesSQL).execute()
                    .compose(rowSet -> {
                        logger.info("========== DATABASE INFRASTRUCTURE VALIDATION ==========");
                        logger.info("Database: {}, Schema: {}", dbConfig.getDatabaseName(), dbConfig.getSchema());

                        Set<String> tables = new HashSet<>();
                        rowSet.forEach(row -> {
                            String tableName = row.getString("table_name");
                            tables.add(tableName);
                            logger.info("  - {}", tableName);
                        });

                        // Check for required tables
                        boolean hasQueueMessages = tables.contains("queue_messages");
                        boolean hasOutbox = tables.contains("outbox");
                        boolean hasDeadLetterQueue = tables.contains("dead_letter_queue");

                        logger.info("========== REQUIRED TABLES CHECK ==========");
                        logger.info("  queue_messages: {}", hasQueueMessages ? "✓ EXISTS" : "✗ MISSING");
                        logger.info("  outbox: {}", hasOutbox ? "✓ EXISTS" : "✗ MISSING");
                        logger.info("  dead_letter_queue: {}", hasDeadLetterQueue ? "✓ EXISTS" : "✗ MISSING");

                        if (!hasQueueMessages || !hasOutbox || !hasDeadLetterQueue) {
                            List<String> missingTables = new ArrayList<>();
                            if (!hasQueueMessages) missingTables.add("queue_messages");
                            if (!hasOutbox) missingTables.add("outbox");
                            if (!hasDeadLetterQueue) missingTables.add("dead_letter_queue");

                            logger.error("VALIDATION FAILED: Missing required tables: {}", missingTables);
                            return Future.failedFuture(new IllegalStateException(
                                    "Database infrastructure validation failed - missing tables: " + missingTables));
                        }

                        logger.info("========== VALIDATION PASSED ==========");
                        return Future.succeededFuture();
                    });
        })
        .onComplete(ar -> {
            validationPool.close();
            if (ar.succeeded()) {
                validationFuture.complete(null);
            } else {
                validationFuture.completeExceptionally(ar.cause());
            }
        });

        return validationFuture;
    }

    @Override
    public dev.mars.peegeeq.api.QueueFactoryProvider getQueueFactoryProviderForSetup(String setupId) {
        PeeGeeQManager manager = activeManagers.get(setupId);
        if (manager == null) {
            logger.debug("Manager not found for setupId: {}", setupId);
            return null;
        }
        return manager.getQueueFactoryProvider();
    }
}