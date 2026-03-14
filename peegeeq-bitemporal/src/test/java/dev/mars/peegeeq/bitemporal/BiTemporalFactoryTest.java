package dev.mars.peegeeq.bitemporal;

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Core tests for BiTemporal module classes.
 */
@Tag(TestCategories.CORE)
class BiTemporalFactoryTest {

    @Test
    void testFactoryClassExists() {
        // Test that factory class exists and can be referenced
        assertNotNull(BiTemporalEventStoreFactory.class, "BiTemporalEventStoreFactory class should exist");
    }

    @Test
    void testEventStoreClassExists() {
        // Test that event store class exists and can be referenced
        assertNotNull(PgBiTemporalEventStore.class, "PgBiTemporalEventStore class should exist");
    }

    @Test
    void testVertxPoolAdapterClassExists() {
        // Test that vertx pool adapter class exists and can be referenced
        assertNotNull(VertxPoolAdapter.class, "VertxPoolAdapter class should exist");
    }

    @Test
    void testReactiveNotificationHandlerClassExists() {
        // Test that reactive notification handler class exists and can be referenced
        assertNotNull(ReactiveNotificationHandler.class, "ReactiveNotificationHandler class should exist");
    }

    @Test
    void testClassNamesAreCorrect() {
        // Test that class names are as expected
        assertEquals("BiTemporalEventStoreFactory", BiTemporalEventStoreFactory.class.getSimpleName());
        assertEquals("PgBiTemporalEventStore", PgBiTemporalEventStore.class.getSimpleName());
        assertEquals("VertxPoolAdapter", VertxPoolAdapter.class.getSimpleName());
        assertEquals("ReactiveNotificationHandler", ReactiveNotificationHandler.class.getSimpleName());
    }

    @Test
    void testPackageStructure() {
        // Test that classes are in the correct package
        String expectedPackage = "dev.mars.peegeeq.bitemporal";
        assertEquals(expectedPackage, BiTemporalEventStoreFactory.class.getPackage().getName());
        assertEquals(expectedPackage, PgBiTemporalEventStore.class.getPackage().getName());
        assertEquals(expectedPackage, VertxPoolAdapter.class.getPackage().getName());
        assertEquals(expectedPackage, ReactiveNotificationHandler.class.getPackage().getName());
    }

    @Test
    void testFactoryRejectsQualifiedTableName() {
        PeeGeeQManager manager = new PeeGeeQManager(new PeeGeeQConfiguration());
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(manager);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> factory.createEventStore(String.class, "tenant_a.bitemporal_event_log"));

        assertTrue(exception.getMessage().contains("unqualified"),
                "Expected unqualified table-name validation message");
    }

    @Test
    void testFactoryRejectsInvalidTableIdentifier() {
        PeeGeeQManager manager = new PeeGeeQManager(new PeeGeeQConfiguration());
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(manager);

        assertThrows(IllegalArgumentException.class,
                () -> factory.createEventStore(String.class, "bad-table-name"));
    }
}
