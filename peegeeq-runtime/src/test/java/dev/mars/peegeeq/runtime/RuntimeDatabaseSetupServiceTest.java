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

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RuntimeDatabaseSetupService.
 * Tests factory registration and delegation using real PeeGeeQRuntime.
 */
class RuntimeDatabaseSetupServiceTest {

    private RuntimeDatabaseSetupService service;

    @BeforeEach
    void setUp() {
        // Use real runtime to create the service
        DatabaseSetupService delegate = PeeGeeQRuntime.createDatabaseSetupService();
        // The runtime already returns a RuntimeDatabaseSetupService, so we test it directly
        assertInstanceOf(RuntimeDatabaseSetupService.class, delegate);
        service = (RuntimeDatabaseSetupService) delegate;
    }

    // ========================================================================
    // Factory Registration Tests
    // ========================================================================

    @Test
    @DisplayName("addFactoryRegistration - stores registration in list")
    void addFactoryRegistration_storesRegistration() {
        // Given
        Consumer<QueueFactoryRegistrar> registration = registrar -> {};
        int initialSize = service.getFactoryRegistrations().size();

        // When
        service.addFactoryRegistration(registration);

        // Then
        assertEquals(initialSize + 1, service.getFactoryRegistrations().size());
        assertTrue(service.getFactoryRegistrations().contains(registration));
    }

    @Test
    @DisplayName("addFactoryRegistration - multiple registrations are stored")
    void addFactoryRegistration_multipleRegistrations() {
        // Given
        Consumer<QueueFactoryRegistrar> reg1 = registrar -> {};
        Consumer<QueueFactoryRegistrar> reg2 = registrar -> {};
        int initialSize = service.getFactoryRegistrations().size();

        // When
        service.addFactoryRegistration(reg1);
        service.addFactoryRegistration(reg2);

        // Then
        assertEquals(initialSize + 2, service.getFactoryRegistrations().size());
    }

    @Test
    @DisplayName("getFactoryRegistrations - returns list containing added registrations")
    void getFactoryRegistrations_returnsAddedRegistrations() {
        // Given
        Consumer<QueueFactoryRegistrar> registration = registrar -> {};

        // When
        service.addFactoryRegistration(registration);

        // Then
        assertNotNull(service.getFactoryRegistrations());
        assertTrue(service.getFactoryRegistrations().contains(registration));
    }

    // ========================================================================
    // Service Access Tests (without database - just verify methods exist)
    // ========================================================================

    @Test
    @DisplayName("getAllActiveSetupIds - returns CompletableFuture")
    void getAllActiveSetupIds_returnsCompletableFuture() {
        // When
        var future = service.getAllActiveSetupIds();

        // Then
        assertNotNull(future, "Should return a CompletableFuture");
    }

    @Test
    @DisplayName("getSetupStatus - returns CompletableFuture for unknown setup")
    void getSetupStatus_returnsCompletableFuture() {
        // When
        var future = service.getSetupStatus("unknown-setup-id");

        // Then
        assertNotNull(future, "Should return a CompletableFuture");
    }

    @Test
    @DisplayName("getSetupResult - returns CompletableFuture for unknown setup")
    void getSetupResult_returnsCompletableFuture() {
        // When
        var future = service.getSetupResult("unknown-setup-id");

        // Then
        assertNotNull(future, "Should return a CompletableFuture");
    }

    @Test
    @DisplayName("destroySetup - returns CompletableFuture for unknown setup")
    void destroySetup_returnsCompletableFuture() {
        // When
        var future = service.destroySetup("unknown-setup-id");

        // Then
        assertNotNull(future, "Should return a CompletableFuture");
    }
}

