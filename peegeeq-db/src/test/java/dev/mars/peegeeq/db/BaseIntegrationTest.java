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
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxTestContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.VertxExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.junit.jupiter.api.Tag;
import dev.mars.peegeeq.test.categories.TestCategories;

import java.util.UUID;

/**
 * Base class for integration tests that provides proper database connection management,
 * TestContainers lifecycle management, and resource cleanup.
 *
 * <p>This class uses {@link SharedPostgresTestExtension} to provide a SHARED PostgreSQL
 * container across ALL tests in the module, ensuring database schema is created ONCE
 * even with parallel test execution.</p>
 *
 * <p>Key features:</p>
 * <ul>
 *   <li>Single shared TestContainer across all tests (via extension)</li>
 *   <li>Database schema created once globally, thread-safe for parallel execution</li>
 *   <li>Tests can run in parallel without schema conflicts</li>
 *   <li>Proper resource cleanup after each test</li>
 * </ul>
 *
 * <p>Tests extending this class can safely run in parallel as configured in
 * junit-platform.properties.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 2.0
 */
@ExtendWith({SharedPostgresTestExtension.class, VertxExtension.class})
@Tag(TestCategories.INTEGRATION)
public abstract class BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(BaseIntegrationTest.class);

    protected PeeGeeQManager manager;
    protected PeeGeeQConfiguration configuration;
    protected String testProfile;

    /**
     * Get the shared PostgreSQL container from the extension.
     */
    protected PostgreSQLContainer getPostgres() {
        return SharedPostgresTestExtension.getContainer();
    }

    @BeforeEach
    protected void setUpBaseIntegration(VertxTestContext testContext) {
        // Generate unique test profile to avoid conflicts
        testProfile = "test-" + UUID.randomUUID().toString().substring(0, 8);

        logger.info("Setting up integration test with profile: {}", testProfile);

        // Set up test-specific configuration (non-database system properties)
        setupTestConfiguration();

        // Create configuration with explicit database settings from the shared container
        // to avoid System.setProperty race conditions under parallel execution.
        PostgreSQLContainer postgres = getPostgres();
        configuration = new PeeGeeQConfiguration(
                testProfile,
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getDatabaseName(),
                postgres.getUsername(),
                postgres.getPassword(),
                "public");
        manager = new PeeGeeQManager(configuration, new SimpleMeterRegistry());

        manager.start()
            .onSuccess(v -> {
                logger.info("PeeGeeQ Manager started successfully for profile: {}", testProfile);
                testContext.completeNow();
            })
            .onFailure(e -> {
                logger.error("Failed to start PeeGeeQ Manager for profile: {}", testProfile, e);
                PeeGeeQManager failedManager = manager;
                manager = null;
                if (failedManager != null) {
                    failedManager.closeReactive()
                        .onSuccess(ignored -> testContext.failNow(e))
                        .onFailure(ignored -> testContext.failNow(e));
                } else {
                    testContext.failNow(e);
                }
            });
    }
    
    @AfterEach
    void tearDownBaseIntegration(VertxTestContext testContext) {
        logger.info("Tearing down integration test for profile: {}", testProfile);

        // Capture Vertx reference before nulling manager we need it for the
        // belt-and-suspenders close below (Tier 2 fix for connection exhaustion).
        Vertx vertxRef = (manager != null) ? manager.getVertx() : null;
        PeeGeeQManager currentManager = manager;
        manager = null;

        // closeReactive() handles dead Vertx internally (step 7 catch).
        // No grace timer needed it serves no documented purpose and
        // throws RejectedExecutionException when the event loop is dead.
        Future<Void> closeManager = (currentManager != null)
            ? currentManager.closeReactive()
                .onSuccess(v -> logger.info("PeeGeeQ Manager closed successfully for profile: {}", testProfile))
                .onFailure(e -> logger.error("Error closing PeeGeeQ Manager for profile: {}", testProfile, e))
            : Future.succeededFuture();

        // Tier 2: Eagerly close Vert.x to guarantee TCP socket release before the
        // next test starts. closeReactive() step 7 attempts this, but the Vert.x
        // instance may already be closed (RejectedExecutionException). Use .eventually()
        // so this cleanup always runs without affecting the outcome of the chain.
        closeManager
            .eventually(() -> vertxRef != null
                ? vertxRef.close()
                    .onSuccess(ignored -> logger.info("Vert.x instance closed explicitly for profile: {}", testProfile))
                    .onFailure(e -> logger.debug("Vert.x close after manager shutdown (expected if already closed): {}", e.getMessage()))
                : Future.succeededFuture())
            .onSuccess(v -> {
                logger.info("Integration test teardown completed for profile: {}", testProfile);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }
    
    /**
     * Set up database connection properties from TestContainer
     */
    private void setupDatabaseProperties() {
        PostgreSQLContainer postgres = getPostgres();
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // CRITICAL: Disable migrations - tables are created once by SharedPostgresTestExtension
        // Running migrations on every test causes duplicate key violations with shared TestContainer
        System.setProperty("peegeeq.migration.enabled", "false");

        logger.debug("Database properties set: {}:{}/{} (migrations disabled)",
            postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());
    }
    
    /**
     * Set up test-specific configuration with conservative settings.
     *
     * <p>Pool size is kept small (max 3) to avoid exhausting PostgreSQL connections
     * when tests run in parallel. With 4 parallel test threads and multiple test classes,
     * connection usage can spike quickly.</p>
     */
    private void setupTestConfiguration() {
        // Use the keys the loader actually reads (millisecond longs, not Duration strings).
        // NOTE: PgConnectionManager truncates these to whole seconds via Duration.toSeconds(),
        // so values must be >= 1000 ms. A value < 1000 ms truncates to 0 = "no timeout".
        System.setProperty("peegeeq.database.pool.min-size", "1");  // needed for validation (max >= min)
        System.setProperty("peegeeq.database.pool.max-size", "3");
        System.setProperty("peegeeq.database.pool.connection-timeout-ms", "30000");   // 30 s - generous for parallel test runs
        System.setProperty("peegeeq.database.pool.idle-timeout-ms", "5000");          // 5 s

        // Force non-shared pools in tests so pool.close() drops the underlying connections
        // deterministically, without reference-counting deferral (see §3b).
        System.setProperty("peegeeq.database.pool.shared", "false");

        // Health check settings - faster for tests
        System.setProperty("peegeeq.health.check-interval", "PT10S");
        System.setProperty("peegeeq.health.timeout", "PT5S");

        // Metrics settings - faster for tests
        System.setProperty("peegeeq.metrics.reporting-interval", "PT30S");
        System.setProperty("peegeeq.metrics.enabled", "true");

        // Circuit breaker settings
        System.setProperty("peegeeq.circuit-breaker.enabled", "true");
        System.setProperty("peegeeq.circuit-breaker.failure-rate-threshold", "50.0");
        System.setProperty("peegeeq.circuit-breaker.minimum-number-of-calls", "3");

        // Migration settings - keep disabled because schema is created once by SharedPostgresTestExtension
        // Avoid enabling migrations here to prevent duplicate DDL when tests run in parallel
        System.setProperty("peegeeq.migration.enabled", "false");
        System.setProperty("peegeeq.migration.auto-migrate", "false");

        // Disable background dead consumer detection to prevent the PeeGeeQManager's
        // periodic job from interfering with tests that explicitly test dead consumer
        // detection. Without this, a background job from a parallel test's PeeGeeQManager
        // can mark subscriptions DEAD before the test's own detector call runs.
        System.setProperty("peegeeq.queue.dead-consumer-detection.enabled", "false");

        // Disable background consumer group retry job to prevent the PeeGeeQManager's
        // periodic job from resetting FAILED→PENDING rows during tests that assert on
        // FAILED status. Tests for retry behaviour create their own RetryService directly.
        System.setProperty("peegeeq.queue.consumer-group-retry.enabled", "false");

        logger.debug("Test configuration properties set");
    }
    
    /**
     * Get the test profile name for this test instance
     */
    protected String getTestProfile() {
        return testProfile;
    }

}

