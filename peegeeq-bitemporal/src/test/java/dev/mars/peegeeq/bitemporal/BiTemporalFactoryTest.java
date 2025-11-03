package dev.mars.peegeeq.bitemporal;

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
    void testReactiveUtilsClassExists() {
        // Test that reactive utils class exists and can be referenced
        assertNotNull(ReactiveUtils.class, "ReactiveUtils class should exist");
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
        assertEquals("ReactiveUtils", ReactiveUtils.class.getSimpleName());
        assertEquals("VertxPoolAdapter", VertxPoolAdapter.class.getSimpleName());
        assertEquals("ReactiveNotificationHandler", ReactiveNotificationHandler.class.getSimpleName());
    }

    @Test
    void testPackageStructure() {
        // Test that classes are in the correct package
        String expectedPackage = "dev.mars.peegeeq.bitemporal";
        assertEquals(expectedPackage, BiTemporalEventStoreFactory.class.getPackage().getName());
        assertEquals(expectedPackage, PgBiTemporalEventStore.class.getPackage().getName());
        assertEquals(expectedPackage, ReactiveUtils.class.getPackage().getName());
        assertEquals(expectedPackage, VertxPoolAdapter.class.getPackage().getName());
        assertEquals(expectedPackage, ReactiveNotificationHandler.class.getPackage().getName());
    }
}
