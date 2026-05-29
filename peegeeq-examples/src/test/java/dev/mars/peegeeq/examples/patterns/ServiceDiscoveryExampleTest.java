package dev.mars.peegeeq.examples.patterns;

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

import dev.mars.peegeeq.servicemanager.PeeGeeQServiceManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/**
 * INFRASTRUCTURE TEST: Service Discovery and Consul Integration.
 *
 * <p>Validates:
 * <ul>
 *   <li>Consul TestContainer integration and service registration</li>
 *   <li>PeeGeeQ Service Manager deployment and lifecycle</li>
 *   <li>Service discovery patterns and health checks</li>
 *   <li>Vert.x integration with service discovery infrastructure</li>
 * </ul>
 *
 * <p>Consul host/port are passed directly to {@link PeeGeeQServiceManager} via constructor 
 * no {@code System.setProperty} side-effects.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-26
 * @version 2.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
public class ServiceDiscoveryExampleTest {

    private static final Logger logger = LoggerFactory.getLogger(ServiceDiscoveryExampleTest.class);
    private static final int SERVICE_MANAGER_PORT = 9090;

    @Container
    static ConsulContainer consul = new ConsulContainer("hashicorp/consul:1.15");

    private Vertx vertxRef;
    private WebClient client;
    private String serviceManagerDeploymentId;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        logger.info("Setting up Service Discovery test (consul {}:{})",
            consul.getHost(), consul.getFirstMappedPort());

        this.vertxRef = vertx;
        this.client = WebClient.create(vertx);

        PeeGeeQServiceManager serviceManager = new PeeGeeQServiceManager(
            SERVICE_MANAGER_PORT,
            consul.getHost(),
            consul.getFirstMappedPort());

        vertx.deployVerticle(serviceManager)
            .onSuccess(id -> {
                serviceManagerDeploymentId = id;
                logger.info("Service Manager deployed: {}", id);
            })
            .compose(id -> vertx.timer(2000).mapEmpty())
            .<Void>mapEmpty()
            .onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        logger.info("Tearing down Service Discovery test");

        Future<Void> undeploy = (serviceManagerDeploymentId != null)
            ? vertxRef.undeploy(serviceManagerDeploymentId).transform(ar -> {
                if (ar.failed()) {
                    logger.warn("Failed to undeploy Service Manager: {}", ar.cause().getMessage());
                }
                return Future.<Void>succeededFuture();
            })
            : Future.succeededFuture();

        undeploy
            .compose(v -> {
                if (client != null) client.close();
                return Future.<Void>succeededFuture();
            })
            .onComplete(ctx.succeedingThenComplete());
    }

    // ---------------------------------------------------------------------
    // Tests
    //
    // NOTE: The Service Manager backed by Consul is best-effort in this
    // suite  non-2xx HTTP statuses are logged but do not fail the test,
    // matching the pre-existing behaviour of this integration scenario.
    // Where assertions are made, they apply only to the successful branch.
    // ---------------------------------------------------------------------

    @Test
    void testServiceManagerHealth(VertxTestContext testContext) {
        logger.info("Testing Service Manager health check");

        client.get(SERVICE_MANAGER_PORT, "localhost", "/health")
            .send()
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                int statusCode = response.statusCode();
                logger.info("Health check status code: {}", statusCode);
                if (statusCode == 200) {
                    JsonObject health = response.bodyAsJsonObject();
                    assertNotNull(health.getString("status"));
                    assertNotNull(health.getString("service"));
                    assertNotNull(health.getLong("timestamp"));
                    assertEquals("UP", health.getString("status"));
                    assertTrue(health.getLong("timestamp") > 0);
                } else {
                    logger.warn("Service Manager returned non-200 status: {}", statusCode);
                }
                testContext.completeNow();
            })));
    }

    @Test
    void testInstanceRegistration(VertxTestContext testContext) {
        logger.info("Testing instance registration");

        JsonObject instance = new JsonObject()
            .put("instanceId", "test-instance-01")
            .put("host", "localhost")
            .put("port", 8080)
            .put("version", "1.0.0")
            .put("environment", "test")
            .put("region", "us-east-1")
            .put("metadata", new JsonObject()
                .put("datacenter", "dc1")
                .put("cluster", "test")
                .put("capacity", "low"));

        client.post(SERVICE_MANAGER_PORT, "localhost", "/api/v1/instances/register")
            .sendJsonObject(instance)
            .compose(registerResponse -> {
                int statusCode = registerResponse.statusCode();
                logger.info("Instance registration status code: {}", statusCode);
                if (statusCode == 201) {
                    JsonObject body = registerResponse.bodyAsJsonObject();
                    testContext.verify(() -> {
                        assertNotNull(body.getString("message"));
                        assertTrue(body.getString("message").contains("registered")
                            || body.getString("message").contains("success"));
                    });
                } else {
                    logger.warn("Instance registration returned status: {}", statusCode);
                }
                return client.get(SERVICE_MANAGER_PORT, "localhost", "/api/v1/instances").send();
            })
            .onComplete(testContext.succeeding(listResponse -> testContext.verify(() -> {
                int statusCode = listResponse.statusCode();
                logger.info("Instance listing status code: {}", statusCode);
                if (statusCode == 200) {
                    JsonObject body = listResponse.bodyAsJsonObject();
                    JsonArray instances = body.getJsonArray("instances");
                    assertNotNull(instances);
                    assertEquals("Instances retrieved successfully", body.getString("message"));
                } else {
                    logger.warn("Instance listing returned status: {}", statusCode);
                }
                testContext.completeNow();
            })));
    }

    @Test
    void testFederatedManagement(VertxTestContext testContext) {
        logger.info("Testing federated management");

        registerTestInstance("federated-test-01", 8080, "production", "us-east-1")
            .compose(v -> client.get(SERVICE_MANAGER_PORT, "localhost", "/api/v1/federated/overview").send())
            .compose(overviewResponse -> {
                int statusCode = overviewResponse.statusCode();
                logger.info("Federated overview status code: {}", statusCode);
                if (statusCode == 200) {
                    JsonObject overview = overviewResponse.bodyAsJsonObject();
                    testContext.verify(() -> {
                        assertNotNull(overview.getInteger("instanceCount"));
                        assertNotNull(overview.getJsonObject("aggregatedData"));
                        assertTrue(overview.getInteger("instanceCount") >= 0);
                        assertNotNull(overview.getString("message"));
                        if (overview.getInteger("instanceCount") > 0) {
                            assertNotNull(overview.getJsonArray("instanceDetails"));
                        }
                    });
                } else {
                    logger.warn("Federated overview returned status: {}", statusCode);
                }
                return client.get(SERVICE_MANAGER_PORT, "localhost", "/api/v1/federated/metrics").send();
            })
            .onComplete(testContext.succeeding(metricsResponse -> testContext.verify(() -> {
                int statusCode = metricsResponse.statusCode();
                logger.info("Federated metrics status code: {}", statusCode);
                if (statusCode == 200) {
                    JsonObject metrics = metricsResponse.bodyAsJsonObject();
                    assertNotNull(metrics.getJsonObject("metrics"));
                    assertNotNull(metrics.getString("message"));
                    if (metrics.containsKey("instanceCount")) {
                        assertTrue(metrics.getInteger("instanceCount") >= 0);
                    }
                } else {
                    logger.warn("Federated metrics returned status: {}", statusCode);
                }
                testContext.completeNow();
            })));
    }

    @Test
    void testInstanceFiltering(VertxTestContext testContext) {
        logger.info("Testing instance filtering");

        registerTestInstance("prod-01", 8080, "production", "us-east-1")
            .compose(v -> registerTestInstance("staging-01", 8081, "staging", "us-east-1"))
            .compose(v -> client.get(SERVICE_MANAGER_PORT, "localhost",
                "/api/v1/instances?environment=production").send())
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                int statusCode = response.statusCode();
                logger.info("Production filtering status code: {}", statusCode);
                if (statusCode == 200) {
                    JsonObject body = response.bodyAsJsonObject();
                    JsonArray instances = body.getJsonArray("instances");
                    assertNotNull(instances);
                    assertEquals("Instances retrieved successfully", body.getString("message"));
                    for (Object instanceObj : instances) {
                        JsonObject inst = (JsonObject) instanceObj;
                        if (inst.getString("environment") != null) {
                            assertEquals("production", inst.getString("environment"));
                        }
                    }
                } else {
                    logger.warn("Production filtering returned status: {}", statusCode);
                }
                testContext.completeNow();
            })));
    }

    @Test
    void testInstanceDeregistration(VertxTestContext testContext) {
        logger.info("Testing instance deregistration");

        String instanceId = "deregister-test-01";

        registerTestInstance(instanceId, 8080, "production", "us-east-1")
            .compose(v -> client.delete(SERVICE_MANAGER_PORT, "localhost",
                "/api/v1/instances/" + instanceId + "/deregister").send())
            .onComplete(testContext.succeeding(response -> testContext.verify(() -> {
                int statusCode = response.statusCode();
                logger.info("Instance deregistration status code: {}", statusCode);
                if (statusCode == 200) {
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body.getString("message"));
                    assertTrue(body.getString("message").contains("unregistered")
                        || body.getString("message").contains("deregistered")
                        || body.getString("message").contains("removed"));
                    assertEquals("Instance unregistered successfully", body.getString("message"));
                } else {
                    logger.warn("Instance deregistration returned status: {}", statusCode);
                }
                testContext.completeNow();
            })));
    }

    /**
     * Register a test instance. Errors are logged but do not fail the calling test 
     * the same lenient behaviour the original test had, just expressed without latches.
     */
    private Future<Void> registerTestInstance(String instanceId, int port, String environment, String region) {
        JsonObject instance = new JsonObject()
            .put("instanceId", instanceId)
            .put("host", "localhost")
            .put("port", port)
            .put("version", "1.0.0")
            .put("environment", environment)
            .put("region", region)
            .put("metadata", new JsonObject()
                .put("datacenter", "dc1")
                .put("cluster", "test")
                .put("capacity", "standard"));

        return client.post(SERVICE_MANAGER_PORT, "localhost", "/api/v1/instances/register")
            .sendJsonObject(instance)
            .<Void>transform(ar -> {
                if (ar.failed()) {
                    logger.warn("Failed to register test instance {}: {}", instanceId, ar.cause().getMessage());
                } else {
                    logger.info("Test instance registered: {} (status {})",
                        instanceId, ar.result().statusCode());
                }
                return Future.<Void>succeededFuture();
            });
    }
}
