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

package dev.mars.peegeeq.rest;

import io.vertx.core.Vertx;
import io.vertx.core.Future;

/**
 * Standalone utility to start the PeeGeeQ REST API server.
 * 
 * This is a convenience class for quickly starting the REST server during development
 * or for demonstration purposes. It provides a simple main method that starts the
 * PeeGeeQRestServer with configurable port.
 * 
 * Usage:
 * - Default port (8080): java dev.mars.peegeeq.rest.StartRestServer
 * - Custom port: java dev.mars.peegeeq.rest.StartRestServer 9090
 * 
 * The server provides:
 * - Health endpoint: http://localhost:port/health
 * - Management API: http://localhost:port/api/v1/management/overview
 * - Queue operations: http://localhost:port/api/v1/queues
 * - Consumer groups: http://localhost:port/api/v1/consumer-groups
 * - Event stores: http://localhost:port/api/v1/eventstores
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 */
public class StartRestServer {
    
    /**
     * Main method to start the PeeGeeQ REST server.
     * 
     * @param args Command line arguments. First argument is the port number (optional, defaults to 8080)
     */
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        // Display PeeGeeQ logo
        System.out.println();
        System.out.println("    ____            ______            ____");
        System.out.println("   / __ \\___  ___  / ____/__  ___    / __ \\");
        System.out.println("  / /_/ / _ \\/ _ \\/ / __/ _ \\/ _ \\  / / / /");
        System.out.println(" / ____/  __/  __/ /_/ /  __/  __/ / /_/ /");
        System.out.println("/_/    \\___/\\___/\\____/\\___/\\___/  \\___\\_\\");
        System.out.println();
        System.out.println("PostgreSQL Event-Driven Queue System");
        System.out.println("REST API Server - Vert.x 5.0.4");
        System.out.println();

        System.out.println("Starting PeeGeeQ REST Server on port " + port);
        
        Vertx vertx = Vertx.vertx();

        // Start server with composable Future chain
        Future.succeededFuture(vertx)
            .compose(v -> vertx.deployVerticle(new PeeGeeQRestServer(port)))
            .compose(deploymentId -> {
                System.out.println("✅ PeeGeeQ REST Server started successfully on port " + port);
                System.out.println("Health endpoint: http://localhost:" + port + "/health");
                System.out.println("Management API: http://localhost:" + port + "/api/v1/management/overview");
                System.out.println("Queue API: http://localhost:" + port + "/api/v1/queues");
                System.out.println("Consumer Groups API: http://localhost:" + port + "/api/v1/consumer-groups");
                System.out.println("Event Stores API: http://localhost:" + port + "/api/v1/eventstores");
                System.out.println("Press Ctrl+C to stop the server");
                return Future.succeededFuture();
            })
            .onFailure(cause -> {
                System.err.println("❌ Failed to start server: " + cause.getMessage());
                cause.printStackTrace();
                System.exit(1);
            });
        
        // Add shutdown hook for graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n🛑 Shutting down PeeGeeQ REST Server...");
            vertx.close();
        }));
    }
}
