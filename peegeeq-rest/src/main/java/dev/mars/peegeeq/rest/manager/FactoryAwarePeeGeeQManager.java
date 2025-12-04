package dev.mars.peegeeq.rest.manager;

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A PeeGeeQManager that automatically registers queue factory implementations
 * during initialization. This ensures that queue factories are available
 * without requiring reflection or circular dependencies.
 *
 * <p>Factory registrations are provided externally via {@link #addFactoryRegistration(Consumer)},
 * allowing the REST layer to remain decoupled from implementation modules.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * FactoryAwarePeeGeeQManager manager = new FactoryAwarePeeGeeQManager(config);
 * manager.addFactoryRegistration(PgNativeFactoryRegistrar::registerWith);
 * manager.addFactoryRegistration(OutboxFactoryRegistrar::registerWith);
 * manager.start();
 * }</pre>
 */
public class FactoryAwarePeeGeeQManager extends PeeGeeQManager {

    private static final Logger logger = LoggerFactory.getLogger(FactoryAwarePeeGeeQManager.class);

    // List of factory registration callbacks - injected externally
    private final List<Consumer<QueueFactoryRegistrar>> factoryRegistrations = new ArrayList<>();

    public FactoryAwarePeeGeeQManager(PeeGeeQConfiguration configuration) {
        super(configuration);
    }

    public FactoryAwarePeeGeeQManager(PeeGeeQConfiguration configuration, MeterRegistry meterRegistry) {
        super(configuration, meterRegistry);
    }

    /**
     * Adds a factory registration callback that will be invoked during start().
     * This allows implementation modules to register their factories without
     * creating direct dependencies from the REST layer.
     *
     * @param registration A consumer that registers a factory with the registrar
     */
    public void addFactoryRegistration(Consumer<QueueFactoryRegistrar> registration) {
        factoryRegistrations.add(registration);
    }

    @Override
    public void start() {
        // Start the base manager first
        super.start();

        // Register queue factory implementations after the manager is started
        registerQueueFactories();
    }

    /**
     * Registers queue factory implementations with this manager's factory provider.
     */
    private void registerQueueFactories() {
        try {
            var queueFactoryProvider = getQueueFactoryProvider();

            if (queueFactoryProvider instanceof QueueFactoryRegistrar) {
                QueueFactoryRegistrar registrar = (QueueFactoryRegistrar) queueFactoryProvider;

                // Invoke all registered factory registration callbacks
                for (Consumer<QueueFactoryRegistrar> registration : factoryRegistrations) {
                    try {
                        registration.accept(registrar);
                        logger.info("Registered queue factory implementation via callback");
                    } catch (Exception e) {
                        logger.error("Failed to register queue factory via callback: {}", e.getMessage(), e);
                    }
                }

                logger.info("Successfully registered {} queue factory implementations. Available types: {}",
                    factoryRegistrations.size(), queueFactoryProvider.getSupportedTypes());
            } else {
                logger.warn("Queue factory provider does not support registration");
            }
        } catch (Exception e) {
            logger.error("Failed to register queue factory implementations: {}", e.getMessage(), e);
        }
    }
}
