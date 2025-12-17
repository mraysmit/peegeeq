package dev.mars.peegeeq.db.provider;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE tests for PgQueueFactoryProvider.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
@Execution(ExecutionMode.SAME_THREAD)
public class PgQueueFactoryProviderCoreTest extends BaseIntegrationTest {

    private PgQueueFactoryProvider provider;
    private DatabaseService databaseService;

    @BeforeEach
    void setUp() throws Exception {
        provider = new PgQueueFactoryProvider();
        databaseService = new PgDatabaseService(manager);
    }

    @Test
    void testPgQueueFactoryProviderCreation() {
        assertNotNull(provider);
    }

    @Test
    void testPgQueueFactoryProviderCreationWithConfiguration() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        PgQueueFactoryProvider providerWithConfig = new PgQueueFactoryProvider(config);
        assertNotNull(providerWithConfig);
    }

    @Test
    void testRegisterFactory() {
        QueueFactoryRegistrar.QueueFactoryCreator creator = (dbService, config) -> new TestQueueFactory(dbService);
        provider.registerFactory("test-type", creator);
        assertTrue(provider.isTypeSupported("test-type"));
    }

    @Test
    void testRegisterFactoryWithNullType() {
        QueueFactoryRegistrar.QueueFactoryCreator creator = (dbService, config) -> new TestQueueFactory(dbService);
        assertThrows(IllegalArgumentException.class, () -> provider.registerFactory(null, creator));
    }

    @Test
    void testRegisterFactoryWithEmptyType() {
        QueueFactoryRegistrar.QueueFactoryCreator creator = (dbService, config) -> new TestQueueFactory(dbService);
        assertThrows(IllegalArgumentException.class, () -> provider.registerFactory("", creator));
    }

    @Test
    void testRegisterFactoryWithNullCreator() {
        assertThrows(IllegalArgumentException.class, () -> provider.registerFactory("test-type", null));
    }

    @Test
    void testUnregisterFactory() {
        QueueFactoryRegistrar.QueueFactoryCreator creator = (dbService, config) -> new TestQueueFactory(dbService);
        provider.registerFactory("test-type", creator);
        assertTrue(provider.isTypeSupported("test-type"));

        provider.unregisterFactory("test-type");
        assertFalse(provider.isTypeSupported("test-type"));
    }

    @Test
    void testUnregisterFactoryWithNull() {
        assertDoesNotThrow(() -> provider.unregisterFactory(null));
    }

    @Test
    void testIsTypeSupported() {
        assertFalse(provider.isTypeSupported("non-existent"));
    }

    @Test
    void testIsTypeSupportedWithNull() {
        assertFalse(provider.isTypeSupported(null));
    }

    @Test
    void testGetSupportedTypes() {
        Set<String> types = provider.getSupportedTypes();
        assertNotNull(types);
    }

    @Test
    void testCreateFactoryWithNullType() {
        assertThrows(IllegalArgumentException.class, () -> provider.createFactory(null, databaseService));
    }

    @Test
    void testCreateFactoryWithEmptyType() {
        assertThrows(IllegalArgumentException.class, () -> provider.createFactory("", databaseService));
    }

    @Test
    void testCreateFactoryWithNullDatabaseService() {
        assertThrows(IllegalArgumentException.class, () -> provider.createFactory("test-type", null));
    }

    @Test
    void testCreateFactoryWithNoRegisteredFactories() {
        assertThrows(IllegalStateException.class, () -> provider.createFactory("test-type", databaseService));
    }

    @Test
    void testCreateFactoryWithUnsupportedType() {
        QueueFactoryRegistrar.QueueFactoryCreator creator = (dbService, config) -> new TestQueueFactory(dbService);
        provider.registerFactory("test-type", creator);

        assertThrows(IllegalArgumentException.class, () -> provider.createFactory("unsupported-type", databaseService));
    }

    @Test
    void testCreateFactorySuccess() {
        QueueFactoryRegistrar.QueueFactoryCreator creator = (dbService, config) -> new TestQueueFactory(dbService);
        provider.registerFactory("test-type", creator);

        QueueFactory factory = provider.createFactory("test-type", databaseService);
        assertNotNull(factory);
    }

    @Test
    void testCreateFactoryWithConfiguration() {
        QueueFactoryRegistrar.QueueFactoryCreator creator = (dbService, config) -> new TestQueueFactory(dbService);
        provider.registerFactory("test-type", creator);

        Map<String, Object> config = new HashMap<>();
        config.put("key", "value");

        QueueFactory factory = provider.createFactory("test-type", databaseService, config);
        assertNotNull(factory);
    }

    @Test
    void testGetBestAvailableType() {
        java.util.Optional<String> type = provider.getBestAvailableType();
        assertNotNull(type);
    }

    @Test
    void testGetDefaultTypeWithNoFactories() {
        assertThrows(IllegalStateException.class, () -> provider.getDefaultType());
    }

    // Simple test implementation of QueueFactory
    private static class TestQueueFactory extends PgQueueFactory {
        public TestQueueFactory(DatabaseService databaseService) {
            super(databaseService);
        }

        @Override
        public <T> dev.mars.peegeeq.api.messaging.MessageProducer<T> createProducer(String topic, Class<T> payloadType) {
            return null;
        }

        @Override
        public <T> dev.mars.peegeeq.api.messaging.MessageConsumer<T> createConsumer(String topic, Class<T> payloadType) {
            return null;
        }

        @Override
        public <T> dev.mars.peegeeq.api.messaging.ConsumerGroup<T> createConsumerGroup(String groupName, String topic, Class<T> payloadType) {
            return null;
        }

        @Override
        public <T> dev.mars.peegeeq.api.messaging.QueueBrowser<T> createBrowser(String topic, Class<T> payloadType) {
            return null;
        }

        @Override
        public String getImplementationType() {
            return "test";
        }

        @Override
        protected void closeResources() throws Exception {
            // No-op
        }
    }
}

