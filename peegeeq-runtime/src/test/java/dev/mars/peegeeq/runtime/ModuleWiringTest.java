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

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for module wiring in PeeGeeQRuntime.
 * Verifies that native/outbox/bitemporal modules are correctly wired based on config.
 */
class ModuleWiringTest {

    // ========================================================================
    // Factory Registration Wiring Tests
    // ========================================================================

    @Test
    @DisplayName("defaults - registers both native and outbox factories")
    void defaults_registersBothFactories() {
        // When
        DatabaseSetupService service = PeeGeeQRuntime.createDatabaseSetupService();

        // Then
        assertInstanceOf(RuntimeDatabaseSetupService.class, service);
        RuntimeDatabaseSetupService runtimeService = (RuntimeDatabaseSetupService) service;
        
        // Both native and outbox should be registered by default
        assertEquals(2, runtimeService.getFactoryRegistrations().size(),
            "Should have 2 factory registrations (native + outbox)");
    }

    @Test
    @DisplayName("native only - registers only native factory")
    void nativeOnly_registersOnlyNativeFactory() {
        // Given
        RuntimeConfig config = RuntimeConfig.builder()
            .enableNativeQueues(true)
            .enableOutboxQueues(false)
            .enableBiTemporalEventStore(false)
            .build();

        // When
        DatabaseSetupService service = PeeGeeQRuntime.createDatabaseSetupService(config);

        // Then
        RuntimeDatabaseSetupService runtimeService = (RuntimeDatabaseSetupService) service;
        assertEquals(1, runtimeService.getFactoryRegistrations().size(),
            "Should have 1 factory registration (native only)");
    }

    @Test
    @DisplayName("outbox only - registers only outbox factory")
    void outboxOnly_registersOnlyOutboxFactory() {
        // Given
        RuntimeConfig config = RuntimeConfig.builder()
            .enableNativeQueues(false)
            .enableOutboxQueues(true)
            .enableBiTemporalEventStore(false)
            .build();

        // When
        DatabaseSetupService service = PeeGeeQRuntime.createDatabaseSetupService(config);

        // Then
        RuntimeDatabaseSetupService runtimeService = (RuntimeDatabaseSetupService) service;
        assertEquals(1, runtimeService.getFactoryRegistrations().size(),
            "Should have 1 factory registration (outbox only)");
    }

    @Test
    @DisplayName("no queues - registers no factories")
    void noQueues_registersNoFactories() {
        // Given
        RuntimeConfig config = RuntimeConfig.builder()
            .enableNativeQueues(false)
            .enableOutboxQueues(false)
            .enableBiTemporalEventStore(true)
            .build();

        // When
        DatabaseSetupService service = PeeGeeQRuntime.createDatabaseSetupService(config);

        // Then
        RuntimeDatabaseSetupService runtimeService = (RuntimeDatabaseSetupService) service;
        assertEquals(0, runtimeService.getFactoryRegistrations().size(),
            "Should have 0 factory registrations when both queue types disabled");
    }

    // ========================================================================
    // Bi-Temporal Event Store Wiring Tests
    // ========================================================================

    @Test
    @DisplayName("bitemporal enabled - context reports bitemporal available")
    void bitemporalEnabled_contextReportsAvailable() {
        // Given
        RuntimeConfig config = RuntimeConfig.builder()
            .enableNativeQueues(false)
            .enableOutboxQueues(false)
            .enableBiTemporalEventStore(true)
            .build();

        // When
        PeeGeeQContext context = PeeGeeQRuntime.bootstrap(config);

        // Then
        assertTrue(context.hasBiTemporalEventStore());
    }

    @Test
    @DisplayName("bitemporal disabled - context reports bitemporal unavailable")
    void bitemporalDisabled_contextReportsUnavailable() {
        // Given
        RuntimeConfig config = RuntimeConfig.builder()
            .enableNativeQueues(true)
            .enableOutboxQueues(false)
            .enableBiTemporalEventStore(false)
            .build();

        // When
        PeeGeeQContext context = PeeGeeQRuntime.bootstrap(config);

        // Then
        assertFalse(context.hasBiTemporalEventStore());
    }

    // ========================================================================
    // Combined Configuration Tests
    // ========================================================================

    @Test
    @DisplayName("all features enabled - all modules wired correctly")
    void allFeaturesEnabled_allModulesWired() {
        // Given
        RuntimeConfig config = RuntimeConfig.builder()
            .enableNativeQueues(true)
            .enableOutboxQueues(true)
            .enableBiTemporalEventStore(true)
            .build();

        // When
        PeeGeeQContext context = PeeGeeQRuntime.bootstrap(config);

        // Then
        assertTrue(context.hasNativeQueues());
        assertTrue(context.hasOutboxQueues());
        assertTrue(context.hasBiTemporalEventStore());
        
        RuntimeDatabaseSetupService runtimeService = 
            (RuntimeDatabaseSetupService) context.getDatabaseSetupService();
        assertEquals(2, runtimeService.getFactoryRegistrations().size());
    }
}

