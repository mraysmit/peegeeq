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
package dev.mars.peegeeq.integration;

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.base.BaseConfigurableTest;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Base class for E2E smoke tests.
 * 
 * Provides:
 * - PostgreSQL TestContainer
 * - REST server lifecycle management
 * - WebClient for HTTP requests
 * - Common test utilities
 */
@Tag(TestCategories.SMOKE)
@Testcontainers
public abstract class SmokeTestBase extends BaseConfigurableTest {

    protected static final Logger logger = LoggerFactory.getLogger(SmokeTestBase.class);

    protected static WebClient webClient;
    protected static String deploymentId;
    protected static DatabaseSetupService setupService;

    @Container
    protected static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_smoke_test")
            .withUsername("postgres")
            .withPassword("postgres");

    @BeforeAll
    static void startServer() throws Exception {
        logger.info("Starting smoke test infrastructure...");

        // Initialize Vert.x and load configuration
        setupVertxAndConfig("smoke-test-config.json");

        JsonObject serverConfig = testConfig.getJsonObject("server");
        JsonObject clientConfig = testConfig.getJsonObject("client");

        webClient = WebClient.create(vertx, new WebClientOptions()
                .setDefaultHost(serverConfig.getString("host"))
                .setDefaultPort(serverConfig.getInteger("port"))
                .setConnectTimeout(clientConfig.getInteger("timeout")));

        // Create the setup service using PeeGeeQRuntime - handles all wiring internally
        setupService = PeeGeeQRuntime.createDatabaseSetupService();

        CountDownLatch latch = new CountDownLatch(1);
        final Throwable[] error = new Throwable[1];

        // Create REST server with proper configuration object
        RestServerConfig config = new RestServerConfig(
                serverConfig.getInteger("port"), 
                RestServerConfig.MonitoringConfig.defaults(),
                serverConfig.getJsonArray("cors").getList());

        vertx.deployVerticle(new PeeGeeQRestServer(config, setupService))
                .onSuccess(id -> {
                    deploymentId = id;
                    logger.info("REST server deployed on port {}", config.port());
                    latch.countDown();
                })
                .onFailure(err -> {
                    logger.error("Failed to deploy REST server", err);
                    error[0] = err;
                    latch.countDown();
                });

        if (!latch.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout waiting for REST server to start");
        }

        if (error[0] != null) {
            throw new RuntimeException("Failed to start REST server", error[0]);
        }

        logger.info("Smoke test infrastructure ready");
    }

    @AfterAll
    static void stopServer() throws Exception {
        logger.info("Stopping smoke test infrastructure...");

        // Cleanup all setups to stop their managers (and internal Vert.x instances)
        if (setupService != null) {
            try {
                java.util.Set<String> setupIds = setupService.getAllActiveSetupIds().get(10, TimeUnit.SECONDS);
                if (setupIds != null && !setupIds.isEmpty()) {
                    logger.info("Cleaning up {} active setups: {}", setupIds.size(), setupIds);
                    CountDownLatch cleanupLatch = new CountDownLatch(setupIds.size());
                    for (String setupId : setupIds) {
                        setupService.destroySetup(setupId)
                            .whenComplete((v, e) -> {
                                if (e != null) logger.warn("Failed to destroy setup " + setupId, e);
                                cleanupLatch.countDown();
                            });
                    }
                    if (!cleanupLatch.await(30, TimeUnit.SECONDS)) {
                        logger.warn("Timeout waiting for setups to be destroyed");
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to cleanup setups", e);
            }
        }

        if (deploymentId != null && vertx != null) {
            CountDownLatch latch = new CountDownLatch(1);
            vertx.undeploy(deploymentId)
                    .onComplete(ar -> latch.countDown());
            latch.await(10, TimeUnit.SECONDS);
        }

        if (webClient != null) {
            webClient.close();
        }

        if (vertx != null) {
            CountDownLatch latch = new CountDownLatch(1);
            vertx.close().onComplete(ar -> latch.countDown());
            latch.await(10, TimeUnit.SECONDS);
            vertx = null;
        }

        logger.info("Smoke test infrastructure stopped");
    }

    protected String getApiBaseUrl() {
        JsonObject serverConfig = testConfig.getJsonObject("server");
        return "http://" + serverConfig.getString("host") + ":" + serverConfig.getInteger("port");
    }

    protected String getPostgresHost() {
        return postgres.getHost();
    }

    protected int getPostgresPort() {
        return postgres.getMappedPort(5432);
    }

    protected String getPostgresDatabase() {
        return postgres.getDatabaseName();
    }

    protected String getPostgresUsername() {
        return postgres.getUsername();
    }

    protected String getPostgresPassword() {
        return postgres.getPassword();
    }

    protected String generateSetupId() {
        return "smoke-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected JsonObject createDatabaseSetupRequest(String setupId, String queueName) {
        return new JsonObject()
                .put("setupId", setupId)
                .put("databaseConfig", new JsonObject()
                        .put("host", getPostgresHost())
                        .put("port", getPostgresPort())
                        .put("databaseName", "smoke_db_" + setupId.replace("-", "_"))
                        .put("username", getPostgresUsername())
                        .put("password", getPostgresPassword())
                        .put("schema", "public")
                        .put("templateDatabase", "template0")
                        .put("encoding", "UTF8"))
                .put("queues", new io.vertx.core.json.JsonArray()
                        .add(new JsonObject()
                                .put("queueName", queueName)
                                .put("maxRetries", 3)
                                .put("visibilityTimeoutSeconds", 30)))
                .put("eventStores", new io.vertx.core.json.JsonArray());
    }
}
