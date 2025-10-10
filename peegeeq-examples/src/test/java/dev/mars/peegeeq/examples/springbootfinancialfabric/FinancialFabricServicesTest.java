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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.examples.springbootfinancialfabric.events.*;
import dev.mars.peegeeq.examples.springbootfinancialfabric.service.*;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for Financial Fabric domain services.
 * 
 * Tests the complete trade lifecycle across all 5 domain services:
 * 1. TradeCaptureService - Trade capture and confirmation
 * 2. SettlementService - Settlement instruction and confirmation
 * 3. CashManagementService - Cash movement recording
 * 4. PositionService - Position update tracking
 * 5. RegulatoryReportingService - Regulatory report submission
 */
@SpringBootTest(
    classes = SpringBootFinancialFabricApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class FinancialFabricServicesTest {
    
    private static final Logger log = LoggerFactory.getLogger(FinancialFabricServicesTest.class);
    
    @Container
    static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @BeforeAll
    static void initializeSchema() {
        log.info("Initializing database schema for Financial Fabric test");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
        log.info("Database schema initialized successfully using centralized schema initializer (ALL components)");
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        log.info("Configuring properties for Financial Fabric Services test");
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
    private TradeCaptureService tradeCaptureService;
    
    @Autowired
    private SettlementService settlementService;
    
    @Autowired
    private CashManagementService cashManagementService;
    
    @Autowired
    private PositionService positionService;
    
    @Autowired
    private RegulatoryReportingService regulatoryReportingService;
    
    @Autowired
    private PeeGeeQManager peeGeeQManager;
    
    /**
     * Test complete trade lifecycle across all domain services.
     * 
     * Workflow:
     * 1. Capture trade
     * 2. Confirm trade
     * 3. Submit settlement instruction
     * 4. Record cash movement
     * 5. Update position
     * 6. Submit regulatory report
     */
    @Test
    void testCompleteTradeLifecycle() throws Exception {
        log.info("=== Testing Complete Trade Lifecycle ===");
        
        // Generate correlation ID for the entire workflow
        String correlationId = UUID.randomUUID().toString();
        String tradeId = "TRADE-" + UUID.randomUUID().toString();
        Instant validTime = Instant.now();
        
        DatabaseService databaseService = new PgDatabaseService(peeGeeQManager);

        // Execute the complete workflow in a transaction
        databaseService.getConnectionProvider().withTransaction("peegeeq-main", connection -> {

            // Step 1: Capture trade
            TradeEvent tradeEvent = new TradeEvent(
                tradeId,
                "AAPL",
                new BigDecimal("100"),
                new BigDecimal("150.50"),
                "COUNTERPARTY-A",
                "EQUITIES",
                "BUY",
                validTime
            );

            return Future.fromCompletionStage(tradeCaptureService.captureTrade(
                tradeEvent,
                correlationId,
                null,  // No causation for first event
                validTime,
                connection
            )).compose(tradeCloudEvent -> {
                log.info("✅ Step 1: Trade captured - eventId={}", tradeCloudEvent.getId());
                assertNotNull(tradeCloudEvent);
                assertEquals("com.fincorp.trading.equities.capture.completed.v1", tradeCloudEvent.getType());

                // Step 2: Confirm trade
                return Future.fromCompletionStage(tradeCaptureService.confirmTrade(
                    tradeId,
                    correlationId,
                    tradeCloudEvent.getId(),  // Causation links to trade capture
                    validTime,
                    connection
                ));
            }).compose(confirmCloudEvent -> {
                log.info("✅ Step 2: Trade confirmed - eventId={}", confirmCloudEvent.getId());
                assertNotNull(confirmCloudEvent);

                // Step 3: Submit settlement instruction
                String instructionId = "INST-" + UUID.randomUUID().toString();
                SettlementInstructionEvent instructionEvent = new SettlementInstructionEvent(
                    instructionId,
                    tradeId,
                    "AAPL",
                    new BigDecimal("100"),
                    "CUSTODIAN-X",
                    LocalDate.now().plusDays(2),
                    validTime
                );

                return Future.fromCompletionStage(settlementService.submitSettlementInstruction(
                    instructionEvent,
                    correlationId,
                    confirmCloudEvent.getId(),
                    validTime,
                    connection
                ));
            }).compose(settlementCloudEvent -> {
                log.info("✅ Step 3: Settlement instruction submitted - eventId={}", settlementCloudEvent.getId());
                assertNotNull(settlementCloudEvent);

                // Step 4: Record cash movement
                String movementId = "CASH-" + UUID.randomUUID().toString();
                CashMovementEvent cashEvent = new CashMovementEvent(
                    movementId,
                    tradeId,
                    new BigDecimal("15050.00"),  // 100 * 150.50
                    "USD",
                    "ACCOUNT-123",
                    "DEBIT",
                    validTime
                );

                return Future.fromCompletionStage(cashManagementService.recordCashMovement(
                    cashEvent,
                    correlationId,
                    settlementCloudEvent.getId(),
                    validTime,
                    connection
                ));
            }).compose(cashCloudEvent -> {
                log.info("✅ Step 4: Cash movement recorded - eventId={}", cashCloudEvent.getId());
                assertNotNull(cashCloudEvent);

                // Step 5: Update position
                String updateId = "POS-" + UUID.randomUUID().toString();
                PositionUpdateEvent positionEvent = new PositionUpdateEvent(
                    updateId,
                    tradeId,
                    "AAPL",
                    "ACCOUNT-123",
                    new BigDecimal("100"),
                    new BigDecimal("100"),
                    validTime
                );

                return Future.fromCompletionStage(positionService.recordPositionUpdate(
                    positionEvent,
                    correlationId,
                    cashCloudEvent.getId(),
                    validTime,
                    connection
                ));
            }).compose(positionCloudEvent -> {
                log.info("✅ Step 5: Position updated - eventId={}", positionCloudEvent.getId());
                assertNotNull(positionCloudEvent);

                // Step 6: Submit regulatory report
                String reportId = "REG-" + UUID.randomUUID().toString();
                RegulatoryReportEvent reportEvent = new RegulatoryReportEvent(
                    reportId,
                    tradeId,
                    "TRANSACTION_REPORT",
                    "MIFID_II",
                    validTime
                );

                return Future.fromCompletionStage(regulatoryReportingService.submitRegulatoryReport(
                    reportEvent,
                    correlationId,
                    positionCloudEvent.getId(),
                    validTime,
                    connection
                ));
            }).map(regulatoryCloudEvent -> {
                log.info("✅ Step 6: Regulatory report submitted - eventId={}", regulatoryCloudEvent.getId());
                assertNotNull(regulatoryCloudEvent);
                assertEquals("com.fincorp.regulatory.transaction.reported.v1", regulatoryCloudEvent.getType());

                log.info("✅ Complete trade lifecycle executed successfully");
                return (Void) null;
            });
        }).toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        
        log.info("✅ Complete Trade Lifecycle test passed");
    }
}

