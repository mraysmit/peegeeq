package dev.mars.peegeeq.api.setup;

import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.messaging.QueueFactory;

import java.util.HashMap;
import java.util.Map;

public class DatabaseSetupResult {
    private final String setupId;
    private final Map<String, QueueFactory> queueFactories;
    private final Map<String, EventStore<?>> eventStores;
    private final DatabaseSetupStatus status;
    private final String connectionUrl;
    private final long createdAt;
    private final Map<String, QueueConfig> queueConfigs = new HashMap<>();

    public DatabaseSetupResult(String setupId, Map<String, QueueFactory> queueFactories,
                              Map<String, EventStore<?>> eventStores, DatabaseSetupStatus status) {
        this.setupId = setupId;
        this.queueFactories = queueFactories;
        this.eventStores = eventStores;
        this.status = status;
        this.connectionUrl = null;
        this.createdAt = System.currentTimeMillis();
    }

    public String getSetupId() { return setupId; }
    public Map<String, QueueFactory> getQueueFactories() { return queueFactories; }
    public Map<String, EventStore<?>> getEventStores() { return eventStores; }
    public DatabaseSetupStatus getStatus() { return status; }
    public String getConnectionUrl() { return connectionUrl; }
    public long getCreatedAt() { return createdAt; }
    public Map<String, QueueConfig> getQueueConfigs() { return queueConfigs; }

    public void putQueueConfig(String queueName, QueueConfig config) {
        queueConfigs.put(queueName, config);
    }

    public QueueConfig getQueueConfig(String queueName) {
        return queueConfigs.get(queueName);
    }
}
