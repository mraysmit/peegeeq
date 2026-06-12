package dev.mars.peegeeq.bitemporal;

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Core tests for BiTemporalEventStoreFactory validation behavior.
 */
@Tag(TestCategories.CORE)
class BiTemporalFactoryTest {

    private static final Logger logger = LoggerFactory.getLogger(BiTemporalFactoryTest.class);

    @Test
    void testFactoryRejectsQualifiedTableName() {
        PeeGeeQManager manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", new Properties()));
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(Vertx.vertx(), manager);

        logger.error("THIS IS AN INTENTIONAL TEST ERROR: Negative-path case = factory rejects schema-qualified table name");
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> factory.createEventStore(String.class, "tenant_a.bitemporal_event_log"));
        logger.error("THIS IS AN INTENTIONAL TEST ERROR: Captured expected validation failure = {}", exception.getMessage());

        assertTrue(exception.getMessage().contains("unqualified"),
                "Expected unqualified table-name validation message");
    }

    @Test
    void testFactoryRejectsInvalidTableIdentifier() {
        PeeGeeQManager manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", new Properties()));
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(Vertx.vertx(), manager);

        logger.error("THIS IS AN INTENTIONAL TEST ERROR: Negative-path case = factory rejects invalid table identifier");
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> factory.createEventStore(String.class, "bad-table-name"));
        logger.error("THIS IS AN INTENTIONAL TEST ERROR: Captured expected validation failure = {}", exception.getMessage());
    }
}
