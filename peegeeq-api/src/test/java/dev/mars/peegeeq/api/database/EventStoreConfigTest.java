package dev.mars.peegeeq.api.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
class EventStoreConfigTest {

    @Test
    void testConstructorAndGetters() {
        EventStoreConfig config = new EventStoreConfig(
            "test-store",
            "events_table",
            "notify_",
            500,
            false,
            String.class,
            true,
            "daily"
        );

        assertEquals("test-store", config.getEventStoreName());
        assertEquals("events_table", config.getTableName());
        assertEquals("notify_", config.getNotificationPrefix());
        assertEquals(500, config.getQueryLimit());
        assertFalse(config.isMetricsEnabled());
        assertEquals(String.class, config.getEventType());
        assertTrue(config.isBiTemporalEnabled());
        assertEquals("daily", config.getPartitionStrategy());
    }

    @Test
    void testDefaults() {
        EventStoreConfig config = new EventStoreConfig(
            "test-store",
            "events_table",
            null, // notificationPrefix
            0, // queryLimit
            true,
            null, // eventType
            false,
            null // partitionStrategy
        );

        assertEquals("peegeeq_events_", config.getNotificationPrefix());
        assertEquals(1000, config.getQueryLimit());
    }

    @Test
    void testBuilder() {
        EventStoreConfig config = new EventStoreConfig.Builder()
            .eventStoreName("builder-store")
            .tableName("builder_table")
            .notificationPrefix("b_notify_")
            .queryLimit(200)
            .metricsEnabled(false)
            .eventType(Integer.class)
            .biTemporalEnabled(false)
            .partitionStrategy("yearly")
            .build();

        assertEquals("builder-store", config.getEventStoreName());
        assertEquals("builder_table", config.getTableName());
        assertEquals("b_notify_", config.getNotificationPrefix());
        assertEquals(200, config.getQueryLimit());
        assertFalse(config.isMetricsEnabled());
        assertEquals(Integer.class, config.getEventType());
        assertFalse(config.isBiTemporalEnabled());
        assertEquals("yearly", config.getPartitionStrategy());
    }
}
