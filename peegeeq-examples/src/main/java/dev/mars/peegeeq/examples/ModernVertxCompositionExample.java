package dev.mars.peegeeq.examples;

import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.servicemanager.PeeGeeQServiceManager;
import io.vertx.core.Vertx;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Demonstrates modern Vert.x 5.x composable Future patterns.
 * 
 * This example shows how to use .compose() chains instead of nested
 * .onSuccess()/.onFailure() callbacks for better readability and error handling.
 * 
 * Key patterns demonstrated:
 * 1. Server startup with sequential operations
 * 2. Database initialization with dependent steps
 * 3. Service registration with health checks
 * 4. Error recovery and graceful degradation
 */
public class ModernVertxCompositionExample {
    
    private static final Logger logger = LoggerFactory.getLogger(ModernVertxCompositionExample.class);
    private static final int REST_PORT = 8080;
    private static final int SERVICE_MANAGER_PORT = 9090;
    
    public static void main(String[] args) throws Exception {
        // Display PeeGeeQ logo
        System.out.println();
        System.out.println("    ____            ______            ____");
        System.out.println("   / __ \\___  ___  / ____/__  ___    / __ \\");
        System.out.println("  / /_/ / _ \\/ _ \\/ / __/ _ \\/ _ \\  / / / /");
        System.out.println(" / ____/  __/  __/ /_/ /  __/ / /_/ /");
        System.out.println("/_/    \\___/\\___/\\____/\\___/\\___/  \\___\\_\\");
        System.out.println();
        System.out.println("PostgreSQL Event-Driven Queue System");
        System.out.println("Modern Vert.x 5.x Composition Example");
        System.out.println();

        logger.info("=== Modern Vert.x 5.x Composition Example ===");
        
        // Start PostgreSQL container
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
                .withDatabaseName("peegeeq_composition_demo")
                .withUsername("postgres")
                .withPassword("password")) {
            
            postgres.start();
            logger.info("PostgreSQL container started: {}", postgres.getJdbcUrl());
            
            Vertx vertx = Vertx.vertx();
            WebClient client = WebClient.create(vertx);
            
            try {
                // Demonstrate modern composable startup sequence
                startApplicationWithComposition(vertx, client, postgres)
                    .compose(v -> performDatabaseOperations(client))
                    .compose(v -> demonstrateServiceInteractions(client))
                    .compose(v -> performGracefulShutdown(vertx))
                    .onSuccess(v -> logger.info("✅ All operations completed successfully"))
                    .onFailure(throwable -> logger.error("❌ Application failed", throwable))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(); // Wait for completion in example
                    
            } finally {
                client.close();
                vertx.close();
            }
        }
    }
    
    /**
     * Demonstrates composable server startup with sequential operations.
     * 
     * Pattern: server.listen(port)
     *   .compose(s -> doWarmupQuery())     // returns Future<Void>
     *   .compose(v -> registerWithRegistry()) // returns Future<Void>
     *   .onSuccess(v -> System.out.println("Server is ready"))
     *   .onFailure(Throwable::printStackTrace);
     */
    private static Future<Void> startApplicationWithComposition(Vertx vertx, WebClient client, 
                                                               PostgreSQLContainer<?> postgres) {
        logger.info("Starting application with composable Future chain...");
        
        return Future.succeededFuture()
            .compose(v -> {
                logger.info("Step 1: Deploying REST server...");
                return vertx.deployVerticle(new PeeGeeQRestServer(REST_PORT));
            })
            .compose(restDeploymentId -> {
                logger.info("✅ REST server deployed: {}", restDeploymentId);
                logger.info("Step 2: Deploying Service Manager...");
                return vertx.deployVerticle(new PeeGeeQServiceManager(SERVICE_MANAGER_PORT));
            })
            .compose(serviceManagerId -> {
                logger.info("✅ Service Manager deployed: {}", serviceManagerId);
                logger.info("Step 3: Performing warmup operations...");
                return performWarmupOperations(client);
            })
            .compose(v -> {
                logger.info("✅ Warmup completed");
                logger.info("Step 4: Registering with service registry...");
                return registerWithServiceRegistry(client);
            })
            .compose(v -> {
                logger.info("✅ Service registration completed");
                logger.info("Step 5: Performing health checks...");
                return performHealthChecks(client);
            })
            .recover(throwable -> {
                logger.warn("⚠️ Some startup steps failed, continuing with degraded functionality: {}", 
                           throwable.getMessage());
                // Graceful degradation - continue even if some steps fail
                return Future.succeededFuture();
            })
            .compose(v -> {
                logger.info("🚀 Application startup sequence completed successfully");
                return Future.succeededFuture();
            });
    }
    
    /**
     * Demonstrates composable database operations with error recovery.
     */
    private static Future<Void> performDatabaseOperations(WebClient client) {
        logger.info("Performing database operations with composition...");
        
        JsonObject setupRequest = new JsonObject()
            .put("setupId", "composition-demo-setup")
            .put("databaseConfig", new JsonObject()
                .put("host", "localhost")
                .put("port", 5432)
                .put("database", "peegeeq_composition_demo")
                .put("username", "postgres")
                .put("password", "password"))
            .put("queues", new JsonObject()
                .put("orders", new JsonObject().put("type", "native"))
                .put("notifications", new JsonObject().put("type", "outbox")))
            .put("eventStores", new JsonObject()
                .put("order-events", new JsonObject().put("type", "bitemporal")));
        
        return client.post(REST_PORT, "localhost", "/api/v1/database-setup/create")
            .sendJsonObject(setupRequest)
            .compose(response -> {
                if (response.statusCode() == 200) {
                    JsonObject result = response.bodyAsJsonObject();
                    logger.info("✅ Database setup created: {}", result.getString("message"));
                    return Future.<Void>succeededFuture();
                } else {
                    return Future.<Void>failedFuture("Database setup failed with status: " + response.statusCode());
                }
            })
            .recover(throwable -> {
                logger.warn("⚠️ Database setup failed, using fallback configuration: {}", throwable.getMessage());
                return performFallbackDatabaseSetup(client);
            });
    }
    
    /**
     * Demonstrates service interaction patterns with composition.
     */
    private static Future<Void> demonstrateServiceInteractions(WebClient client) {
        logger.info("Demonstrating service interactions...");
        
        return client.get(REST_PORT, "localhost", "/health")
            .send()
            .compose(healthResponse -> {
                logger.info("✅ REST API health check: {}", healthResponse.statusCode());
                return client.get(SERVICE_MANAGER_PORT, "localhost", "/health").send();
            })
            .compose(serviceHealthResponse -> {
                logger.info("✅ Service Manager health check: {}", serviceHealthResponse.statusCode());
                return client.get(SERVICE_MANAGER_PORT, "localhost", "/api/v1/instances").send();
            })
            .compose(instancesResponse -> {
                if (instancesResponse.statusCode() == 200) {
                    logger.info("✅ Retrieved service instances: {}", instancesResponse.bodyAsJsonArray().size());
                }
                return Future.<Void>succeededFuture();
            })
            .recover(throwable -> {
                logger.warn("⚠️ Some service interactions failed: {}", throwable.getMessage());
                return Future.<Void>succeededFuture(); // Continue despite failures
            });
    }
    
    /**
     * Helper method for warmup operations.
     */
    private static Future<Void> performWarmupOperations(WebClient client) {
        return Future.succeededFuture()
            .compose(v -> {
                // Simulate warmup delay with timer
                logger.info("Performing warmup operations...");
                return Future.succeededFuture();
            })
            .compose(v -> {
                logger.info("Warmup operations completed");
                return Future.succeededFuture();
            });
    }
    
    /**
     * Helper method for service registry registration.
     */
    private static Future<Void> registerWithServiceRegistry(WebClient client) {
        JsonObject registrationRequest = new JsonObject()
            .put("instanceId", "composition-demo-instance")
            .put("host", "localhost")
            .put("port", REST_PORT)
            .put("environment", "demo")
            .put("region", "local");
            
        return client.post(SERVICE_MANAGER_PORT, "localhost", "/api/v1/instances/register")
            .sendJsonObject(registrationRequest)
            .compose(response -> {
                if (response.statusCode() == 201) {
                    logger.info("Service registered successfully");
                    return Future.succeededFuture();
                } else {
                    return Future.failedFuture("Registration failed: " + response.statusCode());
                }
            });
    }
    
    /**
     * Helper method for health checks.
     */
    private static Future<Void> performHealthChecks(WebClient client) {
        return client.get(REST_PORT, "localhost", "/health")
            .send()
            .compose(response -> {
                if (response.statusCode() == 200) {
                    return Future.succeededFuture();
                } else {
                    return Future.failedFuture("Health check failed: " + response.statusCode());
                }
            });
    }
    
    /**
     * Helper method for fallback database setup.
     */
    private static Future<Void> performFallbackDatabaseSetup(WebClient client) {
        logger.info("Performing fallback database setup...");
        // Simulate fallback setup
        return Future.succeededFuture();
    }
    
    /**
     * Demonstrates graceful shutdown with composition.
     */
    private static Future<Void> performGracefulShutdown(Vertx vertx) {
        logger.info("Performing graceful shutdown...");
        
        return Future.succeededFuture()
            .compose(v -> {
                logger.info("Shutdown sequence completed");
                return Future.succeededFuture();
            });
    }
}
