package dev.mars.peegeeq.examples.springbootretry;

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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot Retry Strategies Example Application.
 * 
 * This example demonstrates advanced retry patterns with PeeGeeQ:
 * - Configurable max retries
 * - Exponential backoff (via next_retry_at)
 * - Circuit breaker pattern
 * - Failure classification (transient vs permanent)
 * - Retry metrics and monitoring
 * - Health indicators for circuit breaker state
 * 
 * Key Concepts:
 * 1. PeeGeeQ handles retry logic automatically
 * 2. Configure max retries via system properties
 * 3. Use next_retry_at for exponential backoff
 * 4. Classify exceptions to determine retry behavior
 * 5. Monitor retry metrics for circuit breaker decisions
 * 
 * Run with:
 * mvn spring-boot:run -pl peegeeq-examples \
 *   -Dspring-boot.run.main-class=dev.mars.peegeeq.examples.springbootretry.SpringBootRetryApplication \
 *   -Dspring-boot.run.profiles=springboot-retry
 */
@SpringBootApplication
public class SpringBootRetryApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(SpringBootRetryApplication.class, args);
    }
}

