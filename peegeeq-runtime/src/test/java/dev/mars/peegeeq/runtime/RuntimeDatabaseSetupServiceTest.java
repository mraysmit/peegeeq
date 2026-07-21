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
import org.junit.jupiter.api.Tag;
import dev.mars.peegeeq.test.categories.TestCategories;

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.SetupNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RuntimeDatabaseSetupService.
 * Tests factory registration and delegation using real PeeGeeQRuntime.
 */
@Tag(TestCategories.CORE)
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

    @Test
    @DisplayName("constructor - null delegate throws NullPointerException")
    void constructor_nullDelegate_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> new RuntimeDatabaseSetupService(null));
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

    @Test
    @DisplayName("addFactoryRegistration - null registration throws NullPointerException")
    void addFactoryRegistration_nullRegistration_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> service.addFactoryRegistration(null));
    }

    @Test
    @DisplayName("getFactoryRegistrations - returned list is immutable")
    void getFactoryRegistrations_returnedListIsImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> service.getFactoryRegistrations().add(registrar -> {}));
    }

    // ========================================================================
    // Service Access Tests (no database: the delegate answers these paths
    // synchronously from its in-memory registry, so the OUTCOME is assertable
    // — replaced 2026-07-21; the previous assertNotNull(future) versions could
    // never fail while the signatures compiled, and their DisplayNames named
    // the banned CompletableFuture type)
    // ========================================================================

    @Test
    @DisplayName("getAllActiveSetupIds - completes with the empty set when no setups are active")
    void getAllActiveSetupIds_noActiveSetups_completesEmpty() {
        // When
        var future = service.getAllActiveSetupIds();

        // Then: answered synchronously from the in-memory registry.
        assertTrue(future.succeeded(), "Expected a synchronously succeeded Future");
        assertTrue(future.result().isEmpty(), "A fresh runtime has no active setups");
    }

    @Test
    @DisplayName("getSetupStatus - fails with SetupNotFoundException for an unknown setup")
    void getSetupStatus_unknownSetup_failsWithSetupNotFound() {
        // When
        var future = service.getSetupStatus("unknown-setup-id");

        // Then
        assertTrue(future.failed(), "Expected a synchronously failed Future");
        assertInstanceOf(SetupNotFoundException.class, future.cause());
        assertTrue(future.cause().getMessage().contains("unknown-setup-id"),
                "The failure must name the missing setup");
    }

    @Test
    @DisplayName("getSetupResult - fails with SetupNotFoundException for an unknown setup")
    void getSetupResult_unknownSetup_failsWithSetupNotFound() {
        // When
        var future = service.getSetupResult("unknown-setup-id");

        // Then
        assertTrue(future.failed(), "Expected a synchronously failed Future");
        assertInstanceOf(SetupNotFoundException.class, future.cause());
    }

    @Test
    @DisplayName("destroySetup - succeeds idempotently for an unknown setup")
    void destroySetup_unknownSetup_succeedsIdempotently() {
        // When
        var future = service.destroySetup("unknown-setup-id");

        // Then: destroy releases the in-memory binding; an unknown or
        // already-destroyed setup is a no-op success, not a failure.
        assertTrue(future.succeeded(), "destroy of an unknown setup must succeed idempotently");
    }

    // ========================================================================
    // close() Delegation Tests
    // ========================================================================

    @Test
    @DisplayName("close - delegates to the underlying delegate close()")
    void close_delegatesToDelegate() {
        // Given: a spy delegate that records whether close() was called
        boolean[] closeCalled = {false};
        DatabaseSetupService spyDelegate = new DatabaseSetupService() {
            @Override public io.vertx.core.Future<dev.mars.peegeeq.api.setup.DatabaseSetupResult> createCompleteSetup(dev.mars.peegeeq.api.setup.DatabaseSetupRequest r) { return io.vertx.core.Future.succeededFuture(null); }
            @Override public io.vertx.core.Future<Void> destroySetup(String id) { return io.vertx.core.Future.succeededFuture(); }
            @Override public io.vertx.core.Future<dev.mars.peegeeq.api.setup.DatabaseSetupStatus> getSetupStatus(String id) { return io.vertx.core.Future.succeededFuture(dev.mars.peegeeq.api.setup.DatabaseSetupStatus.ACTIVE); }
            @Override public io.vertx.core.Future<dev.mars.peegeeq.api.setup.DatabaseSetupResult> getSetupResult(String id) { return io.vertx.core.Future.succeededFuture(null); }
            @Override public io.vertx.core.Future<Void> addQueue(String id, dev.mars.peegeeq.api.database.QueueConfig q) { return io.vertx.core.Future.succeededFuture(); }
            @Override public io.vertx.core.Future<Void> addEventStore(String id, dev.mars.peegeeq.api.database.EventStoreConfig e) { return io.vertx.core.Future.succeededFuture(); }
            @Override public io.vertx.core.Future<Void> removeEventStore(String id, String storeName) { return io.vertx.core.Future.succeededFuture(); }
            @Override public io.vertx.core.Future<java.util.Set<String>> getAllActiveSetupIds() { return io.vertx.core.Future.succeededFuture(java.util.Collections.emptySet()); }
            @Override public dev.mars.peegeeq.api.subscription.SubscriptionService getSubscriptionServiceForSetup(String id) { return null; }
            @Override public dev.mars.peegeeq.api.deadletter.DeadLetterService getDeadLetterServiceForSetup(String id) { return null; }
            @Override public dev.mars.peegeeq.api.health.HealthService getHealthServiceForSetup(String id) { return null; }
            @Override public dev.mars.peegeeq.api.QueueFactoryProvider getQueueFactoryProviderForSetup(String id) { return null; }
            @Override public io.vertx.core.Future<Void> close() {
                closeCalled[0] = true;
                return io.vertx.core.Future.succeededFuture();
            }
        };

        RuntimeDatabaseSetupService runtimeService = new RuntimeDatabaseSetupService(spyDelegate);

        // When
        io.vertx.core.Future<Void> result = runtimeService.close();

        // Then
        assertNotNull(result);
        assertTrue(result.succeeded(), "close() must return a succeeded Future");
        assertTrue(closeCalled[0], "close() must delegate to the underlying delegate's close()");
    }
}

