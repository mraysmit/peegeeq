package dev.mars.peegeeq.examples;

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
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Example demonstrating PeeGeeQ Service Discovery and Management capabilities.
 * 
 * This example shows:
 * - Service Manager startup and configuration
 * - Instance registration and management
 * - Federated management across multiple instances
 * - Health monitoring and status checking
 * - Load balancing and failover scenarios
 * 
 * Note: This example runs without external dependencies like Consul for simplicity.
 * For production deployments, Consul integration is recommended.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-26
 * @version 1.0
 */
public class ServiceDiscoveryExample {
    
    private static final Logger logger = LoggerFactory.getLogger(ServiceDiscoveryExample.class);
    private static final int SERVICE_MANAGER_PORT = 9090;
    
    public static void main(String[] args) throws Exception {
        logger.info("=== PeeGeeQ Service Discovery Example ===");
        logger.info("This example demonstrates service discovery without external dependencies");
        
        Vertx vertx = Vertx.vertx();
        WebClient client = WebClient.create(vertx);
        
        try {
            // Start Service Manager
            String deploymentId = startServiceManager(vertx);
            
            // Wait for service to be ready
            Thread.sleep(3000);
            
            // Run service discovery demonstrations
            demonstrateServiceManagerHealth(client);
            demonstrateInstanceRegistration(client);
            demonstrateFederatedManagement(client);
            demonstrateInstanceManagement(client);
            
            logger.info("Service Discovery Example completed successfully!");
            
            // Clean up
            if (deploymentId != null) {
                CountDownLatch undeployLatch = new CountDownLatch(1);
                vertx.undeploy(deploymentId, result -> {
                    if (result.succeeded()) {
                        logger.info("✅ Service Manager undeployed");
                    } else {
                        logger.error("❌ Failed to undeploy Service Manager", result.cause());
                    }
                    undeployLatch.countDown();
                });
                undeployLatch.await(10, TimeUnit.SECONDS);
            }
            
        } finally {
            client.close();
            vertx.close();
        }
    }
    
    /**
     * Starts the PeeGeeQ Service Manager.
     */
    private static String startServiceManager(Vertx vertx) throws Exception {
        logger.info("Starting PeeGeeQ Service Manager on port {}", SERVICE_MANAGER_PORT);
        
        CountDownLatch deployLatch = new CountDownLatch(1);
        final String[] deploymentId = new String[1];
        
        vertx.deployVerticle(new PeeGeeQServiceManager(SERVICE_MANAGER_PORT), result -> {
            if (result.succeeded()) {
                deploymentId[0] = result.result();
                logger.info("✅ PeeGeeQ Service Manager started successfully");
                deployLatch.countDown();
            } else {
                logger.error("❌ Failed to start Service Manager", result.cause());
                deployLatch.countDown(); // Continue even if failed for demo purposes
            }
        });
        
        if (!deployLatch.await(15, TimeUnit.SECONDS)) {
            logger.warn("Service Manager startup timeout - continuing with demo");
        }
        
        return deploymentId[0];
    }
    
    /**
     * Demonstrates Service Manager health checking.
     */
    private static void demonstrateServiceManagerHealth(WebClient client) throws Exception {
        logger.info("\n--- Service Manager Health Check ---");
        
        CountDownLatch healthLatch = new CountDownLatch(1);
        client.get(SERVICE_MANAGER_PORT, "localhost", "/health")
            .send(result -> {
                if (result.succeeded()) {
                    int statusCode = result.result().statusCode();
                    if (statusCode == 200) {
                        JsonObject health = result.result().bodyAsJsonObject();
                        logger.info("✅ Service Manager health check successful");
                        logger.info("   Status: {}", health.getString("status"));
                        logger.info("   Uptime: {}ms", health.getLong("uptimeMs"));
                        
                        JsonObject components = health.getJsonObject("components");
                        if (components != null) {
                            components.fieldNames().forEach(component -> {
                                logger.info("   Component {}: {}", component, 
                                           components.getJsonObject(component).getString("status"));
                            });
                        }
                    } else {
                        logger.warn("⚠️ Service Manager health check returned status: {}", statusCode);
                    }
                } else {
                    logger.warn("⚠️ Service Manager health check failed: {}", result.cause().getMessage());
                    logger.info("   This is expected if Service Manager is not fully started yet");
                }
                healthLatch.countDown();
            });
        
        healthLatch.await(10, TimeUnit.SECONDS);
    }
    
