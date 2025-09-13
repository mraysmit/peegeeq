package dev.mars.peegeeq.db.setup;

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.test.TestFactoryRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test-specific implementation of PeeGeeQDatabaseSetupService that registers
 * real queue factory implementations for testing purposes using TestContainers.
 *
 * This class overrides the registerAvailableQueueFactories method to ensure
 * that real factory implementations are available during testing, avoiding the
 * "No queue factory implementations are registered" error.
 * No mocking is used - all tests use real PostgreSQL instances via TestContainers.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-08
 * @version 1.0
 */
public class TestPeeGeeQDatabaseSetupService extends PeeGeeQDatabaseSetupService {
    
    private static final Logger logger = LoggerFactory.getLogger(TestPeeGeeQDatabaseSetupService.class);
    
    /**
     * Registers available real queue factory implementations for testing.
     * This implementation registers real factory implementations (native, outbox)
     * if they are available on the classpath. Uses TestContainers with real PostgreSQL.
     *
     * @param manager The PeeGeeQ manager to register factories with
     */
    @Override
    protected void registerAvailableQueueFactories(PeeGeeQManager manager) {
        logger.info("Registering real queue factory implementations for testing with TestContainers");

        try {
            // Register all available real factories (native, outbox) - no mocking
            TestFactoryRegistration.registerAvailableFactories(manager.getQueueFactoryRegistrar());
            logger.info("Successfully registered real queue factory implementations for testing");
        } catch (Exception e) {
            logger.error("Failed to register real queue factory implementations for testing", e);
            throw new RuntimeException("Failed to register real queue factory implementations", e);
        }
    }
}
