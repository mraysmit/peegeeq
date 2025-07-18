package dev.mars.peegeeq.api.setup;

import java.util.List;
import java.util.Map;

public class DatabaseSetupRequest {
    private final String setupId;
    private final DatabaseConfig databaseConfig;
    private final List<QueueConfig> queues;
    private final List<EventStoreConfig> eventStores;
    private final Map<String, Object> additionalProperties;
    
    public DatabaseSetupRequest(String setupId, DatabaseConfig databaseConfig,
                               List<QueueConfig> queues, List<EventStoreConfig> eventStores,
                               Map<String, Object> additionalProperties) {
        this.setupId = setupId;
        this.databaseConfig = databaseConfig;
        this.queues = queues;
        this.eventStores = eventStores;
        this.additionalProperties = additionalProperties;
    }

    public String getSetupId() { return setupId; }
    public DatabaseConfig getDatabaseConfig() { return databaseConfig; }
    public List<QueueConfig> getQueues() { return queues; }
    public List<EventStoreConfig> getEventStores() { return eventStores; }
    public Map<String, Object> getAdditionalProperties() { return additionalProperties; }
}
