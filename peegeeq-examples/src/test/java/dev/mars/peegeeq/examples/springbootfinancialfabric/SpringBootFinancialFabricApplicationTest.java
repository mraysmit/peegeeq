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

import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.examples.springbootfinancialfabric.cloudevents.FinancialCloudEventBuilder;
import dev.mars.peegeeq.examples.springbootfinancialfabric.config.FinancialFabricProperties;
import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Financial Fabric Application - Phase 1.
 * 
 * Tests basic setup:
 * - Spring Boot application context loads
 * - All 5 event stores are created
 * - CloudEvents builder is configured
 * - Configuration properties are loaded
 */
@SpringBootTest(
    classes = SpringBootFinancialFabricApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.profiles.active=test"
    }
)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class SpringBootFinancialFabricApplicationTest {
    
    private static final Logger log = LoggerFactory.getLogger(SpringBootFinancialFabricApplicationTest.class);
    
    @Container
    static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        log.info("Configuring properties for Financial Fabric test");
        SharedTestContainers.configureSharedProperties(registry);

        // Add financial-fabric specific database properties
        String host = postgres.getHost();
        Integer port = postgres.getFirstMappedPort();
        String database = postgres.getDatabaseName();
        String username = postgres.getUsername();
        String password = postgres.getPassword();

        registry.add("peegeeq.financial-fabric.database.host", () -> host);
        registry.add("peegeeq.financial-fabric.database.port", () -> port.toString());
        registry.add("peegeeq.financial-fabric.database.name", () -> database);
        registry.add("peegeeq.financial-fabric.database.username", () -> username);
        registry.add("peegeeq.financial-fabric.database.password", () -> password);

        log.info("Financial Fabric database properties configured: host={}, port={}, database={}", host, port, database);
    }
    
    @Autowired
    @Qualifier("tradingEventStore")
    private EventStore<CloudEvent> tradingEventStore;

    @Autowired
    @Qualifier("settlementEventStore")
    private EventStore<CloudEvent> settlementEventStore;

    @Autowired
    @Qualifier("cashEventStore")
    private EventStore<CloudEvent> cashEventStore;

    @Autowired
    @Qualifier("positionEventStore")
    private EventStore<CloudEvent> positionEventStore;

    @Autowired
    @Qualifier("regulatoryEventStore")
    private EventStore<CloudEvent> regulatoryEventStore;
    
    @Autowired
    private FinancialCloudEventBuilder cloudEventBuilder;
    
    @Autowired
    private FinancialFabricProperties properties;
    
    @AfterAll
    static void tearDown() {
        log.info("🧹 Cleaning up Financial Fabric Test resources");
        log.info("✅ Financial Fabric Test cleanup complete");
    }
    
    @Test
    public void testApplicationContextLoads() {
        log.info("=== Testing Application Context Loads ===");
        
        assertNotNull(tradingEventStore, "Trading event store should be created");
        assertNotNull(settlementEventStore, "Settlement event store should be created");
        assertNotNull(cashEventStore, "Cash event store should be created");
        assertNotNull(positionEventStore, "Position event store should be created");
        assertNotNull(regulatoryEventStore, "Regulatory event store should be created");
        assertNotNull(cloudEventBuilder, "CloudEvent builder should be created");
        assertNotNull(properties, "Properties should be loaded");
        
        log.info("✅ Application Context Loads test passed");
    }
    
    @Test
    public void testEventStoreConfiguration() {
        log.info("=== Testing Event Store Configuration ===");
        
        // Verify all event stores are enabled
        assertTrue(properties.getEventStores().getTrading().isEnabled());
        assertTrue(properties.getEventStores().getSettlement().isEnabled());
        assertTrue(properties.getEventStores().getCash().isEnabled());
        assertTrue(properties.getEventStores().getPosition().isEnabled());
        assertTrue(properties.getEventStores().getRegulatory().isEnabled());
        
        // Verify table names
        assertEquals("trading_events", properties.getEventStores().getTrading().getTableName());
        assertEquals("settlement_events", properties.getEventStores().getSettlement().getTableName());
        assertEquals("cash_events", properties.getEventStores().getCash().getTableName());
        assertEquals("position_events", properties.getEventStores().getPosition().getTableName());
        assertEquals("regulatory_events", properties.getEventStores().getRegulatory().getTableName());
        
        log.info("✅ Event Store Configuration test passed");
    }
    
    @Test
    public void testCloudEventsConfiguration() {
        log.info("=== Testing CloudEvents Configuration ===");
        
        assertEquals("trading-system", properties.getCloudevents().getSource());
        assertEquals("1.0", properties.getCloudevents().getSpecVersion());
        assertEquals("application/json", properties.getCloudevents().getDataContentType());
        
        log.info("✅ CloudEvents Configuration test passed");
    }
    
    @Test
    public void testRoutingConfiguration() {
        log.info("=== Testing Routing Configuration ===");
        
        assertEquals("*.*.*.*.high", properties.getRouting().getHighPriorityPattern());
        assertEquals("*.*.*.failed", properties.getRouting().getFailurePattern());
        assertEquals("regulatory.*.*", properties.getRouting().getRegulatoryPattern());
        
        log.info("✅ Routing Configuration test passed");
    }
}

