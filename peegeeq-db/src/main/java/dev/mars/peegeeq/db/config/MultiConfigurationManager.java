package dev.mars.peegeeq.db.config;

import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import io.vertx.core.Future;

/**
 * Manager for handling multiple PeeGeeQ configurations within the same application.
 * Supports named configurations with different database connections, queue settings,
 * and performance characteristics.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-17
 * @version 1.0
 */
public class MultiConfigurationManager implements AutoCloseable {

    /**
     * Lifecycle states for the manager.
     */
    private enum State { STOPPED, STARTING, STARTED, CLOSING }
    
    private static final Logger logger = LoggerFactory.getLogger(MultiConfigurationManager.class);
    
    private final Map<String, PeeGeeQConfiguration> configurations = new ConcurrentHashMap<>();
    private final Map<String, PeeGeeQManager> managers = new ConcurrentHashMap<>();
    private final Map<String, DatabaseService> databaseServices = new ConcurrentHashMap<>();
    private final QueueFactoryProvider factoryProvider;
    private final MeterRegistry meterRegistry;
    private final boolean ownsMeterRegistry;
    private final AtomicBoolean meterRegistryClosed = new AtomicBoolean(false);
    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);
    
    /**
     * Creates a new MultiConfigurationManager with default meter registry.
     */
    public MultiConfigurationManager() {
        this(new SimpleMeterRegistry(), true);
    }
    
    /**
     * Creates a new MultiConfigurationManager with the specified meter registry.
     *
     * @param meterRegistry The meter registry for metrics collection
     */
    public MultiConfigurationManager(MeterRegistry meterRegistry) {
        this(meterRegistry, false);
    }

    private MultiConfigurationManager(MeterRegistry meterRegistry, boolean ownsMeterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.ownsMeterRegistry = ownsMeterRegistry;
        this.factoryProvider = new PgQueueFactoryProvider();
        logger.debug("Initialized MultiConfigurationManager");
    }

    
    /**
     * Registers a named configuration.
     * 
     * @param name The unique name for this configuration
     * @param config The PeeGeeQ configuration
     * @throws IllegalArgumentException if name is null/empty or config is null
     * @throws IllegalStateException if configuration name already exists
     */
    public synchronized void registerConfiguration(String name, PeeGeeQConfiguration config) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Configuration name cannot be null or empty");
        }

        if (config == null) {
            throw new IllegalArgumentException("Configuration cannot be null");
        }

        if (configurations.containsKey(name)) {
            throw new IllegalStateException("Configuration with name '" + name + "' already exists");
        }

        if (state.get() != State.STOPPED) {
            throw new IllegalStateException("Cannot register configurations while manager is in state: " + state.get());
        }

        PeeGeeQManager manager = null;
        try {
            configurations.put(name, config);
            manager = new PeeGeeQManager(config, meterRegistry);
            managers.put(name, manager);
            databaseServices.put(name, new PgDatabaseService(manager));

            logger.info("Registered configuration: {}", name);
        } catch (Exception e) {
            // Cleanup on failure - CRITICAL: Close the manager if it was created
            configurations.remove(name);
            PeeGeeQManager managerToClose = managers.remove(name);
            databaseServices.remove(name);

            // Close the manager if it was created to prevent thread leaks
            if (managerToClose != null) {
                try {
                    managerToClose.close();
                    logger.info("Closed PeeGeeQManager during cleanup for failed configuration: {}", name);
                } catch (Exception closeException) {
                    logger.error("Failed to close PeeGeeQManager during cleanup for configuration: {}", name, closeException);
                }
            }

            logger.error("Failed to register configuration: {}", name, e);
            throw new RuntimeException("Failed to register configuration: " + name, e);
        }
    }
    
    /**
     * Registers a configuration using a profile name.
     * 
     * @param name The unique name for this configuration
     * @param profile The profile name to load configuration from
     */
    public void registerConfiguration(String name, String profile) {
        registerConfiguration(name, new PeeGeeQConfiguration(profile));
    }
    
    /**
     * Starts all registered configurations by delegating to the reactive lifecycle.
     *
     * This method is non-blocking and exists for compatibility.
     */
    public synchronized void start() {
        startReactive()
            .onFailure(e -> logger.error("Failed to start MultiConfigurationManager", e));
    }

    /**
     * Starts all registered configurations reactively.
     *
     * @return A Future that completes when all configurations are started
     */
    public Future<Void> startReactive() {
        if (!state.compareAndSet(State.STOPPED, State.STARTING)) {
            State current = state.get();
            if (current == State.STARTED || current == State.STARTING) {
                logger.warn("MultiConfigurationManager is already {}", current);
                return Future.succeededFuture();
            }
            return Future.failedFuture("Cannot start while in state: " + current);
        }

        logger.info("Starting MultiConfigurationManager with {} configurations", configurations.size());

        List<Future<?>> futures = new ArrayList<>();
        for (Map.Entry<String, PeeGeeQManager> entry : managers.entrySet()) {
            futures.add(entry.getValue().start()
                .onSuccess(v -> logger.info("Started configuration: {}", entry.getKey()))
                .onFailure(e -> logger.error("Failed to start configuration: {}", entry.getKey(), e)));
        }

        return Future.all(futures)
            .compose(v -> {
                state.set(State.STARTED);
                logger.info("MultiConfigurationManager started successfully");
                return Future.<Void>succeededFuture();
            })
            .recover(e -> {
                state.set(State.STOPPED);
                logger.error("MultiConfigurationManager failed to start, reverting to STOPPED", e);
                return Future.failedFuture(e);
            });
    }
    
    /**
     * Creates a queue factory for the specified configuration and implementation type.
     * 
     * @param configName The name of the registered configuration
     * @param implementationType The queue implementation type (e.g., "native", "outbox")
     * @return A queue factory instance
     * @throws IllegalArgumentException if configuration doesn't exist or implementation type is unsupported
     */
    public QueueFactory createFactory(String configName, String implementationType) {
        return createFactory(configName, implementationType, new HashMap<>());
    }
    
    /**
     * Creates a queue factory with additional configuration parameters.
     * 
     * @param configName The name of the registered configuration
     * @param implementationType The queue implementation type
     * @param additionalConfig Additional configuration parameters
     * @return A queue factory instance
     */
    public QueueFactory createFactory(String configName, String implementationType, 
                                    Map<String, Object> additionalConfig) {
        if (state.get() != State.STARTED) {
            throw new IllegalStateException("MultiConfigurationManager is not started");
        }

        if (!configurations.containsKey(configName)) {
            throw new IllegalArgumentException("Configuration not found: " + configName);
        }
        
        DatabaseService databaseService = databaseServices.get(configName);
        if (databaseService == null) {
            throw new IllegalStateException("Database service not available for configuration: " + configName);
        }
        
        try {
            Map<String, Object> safeAdditionalConfig = additionalConfig != null
                ? additionalConfig
                : Collections.emptyMap();
            QueueFactory factory = factoryProvider.createFactory(implementationType, databaseService, safeAdditionalConfig);
            logger.info("Created {} queue factory for configuration: {}", implementationType, configName);
            return factory;
        } catch (Exception e) {
            logger.error("Failed to create queue factory for configuration: {}", configName, e);
            throw new RuntimeException("Failed to create queue factory", e);
        }
    }
    
    /**
     * Gets the names of all registered configurations.
     *
     * @return A set of configuration names
     */
    public Set<String> getConfigurationNames() {
        return Set.copyOf(configurations.keySet());
    }

    /**
     * Gets the queue factory provider for registering additional factory types.
     *
     * @return The queue factory provider
     */
    public QueueFactoryProvider getFactoryProvider() {
        return factoryProvider;
    }

    /**
     * Registers queue factory implementations with this manager.
     * This is a convenience method that delegates to the internal factory provider.
     *
     * @param implementationType The implementation type name
     * @param creator The factory creator
     */
    public void registerFactory(String implementationType, QueueFactoryRegistrar.QueueFactoryCreator creator) {
        if (factoryProvider instanceof QueueFactoryRegistrar) {
            ((QueueFactoryRegistrar) factoryProvider).registerFactory(implementationType, creator);
        } else {
            throw new UnsupportedOperationException("Factory provider does not support registration");
        }
    }
    
    /**
     * Checks if a configuration with the given name exists.
     * 
     * @param name The configuration name to check
     * @return true if the configuration exists, false otherwise
     */
    public boolean hasConfiguration(String name) {
        return configurations.containsKey(name);
    }
    
    /**
     * Gets the configuration for the specified name.
     * 
     * @param name The configuration name
     * @return The PeeGeeQ configuration
     * @throws IllegalArgumentException if configuration doesn't exist
     */
    public PeeGeeQConfiguration getConfiguration(String name) {
        PeeGeeQConfiguration config = configurations.get(name);
        if (config == null) {
            throw new IllegalArgumentException("Configuration not found: " + name);
        }
        return config;
    }
    
    /**
     * Gets the database service for the specified configuration.
     * 
     * @param name The configuration name
     * @return The database service
     * @throws IllegalArgumentException if configuration doesn't exist
     */
    public DatabaseService getDatabaseService(String name) {
        DatabaseService service = databaseServices.get(name);
        if (service == null) {
            throw new IllegalArgumentException("Database service not found for configuration: " + name);
        }
        return service;
    }
    
    /**
     * Checks if the manager has been started.
     * 
     * @return true if started, false otherwise
     */
    public boolean isStarted() {
        return state.get() == State.STARTED;
    }
    
    /**
     * Stops all configurations and releases resources by delegating to the reactive lifecycle.
     *
     * This method is non-blocking and exists for compatibility.
     */
    @Override
    public void close() {
        closeReactive()
            .onFailure(e -> logger.error("Failed to close MultiConfigurationManager", e));
    }

    /**
     * Stops all configurations and releases resources reactively.
     *
     * @return A Future that completes when all configurations are closed
     */
    public Future<Void> closeReactive() {
        State previous = state.getAndSet(State.CLOSING);
        if (previous == State.STOPPED || previous == State.CLOSING) {
            logger.info("MultiConfigurationManager is already {} — skipping close", previous);
            closeOwnedMeterRegistry();
            return Future.succeededFuture();
        }

        logger.info("Closing MultiConfigurationManager");

        List<Future<?>> futures = new ArrayList<>();
        for (Map.Entry<String, PeeGeeQManager> entry : managers.entrySet()) {
            futures.add(entry.getValue().closeReactive()
                .onSuccess(v -> logger.info("Closed configuration: {}", entry.getKey()))
                .onFailure(e -> logger.error("Failed to close configuration: {}", entry.getKey(), e)));
        }

        return Future.join(futures)
            .compose(v -> Future.<Void>succeededFuture())
            .recover(e -> {
                logger.warn("Some configurations failed to close cleanly: {}", e.getMessage());
                return Future.<Void>succeededFuture();
            })
            .eventually(() -> {
                configurations.clear();
                managers.clear();
                databaseServices.clear();
                state.set(State.STOPPED);
                closeOwnedMeterRegistry();
                logger.info("MultiConfigurationManager closed");
                return Future.<Void>succeededFuture();
            });
    }

    private void closeOwnedMeterRegistry() {
        if (!ownsMeterRegistry) {
            return;
        }
        if (!meterRegistryClosed.compareAndSet(false, true)) {
            return;
        }

        try {
            meterRegistry.close();
            logger.debug("Closed owned MeterRegistry");
        } catch (Exception e) {
            logger.warn("Failed to close owned MeterRegistry cleanly", e);
        }
    }
}
