package dev.mars.peegeeq.db.setup;

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.test.TestFactoryRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test-specific implementation of PeeGeeQDatabaseSetupService that registers
 * mock queue factory implementations for testing purposes.
 * 
 * This class overrides the registerAvailableQueueFactories method to ensure
 * that mock factories are available during testing, avoiding the
 * "No queue factory implementations are registered" error.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-08
 * @version 1.0
 */
public class TestPeeGeeQDatabaseSetupService extends PeeGeeQDatabaseSetupService {
    
    private static final Logger logger = LoggerFactory.getLogger(TestPeeGeeQDatabaseSetupService.class);
    
    /**
     * Registers available queue factory implementations for testing.
     * This implementation registers mock factories and attempts to register
     * real implementations if they are available on the classpath.
     * 
     * @param manager The PeeGeeQ manager to register factories with
     */
    @Override
    protected void registerAvailableQueueFactories(PeeGeeQManager manager) {
        logger.info("Registering queue factory implementations for testing");
        
        try {
            // Register all available factories (mock, native, outbox)
            TestFactoryRegistration.registerAvailableFactories(manager.getQueueFactoryRegistrar());
            logger.info("Successfully registered queue factory implementations for testing");
        } catch (Exception e) {
            logger.error("Failed to register queue factory implementations for testing", e);
            throw new RuntimeException("Failed to register queue factory implementations", e);
        }
    }
}
