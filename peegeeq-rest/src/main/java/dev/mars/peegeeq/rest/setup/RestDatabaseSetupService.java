package dev.mars.peegeeq.rest.setup;

import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.api.setup.DatabaseSetupResult;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * REST-specific database setup service that handles queue factory registration.
 * This service properly registers queue factory implementations using dependency injection
 * rather than reflection, following proper architectural patterns.
 */
public class RestDatabaseSetupService extends PeeGeeQDatabaseSetupService {

    private static final Logger logger = LoggerFactory.getLogger(RestDatabaseSetupService.class);

    @Override
    protected void registerAvailableQueueFactories(PeeGeeQManager manager) {
        try {
            var queueFactoryProvider = manager.getQueueFactoryProvider();

            if (queueFactoryProvider instanceof QueueFactoryRegistrar) {
                QueueFactoryRegistrar registrar = (QueueFactoryRegistrar) queueFactoryProvider;

                // Register native queue factory (available as direct dependency)
                PgNativeFactoryRegistrar.registerWith(registrar);
                logger.info("Registered native queue factory implementation");

                // Register outbox queue factory (available as direct dependency)
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
