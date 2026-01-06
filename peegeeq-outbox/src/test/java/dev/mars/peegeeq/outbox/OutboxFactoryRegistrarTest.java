package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for OutboxFactoryRegistrar.
 * Tests registration, unregistration, and factory creation logic without mocking.
 */
class OutboxFactoryRegistrarTest {

    private TestQueueFactoryRegistrar testRegistrar;

    @BeforeEach
    void setUp() {
        testRegistrar = new TestQueueFactoryRegistrar();
    }

    @Test
    @DisplayName("Should register outbox factory")
    void testRegisterWith() {
        OutboxFactoryRegistrar.registerWith(testRegistrar);
        
        assertEquals(1, testRegistrar.registerCount.get());
        assertEquals("outbox", testRegistrar.lastRegisteredName);
        assertNotNull(testRegistrar.lastCreator);
    }

    @Test
    @DisplayName("Should unregister outbox factory")
    void testUnregisterFrom() {
        OutboxFactoryRegistrar.unregisterFrom(testRegistrar);
        
        assertEquals(1, testRegistrar.unregisterCount.get());
        assertEquals("outbox", testRegistrar.lastUnregisteredName);
    }

    @Test
    @DisplayName("Should create factory with null DatabaseService")
    void testCreateFactoryWithNullDatabaseService() throws Exception {
        OutboxFactoryRegistrar.registerWith(testRegistrar);
        
        Map<String, Object> config = new HashMap<>();
        QueueFactory factory = testRegistrar.lastCreator.create(null, config);
        
        assertNotNull(factory);
        assertInstanceOf(OutboxFactory.class, factory);
    }

    @Test
    @DisplayName("Should create factory with DatabaseService only")
    void testCreateFactoryWithDatabaseServiceOnly() throws Exception {
        OutboxFactoryRegistrar.registerWith(testRegistrar);
        
        TestDatabaseService dbService = new TestDatabaseService();
        Map<String, Object> config = new HashMap<>();
        QueueFactory factory = testRegistrar.lastCreator.create(dbService, config);
        
        assertNotNull(factory);
        assertInstanceOf(OutboxFactory.class, factory);
    }

    @Test
    @DisplayName("Should create factory with PeeGeeQConfiguration")
    void testCreateFactoryWithPeeGeeQConfiguration() throws Exception {
        OutboxFactoryRegistrar.registerWith(testRegistrar);
        
        TestDatabaseService dbService = new TestDatabaseService();
        PeeGeeQConfiguration peeGeeQConfig = new PeeGeeQConfiguration("test");
        Map<String, Object> config = new HashMap<>();
        config.put("peeGeeQConfiguration", peeGeeQConfig);
        
        QueueFactory factory = testRegistrar.lastCreator.create(dbService, config);
        
        assertNotNull(factory);
        assertInstanceOf(OutboxFactory.class, factory);
    }

    @Test
    @DisplayName("Should ignore non-PeeGeeQConfiguration in config map")
    void testCreateFactoryWithInvalidConfigurationType() throws Exception {
        OutboxFactoryRegistrar.registerWith(testRegistrar);
        
        TestDatabaseService dbService = new TestDatabaseService();
        Map<String, Object> config = new HashMap<>();
        config.put("peeGeeQConfiguration", "not a config object");
        
        QueueFactory factory = testRegistrar.lastCreator.create(dbService, config);
        
        assertNotNull(factory, "Should create factory even with invalid config type");
        assertInstanceOf(OutboxFactory.class, factory);
    }

    @Test
    @DisplayName("Should handle empty configuration map")
    void testCreateFactoryWithEmptyConfig() throws Exception {
        OutboxFactoryRegistrar.registerWith(testRegistrar);
        
        TestDatabaseService dbService = new TestDatabaseService();
        Map<String, Object> config = new HashMap<>();
        QueueFactory factory = testRegistrar.lastCreator.create(dbService, config);
        
        assertNotNull(factory);
        assertInstanceOf(OutboxFactory.class, factory);
    }

    @Test
    @DisplayName("Should register and unregister in sequence")
    void testRegisterThenUnregister() {
        OutboxFactoryRegistrar.registerWith(testRegistrar);
        OutboxFactoryRegistrar.unregisterFrom(testRegistrar);
        
        assertEquals(1, testRegistrar.registerCount.get());
        assertEquals(1, testRegistrar.unregisterCount.get());
    }

    @Test
    @DisplayName("Should allow multiple registrations")
    void testMultipleRegistrations() {
        OutboxFactoryRegistrar.registerWith(testRegistrar);
        OutboxFactoryRegistrar.registerWith(testRegistrar);
        
        assertEquals(2, testRegistrar.registerCount.get());
    }

    @Test
    @DisplayName("Should allow multiple unregistrations")
    void testMultipleUnregistrations() {
        OutboxFactoryRegistrar.unregisterFrom(testRegistrar);
        OutboxFactoryRegistrar.unregisterFrom(testRegistrar);
        
        assertEquals(2, testRegistrar.unregisterCount.get());
    }

    // Test implementation of QueueFactoryRegistrar for testing
    private static class TestQueueFactoryRegistrar implements QueueFactoryRegistrar {
        AtomicInteger registerCount = new AtomicInteger(0);
        AtomicInteger unregisterCount = new AtomicInteger(0);
        String lastRegisteredName;
        String lastUnregisteredName;
        QueueFactoryCreator lastCreator;

        @Override
        public void registerFactory(String name, QueueFactoryCreator creator) {
            registerCount.incrementAndGet();
            lastRegisteredName = name;
            lastCreator = creator;
        }

        @Override
        public void unregisterFactory(String name) {
            unregisterCount.incrementAndGet();
            lastUnregisteredName = name;
        }
    }

    // Minimal test implementation of DatabaseService
    private static class TestDatabaseService implements DatabaseService {
        @Override
        public java.util.concurrent.CompletableFuture<Void> initialize() { return java.util.concurrent.CompletableFuture.completedFuture(null); }
        
        @Override
        public java.util.concurrent.CompletableFuture<Void> start() { return java.util.concurrent.CompletableFuture.completedFuture(null); }
        
        @Override
        public java.util.concurrent.CompletableFuture<Void> stop() { return java.util.concurrent.CompletableFuture.completedFuture(null); }
        
        @Override
        public boolean isRunning() { return true; }
        
        @Override
        public boolean isHealthy() { return true; }
        
        @Override
        public dev.mars.peegeeq.api.database.ConnectionProvider getConnectionProvider() { return null; }
        
        @Override
        public dev.mars.peegeeq.api.database.MetricsProvider getMetricsProvider() { return null; }
        
        @Override
        public dev.mars.peegeeq.api.subscription.SubscriptionService getSubscriptionService() { return null; }
        
        @Override
        public java.util.concurrent.CompletableFuture<Void> runMigrations() { return java.util.concurrent.CompletableFuture.completedFuture(null); }
        
        @Override
        public java.util.concurrent.CompletableFuture<Boolean> performHealthCheck() { return java.util.concurrent.CompletableFuture.completedFuture(true); }
        
        @Override
        public io.vertx.core.Vertx getVertx() { return null; }
        
        @Override
        public io.vertx.sqlclient.Pool getPool() { return null; }
        
        @Override
        public io.vertx.pgclient.PgConnectOptions getConnectOptions() { return null; }
        
        @Override
        public void close() { }
    }
}
