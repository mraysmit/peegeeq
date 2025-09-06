package dev.mars.peegeeq.examples.springboot;

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
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot Application demonstrating PeeGeeQ Transactional Outbox Pattern integration.
 * 
 * This application follows the patterns outlined in the PeeGeeQ Transactional Outbox Patterns Guide
 * and demonstrates complete Spring Boot integration with PeeGeeQ.
 * 
 * Key Features:
 * - Zero Vert.x exposure to application developers
 * - Standard Spring Boot patterns and annotations
 * - Reactive operations abstracted behind familiar Spring APIs
 * - Production-ready configuration and monitoring
 * - Comprehensive error handling and logging
 * 
 * Usage:
 * - Run with default profile: java -jar app.jar
 * - Run with specific profile: java -jar app.jar --spring.profiles.active=development
 * - Configure database: Set environment variables DB_HOST, DB_PORT, DB_NAME, etc.
 * 
 * Endpoints:
 * - POST /api/orders - Create a new order
 * - POST /api/orders/{id}/validate - Validate an existing order
 * - GET /api/orders/health - Health check
 * - GET /actuator/health - Spring Boot health check
 * - GET /actuator/metrics - Application metrics
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-06
 * @version 1.0
 */
@SpringBootApplication
@EnableAsync
public class SpringBootOutboxApplication {
    private static final Logger log = LoggerFactory.getLogger(SpringBootOutboxApplication.class);

    public static void main(String[] args) {
        log.info("Starting PeeGeeQ Spring Boot Outbox Pattern Example Application");
        log.info("This application demonstrates complete Spring Boot integration with PeeGeeQ");
        
        SpringApplication.run(SpringBootOutboxApplication.class, args);
        
        log.info("PeeGeeQ Spring Boot Application started successfully");
        log.info("Available endpoints:");
        log.info("  POST /api/orders - Create a new order");
        log.info("  POST /api/orders/{{id}}/validate - Validate an existing order");
        log.info("  GET /api/orders/health - Health check");
        log.info("  GET /actuator/health - Spring Boot health check");
        log.info("  GET /actuator/metrics - Application metrics");
    }

    /**
     * Configure async task executor for reactive operations.
     * This executor is used by Spring Boot for @Async methods and CompletableFuture operations.
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
        executor.setThreadNamePrefix("peegeeq-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        
        log.info("Async task executor configured: core={}, max={}, queue={}", 
            executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        
        return executor;
    }
}
