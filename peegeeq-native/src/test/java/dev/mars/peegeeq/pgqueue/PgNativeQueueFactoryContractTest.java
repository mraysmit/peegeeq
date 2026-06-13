package dev.mars.peegeeq.pgqueue;

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

import dev.mars.peegeeq.api.database.ConnectionProvider;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.database.MetricsProvider;
import dev.mars.peegeeq.api.subscription.SubscriptionService;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test: the native queue factory requires a resolvable
 * {@code PeeGeeQConfiguration}. PeeGeeQ has no default schema, so a factory whose
 * configuration cannot be resolved (neither passed explicitly nor available from the
 * {@code DatabaseService}) must fail at construction — never fall back to {@code "public"}
 * channels and SQL.
 *
 * <p>No database is required: the stub service exists only to prove the factory throws
 * before using any of it.</p>
 */
@Tag(TestCategories.CORE)
class PgNativeQueueFactoryContractTest {

    @Test
    void testFactoryWithoutResolvableConfigurationThrows() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new PgNativeQueueFactory(new NoConfigDatabaseService()),
                "A factory without a resolvable PeeGeeQConfiguration must fail at construction");
        assertTrue(ex.getMessage().contains("PeeGeeQConfiguration"),
                "The error must name the missing configuration, got: " + ex.getMessage());
    }

    /** Provides nothing: not a PgDatabaseService, so no configuration can be resolved. */
    private static final class NoConfigDatabaseService implements DatabaseService {
        @Override public Future<Void> initialize() { return Future.succeededFuture(); }
        @Override public Future<Void> start() { return Future.succeededFuture(); }
        @Override public Future<Void> stop() { return Future.succeededFuture(); }
        @Override public boolean isRunning() { return false; }
        @Override public boolean isHealthy() { return false; }
        @Override public ConnectionProvider getConnectionProvider() { return null; }
        @Override public MetricsProvider getMetricsProvider() { return null; }
        @Override public SubscriptionService getSubscriptionService() { return null; }
        @Override public Future<Void> runMigrations() { return Future.succeededFuture(); }
        @Override public Future<Boolean> performHealthCheck() { return Future.succeededFuture(false); }
        @Override public void close() {}
        @Override public Vertx getVertx() { return null; }
        @Override public Pool getPool() { return null; }
        @Override public PgConnectOptions getConnectOptions() { return null; }
    }
}