    /**
     * Demonstrates instance registration with the Service Manager.
     */
    private static void demonstrateInstanceRegistration(WebClient client) throws Exception {
        logger.info("\n--- Instance Registration ---");
        
        // Register test instances
        String[] instances = {
            "test-instance-01:8080:production:us-east-1",
            "test-instance-02:8081:production:us-west-1", 
            "test-instance-03:8082:staging:us-east-1"
        };
        
        for (String instanceConfig : instances) {
            String[] parts = instanceConfig.split(":");
            String instanceId = parts[0];
            int port = Integer.parseInt(parts[1]);
            String environment = parts[2];
            String region = parts[3];
            
            JsonObject instance = new JsonObject()
                .put("instanceId", instanceId)
                .put("host", "localhost")
                .put("port", port)
                .put("version", "1.0.0")
                .put("environment", environment)
                .put("region", region)
                .put("metadata", new JsonObject()
                    .put("datacenter", region.contains("east") ? "dc1" : "dc2")
                    .put("cluster", environment.equals("production") ? "main" : "test")
                    .put("capacity", "standard"));
            
            CountDownLatch registerLatch = new CountDownLatch(1);
            client.post(SERVICE_MANAGER_PORT, "localhost", "/api/v1/instances/register")
                .sendJsonObject(instance, result -> {
                    if (result.succeeded()) {
                        int statusCode = result.result().statusCode();
                        if (statusCode == 201) {
                            JsonObject response = result.result().bodyAsJsonObject();
                            logger.info("✅ Instance registered: {} - {}", instanceId, 
                                       response.getString("message"));
                        } else {
                            logger.warn("⚠️ Instance registration returned status: {} for {}", 
                                       statusCode, instanceId);
                        }
                    } else {
                        logger.warn("⚠️ Failed to register instance {}: {}", 
                                   instanceId, result.cause().getMessage());
                    }
                    registerLatch.countDown();
                });
            
            registerLatch.await(10, TimeUnit.SECONDS);
        }
        
        // List all registered instances
        CountDownLatch listLatch = new CountDownLatch(1);
        client.get(SERVICE_MANAGER_PORT, "localhost", "/api/v1/instances")
            .send(result -> {
                if (result.succeeded()) {
                    int statusCode = result.result().statusCode();
                    if (statusCode == 200) {
                        JsonArray instances_list = result.result().bodyAsJsonArray();
                        logger.info("✅ Registered instances: {}", instances_list.size());
                        
                        instances_list.forEach(instanceObj -> {
                            JsonObject instance = (JsonObject) instanceObj;
                            logger.info("   Instance: {} - {}:{} ({})", 
                                       instance.getString("instanceId"),
                                       instance.getString("host"),
                                       instance.getInteger("port"),
                                       instance.getString("environment"));
                        });
                    } else {
                        logger.warn("⚠️ Instance listing returned status: {}", statusCode);
                    }
                } else {
                    logger.warn("⚠️ Failed to list instances: {}", result.cause().getMessage());
                }
                listLatch.countDown();
            });
        
        listLatch.await(10, TimeUnit.SECONDS);
    }
    
    /**
     * Demonstrates federated management capabilities.
     */
    private static void demonstrateFederatedManagement(WebClient client) throws Exception {
        logger.info("\n--- Federated Management ---");
        
        // Get federated overview
        CountDownLatch overviewLatch = new CountDownLatch(1);
        client.get(SERVICE_MANAGER_PORT, "localhost", "/api/v1/federated/overview")
            .send(result -> {
                if (result.succeeded()) {
                    int statusCode = result.result().statusCode();
                    if (statusCode == 200) {
                        JsonObject overview = result.result().bodyAsJsonObject();
                        logger.info("✅ Federated overview:");
                        logger.info("   Total instances: {}", overview.getInteger("instanceCount"));
                        logger.info("   Healthy instances: {}", overview.getInteger("healthyInstances"));
                        logger.info("   System status: {}", overview.getString("status"));
                        
                        JsonObject aggregatedData = overview.getJsonObject("aggregatedData");
                        if (aggregatedData != null) {
                            logger.info("   Total queues: {}", aggregatedData.getInteger("totalQueues", 0));
                            logger.info("   Total messages: {}", aggregatedData.getLong("totalMessages", 0L));
                        }
                    } else {
                        logger.warn("⚠️ Federated overview returned status: {}", statusCode);
                    }
                } else {
                    logger.warn("⚠️ Failed to get federated overview: {}", result.cause().getMessage());
                }
                overviewLatch.countDown();
            });
        
        overviewLatch.await(10, TimeUnit.SECONDS);
        
        // Get federated metrics
        CountDownLatch metricsLatch = new CountDownLatch(1);
        client.get(SERVICE_MANAGER_PORT, "localhost", "/api/v1/federated/metrics")
            .send(result -> {
                if (result.succeeded()) {
                    int statusCode = result.result().statusCode();
                    if (statusCode == 200) {
                        JsonObject metrics = result.result().bodyAsJsonObject();
                        logger.info("✅ Federated metrics:");
                        logger.info("   Total throughput: {} msg/sec", metrics.getDouble("totalThroughput", 0.0));
                        logger.info("   Average latency: {}ms", metrics.getDouble("averageLatency", 0.0));
                        logger.info("   Error rate: {}%", metrics.getDouble("errorRate", 0.0));
                    } else {
                        logger.warn("⚠️ Federated metrics returned status: {}", statusCode);
                    }
                } else {
                    logger.warn("⚠️ Failed to get federated metrics: {}", result.cause().getMessage());
                }
                metricsLatch.countDown();
            });
        
        metricsLatch.await(10, TimeUnit.SECONDS);
    }
    
