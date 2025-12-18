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
package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.api.messaging.ServerSideFilter;

import java.time.Duration;

/**
 * Configuration for outbox pattern consumers.
 * Follows the established PeeGeeQ configuration pattern with builder support.
 *
 * This allows per-consumer configuration when creating consumers via
 * {@link OutboxFactory#createConsumer(String, Class, Object)}.
 */
public class OutboxConsumerConfig {
    private final Duration pollingInterval;
    private final int batchSize;
    private final int consumerThreads;
    private final int maxRetries;
    private final ServerSideFilter serverSideFilter;

    // Private constructor for builder pattern
    private OutboxConsumerConfig(Builder builder) {
        this.pollingInterval = builder.pollingInterval;
        this.batchSize = builder.batchSize;
        this.consumerThreads = builder.consumerThreads;
        this.maxRetries = builder.maxRetries;
        this.serverSideFilter = builder.serverSideFilter;
    }

    // Getters
    public Duration getPollingInterval() { return pollingInterval; }
    public int getBatchSize() { return batchSize; }
    public int getConsumerThreads() { return consumerThreads; }
    public int getMaxRetries() { return maxRetries; }
    public ServerSideFilter getServerSideFilter() { return serverSideFilter; }
    public boolean hasServerSideFilter() { return serverSideFilter != null; }

    // Builder pattern following established conventions
    public static Builder builder() {
        return new Builder();
    }

    // Default configuration for backward compatibility
    public static OutboxConsumerConfig defaultConfig() {
        return builder().build();
    }

    public static class Builder {
        private Duration pollingInterval = Duration.ofMillis(500);
        private int batchSize = 10;
        private int consumerThreads = 1;
        private int maxRetries = 3;
        private ServerSideFilter serverSideFilter = null; // Optional, null by default

        public Builder pollingInterval(Duration interval) {
            this.pollingInterval = interval;
            return this;
        }

        public Builder batchSize(int size) {
            this.batchSize = size;
            return this;
        }

        public Builder consumerThreads(int threads) {
            this.consumerThreads = threads;
            return this;
        }

        public Builder maxRetries(int retries) {
            this.maxRetries = retries;
            return this;
        }

        /**
         * Sets the server-side filter for database-level message filtering.
         * When set, the filter conditions are applied in the SQL query,
         * reducing network traffic and client CPU usage.
         *
         * @param filter The server-side filter, or null for no filtering
         * @return This builder
         */
        public Builder serverSideFilter(ServerSideFilter filter) {
            this.serverSideFilter = filter;
            return this;
        }

        public OutboxConsumerConfig build() {
            // Validation logic following established patterns
            if (pollingInterval == null) {
                throw new NullPointerException("Polling interval cannot be null");
            }
            if (pollingInterval.isNegative() || pollingInterval.isZero()) {
                throw new IllegalArgumentException("Polling interval must be positive, got: " + pollingInterval);
            }
            if (pollingInterval.compareTo(Duration.ofHours(24)) > 0) {
                throw new IllegalArgumentException("Polling interval too large (maximum 24 hours), got: " + pollingInterval);
            }
            if (batchSize <= 0) {
                throw new IllegalArgumentException("Batch size must be positive, got: " + batchSize);
            }
            if (batchSize > 10000) {
                throw new IllegalArgumentException("Batch size too large (maximum 10000), got: " + batchSize);
            }
            if (consumerThreads <= 0) {
                throw new IllegalArgumentException("Consumer threads must be positive, got: " + consumerThreads);
            }
            if (consumerThreads > 1000) {
                throw new IllegalArgumentException("Consumer threads too large (maximum 1000), got: " + consumerThreads);
            }
            if (maxRetries < 0) {
                throw new IllegalArgumentException("Max retries cannot be negative, got: " + maxRetries);
            }
            if (maxRetries > 100) {
                throw new IllegalArgumentException("Max retries too large (maximum 100), got: " + maxRetries);
            }

            return new OutboxConsumerConfig(this);
        }
    }

    @Override
    public String toString() {
        return "OutboxConsumerConfig{" +
                "pollingInterval=" + pollingInterval +
                ", batchSize=" + batchSize +
                ", consumerThreads=" + consumerThreads +
                ", maxRetries=" + maxRetries +
                ", serverSideFilter=" + serverSideFilter +
                '}';
    }
}

