package dev.mars.peegeeq.examples.springboot2;

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
 * Spring Boot Reactive Application demonstrating PeeGeeQ Transactional Outbox Pattern integration.
 * 
 * This application uses Spring WebFlux and R2DBC for fully reactive, non-blocking operations
 * while integrating with PeeGeeQ's transactional outbox pattern.
 * 
 * Key Features:
 * - Fully reactive with Spring WebFlux (Netty server)
 * - R2DBC for reactive database access
 * - Zero Vert.x exposure to application developers
 * - Standard Spring Boot reactive patterns
 * - Non-blocking operations with Mono and Flux
 * - Production-ready configuration and monitoring
 * - Comprehensive error handling and logging
 * 
 * Technology Stack:
 * - Spring Boot 3.3.5
 * - Spring WebFlux (reactive web)
 * - Spring Data R2DBC (reactive database)
 * - PeeGeeQ (transactional outbox)
 * - PostgreSQL with R2DBC driver
 * - Project Reactor (Mono/Flux)
 * 
 * Usage:
 * - Run with default profile: java -jar app.jar
 * - Run with specific profile: java -jar app.jar --spring.profiles.active=development
 * - Configure database: Set environment variables DB_HOST, DB_PORT, DB_NAME, etc.
 * 
 * Endpoints:
 * - POST /api/orders - Create a new order
 * - POST /api/orders/with-validation - Create order with validation
 * - GET /api/orders/{id} - Get order by ID
 * - GET /api/orders/customer/{customerId} - Get orders by customer
 * - GET /api/orders/stream - Stream recent orders (SSE)
 * - POST /api/orders/{id}/validate - Validate an existing order
 * - GET /api/orders/health - Health check
 * - GET /actuator/health - Spring Boot health check
 * - GET /actuator/metrics - Application metrics
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-01
 * @version 1.0
 */
@SpringBootApplication
@EnableAsync
public class SpringBootReactiveOutboxApplication {
    private static final Logger log = LoggerFactory.getLogger(SpringBootReactiveOutboxApplication.class);

    public static void main(String[] args) {
        log.info("Starting PeeGeeQ Spring Boot Reactive Outbox Pattern Example Application");
        log.info("This application demonstrates complete Spring WebFlux + R2DBC integration with PeeGeeQ");
        log.info("Technology: Spring WebFlux, R2DBC, Project Reactor, PeeGeeQ Outbox Pattern");
        
        SpringApplication.run(SpringBootReactiveOutboxApplication.class, args);
        
        log.info("PeeGeeQ Spring Boot Reactive Application started successfully");
        log.info("Server: Netty (reactive, non-blocking)");
        log.info("Database: PostgreSQL with R2DBC (reactive driver)");
        log.info("");
        log.info("Available endpoints:");
        log.info("  POST   /api/orders - Create a new order");
        log.info("  POST   /api/orders/with-validation - Create order with validation");
        log.info("  GET    /api/orders/{{id}} - Get order by ID");
        log.info("  GET    /api/orders/customer/{{customerId}} - Get orders by customer");
        log.info("  GET    /api/orders/stream - Stream recent orders (SSE)");
        log.info("  POST   /api/orders/{{id}}/validate - Validate an existing order");
        log.info("  GET    /api/orders/health - Health check");
        log.info("  GET    /actuator/health - Spring Boot health check");
        log.info("  GET    /actuator/metrics - Application metrics");
    }

    /**
     * Configure async task executor for reactive operations.
     * 
     * While WebFlux uses Netty's event loop for reactive operations,
     * this executor is still useful for @Async methods and CompletableFuture operations
     * that may be used in the application.
     * 
     * @return Configured task executor
     */
    @Bean
    public TaskExecutor taskExecutor() {
        log.info("Configuring async task executor for reactive operations");
        
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("peegeeq-reactive-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        
        log.info("Async task executor configured: core={}, max={}, queue={}", 
            executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        
        return executor;
    }
}

