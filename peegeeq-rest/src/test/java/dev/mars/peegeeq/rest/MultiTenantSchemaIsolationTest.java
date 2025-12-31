package dev.mars.peegeeq.rest;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the License, Version 2.0 (the "License");
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

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import java.util.List;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-tenant schema isolation tests for the REST API module.
 *
 * These tests verify that REST API endpoints properly isolate data between tenants
 * using schema-based multi-tenancy via the database setup API.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-22
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiTenantSchemaIsolationTest {

    private static final Logger logger = LoggerFactory.getLogger(MultiTenantSchemaIsolationTest.class);
    private static final int TEST_PORT = 18093;

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("multitenant_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    private String deploymentId;
    private WebClient client;
    private String setupIdTenantA;
    private String setupIdTenantB;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext testContext) {
        logger.info("========== SETUP STARTING ==========");

        // For REST API multi-tenant testing, we don't pre-initialize schemas
        // The DatabaseSetupService will create new databases and apply schema templates
        String schemaTenantA = "tenant_a";
        String schemaTenantB = "tenant_b";

        // Create web client
        client = WebClient.create(vertx);

        // Create setup service using PeeGeeQRuntime
        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();

        // Deploy single REST server that will manage multiple tenant setups
        RestServerConfig testConfig = new RestServerConfig(TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), List.of("*"));
        vertx.deployVerticle(new PeeGeeQRestServer(testConfig, setupService))
            .compose(id -> {
                deploymentId = id;
                logger.info("Deployed REST server on port {}", TEST_PORT);

                // Create database setup for Tenant A via REST API
                // Each tenant gets a NEW database with its own schema
                setupIdTenantA = "tenant-a-setup";
                String dbNameA = "tenant_a_db_" + System.currentTimeMillis();
                JsonObject setupRequestA = createSetupRequest(setupIdTenantA, schemaTenantA, dbNameA);
                return client.post(TEST_PORT, "localhost", "/api/v1/setups")
                        .timeout(30000) // Longer timeout for database creation
                        .sendJsonObject(setupRequestA);
            })
            .compose(response -> {
                testContext.verify(() -> {
                    assertEquals(201, response.statusCode());
                    logger.info("Created setup for Tenant A: {}", setupIdTenantA);
                });

                // Create database setup for Tenant B via REST API
                // Each tenant gets a NEW database with its own schema
                setupIdTenantB = "tenant-b-setup";
                String dbNameB = "tenant_b_db_" + System.currentTimeMillis();
                JsonObject setupRequestB = createSetupRequest(setupIdTenantB, schemaTenantB, dbNameB);
                return client.post(TEST_PORT, "localhost", "/api/v1/setups")
                        .timeout(30000) // Longer timeout for database creation
                        .sendJsonObject(setupRequestB);
            })
            .onSuccess(response -> testContext.verify(() -> {
                assertEquals(201, response.statusCode());
                logger.info("Created setup for Tenant B: {}", setupIdTenantB);
                logger.info("========== SETUP COMPLETED ==========");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    private JsonObject createSetupRequest(String setupId, String schema, String dbName) {
        // REST API multi-tenant pattern: Each tenant gets a NEW database
        // The DatabaseSetupService creates the database and applies schema templates
        // Each database uses its own schema for isolation

        // Create queue configurations for testing
        // NOTE: Queue names must be valid PostgreSQL identifiers (use underscores, not hyphens)
        JsonArray queues = new JsonArray()
                .add(new JsonObject().put("queueName", "test_queue"))
                .add(new JsonObject().put("queueName", "stats_queue"))
                .add(new JsonObject().put("queueName", "shared_queue_name"));

        return new JsonObject()
                .put("setupId", setupId)
                .put("databaseConfig", new JsonObject()
                        .put("host", postgres.getHost())
                        .put("port", postgres.getFirstMappedPort())
                        .put("databaseName", dbName)
                        .put("username", postgres.getUsername())
                        .put("password", postgres.getPassword())
                        .put("schema", schema)
                        .put("templateDatabase", "template0")
                        .put("encoding", "UTF8"))
                .put("queues", queues)
                .put("eventStores", new JsonArray());
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        logger.info("========== TEARDOWN STARTING ==========");

        if (client != null) {
            client.close();
        }

        // Undeploy REST server
        if (deploymentId != null) {
            vertx.undeploy(deploymentId)
                .onComplete(ar -> {
                    logger.info("========== TEARDOWN COMPLETED ==========");
                    testContext.completeNow();
                });
        } else {
            logger.info("========== TEARDOWN COMPLETED ==========");
            testContext.completeNow();
        }
    }

    @Test
    void testMessageIsolationBetweenTenants(Vertx vertx, VertxTestContext testContext) {
        logger.info("========== TEST: testMessageIsolationBetweenTenants ==========");

        String queueName = "test_queue";

        // Tenant A sends a message
        JsonObject messageA = new JsonObject()
                .put("payload", "tenant-a-message")
                .put("headers", new JsonObject().put("source", "tenant-a"));

        client.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupIdTenantA + "/" + queueName + "/messages")
                .timeout(5000)
                .sendJsonObject(messageA)
                .compose(response -> {
                    testContext.verify(() -> {
                        assertEquals(200, response.statusCode());
                        logger.info("Tenant A sent message successfully");
                    });

                    // Check Tenant B's queue stats - should have 0 messages (isolation verified)
                    return client.get(TEST_PORT, "localhost", "/api/v1/queues/" + setupIdTenantB + "/" + queueName + "/stats")
                            .timeout(5000)
                            .send();
                })
                .compose(response -> {
                    testContext.verify(() -> {
                        assertEquals(200, response.statusCode());
                        JsonObject stats = response.bodyAsJsonObject();
                        int totalMessages = stats.getInteger("totalMessages", 0);
                        assertEquals(0, totalMessages, "Tenant B should have 0 messages (isolation verified)");
                        logger.info("Tenant B has 0 messages (isolation working)");
                    });

                    // Check Tenant A's queue stats - should have 1 message
                    return client.get(TEST_PORT, "localhost", "/api/v1/queues/" + setupIdTenantA + "/" + queueName + "/stats")
                            .timeout(5000)
                            .send();
                })
                .onSuccess(response -> testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    JsonObject stats = response.bodyAsJsonObject();
                    int totalMessages = stats.getInteger("totalMessages", 0);
                    assertEquals(1, totalMessages, "Tenant A should have 1 message");
                    logger.info("✅ Message isolation verified - Tenant A has 1 message, Tenant B has 0");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testStatsIsolationBetweenTenants(Vertx vertx, VertxTestContext testContext) {
        logger.info("========== TEST: testStatsIsolationBetweenTenants ==========");

        String queueName = "stats_queue";

        // Tenant A sends 3 messages
        JsonObject messageA = new JsonObject()
                .put("payload", "tenant-a-message");

        client.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupIdTenantA + "/" + queueName + "/messages")
                .timeout(5000)
                .sendJsonObject(messageA)
                .compose(r1 -> client.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupIdTenantA + "/" + queueName + "/messages")
                        .timeout(5000)
                        .sendJsonObject(messageA))
                .compose(r2 -> client.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupIdTenantA + "/" + queueName + "/messages")
                        .timeout(5000)
                        .sendJsonObject(messageA))
                .compose(r3 -> {
                    logger.info("Tenant A sent 3 messages");

                    // Tenant B sends 2 messages
                    JsonObject messageB = new JsonObject()
                            .put("payload", "tenant-b-message");

                    return client.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupIdTenantB + "/" + queueName + "/messages")
                            .timeout(5000)
                            .sendJsonObject(messageB);
                })
                .compose(r1 -> client.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupIdTenantB + "/" + queueName + "/messages")
                        .timeout(5000)
                        .sendJsonObject(new JsonObject().put("payload", "tenant-b-message")))
                .compose(r2 -> {
                    logger.info("Tenant B sent 2 messages");

                    // Get stats for Tenant A
                    return client.get(TEST_PORT, "localhost", "/api/v1/queues/" + setupIdTenantA + "/" + queueName + "/stats")
                            .timeout(5000)
                            .send();
                })
                .compose(response -> {
                    testContext.verify(() -> {
                        assertEquals(200, response.statusCode());
                        JsonObject stats = response.bodyAsJsonObject();
                        assertEquals(3, stats.getInteger("totalMessages"), "Tenant A should see 3 messages");
                        logger.info("Tenant A stats: {}", stats.encode());
                    });

                    // Get stats for Tenant B
                    return client.get(TEST_PORT, "localhost", "/api/v1/queues/" + setupIdTenantB + "/" + queueName + "/stats")
                            .timeout(5000)
                            .send();
                })
                .onSuccess(response -> testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    JsonObject stats = response.bodyAsJsonObject();
                    assertEquals(2, stats.getInteger("totalMessages"), "Tenant B should see 2 messages");
                    logger.info("Tenant B stats: {}", stats.encode());
                    logger.info("✅ Stats isolation verified");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testSameQueueNameAcrossTenants(Vertx vertx, VertxTestContext testContext) {
        logger.info("========== TEST: testSameQueueNameAcrossTenants ==========");

        String sharedQueueName = "shared_queue_name";

        // Both tenants send messages to the same queue name
        JsonObject messageA = new JsonObject()
                .put("payload", "tenant-a-data");
        JsonObject messageB = new JsonObject()
                .put("payload", "tenant-b-data");

        client.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupIdTenantA + "/" + sharedQueueName + "/messages")
                .timeout(5000)
                .sendJsonObject(messageA)
                .compose(r1 -> client.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupIdTenantB + "/" + sharedQueueName + "/messages")
                        .timeout(5000)
                        .sendJsonObject(messageB))
                .compose(r2 -> {
                    logger.info("Both tenants sent messages to queue: {}", sharedQueueName);

                    // Check Tenant A's stats - should have 1 message
                    return client.get(TEST_PORT, "localhost", "/api/v1/queues/" + setupIdTenantA + "/" + sharedQueueName + "/stats")
                            .timeout(5000)
                            .send();
                })
                .compose(response -> {
                    testContext.verify(() -> {
                        assertEquals(200, response.statusCode());
                        JsonObject stats = response.bodyAsJsonObject();
                        int totalMessages = stats.getInteger("totalMessages", 0);
                        assertEquals(1, totalMessages, "Tenant A should have exactly 1 message");
                        logger.info("Tenant A has 1 message in queue '{}'", sharedQueueName);
                    });

                    // Check Tenant B's stats - should have 1 message
                    return client.get(TEST_PORT, "localhost", "/api/v1/queues/" + setupIdTenantB + "/" + sharedQueueName + "/stats")
                            .timeout(5000)
                            .send();
                })
                .onSuccess(response -> testContext.verify(() -> {
                    assertEquals(200, response.statusCode());
                    JsonObject stats = response.bodyAsJsonObject();
                    int totalMessages = stats.getInteger("totalMessages", 0);
                    assertEquals(1, totalMessages, "Tenant B should have exactly 1 message");
                    logger.info("Tenant B has 1 message in queue '{}'", sharedQueueName);
                    logger.info("✅ Same queue name isolation verified - both tenants have separate queues");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
}


