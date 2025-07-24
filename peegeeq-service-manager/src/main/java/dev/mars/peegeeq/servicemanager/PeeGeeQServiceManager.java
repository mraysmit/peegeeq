package dev.mars.peegeeq.servicemanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.servicemanager.discovery.ConsulServiceDiscovery;
import dev.mars.peegeeq.servicemanager.federation.FederatedManagementHandler;
import dev.mars.peegeeq.servicemanager.registration.InstanceRegistrationHandler;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.consul.ConsulClient;
import io.vertx.ext.consul.ConsulClientOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.StaticHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PeeGeeQ Service Manager - Discovery and Federation Service
 * 
 * This service acts as a central discovery and federation point for multiple PeeGeeQ instances.
 * It provides:
 * - Service discovery using Consul
 * - Instance registration and health monitoring
 * - Federated management API that routes requests to registered instances
 * - Load balancing and failover capabilities
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-24
 * @version 1.0
 */
public class PeeGeeQServiceManager extends AbstractVerticle {
    
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQServiceManager.class);
    
    private final int port;
    private HttpServer server;
    private ConsulClient consulClient;
    private ConsulServiceDiscovery serviceDiscovery;
    private ObjectMapper objectMapper;
    
    // Configuration
    private static final String CONSUL_HOST = System.getProperty("consul.host", "localhost");
    private static final int CONSUL_PORT = Integer.parseInt(System.getProperty("consul.port", "8500"));
    private static final String SERVICE_NAME = "peegeeq-service-manager";
    
    public PeeGeeQServiceManager() {
        this(9090);
    }
    
    public PeeGeeQServiceManager(int port) {
        this.port = port;
    }
    
    @Override
    public void start(Promise<Void> startPromise) {
        logger.info("Starting PeeGeeQ Service Manager on port {}", port);
        
        // Initialize components
        initializeComponents();
        
        // Create and configure router
        Router router = createRouter();
        
        // Start HTTP server
        server = vertx.createHttpServer()
                .requestHandler(router)
                .listen(port, serverResult -> {
                    if (serverResult.succeeded()) {
                        logger.info("PeeGeeQ Service Manager started successfully on port {}", port);

                        // Register this service manager with Consul (optional)
                        registerSelfWithConsul()
                            .onComplete(consulResult -> {
                                if (consulResult.succeeded()) {
                                    logger.info("Service Manager registered with Consul");
                                } else {
                                    logger.warn("Failed to register with Consul (continuing without Consul): {}",
                                            consulResult.cause().getMessage());
                                }
                                // Always complete startup, even if Consul registration fails
                                startPromise.complete();
                            });
                    } else {
                        logger.error("Failed to start PeeGeeQ Service Manager", serverResult.cause());
                        startPromise.fail(serverResult.cause());
                    }
                });
    }
    
    @Override
    public void stop(Promise<Void> stopPromise) {
        logger.info("Stopping PeeGeeQ Service Manager...");
        
        if (server != null) {
            server.close(result -> {
                if (result.succeeded()) {
                    logger.info("PeeGeeQ Service Manager stopped successfully");
                    stopPromise.complete();
                } else {
                    logger.error("Error stopping PeeGeeQ Service Manager", result.cause());
                    stopPromise.fail(result.cause());
                }
            });
        } else {
            stopPromise.complete();
        }
    }
    
    private void initializeComponents() {
        // Initialize Jackson ObjectMapper
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        
        // Initialize Consul client
        ConsulClientOptions consulOptions = new ConsulClientOptions()
                .setHost(CONSUL_HOST)
                .setPort(CONSUL_PORT);
        
        consulClient = ConsulClient.create(vertx, consulOptions);
        
        // Initialize service discovery
        serviceDiscovery = new ConsulServiceDiscovery(vertx, consulClient, objectMapper);
        
        logger.info("Initialized components - Consul: {}:{}", CONSUL_HOST, CONSUL_PORT);
    }
    
    private Router createRouter() {
        Router router = Router.router(vertx);
        
        // Add CORS handler
        router.route().handler(CorsHandler.create("*")
                .allowedMethod(io.vertx.core.http.HttpMethod.GET)
                .allowedMethod(io.vertx.core.http.HttpMethod.POST)
                .allowedMethod(io.vertx.core.http.HttpMethod.PUT)
                .allowedMethod(io.vertx.core.http.HttpMethod.DELETE)
                .allowedMethod(io.vertx.core.http.HttpMethod.OPTIONS)
                .allowedHeader("Content-Type")
                .allowedHeader("Authorization"));
        
        // Add body handler
        router.route().handler(BodyHandler.create());
        
        // Create handlers
        InstanceRegistrationHandler registrationHandler = new InstanceRegistrationHandler(serviceDiscovery, objectMapper);
        FederatedManagementHandler federatedHandler = new FederatedManagementHandler(serviceDiscovery, objectMapper);
        
        // Health check endpoint
        router.get("/health").handler(ctx -> {
            JsonObject health = new JsonObject()
                    .put("status", "UP")
                    .put("service", SERVICE_NAME)
                    .put("timestamp", System.currentTimeMillis())
                    .put("consul", consulClient != null ? "connected" : "disconnected");
            
            ctx.response()
                    .setStatusCode(200)
                    .putHeader("content-type", "application/json")
                    .end(health.encode());
        });
        
        // Instance registration endpoints
        router.post("/api/v1/instances/register").handler(registrationHandler::registerInstance);
        router.delete("/api/v1/instances/:instanceId/deregister").handler(registrationHandler::deregisterInstance);
        router.get("/api/v1/instances").handler(registrationHandler::listInstances);
        router.get("/api/v1/instances/:instanceId/health").handler(registrationHandler::checkInstanceHealth);
        
        // Federated management endpoints
        router.get("/api/v1/federated/overview").handler(federatedHandler::getFederatedOverview);
        router.get("/api/v1/federated/queues").handler(federatedHandler::getFederatedQueues);
        router.get("/api/v1/federated/consumer-groups").handler(federatedHandler::getFederatedConsumerGroups);
        router.get("/api/v1/federated/event-stores").handler(federatedHandler::getFederatedEventStores);
        router.get("/api/v1/federated/metrics").handler(federatedHandler::getFederatedMetrics);
        
        // Instance-specific routing endpoints
        router.get("/api/v1/instances/:instanceId/overview").handler(federatedHandler::getInstanceOverview);
        router.get("/api/v1/instances/:instanceId/queues").handler(federatedHandler::getInstanceQueues);
        router.get("/api/v1/instances/:instanceId/consumer-groups").handler(federatedHandler::getInstanceConsumerGroups);
        router.get("/api/v1/instances/:instanceId/event-stores").handler(federatedHandler::getInstanceEventStores);
        router.get("/api/v1/instances/:instanceId/metrics").handler(federatedHandler::getInstanceMetrics);
        
        // Static file serving for management UI (if needed)
        router.route("/ui/*").handler(StaticHandler.create("webroot").setIndexPage("index.html"));
        router.route("/").handler(ctx -> ctx.response().putHeader("location", "/ui/").setStatusCode(302).end());
        
        logger.info("Router configured with registration and federation endpoints");
        return router;
    }
    
    private Future<Void> registerSelfWithConsul() {
        Promise<Void> promise = Promise.promise();

        // Register this service manager instance with Consul
        io.vertx.ext.consul.ServiceOptions serviceOptions = new io.vertx.ext.consul.ServiceOptions()
                .setId(SERVICE_NAME + "-" + System.currentTimeMillis())
                .setName(SERVICE_NAME)
                .setAddress("localhost")
                .setPort(port);

        // Add health check (use interval for HTTP checks, not TTL)
        io.vertx.ext.consul.CheckOptions healthCheck = new io.vertx.ext.consul.CheckOptions()
                .setHttp("http://localhost:" + port + "/health")
                .setInterval("10s");

        serviceOptions.setCheckOptions(healthCheck);

        consulClient.registerService(serviceOptions, result -> {
            if (result.succeeded()) {
                promise.complete();
            } else {
                promise.fail(result.cause());
            }
        });

        return promise.future();
    }
    
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9090;
        
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new PeeGeeQServiceManager(port), result -> {
            if (result.succeeded()) {
                logger.info("PeeGeeQ Service Manager deployed successfully");
            } else {
                logger.error("Failed to deploy PeeGeeQ Service Manager", result.cause());
                System.exit(1);
            }
        });
    }
}
