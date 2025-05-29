package dev.mars.peegeeq.db.config;

/**
 * Configuration for PostgreSQL connection pools.
 */
public class PgPoolConfig {
    private final int minimumIdle;
    private final int maximumPoolSize;
    private final long connectionTimeout;
    private final long idleTimeout;
    private final long maxLifetime;
    private final boolean autoCommit;
    
    private PgPoolConfig(Builder builder) {
        this.minimumIdle = builder.minimumIdle;
        this.maximumPoolSize = builder.maximumPoolSize;
        this.connectionTimeout = builder.connectionTimeout;
        this.idleTimeout = builder.idleTimeout;
        this.maxLifetime = builder.maxLifetime;
        this.autoCommit = builder.autoCommit;
    }
    
    public int getMinimumIdle() {
        return minimumIdle;
    }
    
    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }
    
    public long getConnectionTimeout() {
        return connectionTimeout;
    }
    
    public long getIdleTimeout() {
        return idleTimeout;
    }
    
    public long getMaxLifetime() {
        return maxLifetime;
    }
    
    public boolean isAutoCommit() {
        return autoCommit;
    }
    
    /**
     * Builder for PgPoolConfig.
     */
    public static class Builder {
        private int minimumIdle = 5;
        private int maximumPoolSize = 10;
        private long connectionTimeout = 30000; // 30 seconds
        private long idleTimeout = 600000; // 10 minutes
        private long maxLifetime = 1800000; // 30 minutes
        private boolean autoCommit = true;
        
        public Builder minimumIdle(int minimumIdle) {
            this.minimumIdle = minimumIdle;
            return this;
        }
        
        public Builder maximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
            return this;
        }
        
        public Builder connectionTimeout(long connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
            return this;
        }
        
        public Builder idleTimeout(long idleTimeout) {
            this.idleTimeout = idleTimeout;
            return this;
        }
        
        public Builder maxLifetime(long maxLifetime) {
            this.maxLifetime = maxLifetime;
            return this;
        }
        
        public Builder autoCommit(boolean autoCommit) {
            this.autoCommit = autoCommit;
            return this;
        }
        
        public PgPoolConfig build() {
            return new PgPoolConfig(this);
        }
    }
}