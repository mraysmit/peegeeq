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

import java.time.Duration;
import java.util.Objects;

/**
 * Reactive PostgreSQL pool configuration for Vert.x 5.x.
 *
 * This configuration class is designed specifically for Vert.x reactive pools,
 * using proper Vert.x semantics instead of JDBC/HikariCP concepts.
 *
 * Key differences from JDBC pools:
 * - Uses Duration for timeouts (not long milliseconds)
 * - Includes maxWaitQueueSize to prevent memory exhaustion
 * - Removes JDBC-only concepts (minimumIdle, maxLifetime, autoCommit)
 * - Shared pools work across verticles with matching configurations
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 2.0
 */
public final class PgPoolConfig {
    private final int maxSize;
    private final int maxWaitQueueSize;
    private final Duration connectionTimeout;
    private final Duration idleTimeout;
    private final boolean shared;

    private PgPoolConfig(Builder builder) {
        this.maxSize = builder.maxSize;
        this.maxWaitQueueSize = builder.maxWaitQueueSize;
        this.connectionTimeout = builder.connectionTimeout;
        this.idleTimeout = builder.idleTimeout;
        this.shared = builder.shared;
    }

    /**
     * Maximum number of connections in the pool.
     * Maps directly to Vert.x PoolOptions.setMaxSize().
     */
    public int getMaxSize() {
        return maxSize;
    }

    /**
     * Maximum number of requests that can wait for a connection.
     * Prevents memory exhaustion under backpressure.
     * Maps directly to Vert.x PoolOptions.setMaxWaitQueueSize().
     */
    public int getMaxWaitQueueSize() {
        return maxWaitQueueSize;
    }

    /**
     * Maximum time to wait for a new connection.
     * Maps directly to Vert.x PoolOptions.setConnectionTimeout().
     */
    public Duration getConnectionTimeout() {
        return connectionTimeout;
    }

    /**
     * Maximum time a connection can remain idle before being closed.
     * Maps directly to Vert.x PoolOptions.setIdleTimeout().
     */
    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    /**
     * Whether the pool should be shared across verticles.
     * When true, verticles with matching pool configurations will share
     * the same underlying connection pool instance.
     */
    public boolean isShared() {
        return shared;
    }





    @Override
    public String toString() {
        return "PgPoolConfig{" +
            "maxSize=" + maxSize +
            ", maxWaitQueueSize=" + maxWaitQueueSize +
            ", connectionTimeout=" + connectionTimeout +
            ", idleTimeout=" + idleTimeout +
            ", shared=" + shared +
            '}';
    }

    /**
     * Builder for PgPoolConfig with Vert.x 5.x reactive semantics.
     * Uses production-ready defaults suitable for most applications.
     */
    public static final class Builder {
        private int maxSize = 16; // Production default, not test default
        private int maxWaitQueueSize = 128; // Prevent memory exhaustion under backpressure
        private Duration connectionTimeout = Duration.ofSeconds(30);
        private Duration idleTimeout = Duration.ofMinutes(10);
        private boolean shared = true; // Share pool across verticles with matching configs

        /**
         * Sets the maximum number of connections in the pool.
         * Maps directly to Vert.x PoolOptions.setMaxSize().
         */
        public Builder maxSize(int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        /**
         * Sets the maximum number of requests that can wait for a connection.
         * Prevents memory exhaustion under backpressure conditions.
         * Maps directly to Vert.x PoolOptions.setMaxWaitQueueSize().
         */
        public Builder maxWaitQueueSize(int maxWaitQueueSize) {
            this.maxWaitQueueSize = maxWaitQueueSize;
            return this;
        }

        /**
         * Sets the maximum time to wait for a new connection.
         * Maps directly to Vert.x PoolOptions.setConnectionTimeout().
         */
        public Builder connectionTimeout(Duration connectionTimeout) {
            this.connectionTimeout = Objects.requireNonNull(connectionTimeout, "connectionTimeout");
            return this;
        }

        /**
         * Sets the maximum time a connection can remain idle.
         * Maps directly to Vert.x PoolOptions.setIdleTimeout().
         */
        public Builder idleTimeout(Duration idleTimeout) {
            this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout");
            return this;
        }

        /**
         * Sets whether the pool should be shared across verticles.
         * When true, verticles with matching configurations share the same pool.
         */
        public Builder shared(boolean shared) {
            this.shared = shared;
            return this;
        }



        public PgPoolConfig build() {
            return new PgPoolConfig(this);
        }
    }
}