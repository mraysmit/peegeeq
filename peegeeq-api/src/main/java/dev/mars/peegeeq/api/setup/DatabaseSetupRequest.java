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
    private final boolean persistBinding;
    private final String credentialRef;

    /**
     * @param persistBinding opt-in "remember this setup": when true, the service persists the
     *                       binding coordinates (plus optional credential reference — never the
     *                       password) to the durable registry so the setup is re-established on
     *                       the next backend start. Defaults to false; absent in JSON means false,
     *                       so existing clients keep today's behaviour.
     * @param credentialRef  opaque, nullable pointer into the adopter's secret store, stored with
     *                       the persisted binding and passed verbatim to the CredentialProvider at
     *                       reload time. Never interpreted by PeeGeeQ; null means supplied-at-connect
     *                       (the persisted binding cannot be auto-reloaded without a provider).
     */
    @JsonCreator
    public DatabaseSetupRequest(@JsonProperty("setupId") String setupId,
                               @JsonProperty("databaseConfig") DatabaseConfig databaseConfig,
                               @JsonProperty("queues") List<QueueConfig> queues,
                               @JsonProperty("eventStores") List<EventStoreConfig> eventStores,
                               @JsonProperty("additionalProperties") Map<String, Object> additionalProperties,
                               @JsonProperty("persistBinding") boolean persistBinding,
                               @JsonProperty("credentialRef") String credentialRef) {
        this.setupId = Objects.requireNonNull(setupId, "setupId cannot be null");
        this.databaseConfig = Objects.requireNonNull(databaseConfig, "databaseConfig cannot be null");
        this.queues = (queues == null) ? List.of() : List.copyOf(queues);
        this.eventStores = (eventStores == null) ? List.of() : List.copyOf(eventStores);
        this.additionalProperties = (additionalProperties == null) ? Map.of() : Map.copyOf(additionalProperties);
        this.persistBinding = persistBinding;
        this.credentialRef = credentialRef;
    }

    public DatabaseSetupRequest(String setupId,
                               DatabaseConfig databaseConfig,
                               List<QueueConfig> queues,
                               List<EventStoreConfig> eventStores,
                               Map<String, Object> additionalProperties,
                               boolean persistBinding) {
        this(setupId, databaseConfig, queues, eventStores, additionalProperties, persistBinding, null);
    }

    public DatabaseSetupRequest(String setupId,
                               DatabaseConfig databaseConfig,
                               List<QueueConfig> queues,
                               List<EventStoreConfig> eventStores,
                               Map<String, Object> additionalProperties) {
        this(setupId, databaseConfig, queues, eventStores, additionalProperties, false, null);
    }

    public String getSetupId() { return setupId; }
    public DatabaseConfig getDatabaseConfig() { return databaseConfig; }
    public List<QueueConfig> getQueues() { return queues; }
    public List<EventStoreConfig> getEventStores() { return eventStores; }
    public Map<String, Object> getAdditionalProperties() { return additionalProperties; }
    public boolean isPersistBinding() { return persistBinding; }
    public String getCredentialRef() { return credentialRef; }
}
