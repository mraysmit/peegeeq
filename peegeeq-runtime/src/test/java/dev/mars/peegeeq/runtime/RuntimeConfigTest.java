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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RuntimeConfig.
 */
class RuntimeConfigTest {
    
    @Test
    void defaults_allFeaturesEnabled() {
        // When
        RuntimeConfig config = RuntimeConfig.defaults();
        
        // Then
        assertTrue(config.isNativeQueuesEnabled(), "Native queues should be enabled by default");
        assertTrue(config.isOutboxQueuesEnabled(), "Outbox queues should be enabled by default");
        assertTrue(config.isBiTemporalEventStoreEnabled(), "Bi-temporal should be enabled by default");
    }
    
    @Test
    void builder_withAllDisabled_allFeaturesDisabled() {
        // When
        RuntimeConfig config = RuntimeConfig.builder()
                .enableNativeQueues(false)
                .enableOutboxQueues(false)
                .enableBiTemporalEventStore(false)
                .build();
        
        // Then
        assertFalse(config.isNativeQueuesEnabled());
        assertFalse(config.isOutboxQueuesEnabled());
        assertFalse(config.isBiTemporalEventStoreEnabled());
    }
    
    @Test
    void builder_withMixedSettings_correctConfiguration() {
        // When
        RuntimeConfig config = RuntimeConfig.builder()
                .enableNativeQueues(true)
                .enableOutboxQueues(false)
                .enableBiTemporalEventStore(true)
                .build();
        
        // Then
        assertTrue(config.isNativeQueuesEnabled());
        assertFalse(config.isOutboxQueuesEnabled());
        assertTrue(config.isBiTemporalEventStoreEnabled());
    }
    
    @Test
    void toString_containsAllSettings() {
        // Given
        RuntimeConfig config = RuntimeConfig.builder()
                .enableNativeQueues(true)
                .enableOutboxQueues(false)
                .enableBiTemporalEventStore(true)
                .build();
        
        // When
        String result = config.toString();
        
        // Then
        assertTrue(result.contains("enableNativeQueues=true"));
        assertTrue(result.contains("enableOutboxQueues=false"));
        assertTrue(result.contains("enableBiTemporalEventStore=true"));
    }
}

