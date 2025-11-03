package dev.mars.peegeeq.examples.springbootpriority;

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
 * Spring Boot Priority Example Application.
 * 
 * Demonstrates PeeGeeQ priority-based message processing patterns:
 * - Priority-based message filtering using MessageFilter.byPriority()
 * - Multiple consumer patterns (single consumer vs. multiple filtered consumers)
 * - Trade settlement use case with CRITICAL, HIGH, and NORMAL priorities
 * - Application-level priority handling via headers
 * - Consumer metrics tracking by priority
 * 
 * This example shows the CORRECT way to implement priority-based message
 * processing in a Spring Boot application using PeeGeeQ's outbox pattern
 * and public API.
 * 
 * Use Cases:
 * - CRITICAL: Settlement fails requiring immediate attention
 * - HIGH: Trade amendments that must be processed quickly
 * - NORMAL: New trade confirmations with standard processing
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@SpringBootApplication
public class SpringBootPriorityApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(SpringBootPriorityApplication.class, args);
    }
}

