package dev.mars.peegeeq.bitemporal;

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Core tests for BiTemporalEventStoreFactory validation behavior.
 */
@Tag(TestCategories.CORE)
class BiTemporalFactoryTest {

    @Test
    void testFactoryRejectsQualifiedTableName() {
        PeeGeeQManager manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", new Properties()));
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(Vertx.vertx(), manager);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> factory.createEventStore(String.class, "tenant_a.bitemporal_event_log"));

        assertTrue(exception.getMessage().contains("unqualified"),
                "Expected unqualified table-name validation message");
    }

    @Test
    void testFactoryRejectsInvalidTableIdentifier() {
        PeeGeeQManager manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", new Properties()));
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(Vertx.vertx(), manager);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> factory.createEventStore(String.class, "bad-table-name"));
    }
}
