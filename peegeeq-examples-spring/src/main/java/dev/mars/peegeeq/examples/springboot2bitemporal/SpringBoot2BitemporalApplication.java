package dev.mars.peegeeq.examples.springboot2bitemporal;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Spring Boot Reactive Application demonstrating PeeGeeQ Bi-Temporal Event Store integration.
 * 
 * <p>This application demonstrates how to integrate PeeGeeQ's bi-temporal event store
 * with Spring Boot WebFlux for reactive applications.
 * 
 * <p><b>Key Focus:</b> This example focuses on PeeGeeQ bi-temporal integration patterns,
 * not Spring Boot CRUD operations. It demonstrates:
 * <ul>
 *   <li>Reactive adapter pattern (CompletableFuture and Vert.x Future → Mono/Flux)</li>
 *   <li>Bi-temporal event appending with valid time</li>
 *   <li>Historical queries and point-in-time reconstruction</li>
 *   <li>Bi-temporal corrections with audit trail</li>
 *   <li>Event naming pattern: {entity}.{action}.{state}</li>
 * </ul>
 * 
 * <p><b>Use Case:</b> Back office trade settlement processing
 * <ul>
 *   <li>Submit settlement instructions to custodian</li>
 *   <li>Track settlement lifecycle (submitted → matched → confirmed/failed)</li>
 *   <li>Query historical settlement states</li>
 *   <li>Correct settlement data with full audit trail</li>
 * </ul>
 * 
 * <p><b>Technology Stack:</b>
 * <ul>
 *   <li>Spring Boot 3.3.5</li>
 *   <li>Spring WebFlux (reactive web framework)</li>
 *   <li>Project Reactor (Mono/Flux)</li>
 *   <li>PeeGeeQ Bi-Temporal Event Store</li>
 *   <li>PostgreSQL (bi-temporal tables)</li>
 * </ul>
 * 
 * <p><b>Endpoints:</b>
 * <ul>
 *   <li>POST /api/settlements/submit - Submit settlement instruction</li>
 *   <li>POST /api/settlements/match - Record settlement matched</li>
 *   <li>POST /api/settlements/confirm - Record settlement confirmed</li>
 *   <li>POST /api/settlements/fail - Record settlement failed</li>
 *   <li>POST /api/settlements/correct - Correct settlement data</li>
 *   <li>GET /api/settlements/history/{instructionId} - Get settlement history</li>
 *   <li>GET /api/settlements/state-at/{instructionId}?pointInTime=... - State at specific time</li>
 *   <li>GET /api/settlements/corrections/{instructionId} - View correction history</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@SpringBootApplication
@EnableAsync
public class SpringBoot2BitemporalApplication {
    private static final Logger log = LoggerFactory.getLogger(SpringBoot2BitemporalApplication.class);

    public static void main(String[] args) {
        log.info("Starting PeeGeeQ Spring Boot Reactive Bi-Temporal Example Application");
        log.info("This application demonstrates PeeGeeQ bi-temporal event store integration with Spring WebFlux");
        log.info("Use Case: Back office trade settlement processing");
        log.info("Event Naming Pattern: {{entity}}.{{action}}.{{state}}");
        
        SpringApplication.run(SpringBoot2BitemporalApplication.class, args);
        
        log.info("PeeGeeQ Spring Boot Reactive Bi-Temporal Application started successfully");
        log.info("Server: Netty (reactive, non-blocking)");
        log.info("Event Store: PeeGeeQ Bi-Temporal (PostgreSQL)");
        log.info("");
        log.info("Available endpoints:");
        log.info("  POST   /api/settlements/submit - Submit settlement instruction");
        log.info("  POST   /api/settlements/match - Record settlement matched");
        log.info("  POST   /api/settlements/confirm - Record settlement confirmed");
        log.info("  POST   /api/settlements/fail - Record settlement failed");
        log.info("  POST   /api/settlements/correct - Correct settlement data");
        log.info("  GET    /api/settlements/history/{{instructionId}} - Get settlement history");
        log.info("  GET    /api/settlements/state-at/{{instructionId}}?pointInTime=... - State at time");
        log.info("  GET    /api/settlements/corrections/{{instructionId}} - View corrections");
        log.info("  GET    /actuator/health - Health check");
        log.info("  GET    /actuator/metrics - Metrics");
    }

    /**
     * Configure async task executor for reactive operations.
     * 
     * @return Configured task executor
     */
    @Bean
    public TaskExecutor taskExecutor() {
        log.info("Configuring async task executor for reactive bi-temporal operations");
        
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("peegeeq-reactive-bitemporal-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        
        log.info("Async task executor configured: core={}, max={}, queue={}", 
            executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        
        return executor;
    }
}

