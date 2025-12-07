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

package dev.mars.peegeeq.runtime;

import java.util.Objects;

/**
 * Configuration holder for PeeGeeQ runtime bootstrap.
 * 
 * This class provides configuration options for initializing the PeeGeeQ runtime,
 * including which queue implementations to enable and other runtime settings.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-06
 * @version 1.0
 */
public final class RuntimeConfig {
    
    private final boolean enableNativeQueues;
    private final boolean enableOutboxQueues;
    private final boolean enableBiTemporalEventStore;
    
    private RuntimeConfig(Builder builder) {
        this.enableNativeQueues = builder.enableNativeQueues;
        this.enableOutboxQueues = builder.enableOutboxQueues;
        this.enableBiTemporalEventStore = builder.enableBiTemporalEventStore;
    }
    
    /**
     * Returns whether native queue implementation is enabled.
     * 
     * @return true if native queues are enabled
     */
    public boolean isNativeQueuesEnabled() {
        return enableNativeQueues;
    }
    
    /**
     * Returns whether outbox queue implementation is enabled.
     * 
     * @return true if outbox queues are enabled
     */
    public boolean isOutboxQueuesEnabled() {
        return enableOutboxQueues;
    }
    
    /**
     * Returns whether bi-temporal event store is enabled.
     * 
     * @return true if bi-temporal event store is enabled
     */
    public boolean isBiTemporalEventStoreEnabled() {
        return enableBiTemporalEventStore;
    }
    
    /**
     * Creates a new builder with default settings (all features enabled).
     * 
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Creates a default configuration with all features enabled.
     * 
     * @return a default RuntimeConfig instance
     */
    public static RuntimeConfig defaults() {
        return builder().build();
    }
    
    /**
     * Builder for RuntimeConfig.
     */
    public static final class Builder {
        private boolean enableNativeQueues = true;
        private boolean enableOutboxQueues = true;
        private boolean enableBiTemporalEventStore = true;
        
        private Builder() {}
        
        /**
         * Enables or disables native queue implementation.
         * 
         * @param enable true to enable native queues
         * @return this builder
         */
        public Builder enableNativeQueues(boolean enable) {
            this.enableNativeQueues = enable;
            return this;
        }
        
        /**
         * Enables or disables outbox queue implementation.
         * 
         * @param enable true to enable outbox queues
         * @return this builder
         */
        public Builder enableOutboxQueues(boolean enable) {
            this.enableOutboxQueues = enable;
            return this;
        }
        
        /**
         * Enables or disables bi-temporal event store.
         * 
         * @param enable true to enable bi-temporal event store
         * @return this builder
         */
        public Builder enableBiTemporalEventStore(boolean enable) {
            this.enableBiTemporalEventStore = enable;
            return this;
        }
        
        /**
         * Builds the RuntimeConfig instance.
         * 
         * @return a new RuntimeConfig instance
         */
        public RuntimeConfig build() {
            return new RuntimeConfig(this);
        }
    }
    
    @Override
    public String toString() {
        return "RuntimeConfig{" +
                "enableNativeQueues=" + enableNativeQueues +
                ", enableOutboxQueues=" + enableOutboxQueues +
                ", enableBiTemporalEventStore=" + enableBiTemporalEventStore +
                '}';
    }
}

