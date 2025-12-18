package dev.mars.peegeeq.api.database;

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
import java.util.Collections;
import java.util.Map;

/**
 * No-operation implementation of MetricsProvider.
 * 
 * Use this when metrics collection is disabled. This class implements the
 * Null Object pattern - all methods do nothing but are safe to call.
 * 
 * This ensures that producers and consumers never need to null-check metrics.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public final class NoOpMetricsProvider implements MetricsProvider {
    
    /**
     * Singleton instance. Use this instead of creating new instances.
     */
    public static final NoOpMetricsProvider INSTANCE = new NoOpMetricsProvider();
    
    private NoOpMetricsProvider() {
        // Private constructor to enforce singleton pattern
    }
    
    @Override
    public void recordMessageSent(String topic) {
        // No-op
    }
    
    @Override
    public void recordMessageReceived(String topic) {
        // No-op
    }
    
    @Override
    public void recordMessageProcessed(String topic, Duration processingTime) {
        // No-op
    }
    
    @Override
    public void recordMessageFailed(String topic, String reason) {
        // No-op
    }
    
    @Override
    public void recordMessageDeadLettered(String topic, String reason) {
        // No-op
    }
    
    @Override
    public void recordMessageRetried(String topic, int retryCount) {
        // No-op
    }
    
    @Override
    public void incrementCounter(String name, Map<String, String> tags) {
        // No-op
    }
    
    @Override
    public void recordTimer(String name, Duration duration, Map<String, String> tags) {
        // No-op
    }
    
    @Override
    public void recordGauge(String name, double value, Map<String, String> tags) {
        // No-op
    }
    
    @Override
    public long getQueueDepth(String topic) {
        return 0;
    }
    
    @Override
    public Map<String, Number> getAllMetrics() {
        return Collections.emptyMap();
    }
    
    @Override
    public String getInstanceId() {
        return "noop";
    }
}

