package dev.mars.peegeeq.db.test;

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Registrar for the mock queue factory implementation.
 * This is used only for testing purposes to provide a mock implementation
 * when real implementations are not available.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-08
 * @version 1.0
 */
public class MockFactoryRegistrar {
    
    private static final Logger logger = LoggerFactory.getLogger(MockFactoryRegistrar.class);
    
    /**
     * Registers the mock factory with the provided registrar.
     * 
     * @param registrar The factory registrar to register with
     */
    public static void registerWith(QueueFactoryRegistrar registrar) {
        registrar.registerFactory("mock", new MockFactoryCreator());
        logger.info("Registered mock queue factory for testing");
    }
    
    /**
     * Unregisters the mock factory from the provided registrar.
     * 
     * @param registrar The factory registrar to unregister from
     */
    public static void unregisterFrom(QueueFactoryRegistrar registrar) {
        registrar.unregisterFactory("mock");
        logger.info("Unregistered mock queue factory");
    }
    
    /**
     * Factory creator for mock queue factories.
     */
    private static class MockFactoryCreator implements QueueFactoryRegistrar.QueueFactoryCreator {

        @Override
        public QueueFactory create(DatabaseService databaseService, Map<String, Object> configuration) throws Exception {
            return new MockQueueFactory(databaseService);
        }
    }
}
