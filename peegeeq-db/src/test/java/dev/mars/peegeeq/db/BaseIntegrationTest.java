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

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
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

import java.util.Properties;
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

        // Build isolated per-test configuration  no System.setProperty writes.
        // The schema is the explicit shared-suite constant: SharedPostgresTestExtension
        // creates the DDL in the same schema from the same constant.
        PostgreSQLContainer postgres = getPostgres();
        Properties props = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.database.pool.min-size", "1")
                .property("peegeeq.database.pool.max-size", "3")
                .property("peegeeq.database.pool.connection-timeout-ms", "30000")
                .property("peegeeq.database.pool.idle-timeout-ms", "5000")
                .property("peegeeq.database.pool.shared", "false")
                .property("peegeeq.health.check-interval", "PT10S")
                .property("peegeeq.health.timeout", "PT5S")
                .property("peegeeq.metrics.reporting-interval", "PT30S")
                .property("peegeeq.metrics.enabled", "true")
                .property("peegeeq.circuit-breaker.enabled", "true")
                .property("peegeeq.circuit-breaker.failure-rate-threshold", "50.0")
                .property("peegeeq.circuit-breaker.minimum-number-of-calls", "3")
                .property("peegeeq.migration.enabled", "false")
                .property("peegeeq.migration.auto-migrate", "false")
                .property("peegeeq.queue.dead-consumer-detection.enabled", "false")
                .property("peegeeq.queue.consumer-group-retry.enabled", "false")
                .build();
        configuration = new PeeGeeQConfiguration(testProfile, props);
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
     * Get the test profile name for this test instance
     */
    protected String getTestProfile() {
        return testProfile;
    }

}

