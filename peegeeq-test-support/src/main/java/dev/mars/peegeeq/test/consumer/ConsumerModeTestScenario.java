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
package dev.mars.peegeeq.test.consumer;

import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile;
import dev.mars.peegeeq.pgqueue.ConsumerMode;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration class for consumer mode test scenarios.
 * 
 * This class combines performance profiles with consumer mode configurations
 * to enable parameterized testing across different PostgreSQL configurations
 * and consumer modes. It follows the established PeeGeeQ configuration patterns
 * with immutable objects and builder support.
 * 
 * Usage:
 * ```java
 * ConsumerModeTestScenario scenario = ConsumerModeTestScenario.builder()
 *     .performanceProfile(PerformanceProfile.HIGH_PERFORMANCE)
 *     .consumerMode(ConsumerMode.HYBRID)
 *     .pollingInterval(Duration.ofMillis(100))
 *     .threadCount(4)
 *     .messageCount(1000)
 *     .build();
 * ```
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-19
 * @version 1.0
 */
public class ConsumerModeTestScenario {
    
    private final PerformanceProfile performanceProfile;
    private final ConsumerMode consumerMode;
    private final Duration pollingInterval;
    private final int threadCount;
    private final int messageCount;
    private final int batchSize;
    private final String description;
    
    // Private constructor for builder pattern
    private ConsumerModeTestScenario(Builder builder) {
        this.performanceProfile = builder.performanceProfile;
        this.consumerMode = builder.consumerMode;
        this.pollingInterval = builder.pollingInterval;
        this.threadCount = builder.threadCount;
        this.messageCount = builder.messageCount;
        this.batchSize = builder.batchSize;
        this.description = builder.description;
    }
    
    // Getters
    public PerformanceProfile getPerformanceProfile() { return performanceProfile; }
    public ConsumerMode getConsumerMode() { return consumerMode; }
    public Duration getPollingInterval() { return pollingInterval; }
    public int getThreadCount() { return threadCount; }
    public int getMessageCount() { return messageCount; }
    public int getBatchSize() { return batchSize; }
    public String getDescription() { return description; }
    
    /**
     * Get a human-readable name for this scenario.
     */
    public String getScenarioName() {
        return String.format("%s-%s", 
            performanceProfile.name(), 
            consumerMode.name());
    }
    
    /**
     * Get a detailed description of this scenario.
     */
    public String getDetailedDescription() {
        if (description != null && !description.trim().isEmpty()) {
            return description;
        }
        return String.format("Performance Profile: %s, Consumer Mode: %s, Polling: %s, Threads: %d, Messages: %d, Batch: %d",
            performanceProfile.getDisplayName(),
            consumerMode.name(),
            pollingInterval,
            threadCount,
            messageCount,
            batchSize);
    }
    
    // Builder pattern following established conventions
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Create a scenario with basic defaults for quick testing.
     */
    public static ConsumerModeTestScenario basic(PerformanceProfile profile, ConsumerMode mode) {
        return builder()
            .performanceProfile(profile)
            .consumerMode(mode)
            .build();
    }
    
    public static class Builder {
        private PerformanceProfile performanceProfile = PerformanceProfile.STANDARD;
        private ConsumerMode consumerMode = ConsumerMode.HYBRID;
        private Duration pollingInterval = Duration.ofSeconds(1);
        private int threadCount = 1;
        private int messageCount = 100;
        private int batchSize = 10;
        private String description = null;
        
        public Builder performanceProfile(PerformanceProfile profile) {
            this.performanceProfile = profile;
            return this;
        }
        
        public Builder consumerMode(ConsumerMode mode) {
            this.consumerMode = mode;
            return this;
        }
        
        public Builder pollingInterval(Duration interval) {
            this.pollingInterval = interval;
            return this;
        }
        
        public Builder threadCount(int threads) {
            this.threadCount = threads;
            return this;
        }
        
        public Builder messageCount(int count) {
            this.messageCount = count;
            return this;
        }
        
        public Builder batchSize(int size) {
            this.batchSize = size;
            return this;
        }
        
        public Builder description(String desc) {
            this.description = desc;
            return this;
        }
        
        public ConsumerModeTestScenario build() {
            // Validation following established patterns
            if (performanceProfile == null) {
                throw new NullPointerException("Performance profile cannot be null");
            }
            if (consumerMode == null) {
                throw new NullPointerException("Consumer mode cannot be null");
            }
            if (pollingInterval == null) {
                throw new NullPointerException("Polling interval cannot be null");
            }
            if (pollingInterval.isNegative()) {
                throw new IllegalArgumentException("Polling interval must be positive, got: " + pollingInterval);
            }
            if (consumerMode == ConsumerMode.POLLING_ONLY && pollingInterval.isZero()) {
                throw new IllegalArgumentException("Polling interval cannot be zero for POLLING_ONLY mode");
            }
            if (threadCount <= 0) {
                throw new IllegalArgumentException("Thread count must be positive, got: " + threadCount);
            }
            if (threadCount > 100) {
                throw new IllegalArgumentException("Thread count too large (maximum 100), got: " + threadCount);
            }
            if (messageCount <= 0) {
                throw new IllegalArgumentException("Message count must be positive, got: " + messageCount);
            }
            if (batchSize <= 0) {
                throw new IllegalArgumentException("Batch size must be positive, got: " + batchSize);
            }
            
            return new ConsumerModeTestScenario(this);
        }
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConsumerModeTestScenario that = (ConsumerModeTestScenario) o;
        return threadCount == that.threadCount &&
               messageCount == that.messageCount &&
               batchSize == that.batchSize &&
               performanceProfile == that.performanceProfile &&
               consumerMode == that.consumerMode &&
               Objects.equals(pollingInterval, that.pollingInterval) &&
               Objects.equals(description, that.description);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(performanceProfile, consumerMode, pollingInterval, 
                          threadCount, messageCount, batchSize, description);
    }
    
    @Override
    public String toString() {
        return "ConsumerModeTestScenario{" +
                "profile=" + performanceProfile.name() +
                ", mode=" + consumerMode +
                ", polling=" + pollingInterval +
                ", threads=" + threadCount +
                ", messages=" + messageCount +
                ", batch=" + batchSize +
                (description != null ? ", desc='" + description + '\'' : "") +
                '}';
    }
}
