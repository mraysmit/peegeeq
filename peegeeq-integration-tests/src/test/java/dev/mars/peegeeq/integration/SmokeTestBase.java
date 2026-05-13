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
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

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
    protected static PeeGeeQRestServer restServer;
    protected static int actualServerPort;

    @Container
    protected static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    @BeforeAll
    static void startServer(Vertx injectedVertx, VertxTestContext testContext) {
        logger.info("Starting smoke test infrastructure...");

        // Use the VertxExtension-managed Vertx and load configuration synchronously
        vertx = injectedVertx;
        try (InputStream stream = SmokeTestBase.class.getClassLoader()
                .getResourceAsStream("smoke-test-config.json")) {
            if (stream == null) {
                testContext.failNow(new IllegalStateException("smoke-test-config.json not found in classpath"));
                return;
            }
            testConfig = new JsonObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            testContext.failNow(e);
            return;
        }

        JsonObject serverConfig = testConfig.getJsonObject("server");
        JsonObject clientConfig = testConfig.getJsonObject("client");

        setupService = PeeGeeQRuntime.createDatabaseSetupService();

        // Port 0 lets the OS assign a free ephemeral port, avoiding conflicts
        // when multiple test classes run in parallel.
        RestServerConfig config = new RestServerConfig(
                0,
                RestServerConfig.MonitoringConfig.defaults(),
                serverConfig.getJsonArray("cors").getList());

        restServer = new PeeGeeQRestServer(config, setupService);
        vertx.deployVerticle(restServer)
                .onSuccess(id -> {
                    deploymentId = id;
                    actualServerPort = restServer.actualPort();
                    webClient = WebClient.create(vertx, new WebClientOptions()
                            .setDefaultHost(serverConfig.getString("host"))
                            .setDefaultPort(actualServerPort)
                            .setConnectTimeout(clientConfig.getInteger("timeout")));
                    logger.info("REST server deployed on port {}", actualServerPort);
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @AfterAll
    static void stopServer(VertxTestContext testContext) {
        logger.info("Stopping smoke test infrastructure...");

        if (webClient != null) {
            webClient.close();
            webClient = null;
        }

        Future<Void> undeploy = deploymentId != null
                ? vertx.undeploy(deploymentId).mapEmpty()
                : Future.succeededFuture();

        undeploy
                .compose(v -> setupService != null ? setupService.close() : Future.<Void>succeededFuture())
                .onSuccess(v -> {
                    setupService = null;
                    deploymentId = null;
                    logger.info("Smoke test infrastructure stopped");
                    testContext.completeNow();
                })
                .onFailure(err -> {
                    logger.warn("Error during smoke test cleanup: {}", err.getMessage());
                    testContext.failNow(err);
                });
    }

    protected String getApiBaseUrl() {
        JsonObject serverConfig = testConfig.getJsonObject("server");
        return "http://" + serverConfig.getString("host") + ":" + actualServerPort;
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

    protected Future<Void> cleanupSetupStrict(String setupId) {
        Path logPath = Path.of("logs", "smoke-tests.log");
        long logSizeBeforeDelete = getLogSize(logPath);

        return webClient.delete("/api/v1/setups/" + setupId)
                .send()
                .compose(response -> {
                    int statusCode = response.statusCode();
                    if (statusCode != 204 && statusCode != 200) {
                        return Future.failedFuture(new AssertionError(
                                "Expected setup cleanup status 204/200 for " + setupId + " but got " + statusCode
                                        + " with body: " + response.bodyAsString()));
                    }

                    try {
                        assertNoBlockingLifecycleViolations(setupId, logPath, logSizeBeforeDelete);
                        logger.info("Setup deleted cleanly: {}", setupId);
                        return Future.succeededFuture();
                    } catch (AssertionError e) {
                        return Future.failedFuture(e);
                    }
                });
    }

    private void assertNoBlockingLifecycleViolations(String setupId, Path logPath, long offset) {
        if (!Files.exists(logPath)) {
            logger.warn("Smoke test log file not found at {} while checking setup {}", logPath, setupId);
            return;
        }

        final String newLogContent;
        try {
            String fullLogContent = Files.readString(logPath);
            int safeOffset = (int) Math.max(0L, Math.min(offset, fullLogContent.length()));
            newLogContent = fullLogContent.substring(safeOffset);
        } catch (IOException e) {
            throw new AssertionError("Failed to read smoke test log for setup " + setupId + ": " + e.getMessage(), e);
        }

        String[] forbiddenPatterns = {
                "Do not call blocking stop() on event-loop thread",
                "Do not call blocking close() on event-loop thread"
        };

        for (String pattern : forbiddenPatterns) {
            if (newLogContent.contains(setupId) && newLogContent.contains(pattern)) {
                throw new AssertionError(
                        "Detected lifecycle violation for setup " + setupId + ": " + pattern);
            }
        }
    }

    private long getLogSize(Path logPath) {
        if (!Files.exists(logPath)) {
            return 0L;
        }
        try {
            return Files.readString(logPath).length();
        } catch (IOException e) {
            logger.warn("Failed to read log size for {}", logPath, e);
            return 0L;
        }
    }
}
