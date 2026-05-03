package dev.mars.peegeeq.db;

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

import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that closeReactive() correctly propagates startup errors instead of
 * silently swallowing them.
 *
 * <p>The old bug: closeReactive() used {@code pendingStart.recover(e -> Future.succeededFuture())}
 * to ensure the cleanup chain runs when start() is in-flight and fails, but that
 * converted the failure to success, so the startup error was silently discarded.
 * The caller of closeReactive() saw success and never knew start failed.</p>
 *
 * <p>The fix: use {@code .eventually()} instead. It runs the cleanup chain regardless
 * of whether the preceding future succeeded or failed, then propagates the original result.
 * Cleanup still runs fully; the original error is preserved.</p>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class PeeGeeQManagerCloseReactiveErrorPropagationTest {

    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQManagerCloseReactiveErrorPropagationTest.class);
    private static final String POSTGRES_IMAGE = PgTestImageConstant.POSTGRES_IMAGE;

    // Container with NO schema initialization — start() will fail with "Database required tables missing"
    @SuppressWarnings("resource")
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_empty")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withReuse(false);

    private PeeGeeQManager manager;

    @AfterEach
    void tearDown() {
        clearSystemProperties();
    }

    @Test
    @DisplayName("closeReactive must propagate startup failure when start is in-flight and fails")
    void closeReactive_propagates_startup_failure(VertxTestContext testContext) {
        logger.info("[propagates_startup_failure] Step 1: Setting system properties for empty database (no schema tables)");
        setSystemPropertiesFor(postgres);

        logger.info("[propagates_startup_failure] Step 2: Creating PeeGeeQManager");
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test"), new SimpleMeterRegistry());

        // Fire-and-forget start() — replicates the pattern in 95 test setUp methods.
        // start() will fail asynchronously: validateRequiredTables() finds no tables.
        logger.info("[propagates_startup_failure] Step 3: Fire-and-forget start() — will fail asynchronously (no schema tables)");
        logger.warn("===== INTENTIONAL ERROR TEST ===== The next ERROR log ('Failed to start PeeGeeQ Manager') is EXPECTED — this test deliberately uses an empty database with no schema tables");
        manager.start();

        // Call closeReactive() while start is still in-flight.
        // closeReactive() sees startFuture != null and awaits it.
        //
        // EXPECT: closeReactive() must return a failed future carrying the startup error.
        //         Cleanup (stop, hooks, pools) must still run via .eventually(), but
        //         the original error must propagate.
        logger.info("[propagates_startup_failure] Step 4: Calling closeReactive() while start is in-flight");
        manager.closeReactive()
            .transform(ar -> {
                logger.info("[propagates_startup_failure] Step 5: closeReactive() completed — failed={}, cause={}",
                        ar.failed(), ar.cause() != null ? ar.cause().getMessage() : "none");
                if (!ar.failed()) {
                    return Future.failedFuture(new AssertionError(
                        "closeReactive() must propagate the startup failure, not swallow it. " +
                        "Expected a failed future but got succeeded."));
                }
                if (ar.cause() == null) {
                    return Future.failedFuture(new AssertionError("The failure cause must be present"));
                }
                logger.info("[propagates_startup_failure] PASSED: startup error propagated through closeReactive()");
                return Future.succeededFuture();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("closeReactive runs full cleanup chain even when propagating startup failure")
    void closeReactive_runs_cleanup_despite_propagating_failure(VertxTestContext testContext) {
        logger.info("[cleanup_despite_failure] Step 1: Setting system properties for empty database (no schema tables)");
        setSystemPropertiesFor(postgres);

        logger.info("[cleanup_despite_failure] Step 2: Creating PeeGeeQManager");
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test"), new SimpleMeterRegistry());

        // Fire-and-forget start — will fail (no tables)
        logger.info("[cleanup_despite_failure] Step 3: Fire-and-forget start() — will fail asynchronously (no schema tables)");
        logger.warn("===== INTENTIONAL ERROR TEST ===== The next ERROR log ('Failed to start PeeGeeQ Manager') is EXPECTED — this test deliberately uses an empty database with no schema tables");
        manager.start();

        // closeReactive must: (1) run all cleanup, (2) propagate the failure.
        // After the fix, even though closeReactive returns a failed future,
        // calling closeReactive again should not throw or hang — resources are cleaned up.
        logger.info("[cleanup_despite_failure] Step 4: First closeReactive() call — expecting cleanup + error propagation");
        manager.closeReactive()
            .transform(ar -> {
                logger.info("[cleanup_despite_failure] Step 5: First closeReactive() completed — failed={}, cause={}",
                        ar.failed(), ar.cause() != null ? ar.cause().getMessage() : "none");
                logger.info("[cleanup_despite_failure] Step 6: Second closeReactive() call — proving resources already cleaned up");
                return manager.closeReactive();
            })
            .transform(ar2 -> {
                logger.info("[cleanup_despite_failure] Step 7: Second closeReactive() completed — failed={}, cause={}",
                        ar2.failed(), ar2.cause() != null ? ar2.cause().getMessage() : "none");
                logger.info("[cleanup_despite_failure] PASSED: cleanup ran fully, second close did not hang");
                return Future.succeededFuture();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    // --- Helpers ---

    @SuppressWarnings("unchecked")
    private void setSystemPropertiesFor(PostgreSQLContainer container) {
        Properties props = new Properties();
        props.setProperty("peegeeq.database.host", container.getHost());
        props.setProperty("peegeeq.database.port", String.valueOf(container.getFirstMappedPort()));
        props.setProperty("peegeeq.database.name", container.getDatabaseName());
        props.setProperty("peegeeq.database.username", container.getUsername());
        props.setProperty("peegeeq.database.password", container.getPassword());
        props.setProperty("peegeeq.database.ssl.enabled", "false");
        props.setProperty("peegeeq.database.schema", "public");
        props.setProperty("peegeeq.database.pool.min-size", "1");
        props.setProperty("peegeeq.database.pool.max-size", "3");
        props.setProperty("peegeeq.database.pool.shared", "false");
        props.setProperty("peegeeq.database.pool.idle-timeout-ms", "2000");
        props.setProperty("peegeeq.database.pool.connection-timeout-ms", "5000");
        props.setProperty("peegeeq.health.check-interval", "PT5S");
        props.setProperty("peegeeq.metrics.reporting-interval", "PT10S");
        props.setProperty("peegeeq.migration.enabled", "false");
        props.setProperty("peegeeq.migration.auto-migrate", "false");
        props.forEach((k, v) -> System.setProperty(k.toString(), v.toString()));
    }

    private void clearSystemProperties() {
        System.getProperties().entrySet().removeIf(entry ->
                entry.getKey().toString().startsWith("peegeeq."));
    }
}
