package dev.mars.peegeeq.rest.manager;

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A PeeGeeQManager that automatically registers queue factory implementations
 * during initialization. This ensures that queue factories are available
 * without requiring reflection or circular dependencies.
 */
public class FactoryAwarePeeGeeQManager extends PeeGeeQManager {
    
    private static final Logger logger = LoggerFactory.getLogger(FactoryAwarePeeGeeQManager.class);
    
    public FactoryAwarePeeGeeQManager(PeeGeeQConfiguration configuration) {
        super(configuration);
    }
    
    public FactoryAwarePeeGeeQManager(PeeGeeQConfiguration configuration, MeterRegistry meterRegistry) {
        super(configuration, meterRegistry);
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
                
                // Register native queue factory
                PgNativeFactoryRegistrar.registerWith(registrar);
                logger.info("Registered native queue factory implementation");
                
                // Register outbox queue factory
                OutboxFactoryRegistrar.registerWith(registrar);
                logger.info("Registered outbox queue factory implementation");
                
                logger.info("Successfully registered all queue factory implementations. Available types: {}", 
                    queueFactoryProvider.getSupportedTypes());
            } else {
                logger.warn("Queue factory provider does not support registration");
            }
        } catch (Exception e) {
            logger.error("Failed to register queue factory implementations: {}", e.getMessage(), e);
        }
    }
}
