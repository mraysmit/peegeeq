package dev.mars.peegeeq.api.database;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;

public class ConnectionPoolConfig {
    private final int minSize;
    private final int maxSize;
    private final Duration maxLifetime;
    private final Duration connectionTimeout;
    private final Duration idleTimeout;

    public ConnectionPoolConfig() {
        this(2, 10, Duration.ofMinutes(30), Duration.ofSeconds(30), Duration.ofMinutes(10));
    }

    @JsonCreator
    public ConnectionPoolConfig(@JsonProperty("minSize") int minSize,
                               @JsonProperty("maxSize") int maxSize,
                               @JsonProperty("maxLifetime") Duration maxLifetime,
                               @JsonProperty("connectionTimeout") Duration connectionTimeout,
                               @JsonProperty("idleTimeout") Duration idleTimeout) {
        this.minSize = minSize;
        this.maxSize = maxSize;
        this.maxLifetime = maxLifetime;
        this.connectionTimeout = connectionTimeout;
        this.idleTimeout = idleTimeout;
    }
    
    public int getMinSize() { return minSize; }
    public int getMaxSize() { return maxSize; }
    public Duration getMaxLifetime() { return maxLifetime; }
    public Duration getConnectionTimeout() { return connectionTimeout; }
    public Duration getIdleTimeout() { return idleTimeout; }
}
