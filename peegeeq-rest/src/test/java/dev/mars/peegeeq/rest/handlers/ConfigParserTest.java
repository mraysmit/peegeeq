package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
public class ConfigParserTest {

    @Test
    public void testParseStandardQueueConfig() {
        JsonObject json = new JsonObject()
                .put("queueName", "standard-queue")
                .put("visibilityTimeoutSeconds", 30)
                .put("maxRetries", 5);

        QueueConfig config = ConfigParser.parseQueueConfig(json);

        assertEquals("standard-queue", config.getQueueName());
        assertEquals(Duration.ofSeconds(30), config.getVisibilityTimeout());
        assertEquals(5, config.getMaxRetries());
    }

    @Test
    public void testParseManagementUIQueueConfig() {
        JsonObject json = new JsonObject()
                .put("name", "ui-queue")
                .put("visibilityTimeout", "PT1M") // ISO-8601
                .put("maxRetries", 2);

        QueueConfig config = ConfigParser.parseQueueConfig(json);

        assertEquals("ui-queue", config.getQueueName());
        assertEquals(Duration.ofMinutes(1), config.getVisibilityTimeout());
        assertEquals(2, config.getMaxRetries());
    }

    @Test
    public void testParseQueueConfigWithNumericVisibilityTimeout() {
        JsonObject json = new JsonObject()
                .put("name", "numeric-timeout-queue")
                .put("visibilityTimeout", 120);

        QueueConfig config = ConfigParser.parseQueueConfig(json);

        assertEquals(Duration.ofSeconds(120), config.getVisibilityTimeout());
    }

    @Test
    public void testParseStandardEventStoreConfig() {
        JsonObject json = new JsonObject()
                .put("eventStoreName", "standard-store")
                .put("tableName", "custom_table")
                .put("notificationPrefix", "custom_notify_");

        EventStoreConfig config = ConfigParser.parseEventStoreConfig(json);

        assertEquals("standard-store", config.getEventStoreName());
        assertEquals("custom_table", config.getTableName());
        assertEquals("custom_notify_", config.getNotificationPrefix());
    }

    @Test
    public void testParseManagementUIEventStoreConfigWithDefaulting() {
        JsonObject json = new JsonObject()
                .put("name", "ui-store-hyphenated");

        EventStoreConfig config = ConfigParser.parseEventStoreConfig(json);

        assertEquals("ui-store-hyphenated", config.getEventStoreName());
        // Management UI logic: replace hyphen with underscore
        assertEquals("ui_store_hyphenated_events", config.getTableName());
        assertEquals("ui_store_hyphenated_", config.getNotificationPrefix());
    }

    @Test
    public void testGetSetupIdFromPath() {
        String setupId = ConfigParser.getSetupId("path-setup", new JsonObject());
        assertEquals("path-setup", setupId);
    }

    @Test
    public void testGetSetupIdFromUIJson() {
        JsonObject json = new JsonObject().put("setup", "ui-setup");

        String setupId = ConfigParser.getSetupId(null, json);
        assertEquals("ui-setup", setupId);
    }

    @Test
    public void testGetSetupIdFromStandardJson() {
        JsonObject json = new JsonObject().put("setupId", "standard-setup");

        String setupId = ConfigParser.getSetupId(null, json);
        assertEquals("standard-setup", setupId);
    }

    @Test
    public void testGetSetupIdMissing() {
        assertThrows(IllegalArgumentException.class, () -> {
            ConfigParser.getSetupId(null, new JsonObject());
        });
    }
}
