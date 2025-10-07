package dev.mars.peegeeq.examples.springbootfinancialfabric;

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
 * Spring Boot Financial Services Event Fabric Example Application.
 * 
 * This example demonstrates a comprehensive financial services event fabric using PeeGeeQ:
 * - CloudEvents v1.0 standard format
 * - {entity}.{action}.{state} event naming pattern
 * - Multi-domain event stores (Trading, Settlement, Cash, Position, Regulatory)
 * - Bi-temporal event storage for audit trails
 * - Correlation and causation tracking
 * - Cross-domain transaction coordination
 * 
 * Key Concepts:
 * 1. CloudEvents standard for interoperability
 * 2. Structured event naming for clarity
 * 3. Domain-specific event stores for separation of concerns
 * 4. Bi-temporal queries for regulatory compliance
 * 5. Event correlation for workflow tracking
 * 6. Event causation for lineage tracking
 * 
 * Use Case:
 * End-to-end equity trade lifecycle:
 * Trading → Custody → Treasury → Position Management → Regulatory Reporting
 * 
 * Run with:
 * mvn spring-boot:run -pl peegeeq-examples \
 *   -Dspring-boot.run.main-class=dev.mars.peegeeq.examples.springbootfinancialfabric.SpringBootFinancialFabricApplication \
 *   -Dspring-boot.run.profiles=springboot-financial-fabric
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@SpringBootApplication
public class SpringBootFinancialFabricApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(SpringBootFinancialFabricApplication.class, args);
    }
}

