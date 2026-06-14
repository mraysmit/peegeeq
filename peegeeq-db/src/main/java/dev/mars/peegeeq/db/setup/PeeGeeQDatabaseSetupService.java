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
import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private final Map<String, DatabaseSetupResult> activeSetups = new ConcurrentHashMap<>();
    private final Map<String, DatabaseConfig> setupDatabaseConfigs = new ConcurrentHashMap<>();
    private final Map<String, PeeGeeQManager> activeManagers = new ConcurrentHashMap<>();
    private final Map<String, Map<String, EventStoreConfig>> eventStoreConfigs = new ConcurrentHashMap<>();

    // Reuse Vert.x instance when running inside Vert.x; otherwise create a new one
    // lazily
    private final Vertx vertx;
    private final boolean ownsVertx;

    // Dedicated worker executor to ensure setup runs off the event-loop (no Vert.x context issues)
    private final WorkerExecutor setupWorkerExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final DatabaseTemplateManager templateManager;
    private final SqlTemplateProcessor templateProcessor = new SqlTemplateProcessor();

    /**
     * Registers pre-built setup state for a given setupId.
     * Package-private intended only for lifecycle tests that need to
     * verify teardown ordering without running the full setup flow.
     */
    void registerSetupForTesting(String setupId, DatabaseSetupResult result,
                                 DatabaseConfig config, PeeGeeQManager manager) {
        activeSetups.put(setupId, result);
        setupDatabaseConfigs.put(setupId, config);
        activeManagers.put(setupId, manager);
    }

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
        this.ownsVertx = (ctx == null);
        this.vertx = (ctx != null) ? ctx.owner() : Vertx.vertx();
        this.setupWorkerExecutor = this.vertx.createSharedWorkerExecutor("peegeeq-setup-worker", 20);
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

    WorkerExecutor setupWorkerExecutor() {
        return setupWorkerExecutor;
    }

    @Override
    public Future<DatabaseSetupResult> createCompleteSetup(DatabaseSetupRequest request) {
        // Capture context for tracing restoration in later steps
        Context currentCtx = Vertx.currentContext();
        final TraceCtx capturedTrace = (currentCtx != null) ? (TraceCtx) currentCtx.get(TraceContextUtil.CONTEXT_TRACE_KEY) : null;

        // Validate schema parameter BEFORE creating database
        String schema = request.getDatabaseConfig().getSchema();
        if (schema == null || schema.isBlank()) {
            logger.error("Schema parameter is required and cannot be null or blank");
            return Future.failedFuture(
                new IllegalArgumentException("Schema parameter is required and cannot be null or blank"));
        }

        // Validate schema name using PostgreSqlIdentifierValidator
        try {
            dev.mars.peegeeq.db.util.PostgreSqlIdentifierValidator.validate(schema, "Schema");
            logger.info("Schema validation passed: {}", schema);
        } catch (IllegalArgumentException e) {
            logger.error("Schema validation failed: {}", e.getMessage());
            return Future.failedFuture(e);
        }

        // Offload the entire setup sequence involving blocking DB creation to a worker thread
        // using our tracing wrapper to ensure context propagation
        // 1. Create database from template reactively
        return createDatabaseFromTemplate(request.getDatabaseConfig())
                .map(v -> request)
                .onFailure(ex ->
                    logger.error("STEP 1 FAILED for setupId={}: {}", request.getSetupId(), ex.getMessage(), ex))
                .transform(ar -> {
                    if (ar.failed()) {
                        return Future.failedFuture(new RuntimeException("Database creation failed", ar.cause()));
                    }
                    return Future.succeededFuture(ar.result());
                })
                .compose(req -> {
                    // 2. Apply schema migrations asynchronously
                    return applySchemaTemplates(req);
                })
                .compose(req -> {
                    // 3. Create PeeGeeQ configuration and manager (use setupId as profile)
                    PeeGeeQConfiguration config = createConfiguration(req.getDatabaseConfig(), req.getSetupId());
                    PeeGeeQManager manager = new PeeGeeQManager(config);
                    // Start reactively - DO NOT block with .get()
                    return manager.start().map(v -> {
                        // Store manager for later steps
                        activeManagers.put(req.getSetupId(), manager);
                        return new Object[] { req, manager };
                    });
                })
                .compose(arr -> {
                    DatabaseSetupRequest req = (DatabaseSetupRequest) arr[0];
                    // Validate that all required tables exist in the schema
                    return validateDatabaseInfrastructure(req.getDatabaseConfig(), capturedTrace)
                            .map(v -> arr);
                })
                .map(arr -> {
                    // Restore MDC for this block so factory creation logs have traceId
                    try (var ignored = (capturedTrace != null) ? TraceContextUtil.mdcScope(capturedTrace) : null) {
                        logger.info("STEP 4: Create queues and event stores");
                        DatabaseSetupRequest req = (DatabaseSetupRequest) arr[0];
                        PeeGeeQManager manager = (PeeGeeQManager) arr[1];

                        // Register queue factory implementations with the manager's provider
                        registerAvailableQueueFactories(manager);

                        // Create event store factory from provider if available
                        Optional<EventStoreFactory> eventStoreFactory = eventStoreFactoryProvider
                                .map(provider -> provider.apply(manager));
                        if (eventStoreFactory.isPresent()) {
                            registerEventStoreFactory(manager, eventStoreFactory.get());
                        }

                        // 4. Create queues and event stores
                        Map<String, QueueConfig> queueConfigs = new HashMap<>();
                        Map<String, QueueFactory> queueFactories = createQueueFactories(manager, req.getQueues(), queueConfigs);
                        Map<String, EventStore<?>> eventStores = createEventStores(manager, req.getEventStores(),
                                eventStoreFactory);

                        DatabaseSetupResult result = new DatabaseSetupResult(
                                req.getSetupId(), queueFactories, eventStores, DatabaseSetupStatus.ACTIVE);
                        queueConfigs.forEach(result::putQueueConfig);

                        activeSetups.put(req.getSetupId(), result);
                        setupDatabaseConfigs.put(req.getSetupId(), req.getDatabaseConfig());
                        logger.info("Stored DB config for setup: {} host={} db={}",
                                req.getSetupId(),
                                req.getDatabaseConfig() != null ? req.getDatabaseConfig().getHost() : "NULL",
                                req.getDatabaseConfig() != null ? req.getDatabaseConfig().getDatabaseName() : "NULL");
                        // activeManagers.put(req.getSetupId(), manager); // Already added in Step 3

                        return result;
                    }
                })
                .transform(ar -> {
                    if (ar.succeeded()) {
                        return Future.succeededFuture(ar.result());
                    }
                    Throwable ex = ar.cause();
                    if (isDatabaseCreationConflict(ex)) {
                        logger.debug(
                                "Database creation conflict for setup: {} — concurrent request already created it",
                                request.getSetupId());
                        return Future.failedFuture(new DatabaseCreationConflictException(
                                "Database creation conflict: " + request.getSetupId()));
                    }

                    logger.error("Failed to create database setup: {} - {}", request.getSetupId(), ex.getMessage());

                    // Clean up any partially created resources asynchronously
                    return destroySetup(request.getSetupId())
                            .onFailure(cleanupEx ->
                                logger.error("Failed to clean up after setup failure: {}", request.getSetupId(),
                                        cleanupEx))
                            .transform(ar2 -> Future.failedFuture(
                                    new RuntimeException(
                                            "Failed to create database setup: " + request.getSetupId(), ex)));
                });
    }

    private Future<Void> createDatabaseFromTemplate(DatabaseConfig dbConfig) {
        logger.debug("Creating database with admin connection: host={}, port={}, username={}",
                dbConfig.getHost(), dbConfig.getPort(), dbConfig.getUsername());

        return templateManager.createDatabaseFromTemplate(
                dbConfig.getHost(),
                dbConfig.getPort(),
                dbConfig.getUsername(),
                dbConfig.getPassword(),
                dbConfig.getDatabaseName(),
                dbConfig.getTemplateDatabase() != null ? dbConfig.getTemplateDatabase() : "template0",
                dbConfig.getEncoding(),
                Map.of());
    }

    /**
     * Apply schema templates asynchronously without blocking.
     * Returns Future for proper async chaining.
     *
     * NOTE: Schema validation is performed in createCompleteSetup() before this method is called.
     */
    private Future<DatabaseSetupRequest> applySchemaTemplates(DatabaseSetupRequest request) {

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
            return templateProcessor.applyTemplate(connection, "base", baseParams)
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
                                    return templateProcessor.applyTemplate(connection, "queue", params)
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
                        // Core operational tables (queue_messages, outbox, dead_letter_queue) are now
                        // created by the base template (04a-04c), so no separate step needed
                        logger.debug("Core operational tables created by base template");
                        return Future.succeededFuture(baseApplied);
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
                                        .applyTemplate(connection, "eventstore", params)
                                        .compose(v3 -> config.isAggregateSummaryEnabled()
                                                ? templateProcessor.applyTemplate(connection, "eventstore-aggregate-summary", params)
                                                : Future.succeededFuture())
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
                .onSuccess(v -> logger.debug("applySchemaTemplates completed"))
                .onFailure(err -> logger.error("applySchemaTemplates failed", err))
                .eventually(() -> tempPool.close())
                .map(v -> request);
    }

    private Future<Void> verifyTemplatesExist(SqlConnection connection, String schema) {
        String checkTemplatesSQL = """
                SELECT
                    current_database() as db_name,
                    EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = $1 AND table_name = 'queue_template') as queue_exists,
                    EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = $1 AND table_name = 'event_store_template') as event_store_exists
                """;

        return connection.preparedQuery(checkTemplatesSQL).execute(Tuple.of(schema))
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





    @Override
    public Future<Void> destroySetup(String setupId) {
        try {
            DatabaseSetupResult setup = activeSetups.remove(setupId);
            DatabaseConfig dbConfig = setupDatabaseConfigs.remove(setupId);
            PeeGeeQManager manager = activeManagers.remove(setupId);

            if (setup == null && manager == null && dbConfig == null) {
                logger.info("Setup {} not found or already destroyed", setupId);
                return Future.succeededFuture();
            }

            Future<Void> closeResourcesFuture;
            if (setup != null) {
                List<Future<Void>> closes = new ArrayList<>();
                if (setup.getQueueFactories() != null) {
                    setup.getQueueFactories().values().forEach(factory ->
                            closes.add(factory.close()
                                    .onFailure(e -> logger.warn("Failed to close queue factory", e))
                                    .transform(ar -> Future.<Void>succeededFuture())));
                }
                if (setup.getEventStores() != null) {
                    setup.getEventStores().values().forEach(store ->
                            closes.add(store.close()
                                    .onFailure(e -> logger.warn("Failed to close event store", e))
                                    .transform(ar -> Future.<Void>succeededFuture())));
                }
                closeResourcesFuture = closes.isEmpty()
                        ? Future.succeededFuture()
                        : Future.all(closes).mapEmpty();
            } else {
                closeResourcesFuture = Future.succeededFuture();
            }

            Future<Void> shutdownFuture;

            // Close setup-owned resources before shutting down the manager so resource
            // cleanup can still use its Vert.x and database lifecycle safely.
            if (manager != null) {
                shutdownFuture = closeResourcesFuture.compose(v -> {
                    logger.info("Closing PeeGeeQManager for setup: {}", setupId);
                    return manager.closeReactive()
                            .onSuccess(ignored -> logger.info("PeeGeeQManager closed successfully for setup: {}", setupId))
                            .onFailure(error -> logger.error("Failed to close PeeGeeQManager for setup: {}", setupId, error))
                            .transform(ar -> Future.<Void>succeededFuture());
                });
            } else {
                shutdownFuture = closeResourcesFuture;
            }

            return shutdownFuture;
        } catch (Exception e) {
            return Future.failedFuture(new RuntimeException("Failed to destroy setup: " + setupId, e));
        }
    }

    @Override
    public Future<DatabaseSetupStatus> getSetupStatus(String setupId) {
        DatabaseSetupResult setup = activeSetups.get(setupId);
        if (setup == null) {
            logger.debug("Setup not found: {}", setupId);
            return Future.failedFuture(new SetupNotFoundException("Setup not found: " + setupId));
        }
        return Future.succeededFuture(setup.getStatus());
    }

    @Override
    public Future<DatabaseSetupResult> getSetupResult(String setupId) {
        DatabaseSetupResult setup = activeSetups.get(setupId);
        if (setup == null) {
            logger.debug("Setup not found: {}", setupId);
            return Future.failedFuture(new SetupNotFoundException("Setup not found: " + setupId));
        }
        return Future.succeededFuture(setup);
    }

    @Override
    public Future<DatabaseConfig> getDatabaseConfig(String setupId) {
        DatabaseConfig config = setupDatabaseConfigs.get(setupId);
        if (config == null) {
            logger.debug("Database config not found for setup: {}", setupId);
            return Future.failedFuture(new SetupNotFoundException("Setup not found: " + setupId));
        }
        return Future.succeededFuture(config);
    }

    @Override
    public Future<Void> addQueue(String setupId, QueueConfig queueConfig) {
        // Validate queue name using PostgreSqlIdentifierValidator
        String queueName = queueConfig.getQueueName();
        try {
            dev.mars.peegeeq.db.util.PostgreSqlIdentifierValidator.validate(queueName, "Queue");
            logger.debug("Queue name validation passed: {}", queueName);
        } catch (IllegalArgumentException e) {
            logger.error("Queue name validation failed: {}", e.getMessage());
            return Future.failedFuture(e);
        }

        DatabaseSetupResult setup = activeSetups.get(setupId);
        DatabaseConfig dbConfig = setupDatabaseConfigs.get(setupId);
        PeeGeeQManager manager = activeManagers.get(setupId);

        if (setup == null || dbConfig == null || manager == null) {
            logger.debug("Setup not found: {}", setupId);
            return Future.failedFuture(new SetupNotFoundException("Setup not found: " + setupId));
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
            return templateProcessor.applyTemplate(connection, "queue", params);
        })
                .eventually(() -> tempPool.close())
                .<Void>map(result -> {
                    // After table is created, create and register the QueueFactory
                    logger.info("Creating queue factory for queue: {} in setup: {}",
                               queueConfig.getQueueName(), setupId);

                    // Create a single queue factory for the new queue
                    Map<String, QueueConfig> newConfigs = new HashMap<>();
                    Map<String, QueueFactory> newFactories = createQueueFactories(manager, List.of(queueConfig), newConfigs);

                    if (!newFactories.isEmpty()) {
                        // Add the new factory and its config to the existing setup
                        QueueFactory newFactory = newFactories.get(queueConfig.getQueueName());
                        if (newFactory != null) {
                            setup.getQueueFactories().put(queueConfig.getQueueName(), newFactory);
                            newConfigs.forEach(setup::putQueueConfig);
                            logger.info("Added queue factory for '{}' to setup '{}'. Total factories: {}",
                                       queueConfig.getQueueName(), setupId, setup.getQueueFactories().size());
                        } else {
                            logger.warn("Queue factory was not created for queue: {}", queueConfig.getQueueName());
                            throw new RuntimeException("Failed to create queue '" + queueConfig.getQueueName()
                                    + "' in setup '" + setupId + "': Queue factory was not created");
                        }
                    } else {
                        logger.warn("No queue factories were created for queue: {}", queueConfig.getQueueName());
                        throw new RuntimeException("Failed to create queue '" + queueConfig.getQueueName()
                                + "' in setup '" + setupId + "': No queue factories were created");
                    }
                    return null;
                });
    }

    @Override
    public Future<Void> addEventStore(String setupId, EventStoreConfig eventStoreConfig) {
        // Validate event store table name using PostgreSqlIdentifierValidator
        String tableName = eventStoreConfig.getTableName();
        try {
            dev.mars.peegeeq.db.util.PostgreSqlIdentifierValidator.validate(tableName, "Event store table");
            logger.debug("Event store table name validation passed: {}", tableName);
        } catch (IllegalArgumentException e) {
            logger.error("Event store table name validation failed: {}", e.getMessage());
            return Future.failedFuture(e);
        }

        DatabaseSetupResult setup = activeSetups.get(setupId);
        DatabaseConfig dbConfig = setupDatabaseConfigs.get(setupId);
        PeeGeeQManager manager = activeManagers.get(setupId);

        if (setup == null || dbConfig == null || manager == null) {
            logger.debug("Setup not found: {}", setupId);
            return Future.failedFuture(new SetupNotFoundException("Setup not found: " + setupId));
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
            return templateProcessor.applyTemplate(connection, "eventstore", params)
                    .compose(v -> eventStoreConfig.isAggregateSummaryEnabled()
                            ? templateProcessor.applyTemplate(connection, "eventstore-aggregate-summary", params)
                            : Future.succeededFuture());
        })
                .eventually(() -> tempPool.close())
                .<Void>map(result -> {
                    // After table is created, create and register the EventStore
                    logger.info("Creating event store for: {} in setup: {}",
                            eventStoreConfig.getEventStoreName(), setupId);

                    // Create the event store factory from provider if available
                    Optional<EventStoreFactory> eventStoreFactory = eventStoreFactoryProvider
                            .map(provider -> provider.apply(manager));

                    if (eventStoreFactory.isPresent()) {
                        Map<String, EventStore<?>> newStores = createEventStores(manager, List.of(eventStoreConfig),
                                eventStoreFactory);
                        if (!newStores.isEmpty()) {
                            EventStore<?> newStore = newStores.get(eventStoreConfig.getEventStoreName());
                            if (newStore != null) {
                                setup.getEventStores().put(eventStoreConfig.getEventStoreName(), newStore);
                                eventStoreConfigs.computeIfAbsent(setupId, k -> new ConcurrentHashMap<>())
                                        .put(eventStoreConfig.getEventStoreName(), eventStoreConfig);
                                logger.info("Added event store '{}' to setup '{}'. Total stores: {}",
                                        eventStoreConfig.getEventStoreName(), setupId, setup.getEventStores().size());
                            }
                        }
                    }
                    return null;
                })
                .onFailure(error ->
                    logger.error("Failed to add event store '{}' to setup '{}': {}",
                            eventStoreConfig.getEventStoreName(), setupId, error.getMessage(), error))
                .transform(ar -> {
                    if (ar.failed()) {
                        return Future.failedFuture(
                            new RuntimeException("Failed to add event store '" + eventStoreConfig.getEventStoreName()
                                    + "' to setup '" + setupId + "'", ar.cause()));
                    }
                    return Future.succeededFuture(ar.result());
                });
    }

    @Override
    public Future<Void> removeEventStore(String setupId, String storeName) {
        DatabaseSetupResult setup = activeSetups.get(setupId);
        DatabaseConfig dbConfig = setupDatabaseConfigs.get(setupId);

        if (setup == null || dbConfig == null) {
            return Future.failedFuture(new SetupNotFoundException("Setup not found: " + setupId));
        }

        EventStore<?> store = setup.getEventStores().get(storeName);
        if (store == null) {
            return Future.failedFuture(new IllegalArgumentException("Event store not found: " + storeName));
        }

        Map<String, EventStoreConfig> configs = eventStoreConfigs.getOrDefault(setupId, Map.of());
        EventStoreConfig config = configs.get(storeName);
        String tableName = config != null ? config.getTableName()
                : storeName.replaceAll("-", "_") + "_events";
        String schema = dbConfig.getSchema();

        logger.info("Removing event store '{}' (table: {}.{}) from setup '{}'",
                storeName, schema, tableName, setupId);

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

        return store.close()
                .compose(v -> tempPool.withConnection(conn ->
                        conn.query("DROP TABLE IF EXISTS " + schema + "." + tableName + "_aggregate_summary CASCADE").execute()
                                .compose(r -> conn.query("DROP TABLE IF EXISTS " + schema + "." + tableName + " CASCADE").execute())
                ))
                .eventually(() -> tempPool.close())
                .map(v -> {
                    setup.getEventStores().remove(storeName);
                    Map<String, EventStoreConfig> cfgMap = eventStoreConfigs.get(setupId);
                    if (cfgMap != null) cfgMap.remove(storeName);
                    logger.info("Event store '{}' removed from setup '{}'", storeName, setupId);
                    return (Void) null;
                })
                .onFailure(e -> logger.error("Failed to remove event store '{}' from setup '{}': {}",
                        storeName, setupId, e.getMessage(), e));
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
        return createQueueFactories(manager, queues, null);
    }

    private Map<String, QueueFactory> createQueueFactories(PeeGeeQManager manager, List<QueueConfig> queues,
                                                            Map<String, QueueConfig> configsByName) {
        Map<String, QueueFactory> factories = new HashMap<>();

        logger.info("createQueueFactories called with queues: {}", queues != null ? queues.size() : "null");

        if (queues != null && !queues.isEmpty()) {
            // Get the queue factory provider from the manager
            var queueFactoryProvider = manager.getQueueFactoryProvider();
            var databaseService = manager.getDatabaseService();
            var configuration = manager.getConfiguration();

            // Determine the fallback type for queues that do not request a specific
            // implementation type. When no implementations are registered, only queues that
            // explicitly request an (unsupported) type can fail; queues without a request are
            // skipped, preserving the original behaviour.
            var bestAvailableType = queueFactoryProvider.getBestAvailableType();

            // Prepare configuration map with PeeGeeQConfiguration
            Map<String, Object> factoryConfig = new HashMap<>();
            factoryConfig.put("peeGeeQConfiguration", configuration);

            for (QueueConfig queueConfig : queues) {
                String requestedType = queueConfig.getImplementationType();

                // Resolve the per-queue implementation type: use the explicitly requested type
                // when present, otherwise fall back to the best available type.
                String implementationType;
                if (requestedType != null && !requestedType.isEmpty()) {
                    if (!queueFactoryProvider.isTypeSupported(requestedType)) {
                        // A specific type was requested but is not registered. Fail clearly
                        // instead of silently substituting a different implementation.
                        throw new IllegalArgumentException(
                                "Unsupported implementation type '" + requestedType + "' requested for queue '"
                                        + queueConfig.getQueueName() + "'. Supported types: "
                                        + queueFactoryProvider.getSupportedTypes());
                    }
                    implementationType = requestedType;
                } else if (bestAvailableType.isPresent()) {
                    implementationType = bestAvailableType.get();
                } else {
                    logger.info("No queue factory implementations available and no type requested for queue '{}'. " +
                            "Skipping queue factory creation. This is expected when running without " +
                            "peegeeq-native or peegeeq-outbox modules.", queueConfig.getQueueName());
                    continue;
                }

                try {
                    // Create a queue factory for this queue using the resolved type WITH configuration
                    QueueFactory factory = queueFactoryProvider.createFactory(implementationType, databaseService, factoryConfig);
                    factories.put(queueConfig.getQueueName(), factory);
                    if (configsByName != null) {
                        configsByName.put(queueConfig.getQueueName(), queueConfig);
                    }

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
    private boolean isDatabaseCreationConflict(Throwable e) {
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

                for (EventStoreConfig eventStoreConfig : eventStores) {
                    try {
                        // Use unqualified table names and rely on connection-level search_path for
                        // multi-tenant schema isolation. The full config is passed so factories can
                        // honor per-store options (e.g. the aggregate summary).
                        String tableName = eventStoreConfig.getTableName();
                        EventStore<?> eventStore = factory.createEventStore(Object.class, eventStoreConfig);
                        stores.put(eventStoreConfig.getEventStoreName(), eventStore);

                        logger.info("Created event store '{}' using table '{}' (schema resolved via search_path)",
                                eventStoreConfig.getEventStoreName(),
                                tableName);
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
    public Future<Set<String>> getAllActiveSetupIds() {
        return Future.succeededFuture(activeSetups.keySet());
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
     * @return Future that completes when validation is done
     */
    private Future<Void> validateDatabaseInfrastructure(DatabaseConfig dbConfig, TraceCtx traceCtx) {
        try (var scope = TraceContextUtil.mdcScope(traceCtx)) {
            logger.info("Validating database infrastructure for database={}, schema={}",
                    dbConfig.getDatabaseName(), dbConfig.getSchema());
        }

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

        return validationPool.withConnection(conn -> {
            // Ensure trace context is propagated to the validation connection block
            try (var scope = TraceContextUtil.mdcScope(traceCtx)) {
                // Check what tables exist in the schema
                String checkTablesSQL =
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = $1 ORDER BY table_name";

                return conn.preparedQuery(checkTablesSQL).execute(Tuple.of(dbConfig.getSchema()))
                        .compose(rowSet -> {
                            // Restore trace context for result processing callbacks
                            try (var innerScope = TraceContextUtil.mdcScope(traceCtx)) {
                                logger.info("========== DATABASE INFRASTRUCTURE VALIDATION ==========");
                                logger.info("Database: {}, Schema: {}", dbConfig.getDatabaseName(),
                                        dbConfig.getSchema());

                                Set<String> tables = new HashSet<>();
                                rowSet.forEach(row -> {
                                    String tableName = row.getString("table_name");
                                    tables.add(tableName);
                                    logger.info("  - {}", tableName);
                                });

                                // Derive the required table list from the base template manifest
                                // so this check stays in sync automatically when new tables are added.
                                List<String> requiredTables;
                                try {
                                    requiredTables = List.copyOf(templateProcessor.resolveRequiredTables("base"));
                                } catch (java.io.IOException e) {
                                    logger.error("Could not resolve required tables from base template manifest", e);
                                    return Future.<Void>failedFuture(e);
                                }

                                logger.info("========== REQUIRED TABLES CHECK ({} tables): {} ==========",
                                        requiredTables.size(), requiredTables);
                                List<String> missingTables = new ArrayList<>();
                                for (String required : requiredTables) {
                                    boolean exists = tables.contains(required);
                                    logger.info("  {}: {}", required, exists ? "✓ EXISTS" : "✗ MISSING");
                                    if (!exists) {
                                        missingTables.add(required);
                                    }
                                }

                                if (!missingTables.isEmpty()) {
                                    logger.error("VALIDATION FAILED: Missing required tables: {}", missingTables);
                                    return Future.<Void>failedFuture(new IllegalStateException(
                                            "Database infrastructure validation failed - missing tables: "
                                                    + missingTables));
                                }

                                logger.info("========== VALIDATION PASSED ==========");
                                return Future.<Void>succeededFuture();
                            }
                        });
            }
        })
        .eventually(() -> validationPool.close());
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

    /**
     * Closes this service and releases its owned resources.
     *
     * Teardown order:
     * 1. Destroy any active setups (best-effort)
     * 2. Close setup worker executor
     * 3. Close Vertx only if this service created it
     */
    public Future<Void> close() {
        if (!closed.compareAndSet(false, true)) {
            return Future.succeededFuture();
        }

        // Close each active manager to cancel background timers and release pool connections.
        // Do NOT call destroySetup() here that would drop test databases, which must only
        // happen via an explicit destroySetup() call (e.g. from integration test teardown).
        List<Future<Void>> closeFutures = new ArrayList<>(activeManagers.values()).stream()
                .map(manager -> manager.closeReactive()
                        .onFailure(error ->
                            logger.warn("Failed to close manager during service close: {}", error.getMessage())))
                .toList();

        return Future.join(closeFutures)
                .transform(ar -> Future.<Void>succeededFuture())
                .compose(v -> setupWorkerExecutor.close())
                .compose(v -> {
                    if (ownsVertx) {
                        return vertx.close();
                    }
                    return Future.succeededFuture();
                });
    }
}