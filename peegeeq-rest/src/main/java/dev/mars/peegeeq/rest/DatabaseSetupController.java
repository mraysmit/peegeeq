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

package dev.mars.peegeeq.rest;

import dev.mars.peegeeq.api.setup.*;
import java.util.concurrent.CompletableFuture;

/**
 * Legacy REST controller for PeeGeeQ database setup operations.
 *
 * Note: This class is kept for reference but the actual REST API
 * is implemented using Vert.x handlers in the PeeGeeQRestServer.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-18
 * @version 1.0
 * @deprecated Use PeeGeeQRestServer with Vert.x handlers instead
 */
@Deprecated
public class DatabaseSetupController {

    private final DatabaseSetupService setupService;

    public DatabaseSetupController(DatabaseSetupService setupService) {
        this.setupService = setupService;
    }
    
    /**
     * Creates a complete database setup with queues and event stores.
     */
    @PostMapping("/create")
    public CompletableFuture<ResponseEntity<DatabaseSetupResult>> createSetup(
            @Valid @RequestBody DatabaseSetupRequest request) {
        return setupService.createCompleteSetup(request)
                .thenApply(result -> ResponseEntity.ok(result))
                .exceptionally(throwable -> ResponseEntity.badRequest().build());
    }

    /**
     * Destroys a database setup and cleans up resources.
     */
    @DeleteMapping("/{setupId}")
    public CompletableFuture<ResponseEntity<Void>> destroySetup(@PathVariable String setupId) {
        return setupService.destroySetup(setupId)
                .thenApply(result -> ResponseEntity.noContent().<Void>build())
                .exceptionally(throwable -> ResponseEntity.notFound().build());
    }

    /**
     * Gets the status of a database setup.
     */
    @GetMapping("/{setupId}/status")
    public CompletableFuture<ResponseEntity<DatabaseSetupStatus>> getStatus(@PathVariable String setupId) {
        return setupService.getSetupStatus(setupId)
                .thenApply(status -> ResponseEntity.ok(status))
                .exceptionally(throwable -> ResponseEntity.notFound().build());
    }

    /**
     * Adds a new queue to an existing database setup.
     */
    @PostMapping("/{setupId}/queues")
    public CompletableFuture<ResponseEntity<Void>> addQueue(
            @PathVariable String setupId,
            @Valid @RequestBody QueueConfig queueConfig) {
        return setupService.addQueue(setupId, queueConfig)
                .thenApply(result -> ResponseEntity.created(null).build())
                .exceptionally(throwable -> ResponseEntity.badRequest().build());
    }

    /**
     * Adds a new event store to an existing database setup.
     */
    @PostMapping("/{setupId}/eventstores")
    public CompletableFuture<ResponseEntity<Void>> addEventStore(
            @PathVariable String setupId,
            @Valid @RequestBody EventStoreConfig eventStoreConfig) {
        return setupService.addEventStore(setupId, eventStoreConfig)
                .thenApply(result -> ResponseEntity.created(null).build())
                .exceptionally(throwable -> ResponseEntity.badRequest().build());
    }
}