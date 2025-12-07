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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PeeGeeQRuntime factory class.
 */
class PeeGeeQRuntimeTest {

    @Test
    void createDatabaseSetupService_withDefaults_returnsRuntimeDatabaseSetupService() {
        // When
        DatabaseSetupService service = PeeGeeQRuntime.createDatabaseSetupService();

        // Then
        assertNotNull(service, "Service should not be null");
        assertInstanceOf(RuntimeDatabaseSetupService.class, service,
                "Service should be RuntimeDatabaseSetupService");
    }

    @Test
    void createDatabaseSetupService_withCustomConfig_returnsConfiguredService() {
        // Given
        RuntimeConfig config = RuntimeConfig.builder()
                .enableNativeQueues(true)
                .enableOutboxQueues(false)
                .enableBiTemporalEventStore(true)
                .build();

        // When
        DatabaseSetupService service = PeeGeeQRuntime.createDatabaseSetupService(config);

        // Then
        assertNotNull(service, "Service should not be null");
        assertInstanceOf(RuntimeDatabaseSetupService.class, service);
    }

    @Test
    void createDatabaseSetupService_withNullConfig_throwsNullPointerException() {
        // When/Then
        assertThrows(NullPointerException.class, 
                () -> PeeGeeQRuntime.createDatabaseSetupService(null));
    }
    
    @Test
    void bootstrap_withDefaults_returnsPeeGeeQContext() {
        // When
        PeeGeeQContext context = PeeGeeQRuntime.bootstrap();
        
        // Then
        assertNotNull(context, "Context should not be null");
        assertNotNull(context.getDatabaseSetupService(), "Setup service should not be null");
        assertNotNull(context.getConfig(), "Config should not be null");
        assertTrue(context.hasNativeQueues(), "Native queues should be enabled by default");
        assertTrue(context.hasOutboxQueues(), "Outbox queues should be enabled by default");
        assertTrue(context.hasBiTemporalEventStore(), "Bi-temporal should be enabled by default");
    }
    
    @Test
    void bootstrap_withCustomConfig_returnsPeeGeeQContextWithConfig() {
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
        assertFalse(context.hasNativeQueues(), "Native queues should be disabled");
        assertTrue(context.hasOutboxQueues(), "Outbox queues should be enabled");
        assertFalse(context.hasBiTemporalEventStore(), "Bi-temporal should be disabled");
    }
    
    @Test
    void bootstrap_withNullConfig_throwsNullPointerException() {
        // When/Then
        assertThrows(NullPointerException.class, 
                () -> PeeGeeQRuntime.bootstrap(null));
    }
}

