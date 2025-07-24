package dev.mars.peegeeq.servicemanager.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a registered PeeGeeQ instance in the service discovery system.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-24
 * @version 1.0
 */
public class PeeGeeQInstance {
    
    private String instanceId;
    private String host;
    private int port;
    private String version;
    private String environment;
    private String region;
    private ServiceHealth status;
    private Instant registeredAt;
    private Instant lastHealthCheck;
    private Map<String, String> metadata;
    
    public PeeGeeQInstance() {
        this.metadata = new HashMap<>();
        this.status = ServiceHealth.UNKNOWN;
    }
    
    @JsonCreator
    public PeeGeeQInstance(@JsonProperty("instanceId") String instanceId,
                          @JsonProperty("host") String host,
                          @JsonProperty("port") int port,
                          @JsonProperty("version") String version,
                          @JsonProperty("environment") String environment,
                          @JsonProperty("region") String region) {
        this();
        this.instanceId = instanceId;
        this.host = host;
        this.port = port;
        this.version = version != null ? version : "unknown";
        this.environment = environment != null ? environment : "default";
        this.region = region != null ? region : "default";
    }
    
    // Getters and Setters
    
    public String getInstanceId() {
        return instanceId;
    }
    
    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }
    
    public String getHost() {
        return host;
    }
    
    public void setHost(String host) {
        this.host = host;
    }
    
    public int getPort() {
        return port;
    }
    
    public void setPort(int port) {
        this.port = port;
    }
    
    public String getVersion() {
        return version;
    }
    
    public void setVersion(String version) {
        this.version = version;
    }
    
    public String getEnvironment() {
        return environment;
    }
    
    public void setEnvironment(String environment) {
        this.environment = environment;
    }
    
    public String getRegion() {
        return region;
    }
    
    public void setRegion(String region) {
        this.region = region;
    }
    
    public ServiceHealth getStatus() {
        return status;
    }
    
    public void setStatus(ServiceHealth status) {
        this.status = status;
    }
    
    public Instant getRegisteredAt() {
        return registeredAt;
    }
    
    public void setRegisteredAt(Instant registeredAt) {
        this.registeredAt = registeredAt;
    }
    
    public Instant getLastHealthCheck() {
        return lastHealthCheck;
    }
    
    public void setLastHealthCheck(Instant lastHealthCheck) {
        this.lastHealthCheck = lastHealthCheck;
    }
    
    public Map<String, String> getMetadata() {
        return metadata;
    }
    
    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata != null ? metadata : new HashMap<>();
    }
    
    public void addMetadata(String key, String value) {
        if (this.metadata == null) {
            this.metadata = new HashMap<>();
        }
        this.metadata.put(key, value);
    }
    
    public String getMetadata(String key) {
        return metadata != null ? metadata.get(key) : null;
    }
    
    /**
     * Gets the base URL for this instance's REST API.
     */
    @JsonIgnore
    public String getBaseUrl() {
        return "http://" + host + ":" + port;
    }

    /**
     * Gets the management API URL for this instance.
     */
    @JsonIgnore
    public String getManagementUrl() {
        return getBaseUrl() + "/api/v1/management";
    }

    /**
     * Gets the health check URL for this instance.
     */
    @JsonIgnore
    public String getHealthUrl() {
        return getBaseUrl() + "/health";
    }
    
    /**
     * Checks if this instance is healthy and available.
     */
    @JsonIgnore
    public boolean isHealthy() {
        return status == ServiceHealth.HEALTHY;
    }
    
    /**
     * Checks if this instance is in the same environment.
     */
    @JsonIgnore
    public boolean isInEnvironment(String env) {
        return environment != null && environment.equals(env);
    }

    /**
     * Checks if this instance is in the same region.
     */
    @JsonIgnore
    public boolean isInRegion(String reg) {
        return region != null && region.equals(reg);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PeeGeeQInstance that = (PeeGeeQInstance) o;
        return Objects.equals(instanceId, that.instanceId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(instanceId);
    }
    
    @Override
    public String toString() {
        return "PeeGeeQInstance{" +
                "instanceId='" + instanceId + '\'' +
                ", host='" + host + '\'' +
                ", port=" + port +
                ", version='" + version + '\'' +
                ", environment='" + environment + '\'' +
                ", region='" + region + '\'' +
                ", status=" + status +
                ", registeredAt=" + registeredAt +
                '}';
    }
    
    /**
     * Creates a builder for constructing PeeGeeQInstance objects.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private String instanceId;
        private String host;
        private int port;
        private String version = "unknown";
        private String environment = "default";
        private String region = "default";
        private Map<String, String> metadata = new HashMap<>();
        
        public Builder instanceId(String instanceId) {
            this.instanceId = instanceId;
            return this;
        }
        
        public Builder host(String host) {
            this.host = host;
            return this;
        }
        
        public Builder port(int port) {
            this.port = port;
            return this;
        }
        
        public Builder version(String version) {
            this.version = version;
            return this;
        }
        
        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }
        
        public Builder region(String region) {
            this.region = region;
            return this;
        }
        
        public Builder metadata(String key, String value) {
            this.metadata.put(key, value);
            return this;
        }
        
        public Builder metadata(Map<String, String> metadata) {
            this.metadata.putAll(metadata);
            return this;
        }
        
        public PeeGeeQInstance build() {
            PeeGeeQInstance instance = new PeeGeeQInstance(instanceId, host, port, version, environment, region);
            instance.setMetadata(metadata);
            return instance;
        }
    }
}