    /**
     * Demonstrates instance management operations.
     */
    private static void demonstrateInstanceManagement(WebClient client) throws Exception {
        logger.info("\n--- Instance Management ---");
        
        // Filter instances by environment
        String[] environments = {"production", "staging"};
        
        for (String environment : environments) {
            CountDownLatch envLatch = new CountDownLatch(1);
            
            client.get(SERVICE_MANAGER_PORT, "localhost", "/api/v1/instances?environment=" + environment)
                .send(result -> {
                    if (result.succeeded()) {
                        int statusCode = result.result().statusCode();
                        if (statusCode == 200) {
                            JsonArray instances = result.result().bodyAsJsonArray();
                            logger.info("✅ {} environment instances: {}", environment, instances.size());
                            
                            instances.forEach(instanceObj -> {
                                JsonObject instance = (JsonObject) instanceObj;
                                logger.info("   Instance: {} - Region: {}", 
                                           instance.getString("instanceId"),
                                           instance.getString("region"));
                            });
                        } else {
                            logger.warn("⚠️ Environment filtering returned status: {} for {}", 
                                       statusCode, environment);
                        }
                    } else {
                        logger.warn("⚠️ Failed to filter {} instances: {}", 
                                   environment, result.cause().getMessage());
                    }
                    envLatch.countDown();
                });
            
            envLatch.await(10, TimeUnit.SECONDS);
        }
        
        // Demonstrate instance deregistration (failover scenario)
        String instanceToDeregister = "test-instance-03";
        
        CountDownLatch deregisterLatch = new CountDownLatch(1);
        client.delete(SERVICE_MANAGER_PORT, "localhost", "/api/v1/instances/" + instanceToDeregister + "/deregister")
            .send(result -> {
                if (result.succeeded()) {
                    int statusCode = result.result().statusCode();
                    if (statusCode == 200) {
                        JsonObject response = result.result().bodyAsJsonObject();
                        logger.info("✅ Instance deregistered: {} - {}", 
                                   instanceToDeregister, response.getString("message"));
                    } else {
                        logger.warn("⚠️ Instance deregistration returned status: {} for {}", 
                                   statusCode, instanceToDeregister);
                    }
                } else {
                    logger.warn("⚠️ Failed to deregister instance {}: {}", 
                               instanceToDeregister, result.cause().getMessage());
                }
                deregisterLatch.countDown();
            });
        
        deregisterLatch.await(10, TimeUnit.SECONDS);
        
        // Check remaining instances after failover
        CountDownLatch remainingLatch = new CountDownLatch(1);
        client.get(SERVICE_MANAGER_PORT, "localhost", "/api/v1/instances")
            .send(result -> {
                if (result.succeeded()) {
                    int statusCode = result.result().statusCode();
                    if (statusCode == 200) {
                        JsonArray instances = result.result().bodyAsJsonArray();
                        logger.info("✅ Remaining instances after failover: {}", instances.size());
                        
                        instances.forEach(instanceObj -> {
                            JsonObject instance = (JsonObject) instanceObj;
                            logger.info("   Active instance: {} - {}:{}", 
                                       instance.getString("instanceId"),
                                       instance.getString("host"),
                                       instance.getInteger("port"));
                        });
                    } else {
                        logger.warn("⚠️ Remaining instances check returned status: {}", statusCode);
                    }
                } else {
                    logger.warn("⚠️ Failed to list remaining instances: {}", result.cause().getMessage());
                }
                remainingLatch.countDown();
            });
        
        remainingLatch.await(10, TimeUnit.SECONDS);
    }
}
