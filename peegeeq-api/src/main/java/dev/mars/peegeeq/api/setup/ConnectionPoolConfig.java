package dev.mars.peegeeq.api.setup;

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
    
    public ConnectionPoolConfig(int minSize, int maxSize, Duration maxLifetime, 
                               Duration connectionTimeout, Duration idleTimeout) {
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
