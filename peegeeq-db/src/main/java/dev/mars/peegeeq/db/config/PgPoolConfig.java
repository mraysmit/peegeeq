package dev.mars.peegeeq.db.config;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


/**
 * Configuration for PostgreSQL connection pools.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class PgPoolConfig {
    private final int minimumIdle;
    private final int maximumPoolSize;
    private final long connectionTimeout;
    private final long idleTimeout;
    private final long maxLifetime;
    private final boolean autoCommit;
    private final boolean shared;
    
    private PgPoolConfig(Builder builder) {
        this.minimumIdle = builder.minimumIdle;
        this.maximumPoolSize = builder.maximumPoolSize;
        this.connectionTimeout = builder.connectionTimeout;
        this.idleTimeout = builder.idleTimeout;
        this.maxLifetime = builder.maxLifetime;
        this.autoCommit = builder.autoCommit;
        this.shared = builder.shared;
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

    public boolean isShared() {
        return shared;
    }
    
    /**
     * Builder for PgPoolConfig.
     */
    public static class Builder {
        private int minimumIdle = 8;
        private int maximumPoolSize = 32; // Following Vert.x performance checklist: 16/32
        private long connectionTimeout = 30000; // 30 seconds
        private long idleTimeout = 600000; // 10 minutes
        private long maxLifetime = 1800000; // 30 minutes
        private boolean autoCommit = true;
        private boolean shared = true; // Share one pool across all verticles
        
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

        public Builder shared(boolean shared) {
            this.shared = shared;
            return this;
        }

        public PgPoolConfig build() {
            return new PgPoolConfig(this);
        }
    }
}