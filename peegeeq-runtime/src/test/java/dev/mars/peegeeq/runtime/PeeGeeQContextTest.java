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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PeeGeeQContext.
 * Tests context creation, accessors, and feature flags using real runtime.
 */
class PeeGeeQContextTest {

    @Test
    @DisplayName("bootstrap with defaults - returns context with all features enabled")
    void bootstrap_defaults_allFeaturesEnabled() {
        // When
        PeeGeeQContext context = PeeGeeQRuntime.bootstrap();

        // Then
        assertNotNull(context);
        assertNotNull(context.getDatabaseSetupService());
        assertNotNull(context.getConfig());
        assertTrue(context.hasNativeQueues());
        assertTrue(context.hasOutboxQueues());
        assertTrue(context.hasBiTemporalEventStore());
    }

    @Test
    @DisplayName("bootstrap with native only - returns context with only native queues")
    void bootstrap_nativeOnly_onlyNativeEnabled() {
        // Given
        RuntimeConfig config = RuntimeConfig.builder()
            .enableNativeQueues(true)
            .enableOutboxQueues(false)
            .enableBiTemporalEventStore(false)
            .build();

        // When
        PeeGeeQContext context = PeeGeeQRuntime.bootstrap(config);

        // Then
        assertNotNull(context);
        assertTrue(context.hasNativeQueues());
        assertFalse(context.hasOutboxQueues());
        assertFalse(context.hasBiTemporalEventStore());
    }

    @Test
    @DisplayName("bootstrap with outbox only - returns context with only outbox queues")
    void bootstrap_outboxOnly_onlyOutboxEnabled() {
        // Given
        RuntimeConfig config = RuntimeConfig.builder()
            .enableNativeQueues(false)
            .enableOutboxQueues(true)
            .enableBiTemporalEventStore(false)
            .build();

        // When
        PeeGeeQContext context = PeeGeeQRuntime.bootstrap(config);

        // Then
        assertNotNull(context);
        assertFalse(context.hasNativeQueues());
        assertTrue(context.hasOutboxQueues());
        assertFalse(context.hasBiTemporalEventStore());
    }

    @Test
    @DisplayName("bootstrap with bitemporal only - returns context with only bitemporal")
    void bootstrap_bitemporalOnly_onlyBitemporalEnabled() {
        // Given
        RuntimeConfig config = RuntimeConfig.builder()
            .enableNativeQueues(false)
            .enableOutboxQueues(false)
            .enableBiTemporalEventStore(true)
            .build();

        // When
        PeeGeeQContext context = PeeGeeQRuntime.bootstrap(config);

        // Then
        assertNotNull(context);
        assertFalse(context.hasNativeQueues());
        assertFalse(context.hasOutboxQueues());
        assertTrue(context.hasBiTemporalEventStore());
    }

    @Test
    @DisplayName("getConfig - returns the same config used for bootstrap")
    void getConfig_returnsSameConfig() {
        // Given
        RuntimeConfig config = RuntimeConfig.builder()
            .enableNativeQueues(true)
            .enableOutboxQueues(false)
            .build();

        // When
        PeeGeeQContext context = PeeGeeQRuntime.bootstrap(config);

        // Then
        assertSame(config, context.getConfig());
    }

    @Test
    @DisplayName("getDatabaseSetupService - returns non-null service")
    void getDatabaseSetupService_returnsNonNull() {
        // When
        PeeGeeQContext context = PeeGeeQRuntime.bootstrap();

        // Then
        assertNotNull(context.getDatabaseSetupService());
        assertInstanceOf(RuntimeDatabaseSetupService.class, context.getDatabaseSetupService());
    }

    @Test
    @DisplayName("toString - contains relevant information")
    void toString_containsRelevantInfo() {
        // When
        PeeGeeQContext context = PeeGeeQRuntime.bootstrap();
        String result = context.toString();

        // Then
        assertTrue(result.contains("PeeGeeQContext"));
        assertTrue(result.contains("databaseSetupService"));
        assertTrue(result.contains("config"));
    }
}

