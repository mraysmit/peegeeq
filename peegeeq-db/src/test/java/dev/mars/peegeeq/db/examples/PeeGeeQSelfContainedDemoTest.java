package dev.mars.peegeeq.db.examples;

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

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.SharedPostgresTestExtension;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.health.HealthCheckManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test for PeeGeeQSelfContainedDemo functionality.
 *
 * This test validates self-contained demo patterns:
 * 1. Self-Contained Setup - Automatic container management and configuration
 * 2. Feature Demonstrations - All PeeGeeQ capabilities in demo environment
 * 3. System Monitoring - Health checks, metrics, and system status
 * 4. Container Lifecycle - Proper startup, configuration, and cleanup
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith({SharedPostgresTestExtension.class, VertxExtension.class})
public class PeeGeeQSelfContainedDemoTest {

    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQSelfContainedDemoTest.class);

    private PeeGeeQManager manager;

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        if (manager != null) {
            manager.closeReactive()
                .recover(t -> Future.succeededFuture())
                .onComplete(v -> testContext.completeNow());
        } else {
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    private PeeGeeQManager createManager() {
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("demo",
            postgres.getHost(),
            postgres.getFirstMappedPort(),
            postgres.getDatabaseName(),
            postgres.getUsername(),
            postgres.getPassword(),
            "public");
        return new PeeGeeQManager(config, new SimpleMeterRegistry());
    }

    @Test
    void testSelfContainedSetup(VertxTestContext testContext) throws InterruptedException {
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        assertTrue(postgres.isRunning(), "PostgreSQL container should be running");
        assertEquals("peegeeq_test", postgres.getDatabaseName());
        assertEquals("peegeeq_test", postgres.getUsername());
        assertEquals("peegeeq_test", postgres.getPassword());

        manager = createManager();
        manager.start()
            .onSuccess(v -> testContext.verify(() -> {
                assertTrue(manager.isStarted(), "PeeGeeQ Manager should be started");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testFeatureDemonstrations(VertxTestContext testContext) throws InterruptedException {
        manager = createManager();
        manager.start()
            .compose(v -> {
                // Synchronous configuration checks
                PeeGeeQConfiguration config = manager.getConfiguration();
                assertNotNull(config, "Configuration should not be null");
                assertEquals("demo", config.getProfile());
                assertNotNull(config.getDatabaseConfig());
                assertNotNull(config.getPoolConfig());
                assertNotNull(config.getMetricsConfig());

                // Health check manager check
                HealthCheckManager healthCheckManager = manager.getHealthCheckManager();
                assertNotNull(healthCheckManager, "Health check manager should not be null");

                // Allow health checks to complete before asserting
                return manager.getVertx().timer(1000).mapEmpty();
            })
            .compose(v -> {
                assertTrue(manager.isHealthy(), "System should be healthy");
                return manager.getSystemStatus();
            })
            .onSuccess(systemStatus -> testContext.verify(() -> {
                assertNotNull(systemStatus, "System status should not be null");
                assertNotNull(systemStatus.getMetricsSummary(), "Metrics summary should not be null");
                assertTrue(systemStatus.isStarted(), "System should be started");
                assertEquals("demo", systemStatus.getProfile());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testSystemMonitoring(VertxTestContext testContext) throws InterruptedException {
        manager = createManager();
        manager.start()
            .compose(v -> manager.getVertx().timer(1000).mapEmpty())
            .compose(v -> manager.getSystemStatus())
            .onSuccess(status -> testContext.verify(() -> {
                assertNotNull(status, "System status should be available");
                assertTrue(manager.isHealthy(), "System should be healthy");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testContainerLifecycle() {
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        assertTrue(postgres.isRunning(), "Container should be running");
        assertNotNull(postgres.getJdbcUrl(), "JDBC URL should be available");
        assertTrue(postgres.getFirstMappedPort() > 0, "Port should be mapped");
    }
}


