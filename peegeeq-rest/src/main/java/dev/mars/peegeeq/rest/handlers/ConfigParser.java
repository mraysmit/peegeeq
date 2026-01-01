package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import io.vertx.core.json.JsonObject;
import java.time.Duration;

/**
 * Shared utility for parsing request bodies into domain configuration objects.
 * 
 * Supports both standard API naming (queueName, eventStoreName) and
 * Management UI naming (name, setup) to ensure backward compatibility.
 */
public class ConfigParser {

    /**
     * Parses QueueConfig from a JSON object.
     * Supports "name" as an alias for "queueName".
     */
    public static QueueConfig parseQueueConfig(JsonObject json) {
        String queueName = getRequiredString(json, "queueName", "name");

        QueueConfig.Builder builder = new QueueConfig.Builder()
                .queueName(queueName);

        if (json.containsKey("visibilityTimeout")) {
            Object value = json.getValue("visibilityTimeout");
            if (value instanceof Number) {
                builder.visibilityTimeoutSeconds(((Number) value).intValue());
            } else if (value instanceof String) {
                builder.visibilityTimeout(Duration.parse((String) value));
            }
        } else if (json.containsKey("visibilityTimeoutSeconds")) {
            builder.visibilityTimeoutSeconds(json.getInteger("visibilityTimeoutSeconds"));
        }

        if (json.containsKey("maxRetries")) {
            builder.maxRetries(json.getInteger("maxRetries"));
        }

        if (json.containsKey("deadLetterEnabled")) {
            builder.deadLetterEnabled(json.getBoolean("deadLetterEnabled"));
        }

        if (json.containsKey("deadLetterQueueName")) {
            builder.deadLetterQueueName(json.getString("deadLetterQueueName"));
        }

        if (json.containsKey("batchSize")) {
            builder.batchSize(json.getInteger("batchSize"));
        }

        if (json.containsKey("pollingInterval")) {
            Object value = json.getValue("pollingInterval");
            if (value instanceof Number) {
                builder.pollingInterval(Duration.ofSeconds(((Number) value).longValue()));
            } else if (value instanceof String) {
                builder.pollingInterval(Duration.parse((String) value));
            }
        } else if (json.containsKey("pollingIntervalSeconds")) {
            builder.pollingInterval(Duration.ofSeconds(json.getInteger("pollingIntervalSeconds")));
        }

        if (json.containsKey("fifoEnabled")) {
            builder.fifoEnabled(json.getBoolean("fifoEnabled"));
        }

        return builder.build();
    }

    /**
     * Parses EventStoreConfig from a JSON object.
     * Supports "name" as an alias for "eventStoreName".
     * Implements consistent defaulting for tableName and notificationPrefix.
     */
    public static EventStoreConfig parseEventStoreConfig(JsonObject json) {
        String storeName = getRequiredString(json, "eventStoreName", "name");

        // Defaults matching existing ManagementApiHandler logic
        String safeName = storeName.replaceAll("-", "_");
        String tableName = json.getString("tableName", safeName + "_events");
        String notificationPrefix = json.getString("notificationPrefix", safeName + "_");

        EventStoreConfig.Builder builder = new EventStoreConfig.Builder()
                .eventStoreName(storeName)
                .tableName(tableName)
                .notificationPrefix(notificationPrefix);

        if (json.containsKey("queryLimit")) {
            builder.queryLimit(json.getInteger("queryLimit"));
        }

        if (json.containsKey("metricsEnabled")) {
            builder.metricsEnabled(json.getBoolean("metricsEnabled"));
        }

        if (json.containsKey("biTemporalEnabled")) {
            builder.biTemporalEnabled(json.getBoolean("biTemporalEnabled"));
        }

        if (json.containsKey("partitionStrategy")) {
            builder.partitionStrategy(json.getString("partitionStrategy"));
        }

        return builder.build();
    }

    /**
     * Extracts setupId from a path parameter or JSON body.
     */
    public static String getSetupId(String pathSetupId, JsonObject json) {
        String setupId = pathSetupId;
        if (setupId == null || setupId.isEmpty()) {
            setupId = json.getString("setup");
        }
        if (setupId == null || setupId.isEmpty()) {
            setupId = json.getString("setupId");
        }

        if (setupId == null || setupId.isEmpty()) {
            throw new IllegalArgumentException(
                    "setupId is required (as path parameter or in JSON as 'setup' or 'setupId')");
        }
        return setupId;
    }

    /**
     * Helper to get a required string field supporting aliases.
     */
    private static String getRequiredString(JsonObject json, String primaryKey, String aliasKey) {
        String value = json.getString(primaryKey);
        if (value == null || value.isEmpty()) {
            value = json.getString(aliasKey);
        }

        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(primaryKey + " (or '" + aliasKey + "') is required");
        }
        return value;
    }
}
