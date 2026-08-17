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

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.examples.springboot2bitemporal.config.ReactiveBiTemporalProperties;
import dev.mars.peegeeq.examples.springboot2bitemporal.events.SettlementEvent;
import dev.mars.peegeeq.examples.springboot2bitemporal.model.SettlementStatus;
import dev.mars.peegeeq.examples.springboot2bitemporal.service.SettlementService;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the Spring Boot Reactive Bi-Temporal Application.
 * 
 * <p>This test verifies:
 * <ul>
 *   <li>Spring Boot WebFlux application context loading</li>
 *   <li>PeeGeeQ bi-temporal event store integration</li>
 *   <li>Reactive adapter pattern (Vert.x Future to Mono/Flux)</li>
 *   <li>Bi-temporal event appending</li>
 *   <li>Historical query serialization and parsing</li>
 *   <li>Event naming pattern: {entity}.{action}.{state}</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@SpringBootTest(
    classes = SpringBoot2BitemporalApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.profiles.active=test",
        "spring.config.name=application-springboot2-bitemporal",
        "reactive-bitemporal.profile=test",
        "logging.level.dev.mars.peegeeq=INFO",
        "management.endpoints.web.exposure.include=health,metrics"
    }
)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SpringBoot2BitemporalApplicationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(SpringBoot2BitemporalApplicationTest.class);
    
    @Container
    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @Autowired
    private SettlementService settlementService;
    @Autowired(required = false)
    private PeeGeeQManager peeGeeQManager;
    @Autowired
    private ReactiveBiTemporalProperties properties;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        logger.info("Configuring Spring Boot Reactive Bi-Temporal properties for TestContainer");
        SharedTestContainers.configureSharedProperties(registry);

        // Pattern 1 (Full Spring Boot Integration): No need to set system properties manually
        // The ReactiveBiTemporalConfig.configureSystemProperties() method automatically bridges
        // Spring properties to system properties when creating the PeeGeeQManager bean

        logger.info("Spring properties configured for TestContainer: host={}, port={}, database={}",
            postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());
    }

    @BeforeAll
    static void setupSchema() {
        logger.info("Initializing database schema for Spring Boot 2 bi-temporal application test");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer (ALL components)");
    }
    
    /**
     * Test that the application context loads successfully.
     */
    @Test
    void contextLoads() {
        logger.info("=== Spring Boot Reactive Bi-Temporal Application Context Load Test ===");
        logger.info("Testing that the application context loads successfully with WebFlux");
        
        assertNotNull(settlementService, "SettlementService should be autowired");
        assertNotNull(peeGeeQManager, "PeeGeeQManager should be autowired");
        assertFalse(properties.getDatabase().getPool().isShared(),
                "Spring should bind the reactive bitemporal test pool as isolated");
        assertFalse(peeGeeQManager.getConfiguration().getPoolConfig().isShared(),
            "Spring Boot reactive bitemporal tests must use an isolated Vert.x pool");
        
        logger.info(" Application context loaded successfully");
        logger.info(" PeeGeeQ Manager initialized");
        logger.info(" Bi-Temporal Event Store configured");
        logger.info(" WebFlux (Netty) server started");
        logger.info(" All reactive beans created and wired");
        logger.info("=== Test Completed Successfully ===");
    }

    @Test
    void recordsAndQueriesSettlementThroughLiveDatabase() {
        String instructionId = "INSTRUCTION-" + UUID.randomUUID();
        Instant eventTime = Instant.now().truncatedTo(ChronoUnit.MICROS);
        SettlementEvent settlement = new SettlementEvent(
            instructionId,
            "TRADE-" + UUID.randomUUID(),
            "COUNTERPARTY-A",
            new BigDecimal("125000.50"),
            "USD",
            LocalDate.now().plusDays(2),
            SettlementStatus.SUBMITTED,
            null,
            eventTime);

        StepVerifier.create(
                settlementService.recordSettlement("instruction.settlement.submitted", settlement)
                    .flatMapMany(recorded -> {
                        assertEquals("instruction.settlement.submitted", recorded.getEventType());
                        assertEquals(settlement, recorded.getPayload());
                        return settlementService.getSettlementHistory(instructionId);
                    }))
            .assertNext(stored -> {
                assertEquals("instruction.settlement.submitted", stored.getEventType());
                assertEquals(settlement, stored.getPayload());
                assertEquals(eventTime, stored.getValidTime());
            })
            .verifyComplete();
    }
}
