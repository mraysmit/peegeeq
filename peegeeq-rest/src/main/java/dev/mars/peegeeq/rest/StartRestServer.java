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

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.runtime.PeeGeeQContext;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import io.vertx.core.Vertx;

/**
 * Starts the PeeGeeQ REST API server using PeeGeeQRuntime.
 *
 * This class demonstrates how to start the REST server using the peegeeq-runtime
 * module which handles all the wiring of implementation modules (peegeeq-db,
 * peegeeq-native, peegeeq-outbox, peegeeq-bitemporal).
 *
 * Usage:
 * <pre>
 * mvn exec:java -pl peegeeq-rest
 * </pre>
 *
 * Or programmatically:
 * <pre>
 * public static void main(String[] args) {
 *     int port = 8080;
 *     PeeGeeQContext context = PeeGeeQRuntime.bootstrap();
 *     DatabaseSetupService setupService = context.getDatabaseSetupService();
 *
 *     Vertx vertx = Vertx.vertx();
 *     vertx.deployVerticle(new PeeGeeQRestServer(port, setupService))
 *         .onSuccess(id -> System.out.println("Server started on port " + port))
 *         .onFailure(cause -> {
 *             cause.printStackTrace();
 *             System.exit(1);
 *         });
 * }
 * </pre>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @see PeeGeeQRestServer
 * @see PeeGeeQRuntime
 * @see DatabaseSetupService
 */
public final class StartRestServer {

    private static final int DEFAULT_PORT = 8080;

    private StartRestServer() {
        // Utility class - not instantiable
    }

    /**
     * Starts the PeeGeeQ REST API server.
     *
     * @param args Command line arguments. Optional first argument is the port number.
     */
    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number: " + args[0]);
                System.err.println("Usage: StartRestServer [port]");
                System.exit(1);
            }
        }

        System.out.println("Starting PeeGeeQ REST API server...");
        System.out.println("Bootstrapping PeeGeeQ runtime...");

        // Bootstrap the PeeGeeQ runtime - this wires all implementation modules
        PeeGeeQContext context = PeeGeeQRuntime.bootstrap();
        DatabaseSetupService setupService = context.getDatabaseSetupService();

        System.out.println("PeeGeeQ runtime bootstrapped successfully");
        System.out.println("Starting HTTP server on port " + port + "...");

        // Create and deploy the REST server
        Vertx vertx = Vertx.vertx();
        final int serverPort = port;
        vertx.deployVerticle(new PeeGeeQRestServer(serverPort, setupService))
            .onSuccess(id -> {
                System.out.println("PeeGeeQ REST API server started on port " + serverPort);
                System.out.println("Health check: http://localhost:" + serverPort + "/health");
                System.out.println("API base URL: http://localhost:" + serverPort + "/api/v1");
            })
            .onFailure(cause -> {
                System.err.println("Failed to start PeeGeeQ REST API server: " + cause.getMessage());
                cause.printStackTrace();
                System.exit(1);
            });
    }
}
