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

package dev.mars.peegeeq.api.setup;

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import io.vertx.core.Future;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Database setup service interface.
 *
 * This interface provides methods for creating and managing database setups.
 * All asynchronous operations use Vert.x Future.
 *
 * Extends ServiceProvider to provide access to services (subscription, dead letter,
 * health) for each setup without requiring direct dependencies on implementation modules.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-05
 * @version 1.0
 */
public interface DatabaseSetupService extends ServiceProvider {
    Future<DatabaseSetupResult> createCompleteSetup(DatabaseSetupRequest request);

    /**
     * Non-destructively attaches to a setup whose database and schema already exist.
     *
     * <p>Unlike {@link #createCompleteSetup}, this neither creates the database nor applies schema
     * templates. It validates that the PeeGeeQ schema is present (failing clearly if it is not — it must
     * never create it), reconstitutes the setup's queues and event stores by reading the setup's own
     * self-describing registry tables ({@code peegeeq_setup_metadata} + {@code peegeeq_object_registry})
     * — not from the request body — and then starts the manager and registers the reconstituted factories.
     * {@code PeeGeeQManager.start()} is non-destructive, so no existing data is touched.
     *
     * <p>The {@code queues} / {@code eventStores} on the request are ignored (contents are recovered from
     * the schema); the {@code setupId} is treated as the expected id and validated against the value
     * recovered from {@code peegeeq_setup_metadata}.
     *
     * @param request the setup coordinates (database/schema/credentials + expected setupId)
     * @return a Future completing with the reconstituted, now-active setup result
     */
    default Future<DatabaseSetupResult> connectToExistingSetup(DatabaseSetupRequest request) {
        return Future.failedFuture(
                new UnsupportedOperationException("connectToExistingSetup is not supported by this implementation"));
    }

    Future<Void> destroySetup(String setupId);

    /**
     * Non-destructively detaches from a setup: stops the manager and deregisters the in-memory binding,
     * but NEVER drops the database or schema (the inverse of {@link #connectToExistingSetup}). The data
     * persists and the setup can be reconnected later. This is the safe "remove" for a connected setup;
     * dropping the database is a separate, explicitly-guarded operation.
     *
     * <p>The default delegates to {@link #destroySetup}, which is itself non-destructive (it closes the
     * manager and clears in-memory state, dropping nothing), so implementations get correct detach
     * behaviour without change.
     *
     * @param setupId the setup to detach
     * @return a Future completing when the in-memory binding has been released
     */
    default Future<Void> detachSetup(String setupId) {
        return destroySetup(setupId);
    }

    /**
     * The single DESTRUCTIVE path: drops the setup's database (irreversible). This is deliberately separate
     * from {@link #detachSetup} and {@link #destroySetup} (both non-destructive) and from create — it is the
     * only operation that issues {@code DROP DATABASE}.
     *
     * <p>Guarded by a type-to-confirm token: {@code confirmDatabaseName} must equal the setup's actual
     * database name, otherwise the operation is refused (a failed Future carrying an
     * {@link IllegalArgumentException}) with nothing dropped. This defeats accidental/replayed calls. The
     * drop drains live connections ({@code WITH (FORCE)}) and then detaches the now-dead in-memory binding.
     *
     * <p>The default implementation is unsupported; only implementations that own the database lifecycle
     * override it.
     *
     * @param setupId             the setup whose database to drop
     * @param confirmDatabaseName the exact database name, re-supplied as confirmation
     * @return a Future completing when the database has been dropped; failed (no drop) on confirm mismatch
     */
    default Future<Void> dropSetupDatabase(String setupId, String confirmDatabaseName) {
        return Future.failedFuture(
                new UnsupportedOperationException("dropSetupDatabase is not supported by this implementation"));
    }

    Future<DatabaseSetupStatus> getSetupStatus(String setupId);
    Future<DatabaseSetupResult> getSetupResult(String setupId);
    Future<Void> addQueue(String setupId, QueueConfig queueConfig);
    Future<Void> addEventStore(String setupId, EventStoreConfig eventStoreConfig);
    Future<Void> removeEventStore(String setupId, String storeName);

    /**
     * Gets all active setup IDs.
     * @return A Future that completes with a set of active setup IDs
     */
    Future<Set<String>> getAllActiveSetupIds();

    /**
     * Closes the service and releases all managed resources (active setups, connection pools,
     * background timers). Implementations should close all active {@code PeeGeeQManager}
     * instances so that periodic timers (e.g. depth cache refresh) are cancelled before the
     * underlying database becomes unreachable.
     *
     * <p>The default implementation is a no-op that returns a pre-succeeded Future, preserving
     * backward compatibility for existing implementations that manage their own lifecycle.
     *
     * @return a Future that completes when all resources have been released
     */
    default Future<Void> close() {
        return Future.succeededFuture();
    }

    /**
     * Adds a factory registration callback that will be invoked during setup.
     * This allows implementation modules to register their queue factories without
     * creating direct dependencies.
     *
     * Default implementation does nothing - implementations should override this
     * if they support factory registration.
     *
     * @param registration A consumer that registers a factory with the registrar
     */
    default void addFactoryRegistration(Consumer<QueueFactoryRegistrar> registration) {
        // Default implementation does nothing
    }

    /**
     * Gets the database configuration for the specified setup.
     * Returns host, port, databaseName, and schema (credentials are excluded).
     *
     * <p>The default implementation returns a failed future. Implementations that
     * store the database configuration should override this method.
     *
     * @param setupId the setup identifier
     * @return a Future containing the DatabaseConfig, or a failed Future if not found
     */
    default Future<DatabaseConfig> getDatabaseConfig(String setupId) {
        return Future.failedFuture("Database config not available for setup: " + setupId);
    }
}