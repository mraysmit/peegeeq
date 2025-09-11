package dev.mars.peegeeq.servicemanager;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Manual test to verify that HTTP health endpoints work correctly
 * outside of any Consul integration.
 * 
 * This tests the basic HTTP functionality that Consul health checks depend on.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-24
 * @version 1.0
 */
@ExtendWith(VertxExtension.class)
class ManualHealthCheckTest {
    
    private static final Logger logger = LoggerFactory.getLogger(ManualHealthCheckTest.class);
    
    @Test
    void testHealthEndpointRespondsCorrectly(Vertx vertx, VertxTestContext testContext) {
        int testPort = 8090;
        
        // Start a simple HTTP server with health endpoint
        startTestServer(vertx, testPort)
            .compose(server -> {
                logger.info("✅ Test server started on port {}", testPort);
                
                // Test the health endpoint with HTTP client
                WebClient client = WebClient.create(vertx);
                return client.get(testPort, "localhost", "/health").send()
                        .compose(response -> {
                            logger.info("📡 HTTP Response Status: {}", response.statusCode());
                            logger.info("📡 HTTP Response Headers: {}", response.headers().names());
                            logger.info("📡 HTTP Response Body: {}", response.bodyAsString());
                            
                            // Verify response
                            assertEquals(200, response.statusCode(), "Health endpoint should return 200 OK");
                            assertEquals("application/json", response.getHeader("Content-Type"));
                            
                            JsonObject healthResponse = response.bodyAsJsonObject();
                            assertNotNull(healthResponse);
                            assertEquals("UP", healthResponse.getString("status"));
                            assertTrue(healthResponse.containsKey("timestamp"));
                            
                            logger.info("✅ Health endpoint responds correctly");
                            
                            // Close server
                            return server.close();
                        });
            })
            .onComplete(testContext.succeeding(v -> {
                logger.info("✅ Manual health check test completed successfully");
                testContext.completeNow();
            }));
    }
    
    @Test
    void testUnhealthyEndpointRespondsCorrectly(Vertx vertx, VertxTestContext testContext) {
        int testPort = 8091;
        
        // Start a server that returns 500 for health checks
        startUnhealthyTestServer(vertx, testPort)
            .compose(server -> {
                logger.info("✅ Unhealthy test server started on port {}", testPort);
                
                // Test the health endpoint with HTTP client
                WebClient client = WebClient.create(vertx);
                return client.get(testPort, "localhost", "/health").send()
                        .compose(response -> {
                            logger.info("📡 HTTP Response Status: {}", response.statusCode());
                            logger.info("📡 HTTP Response Body: {}", response.bodyAsString());
                            
                            // Verify response
                            assertEquals(500, response.statusCode(), "Unhealthy endpoint should return 500");
                            assertEquals("application/json", response.getHeader("Content-Type"));
                            
                            JsonObject healthResponse = response.bodyAsJsonObject();
                            assertNotNull(healthResponse);
                            assertEquals("DOWN", healthResponse.getString("status"));
                            assertTrue(healthResponse.containsKey("error"));
                            
                            logger.info("✅ Unhealthy endpoint responds correctly");
                            
                            // Close server
                            return server.close();
                        });
            })
            .onComplete(testContext.succeeding(v -> {
                logger.info("✅ Manual unhealthy check test completed successfully");
                testContext.completeNow();
            }));
    }
    
    @Test
    void testHealthEndpointFromCommandLine(Vertx vertx, VertxTestContext testContext) {
        int testPort = 8092;
        
        // Start server and keep it running for manual testing
        startTestServer(vertx, testPort)
            .onComplete(testContext.succeeding(server -> {
                logger.info("🚀 Test server started on port {} for manual testing", testPort);
                logger.info("🔗 Test URL: http://localhost:{}/health", testPort);
                logger.info("💡 You can now test this endpoint manually with:");
                logger.info("   curl http://localhost:{}/health", testPort);
                logger.info("   curl -v http://localhost:{}/health", testPort);
                logger.info("⏱️  Server will run for 10 seconds...");
                
                // Keep server running for 10 seconds for manual testing
                vertx.setTimer(10000, id -> {
                    server.close().onComplete(closeResult -> {
                        if (closeResult.succeeded()) {
                            logger.info("✅ Test server stopped");
                        } else {
                            logger.error("❌ Failed to stop test server", closeResult.cause());
                        }
                        testContext.completeNow();
                    });
                });
            }));
    }
    
    private Future<HttpServer> startTestServer(Vertx vertx, int port) {
        Promise<HttpServer> promise = Promise.promise();
        
        Router router = Router.router(vertx);
        
        // Health endpoint that returns 200 OK
        router.get("/health").handler(ctx -> {
            JsonObject health = new JsonObject()
                    .put("status", "UP")
                    .put("timestamp", System.currentTimeMillis())
                    .put("service", "manual-test")
                    .put("port", port)
                    .put("message", "Health check endpoint is working correctly");
            
            logger.info("📋 Health endpoint called, returning: {}", health.encode());
            
            ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(health.encode());
        });
        
        // Info endpoint for additional testing
        router.get("/info").handler(ctx -> {
            JsonObject info = new JsonObject()
                    .put("service", "manual-test")
                    .put("version", "1.0.0")
                    .put("port", port)
                    .put("endpoints", new JsonObject()
                            .put("health", "/health")
                            .put("info", "/info"));
            
            ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(info.encode());
        });
        
        vertx.createHttpServer()
                .requestHandler(router)
                .listen(port)
                .onSuccess(server -> {
                    logger.info("✅ Started healthy test server on port {}", port);
                    promise.complete(server);
                })
                .onFailure(throwable -> {
                    logger.error("❌ Failed to start test server on port {}", port, throwable);
                    promise.fail(throwable);
                });
        
        return promise.future();
    }
    
    private Future<HttpServer> startUnhealthyTestServer(Vertx vertx, int port) {
        Promise<HttpServer> promise = Promise.promise();
        
        Router router = Router.router(vertx);
        
        // Health endpoint that returns 500 Internal Server Error
        router.get("/health").handler(ctx -> {
            JsonObject error = new JsonObject()
                    .put("status", "DOWN")
                    .put("error", "Database connection failed")
                    .put("timestamp", System.currentTimeMillis())
                    .put("service", "manual-test")
                    .put("port", port)
                    .put("message", "Service is unhealthy");
            
            logger.info("📋 Unhealthy endpoint called, returning: {}", error.encode());
            
            ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(error.encode());
        });
        
        vertx.createHttpServer()
                .requestHandler(router)
                .listen(port)
                .onSuccess(server -> {
                    logger.info("✅ Started unhealthy test server on port {}", port);
                    promise.complete(server);
                })
                .onFailure(throwable -> {
                    logger.error("❌ Failed to start test server on port {}", port, throwable);
                    promise.fail(throwable);
                });
        
        return promise.future();
    }
}
