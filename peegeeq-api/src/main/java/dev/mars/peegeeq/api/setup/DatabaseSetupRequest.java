package dev.mars.peegeeq.api.setup;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class DatabaseSetupRequest {
    private final String setupId;
    private final DatabaseConfig databaseConfig;
    private final List<QueueConfig> queues;
    private final List<EventStoreConfig> eventStores;
    private final Map<String, Object> additionalProperties;

    @JsonCreator
    public DatabaseSetupRequest(@JsonProperty("setupId") String setupId,
                               @JsonProperty("databaseConfig") DatabaseConfig databaseConfig,
                               @JsonProperty("queues") List<QueueConfig> queues,
                               @JsonProperty("eventStores") List<EventStoreConfig> eventStores,
                               @JsonProperty("additionalProperties") Map<String, Object> additionalProperties) {
        this.setupId = Objects.requireNonNull(setupId, "setupId cannot be null");
        this.databaseConfig = Objects.requireNonNull(databaseConfig, "databaseConfig cannot be null");
        this.queues = (queues == null) ? List.of() : List.copyOf(queues);
        this.eventStores = (eventStores == null) ? List.of() : List.copyOf(eventStores);
        this.additionalProperties = (additionalProperties == null) ? Map.of() : Map.copyOf(additionalProperties);
    }

    public String getSetupId() { return setupId; }
    public DatabaseConfig getDatabaseConfig() { return databaseConfig; }
    public List<QueueConfig> getQueues() { return queues; }
    public List<EventStoreConfig> getEventStores() { return eventStores; }
    public Map<String, Object> getAdditionalProperties() { return additionalProperties; }
}
