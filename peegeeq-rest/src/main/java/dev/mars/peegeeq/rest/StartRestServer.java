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
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.runtime.PeeGeeQContext;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

/**
 * Starts the PeeGeeQ REST API server using PeeGeeQRuntime and ConfigRetriever.
 *
 * This class demonstrates Vert.x 5.x best practices for configuration:
 * - Uses ConfigRetriever to load config from multiple sources (file/env/sysprops)
 * - Parses and validates config once at bootstrap into typed RestServerConfig
 * - Injects config into verticle (not accessed via globals/statics)
 * - Tests can easily create custom configs without file/env dependencies
 *
 * Configuration precedence (highest to lowest):
 * 1. System properties (-Dport=9090 -Dmonitoring.maxConnections=2000)
 * 2. Environment variables (export port=9090)
 * 3. Config file (conf/rest-server.json)
 * 4. Defaults (in RestServerConfig)
 *
 * Usage:
 * <pre>
 * mvn exec:java -pl peegeeq-rest
 * </pre>
 *
 * Or programmatically:
 * <pre>
 * public static void main(String[] args) {
 *     Vertx vertx = Vertx.vertx();
 *     ConfigRetriever retriever = ConfigRetriever.create(vertx);
 *     
 *     retriever.getConfig().compose(json -> {
 *         RestServerConfig config = RestServerConfig.from(json);
 *         PeeGeeQContext context = PeeGeeQRuntime.bootstrap();
 *         DatabaseSetupService setupService = context.getDatabaseSetupService();
 *         return vertx.deployVerticle(new PeeGeeQRestServer(config, setupService));
 *     }).onSuccess(id -> System.out.println("Server started"))
 *       .onFailure(Throwable::printStackTrace);
 * }
 * </pre>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @see PeeGeeQRestServer
 * @see RestServerConfig
 * @see PeeGeeQRuntime
 * @see DatabaseSetupService
 */
public final class StartRestServer {

    private StartRestServer() {
        // Utility class - not instantiable
    }

    /**
     * Starts the PeeGeeQ REST API server with configuration loaded from multiple sources.
     *
     * @param args Command line arguments (not used - configure via system properties/env vars)
     */
    public static void main(String[] args) {
        System.out.println("Starting PeeGeeQ REST API server...");
        
        Vertx vertx = Vertx.vertx();
        
        // Load configuration using ConfigRetriever
        // Precedence: System properties > Environment vars > File > Defaults
        ConfigStoreOptions fileStore = new ConfigStoreOptions()
            .setType("file")
            .setOptional(true)
            .setConfig(new JsonObject()
                .put("path", "conf/rest-server.json"));
        
        ConfigStoreOptions envStore = new ConfigStoreOptions()
            .setType("env")
            .setConfig(new JsonObject().put("raw-data", true));
        
        ConfigStoreOptions sysPropsStore = new ConfigStoreOptions()
            .setType("sys")
            .setConfig(new JsonObject().put("cache", false));
        
        ConfigRetrieverOptions retrieverOptions = new ConfigRetrieverOptions()
            .addStore(fileStore)       // Lowest priority
            .addStore(envStore)        // Middle priority
            .addStore(sysPropsStore);  // Highest priority
        
        ConfigRetriever retriever = ConfigRetriever.create(vertx, retrieverOptions);
        
        retriever.getConfig()
            .compose(jsonConfig -> {
                // Parse and validate configuration once
                RestServerConfig config = RestServerConfig.from(jsonConfig);
                System.out.println("Configuration loaded: port=" + config.port());
                
                // Bootstrap PeeGeeQ runtime
                System.out.println("Bootstrapping PeeGeeQ runtime...");
                PeeGeeQContext context = PeeGeeQRuntime.bootstrap();
                DatabaseSetupService setupService = context.getDatabaseSetupService();
                System.out.println("PeeGeeQ runtime bootstrapped successfully");
                
                // Deploy REST server with injected config
                return vertx.deployVerticle(new PeeGeeQRestServer(config, setupService));
            })
            .onSuccess(id -> {
                System.out.println("PeeGeeQ REST API server started successfully");
                System.out.println("Health check: http://localhost:8080/health");
                System.out.println("API base URL: http://localhost:8080/api/v1");
                System.out.println("");
                System.out.println("Override config via system properties:");
                System.out.println("  -Dport=9090 -Dmonitoring.maxConnections=2000");
            })
            .onFailure(cause -> {
                System.err.println("Failed to start: " + cause.getMessage());
                cause.printStackTrace();
                System.exit(1);
            });
    }
}
