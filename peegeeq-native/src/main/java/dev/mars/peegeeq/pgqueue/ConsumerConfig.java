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
package dev.mars.peegeeq.pgqueue;

import java.time.Duration;

/**
 * Configuration for native queue consumers.
 * Follows the established PeeGeeQ configuration pattern with builder support.
 */
public class ConsumerConfig {
    private final ConsumerMode mode;
    private final Duration pollingInterval;
    private final boolean enableNotifications;
    private final int batchSize;
    private final int consumerThreads;
    
    // Private constructor for builder pattern
    private ConsumerConfig(Builder builder) {
        this.mode = builder.mode;
        this.pollingInterval = builder.pollingInterval;
        this.enableNotifications = builder.enableNotifications;
        this.batchSize = builder.batchSize;
        this.consumerThreads = builder.consumerThreads;
    }
    
    // Getters
    public ConsumerMode getMode() { return mode; }
    public Duration getPollingInterval() { return pollingInterval; }
    public boolean isNotificationsEnabled() { return enableNotifications; }
    public int getBatchSize() { return batchSize; }
    public int getConsumerThreads() { return consumerThreads; }
    
    // Builder pattern following established conventions
    public static Builder builder() {
        return new Builder();
    }
    
    // Default configuration for backward compatibility
    public static ConsumerConfig defaultConfig() {
        return builder().build();
    }
    
    public static class Builder {
        private ConsumerMode mode = ConsumerMode.HYBRID; // Backward compatible default
        private Duration pollingInterval = Duration.ofSeconds(1);
        private boolean enableNotifications = true;
        private int batchSize = 10;
        private int consumerThreads = 1;
        
        public Builder mode(ConsumerMode mode) {
            this.mode = mode;
            return this;
        }
        
        public Builder pollingInterval(Duration interval) {
            this.pollingInterval = interval;
            return this;
        }
        
        public Builder enableNotifications(boolean enable) {
            this.enableNotifications = enable;
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
        
        public ConsumerConfig build() {
            // Validation logic
            if (mode == ConsumerMode.POLLING_ONLY && pollingInterval.isZero()) {
                throw new IllegalArgumentException("Polling interval cannot be zero for POLLING_ONLY mode");
            }
            return new ConsumerConfig(this);
        }
    }
    
    @Override
    public String toString() {
        return "ConsumerConfig{" +
                "mode=" + mode +
                ", pollingInterval=" + pollingInterval +
                ", enableNotifications=" + enableNotifications +
                ", batchSize=" + batchSize +
                ", consumerThreads=" + consumerThreads +
                '}';
    }
}
