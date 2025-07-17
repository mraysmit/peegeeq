package dev.mars.peegeeq.db.config;

import dev.mars.peegeeq.api.DatabaseService;
import dev.mars.peegeeq.api.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for QueueConfigurationBuilder functionality.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-17
 * @version 1.0
 */
class QueueConfigurationBuilderTest {
    
    private static final Logger logger = LoggerFactory.getLogger(QueueConfigurationBuilderTest.class);
    
    private PeeGeeQManager manager;
    private DatabaseService databaseService;
    
    @BeforeEach
    void setUp() {
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        manager.start();
        databaseService = new PgDatabaseService(manager);
    }
    
    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }
    
    @Test
    void testCreateHighThroughputQueue() throws Exception {
        // Create high-throughput queue
        QueueFactory factory = QueueConfigurationBuilder.createHighThroughputQueue(databaseService);

        assertNotNull(factory);
        assertEquals("native", factory.getImplementationType());
        assertTrue(factory.isHealthy());

        factory.close();
    }

    @Test
    void testCreateLowLatencyQueue() throws Exception {
        // Create low-latency queue
        QueueFactory factory = QueueConfigurationBuilder.createLowLatencyQueue(databaseService);

        assertNotNull(factory);
        assertEquals("native", factory.getImplementationType());
        assertTrue(factory.isHealthy());

        factory.close();
    }

    @Test
    void testCreateReliableQueue() throws Exception {
        // Create reliable queue
        QueueFactory factory = QueueConfigurationBuilder.createReliableQueue(databaseService);

        assertNotNull(factory);
        assertEquals("outbox", factory.getImplementationType());
        assertTrue(factory.isHealthy());

        factory.close();
    }

    @Test
    void testCreateDurableQueue() throws Exception {
        // Create durable queue
        QueueFactory factory = QueueConfigurationBuilder.createDurableQueue(databaseService);

        assertNotNull(factory);
        assertEquals("outbox", factory.getImplementationType());
        assertTrue(factory.isHealthy());

        factory.close();
    }
    
    @Test
    void testCreateCustomQueue() throws Exception {
        // Create custom queue with specific settings
        QueueFactory factory = QueueConfigurationBuilder.createCustomQueue(
            databaseService,
            "native",
            5,                              // batch size
            Duration.ofMillis(500),         // polling interval
            3,                              // max retries
            Duration.ofSeconds(30),         // visibility timeout
            true                            // dead letter enabled
        );

        assertNotNull(factory);
        assertEquals("native", factory.getImplementationType());
        assertTrue(factory.isHealthy());

        factory.close();
    }

    @Test
    void testCreateCustomOutboxQueue() throws Exception {
        // Create custom outbox queue
        QueueFactory factory = QueueConfigurationBuilder.createCustomQueue(
            databaseService,
            "outbox",
            10,                             // batch size
            Duration.ofSeconds(1),          // polling interval
            5,                              // max retries
            Duration.ofMinutes(1),          // visibility timeout
            true                            // dead letter enabled
        );

        assertNotNull(factory);
        assertEquals("outbox", factory.getImplementationType());
        assertTrue(factory.isHealthy());

        factory.close();
    }

    @Test
    void testDifferentQueueTypesHaveDifferentCharacteristics() throws Exception {
        // Create different queue types
        QueueFactory highThroughputFactory = QueueConfigurationBuilder.createHighThroughputQueue(databaseService);
        QueueFactory lowLatencyFactory = QueueConfigurationBuilder.createLowLatencyQueue(databaseService);
        QueueFactory reliableFactory = QueueConfigurationBuilder.createReliableQueue(databaseService);
        
        // Verify they are different instances
        assertNotSame(highThroughputFactory, lowLatencyFactory);
        assertNotSame(lowLatencyFactory, reliableFactory);
        assertNotSame(highThroughputFactory, reliableFactory);
        
        // Verify implementation types
        assertEquals("native", highThroughputFactory.getImplementationType());
        assertEquals("native", lowLatencyFactory.getImplementationType());
        assertEquals("outbox", reliableFactory.getImplementationType());
        
        // All should be healthy
        assertTrue(highThroughputFactory.isHealthy());
        assertTrue(lowLatencyFactory.isHealthy());
        assertTrue(reliableFactory.isHealthy());
        
        // Clean up
        highThroughputFactory.close();
        lowLatencyFactory.close();
        reliableFactory.close();
    }
    
    @Test
    void testInvalidCustomQueueParameters() throws Exception {
        // Test with invalid implementation type
        assertThrows(RuntimeException.class, () -> {
            QueueConfigurationBuilder.createCustomQueue(
                databaseService,
                "invalid-type",
                5,
                Duration.ofMillis(500),
                3,
                Duration.ofSeconds(30),
                true
            );
        });
    }

    @Test
    void testNullDatabaseService() throws Exception {
        // Test with null database service
        assertThrows(RuntimeException.class, () -> {
            QueueConfigurationBuilder.createHighThroughputQueue(null);
        });

        assertThrows(RuntimeException.class, () -> {
            QueueConfigurationBuilder.createLowLatencyQueue(null);
        });

        assertThrows(RuntimeException.class, () -> {
            QueueConfigurationBuilder.createReliableQueue(null);
        });

        assertThrows(RuntimeException.class, () -> {
            QueueConfigurationBuilder.createDurableQueue(null);
        });
    }
}
