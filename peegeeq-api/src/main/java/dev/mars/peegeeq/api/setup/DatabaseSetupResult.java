package dev.mars.peegeeq.api.setup;

import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.messaging.QueueFactory;

import java.util.Map;

public class DatabaseSetupResult {
    private final String setupId;
    private final Map<String, QueueFactory> queueFactories;
    private final Map<String, EventStore<?>> eventStores;
    private final DatabaseSetupStatus status;
    private final String connectionUrl;
    private final long createdAt;
    
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
}
