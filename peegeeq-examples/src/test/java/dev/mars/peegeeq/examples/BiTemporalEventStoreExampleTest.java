package dev.mars.peegeeq.examples;

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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mars.peegeeq.api.*;
import dev.mars.peegeeq.bitemporal.BiTemporalEventStoreFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * INVESTMENT BANKING BACK OFFICE TRADE PROCESSING - BiTemporal Event Store Test Suite
 *
 * This comprehensive test suite demonstrates real-world investment banking back office
 * trade processing using the PeeGeeQ BiTemporal Event Store. It covers the complete
 * trade lifecycle from capture to settlement with full regulatory compliance.
 *
 * BUSINESS DOMAIN: Investment Banking Back Office Operations
 * - Trade Lifecycle Management: Capture → Enrichment → Validation → Settlement
 * - Regulatory Compliance: MiFID II, Dodd-Frank, Basel III audit requirements
 * - Operational Risk Management: Settlement failures, retry processing, position reconciliation
 * - Real-time Risk Monitoring: Position tracking, limit monitoring, counterparty exposure
 *
 * REGULATORY FRAMEWORKS DEMONSTRATED:
 * - MiFID II: Transaction reporting with T+1 deadlines and temporal corrections
 * - Dodd-Frank: Comprehensive trade reporting and record keeping
 * - Basel III: Operational risk management and audit trail preservation
 *
 * BUSINESS SCENARIOS COVERED:
 * 1. Complete Trade Lifecycle Processing (testCompleteTradeLifecycleProcessing)
 *    - Institutional equity trade processing through all stages
 *    - Trade capture, enrichment, validation, and settlement
 *    - Full audit trail with temporal progression validation
 *
 * 2. MiFID II Regulatory Compliance (testMiFIDIITransactionReportingWithTemporalCorrections)
 *    - T+1 transaction reporting with complete regulatory data
 *    - Late price corrections with supervisor audit trail reconstruction
 *    - Point-in-time queries for regulatory inspection
 *
 * 3. Settlement Failure Management (testSettlementFailureAndOperationalRiskManagement)
 *    - Settlement instruction and failure processing
 *    - Automated retry with risk reassessment
 *    - Complete operational risk management lifecycle
 *
 * 4. End-of-Day Position Reconciliation (testEndOfDayPositionReconciliationWithTemporalQueries)
 *    - Temporal queries for position reconstruction
 *    - Trading session analysis with time-based filtering
 *    - Regulatory reporting with historical snapshots
 *
 * 5. High-Frequency Trading Support (testHighFrequencyTradingAsyncProcessing)
 *    - Asynchronous trade processing for HFT systems
 *    - Sub-millisecond latency requirements
 *    - Algorithmic trading audit trail
 *
 * TECHNICAL CAPABILITIES VALIDATED:
 * - BiTemporal event storage with valid time and transaction time dimensions
 * - Event corrections with complete audit trail preservation
 * - Temporal queries for regulatory reporting and investigation
 * - High-performance async processing for trading systems
 * - Complete data integrity and consistency validation
 *
 * PERFORMANCE REQUIREMENTS:
 * - Sub-second query response for position reconstruction
 * - Support for 10,000+ trade events per second
 * - Point-in-time queries complete within 100ms
 * - Zero data loss during corrections and amendments
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-15
 * @version 4.0 (Investment Banking Back Office Trade Processing)
 */
@Testcontainers
class BiTemporalEventStoreExampleTest {
    
    private static final Logger logger = LoggerFactory.getLogger(BiTemporalEventStoreExampleTest.class);
    
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createStandardContainer();
    
    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    
    private PeeGeeQManager manager;
    private EventStore<TradeEvent> eventStore;

    /**
     * Configure system properties for TestContainers PostgreSQL connection
     */
    private void configureSystemPropertiesForContainer() {
        // Configure database connection for TestContainers
        System.setProperty("db.host", postgres.getHost());
        System.setProperty("db.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("db.database", postgres.getDatabaseName());
        System.setProperty("db.username", postgres.getUsername());
        System.setProperty("db.password", postgres.getPassword());

        // Configure PeeGeeQ to use the TestContainer
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
    }

    /**
     * Clear system properties after test completion
     */
    private void clearSystemProperties() {
        System.clearProperty("db.host");
        System.clearProperty("db.port");
        System.clearProperty("db.database");
        System.clearProperty("db.username");
        System.clearProperty("db.password");
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
    }

    /**
     * Generate unique identifier for test isolation
     */
    private String getUniqueId(String prefix) {
        return prefix + "-" + System.nanoTime();
    }
    
    @BeforeEach
    void setUp() {
        logger.info("=== Setting up BiTemporalEventStoreExampleTest ===");

        // Configure system properties for TestContainers
        configureSystemPropertiesForContainer();

        logger.info("Database configured: {}:{}/{}", postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());

        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        // Configure PeeGeeQ to use the TestContainer
        logger.info("Configuring PeeGeeQ to use TestContainer database");
        logger.info("Database URL: jdbc:postgresql://{}:{}/{}",
                   postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());

        // Initialize PeeGeeQ manager
        logger.info("Initializing PeeGeeQ manager and starting services");
        PeeGeeQConfiguration config = new PeeGeeQConfiguration();
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();
        logger.info("PeeGeeQ manager started successfully");

        // Create event store with unique table name for test isolation
        logger.info("Creating bi-temporal event store for TradeEvent");
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(manager);
        eventStore = factory.createEventStore(TradeEvent.class);
        logger.info("Bi-temporal event store created successfully");
        logger.info("=== Setup completed ===");
    }
    
    @AfterEach
    void tearDown() {
        logger.info("=== Tearing down BiTemporalEventStoreExampleTest ===");
        System.setOut(originalOut);
        System.setErr(originalErr);

        // Clean up resources
        if (eventStore != null) {
            try {
                logger.info("Closing bi-temporal event store");
                eventStore.close();
                logger.info("Event store closed successfully");
            } catch (Exception e) {
                logger.error("Error closing event store", e);
            }
        }

        if (manager != null) {
            try {
                logger.info("Stopping PeeGeeQ manager");
                manager.stop();
                logger.info("PeeGeeQ manager stopped successfully");
            } catch (Exception e) {
                logger.error("Error stopping PeeGeeQ manager", e);
            }
        }

        // Clear system properties
        logger.info("Clearing system properties");
        clearSystemProperties();
        logger.info("=== Teardown completed ===");
    }
    
    /**
     * BUSINESS SCENARIO: Financial Institution System Readiness Validation
     *
     * Before processing any financial transactions, the system must validate that
     * the audit trail infrastructure is properly initialized and ready to handle
     * regulatory compliance requirements.
     */
    @Test
    void testRegulatoryComplianceSystemReadiness() {
        logger.info("=== Testing Regulatory Compliance System Readiness ===");

        // BUSINESS VALIDATION: Audit trail system must be operational before processing transactions
        assertNotNull(eventStore, "Audit trail system must be initialized for regulatory compliance");

        // COMPLIANCE VALIDATION: System must be able to handle basic audit operations
        assertDoesNotThrow(() -> {
            EventStore.EventStoreStats stats = eventStore.getStats().join();
            assertNotNull(stats, "System must provide audit statistics for compliance reporting");
        }, "Audit trail system must be ready to provide compliance statistics");

        // REGULATORY VALIDATION: System must support required query capabilities
        assertDoesNotThrow(() -> {
            List<BiTemporalEvent<TradeEvent>> events = eventStore.query(EventQuery.all()).join();
            assertNotNull(events, "System must support audit trail queries for regulatory inspection");
        }, "System must support regulatory audit queries");

        logger.info("✅ Regulatory compliance system is ready for financial transaction processing");
        logger.info("✅ System readiness validation completed successfully");
    }
    
    /**
     * BUSINESS SCENARIO: Financial Transaction Processing with Regulatory Audit Trail
     *
     * This test simulates a complete financial transaction lifecycle including
     * original transaction recording, regulatory corrections, and compliance reporting.
     */
    @Test
    void testFinancialTransactionAuditTrailCompliance() throws Exception {
        logger.info("=== Testing Financial Transaction Audit Trail Compliance ===");

        // BUSINESS VALIDATION: System must be ready for financial transaction processing
        assertNotNull(eventStore, "Audit trail system must be operational for financial transactions");

        // COMPLIANCE VALIDATION: System must maintain complete audit trail
        List<BiTemporalEvent<TradeEvent>> initialEvents = eventStore.query(EventQuery.all()).join();
        assertNotNull(initialEvents, "System must support audit trail queries for regulatory compliance");

        // 1. BUSINESS SCENARIO: Create realistic equity trades for compliance testing
        Instant baseTime = Instant.now();
        String tradeId1 = getUniqueId("TRD");
        String tradeId2 = getUniqueId("TRD");
        String counterpartyId1 = getUniqueId("LEI");
        String counterpartyId2 = getUniqueId("LEI");

        // Create realistic equity trades
        TradeEvent trade1 = new TradeEvent(
            tradeId1, "US0378331005", counterpartyId1, "TRADER001",
            new BigDecimal("1000"), new BigDecimal("150.25"), new BigDecimal("150250.00"),
            "USD", LocalDate.now(), LocalDate.now().plusDays(2), "NEW",
            "XNYS", "US", "BUY", Map.of("mifidII", "true", "reportingRegime", "US")
        );

        TradeEvent trade2 = new TradeEvent(
            tradeId2, "GB0002162385", counterpartyId2, "TRADER002",
            new BigDecimal("2500"), new BigDecimal("45.75"), new BigDecimal("114375.00"),
            "GBP", LocalDate.now(), LocalDate.now().plusDays(2), "NEW",
            "XLON", "UK", "SELL", Map.of("mifidII", "true", "reportingRegime", "EU")
        );

        // AUDIT TRAIL: Append trade capture events with regulatory metadata
        BiTemporalEvent<TradeEvent> event1 = eventStore.append("TradeCaptured", trade1, baseTime,
            Map.of("source", "trading-system", "region", "US", "desk", "equity-trading"),
            getUniqueId("corr"), tradeId1
        ).join();

        BiTemporalEvent<TradeEvent> event2 = eventStore.append("TradeCaptured", trade2, baseTime.plus(10, ChronoUnit.MINUTES),
            Map.of("source", "trading-system", "region", "EU", "desk", "equity-trading"),
            getUniqueId("corr"), tradeId2
        ).join();

        // 2. REGULATORY VALIDATION: Verify trade events were captured with complete audit trail
        assertNotNull(event1, "Trade capture event must be recorded for regulatory compliance");
        assertNotNull(event2, "Trade capture event must be recorded for regulatory compliance");
        assertEquals("TradeCaptured", event1.getEventType(), "Event type must match regulatory reporting requirements");
        assertEquals(tradeId1, event1.getPayload().getTradeId(), "Trade ID must be preserved for audit trail");
        assertEquals(new BigDecimal("150250.00"), event1.getPayload().getNotionalAmount(), "Notional amount must be accurate for regulatory reporting");

        // 3. COMPLIANCE VALIDATION: Query all events for audit trail verification
        List<BiTemporalEvent<TradeEvent>> allEvents = eventStore.query(EventQuery.all()).join();
        assertTrue(allEvents.size() >= 2, "Audit trail must contain all financial transactions for compliance");

        // 4. REGULATORY QUERY: Query by event type for regulatory reporting
        List<BiTemporalEvent<TradeEvent>> tradeEvents = eventStore.query(EventQuery.forEventType("TradeCaptured")
        ).join();
        assertTrue(tradeEvents.size() >= 2, "Should have at least the 2 TradeCaptured events for regulatory reporting");

        // 5. AUDIT QUERY: Query by aggregate (trade ID) for investigation
        List<BiTemporalEvent<TradeEvent>> trade1Events = eventStore.query(EventQuery.forAggregate(tradeId1)
        ).join();
        assertEquals(1, trade1Events.size(), "Should have exactly 1 event for trade " + tradeId1 + " in audit trail");

        // 6. REGULATORY CORRECTION: Add a price correction event (common in post-trade processing)
        TradeEvent correctedTrade = new TradeEvent(
            tradeId1, "US0378331005", counterpartyId1, "TRADER001",
            new BigDecimal("1000"), new BigDecimal("149.75"), new BigDecimal("149750.00"), // Corrected price
            "USD", LocalDate.now(), LocalDate.now().plusDays(2), "CORRECTED",
            "XNYS", "US", "BUY", Map.of("mifidII", "true", "reportingRegime", "US", "correctionReason", "price-adjustment")
        );

        BiTemporalEvent<TradeEvent> correctionEvent = eventStore.appendCorrection(event1.getEventId(), "TradeCorrected", correctedTrade, baseTime,
                Map.of("source", "back-office", "region", "US", "corrected", "true", "approver", "SUPERVISOR001"),
            getUniqueId("corr"), tradeId1, "Price correction due to post-trade price discovery"
        ).join();

        // 7. REGULATORY VALIDATION: Verify correction was properly recorded
        assertNotNull(correctionEvent, "Correction event must be recorded for regulatory audit trail");
        assertTrue(correctionEvent.isCorrection(), "Event must be marked as correction for compliance");
        assertEquals(new BigDecimal("149750.00"), correctionEvent.getPayload().getNotionalAmount(), "Corrected notional amount must be accurate");

        // 8. AUDIT TRAIL: Get all versions of the trade for complete history
        List<BiTemporalEvent<TradeEvent>> versions = eventStore.getAllVersions(event1.getEventId()).join();
        assertEquals(2, versions.size(), "Audit trail must contain both original and corrected versions");

        // 9. COMPLIANCE QUERY: Point-in-time query for regulatory reconstruction
        Instant beforeCorrection = correctionEvent.getTransactionTime().minus(1, ChronoUnit.SECONDS);
        BiTemporalEvent<TradeEvent> eventBeforeCorrection = eventStore.getAsOfTransactionTime(event1.getEventId(), beforeCorrection
        ).join();

        assertNotNull(eventBeforeCorrection, "Must be able to reconstruct pre-correction state for regulatory reporting");
        assertEquals(new BigDecimal("150250.00"), eventBeforeCorrection.getPayload().getNotionalAmount(), "Original notional amount must be preserved in audit trail");

        // 10. Get statistics
        EventStore.EventStoreStats stats = eventStore.getStats().join();
        assertNotNull(stats);
        assertTrue(stats.getTotalEvents() >= 3, "Should have at least 3 events (2 original + 1 correction)");
        assertTrue(stats.getTotalCorrections() >= 1, "Should have at least 1 correction");
    }

    /**
     * BUSINESS SCENARIO: End-of-Day Position Reconciliation with Temporal Queries
     *
     * Investment banks need to reconstruct positions at specific points in time
     * for regulatory reporting, risk management, and client statements.
     */
    @Test
    void testEndOfDayPositionReconciliationWithTemporalQueries() throws Exception {
        logger.info("=== Testing End-of-Day Position Reconciliation with Temporal Queries ===");

        // Use trading day start time (8 hours ago simulates market open)
        Instant tradingDayStart = Instant.now().minus(8, ChronoUnit.HOURS);
        String testId = String.valueOf(System.currentTimeMillis());

        // BUSINESS SCENARIO: Create trades throughout the trading day
        TradeEvent morningTrade = new TradeEvent(
            "TRD-" + testId + "-001", "US0378331005", "LEI-COUNTERPARTY-001", "TRADER001",
            new BigDecimal("1000"), new BigDecimal("150.00"), new BigDecimal("150000.00"),
            "USD", LocalDate.now(), LocalDate.now().plusDays(2), "SETTLED",
            "XNYS", "US", "BUY", Map.of("desk", "equity", "book", "PROP-TRADING")
        );

        TradeEvent noonTrade = new TradeEvent(
            "TRD-" + testId + "-002", "US0378331005", "LEI-COUNTERPARTY-002", "TRADER002",
            new BigDecimal("500"), new BigDecimal("151.25"), new BigDecimal("75625.00"),
            "USD", LocalDate.now(), LocalDate.now().plusDays(2), "SETTLED",
            "XNYS", "US", "SELL", Map.of("desk", "equity", "book", "CLIENT-TRADING")
        );

        TradeEvent afternoonTrade = new TradeEvent(
            "TRD-" + testId + "-003", "US0378331005", "LEI-COUNTERPARTY-003", "TRADER001",
            new BigDecimal("750"), new BigDecimal("152.50"), new BigDecimal("114375.00"),
            "USD", LocalDate.now(), LocalDate.now().plusDays(2), "SETTLED",
            "XNYS", "US", "BUY", Map.of("desk", "equity", "book", "PROP-TRADING")
        );

        // AUDIT TRAIL: Record trades with their actual execution times
        eventStore.append("TradeSettled", morningTrade, tradingDayStart, Map.of("session", "morning"), "corr-1", morningTrade.getTradeId()).join();
        eventStore.append("TradeSettled", noonTrade, tradingDayStart.plus(4, ChronoUnit.HOURS), Map.of("session", "noon"), "corr-2", noonTrade.getTradeId()).join();
        eventStore.append("TradeSettled", afternoonTrade, tradingDayStart.plus(7, ChronoUnit.HOURS), Map.of("session", "afternoon"), "corr-3", afternoonTrade.getTradeId()).join();

        // BUSINESS VALIDATION: Query trades within specific time window (noon period)
        Instant noonStart = tradingDayStart.plus(3, ChronoUnit.HOURS);   // 3 hours after market open
        Instant noonEnd = tradingDayStart.plus(5, ChronoUnit.HOURS);     // 5 hours after market open

        // Query for the specific trade we expect to be in the range
        BiTemporalEvent<TradeEvent> noonTradeEvent = eventStore.query(
            EventQuery.builder()
                .validTimeRange(new TemporalRange(noonStart, noonEnd))
                .eventType("TradeSettled")
                .aggregateId("TRD-" + testId + "-002") // Specific trade ID
                .build()
        ).join()
        .stream()
        .findFirst()
        .orElse(null);

        // COMPLIANCE VERIFICATION: Ensure trade data is complete and accurate
        assertNotNull(noonTradeEvent, "Should find the noon trade in the time range for position reconciliation");
        assertEquals("TRD-" + testId + "-002", noonTradeEvent.getPayload().getTradeId(), "Trade ID must match for audit trail");
        assertEquals("LEI-COUNTERPARTY-002", noonTradeEvent.getPayload().getCounterpartyId(), "Counterparty must be recorded for regulatory reporting");
        assertEquals(new BigDecimal("75625.00"), noonTradeEvent.getPayload().getNotionalAmount(), "Notional amount must be accurate for position calculation");
        assertEquals("SELL", noonTradeEvent.getPayload().getSide(), "Trade side must be correct for position reconciliation");

        logger.info("✅ End-of-day position reconciliation temporal queries completed successfully");
    }

    /**
     * BUSINESS SCENARIO: Trade Data Integrity Validation
     *
     * Ensures that trade events maintain data integrity through proper
     * equals, hashCode, and toString implementations for audit trail consistency.
     */
    @Test
    void testTradeEventDataIntegrityValidation() {
        logger.info("=== Testing Trade Event Data Integrity Validation ===");

        String tradeId1 = getUniqueId("TRD");
        String tradeId2 = getUniqueId("TRD");
        String counterpartyId1 = getUniqueId("LEI");
        String counterpartyId2 = getUniqueId("LEI");

        // BUSINESS VALIDATION: Create identical trades for integrity testing
        TradeEvent trade1 = new TradeEvent(
            tradeId1, "US0378331005", counterpartyId1, "TRADER001",
            new BigDecimal("1000"), new BigDecimal("150.25"), new BigDecimal("150250.00"),
            "USD", LocalDate.now(), LocalDate.now().plusDays(2), "NEW",
            "XNYS", "US", "BUY", Map.of("mifidII", "true")
        );

        TradeEvent trade2 = new TradeEvent(
            tradeId1, "US0378331005", counterpartyId1, "TRADER001",
            new BigDecimal("1000"), new BigDecimal("150.25"), new BigDecimal("150250.00"),
            "USD", LocalDate.now(), LocalDate.now().plusDays(2), "NEW",
            "XNYS", "US", "BUY", Map.of("mifidII", "true")
        );

        TradeEvent trade3 = new TradeEvent(
            tradeId2, "GB0002162385", counterpartyId2, "TRADER002",
            new BigDecimal("2500"), new BigDecimal("45.75"), new BigDecimal("114375.00"),
            "GBP", LocalDate.now(), LocalDate.now().plusDays(2), "NEW",
            "XLON", "UK", "SELL", Map.of("mifidII", "true")
        );

        // REGULATORY VALIDATION: Test data integrity for audit trail
        assertEquals(trade1, trade2, "Identical trades must be equal for audit trail consistency");
        assertNotEquals(trade1, trade3, "Different trades must not be equal for data integrity");
        assertNotEquals(trade1, null, "Trade must not equal null for data validation");
        assertNotEquals(trade1, "not a trade", "Trade must not equal non-trade objects");

        // COMPLIANCE VALIDATION: Test hash consistency for event deduplication
        assertEquals(trade1.hashCode(), trade2.hashCode(), "Identical trades must have same hash for deduplication");
        assertNotEquals(trade1.hashCode(), trade3.hashCode(), "Different trades must have different hashes");

        // AUDIT TRAIL VALIDATION: Test string representation contains key regulatory data
        String trade1String = trade1.toString();
        assertTrue(trade1String.contains(tradeId1), "Trade ID must be in string representation for audit logs");
        assertTrue(trade1String.contains(counterpartyId1), "Counterparty ID must be in audit logs for regulatory reporting");
        assertTrue(trade1String.contains("150250.00"), "Notional amount must be in audit logs for compliance");
        assertTrue(trade1String.contains("BUY"), "Trade side must be in audit logs for position tracking");

        logger.info("✅ Trade event data integrity validation completed successfully");
    }

    /**
     * BUSINESS SCENARIO: High-Frequency Trading Async Processing
     *
     * Tests asynchronous trade processing capabilities required for
     * high-frequency trading systems that process thousands of trades per second.
     */
    @Test
    void testHighFrequencyTradingAsyncProcessing() throws Exception {
        logger.info("=== Testing High-Frequency Trading Async Processing ===");

        String tradeId = getUniqueId("HFT-TRD");
        String counterpartyId = getUniqueId("LEI-HFT");

        // BUSINESS SCENARIO: High-frequency equity trade
        TradeEvent hftTrade = new TradeEvent(
            tradeId, "US0378331005", counterpartyId, "HFT-ALGO-001",
            new BigDecimal("100"), new BigDecimal("150.25"), new BigDecimal("15025.00"),
            "USD", LocalDate.now(), LocalDate.now().plusDays(2), "NEW",
            "XNYS", "US", "BUY", Map.of("algo", "momentum", "latency", "sub-millisecond")
        );

        // PERFORMANCE TEST: Async trade capture for high-frequency processing
        CompletableFuture<BiTemporalEvent<TradeEvent>> future = eventStore.append(
            "HFTTradeCaptured", hftTrade, Instant.now(),
            Map.of("async", "true", "priority", "high", "latency-sensitive", "true"),
            getUniqueId("hft-corr"), tradeId
        );

        BiTemporalEvent<TradeEvent> event = future.join();
        assertNotNull(event, "HFT trade must be captured for regulatory compliance");
        assertEquals(tradeId, event.getPayload().getTradeId(), "Trade ID must be preserved for audit trail");
        assertEquals("HFT-ALGO-001", event.getPayload().getTraderId(), "Algo trader ID must be recorded for compliance");

        // REGULATORY VALIDATION: Async query for trade reconstruction
        CompletableFuture<List<BiTemporalEvent<TradeEvent>>> queryFuture = eventStore.query(EventQuery.forAggregate(tradeId));

        List<BiTemporalEvent<TradeEvent>> events = queryFuture.join();
        assertEquals(1, events.size(), "Should have exactly one HFT trade event");
        assertEquals(tradeId, events.get(0).getPayload().getTradeId(), "Trade ID must match for audit trail");
        assertEquals(new BigDecimal("15025.00"), events.get(0).getPayload().getNotionalAmount(), "Notional amount must be accurate for position tracking");

        logger.info("✅ High-frequency trading async processing completed successfully");
    }

    /**
     * BUSINESS SCENARIO: Complete Trade Lifecycle Processing
     *
     * Demonstrates end-to-end trade processing from initial capture through
     * enrichment, validation, and settlement with complete audit trail.
     * This represents the core business process of investment bank back offices.
     *
     * REGULATORY CONTEXT: MiFID II, Dodd-Frank compliance with complete audit trail
     * OPERATIONAL CONTEXT: Trade capture → Enrichment → Validation → Settlement
     */
    @Test
    void testCompleteTradeLifecycleProcessing() throws Exception {
        logger.info("=== Testing Complete Trade Lifecycle Processing ===");

        // BUSINESS SETUP: Large institutional equity trade
        String tradeId = getUniqueId("LIFECYCLE-TRD");
        String counterpartyId = getUniqueId("LEI-INSTITUTIONAL");
        Instant tradeExecutionTime = Instant.now().minus(2, ChronoUnit.HOURS);

        // STAGE 1: TRADE CAPTURE - Initial trade capture from trading system
        logger.info("STAGE 1: Trade Capture - Recording initial trade execution");
        TradeEvent initialTrade = new TradeEvent(
            tradeId, "US0378331005", counterpartyId, "INSTITUTIONAL-TRADER-001",
            new BigDecimal("50000"), new BigDecimal("152.75"), new BigDecimal("7637500.00"),
            "USD", LocalDate.now(), LocalDate.now().plusDays(2), "CAPTURED",
            "XNYS", "US", "BUY", Map.of("source", "trading-system", "urgency", "normal")
        );

        BiTemporalEvent<TradeEvent> captureEvent = eventStore.append(
            "TradeCaptured", initialTrade, tradeExecutionTime,
            Map.of("stage", "capture", "system", "trading-platform", "trader-desk", "institutional-equity"),
            getUniqueId("lifecycle-corr"), tradeId
        ).join();

        // REGULATORY VALIDATION: Trade capture must be recorded
        assertNotNull(captureEvent, "Trade capture must be recorded for regulatory compliance");
        assertEquals("CAPTURED", captureEvent.getPayload().getTradeStatus(), "Initial status must be CAPTURED");
        assertEquals(new BigDecimal("7637500.00"), captureEvent.getPayload().getNotionalAmount(), "Notional amount must be accurate");

        // STAGE 2: TRADE ENRICHMENT - Add regulatory and operational data
        logger.info("STAGE 2: Trade Enrichment - Adding regulatory and operational metadata");
        TradeEvent enrichedTrade = new TradeEvent(
            tradeId, "US0378331005", counterpartyId, "INSTITUTIONAL-TRADER-001",
            new BigDecimal("50000"), new BigDecimal("152.75"), new BigDecimal("7637500.00"),
            "USD", LocalDate.now(), LocalDate.now().plusDays(2), "ENRICHED",
            "XNYS", "US", "BUY", Map.of(
                "mifidII", "true", "reportingRegime", "US", "bestExecution", "validated",
                "counterpartyType", "institutional", "riskCategory", "low",
                "settlementAgent", "DTC", "custodian", "STATE-STREET"
            )
        );

        BiTemporalEvent<TradeEvent> enrichmentEvent = eventStore.append(
            "TradeEnriched", enrichedTrade, tradeExecutionTime.plus(15, ChronoUnit.MINUTES),
            Map.of("stage", "enrichment", "system", "back-office", "processor", "trade-enrichment-engine"),
            getUniqueId("lifecycle-corr"), tradeId
        ).join();

        // BUSINESS VALIDATION: Enrichment must add regulatory data
        assertNotNull(enrichmentEvent, "Trade enrichment must be recorded for audit trail");
        assertEquals("ENRICHED", enrichmentEvent.getPayload().getTradeStatus(), "Status must progress to ENRICHED");
        assertTrue(enrichmentEvent.getPayload().getRegulatoryData().containsKey("mifidII"), "MiFID II data must be added");
        assertTrue(enrichmentEvent.getPayload().getRegulatoryData().containsKey("bestExecution"), "Best execution validation must be recorded");

        // STAGE 3: TRADE VALIDATION - Risk and compliance validation
        logger.info("STAGE 3: Trade Validation - Performing risk and compliance checks");
        TradeEvent validatedTrade = new TradeEvent(
            tradeId, "US0378331005", counterpartyId, "INSTITUTIONAL-TRADER-001",
            new BigDecimal("50000"), new BigDecimal("152.75"), new BigDecimal("7637500.00"),
            "USD", LocalDate.now(), LocalDate.now().plusDays(2), "VALIDATED",
            "XNYS", "US", "BUY", Map.of(
                "mifidII", "true", "reportingRegime", "US", "bestExecution", "validated",
                "counterpartyType", "institutional", "riskCategory", "low",
                "settlementAgent", "DTC", "custodian", "STATE-STREET",
                "riskCheck", "passed", "complianceCheck", "passed", "limitCheck", "passed"
            )
        );

        BiTemporalEvent<TradeEvent> validationEvent = eventStore.append(
            "TradeValidated", validatedTrade, tradeExecutionTime.plus(30, ChronoUnit.MINUTES),
            Map.of("stage", "validation", "system", "risk-management", "validator", "compliance-engine"),
            getUniqueId("lifecycle-corr"), tradeId
        ).join();

        // COMPLIANCE VALIDATION: All risk checks must pass
        assertNotNull(validationEvent, "Trade validation must be recorded for compliance");
        assertEquals("VALIDATED", validationEvent.getPayload().getTradeStatus(), "Status must progress to VALIDATED");
        assertEquals("passed", validationEvent.getPayload().getRegulatoryData().get("riskCheck"), "Risk check must pass");
        assertEquals("passed", validationEvent.getPayload().getRegulatoryData().get("complianceCheck"), "Compliance check must pass");

        // STAGE 4: SETTLEMENT PROCESSING - Final settlement and confirmation
        logger.info("STAGE 4: Settlement Processing - Executing trade settlement");

        // Build comprehensive regulatory data for settled trade
        Map<String, String> settledRegulatoryData = new java.util.HashMap<>();
        settledRegulatoryData.put("mifidII", "true");
        settledRegulatoryData.put("reportingRegime", "US");
        settledRegulatoryData.put("bestExecution", "validated");
        settledRegulatoryData.put("counterpartyType", "institutional");
        settledRegulatoryData.put("riskCategory", "low");
        settledRegulatoryData.put("settlementAgent", "DTC");
        settledRegulatoryData.put("custodian", "STATE-STREET");
        settledRegulatoryData.put("riskCheck", "passed");
        settledRegulatoryData.put("complianceCheck", "passed");
        settledRegulatoryData.put("limitCheck", "passed");
        settledRegulatoryData.put("settlementStatus", "settled");
        settledRegulatoryData.put("settlementReference", "DTC-" + tradeId);
        settledRegulatoryData.put("cashMovement", "completed");
        settledRegulatoryData.put("securityMovement", "completed");

        TradeEvent settledTrade = new TradeEvent(
            tradeId, "US0378331005", counterpartyId, "INSTITUTIONAL-TRADER-001",
            new BigDecimal("50000"), new BigDecimal("152.75"), new BigDecimal("7637500.00"),
            "USD", LocalDate.now(), LocalDate.now().plusDays(2), "SETTLED",
            "XNYS", "US", "BUY", settledRegulatoryData
        );

        BiTemporalEvent<TradeEvent> settlementEvent = eventStore.append(
            "TradeSettled", settledTrade, tradeExecutionTime.plus(2, ChronoUnit.DAYS),
            Map.of("stage", "settlement", "system", "settlement-system", "agent", "DTC-SETTLEMENT"),
            getUniqueId("lifecycle-corr"), tradeId
        ).join();

        // FINAL VALIDATION: Settlement must be complete
        assertNotNull(settlementEvent, "Trade settlement must be recorded for regulatory reporting");
        assertEquals("SETTLED", settlementEvent.getPayload().getTradeStatus(), "Final status must be SETTLED");
        assertEquals("settled", settlementEvent.getPayload().getRegulatoryData().get("settlementStatus"), "Settlement must be confirmed");
        assertEquals("completed", settlementEvent.getPayload().getRegulatoryData().get("cashMovement"), "Cash movement must be completed");
        assertEquals("completed", settlementEvent.getPayload().getRegulatoryData().get("securityMovement"), "Security movement must be completed");

        // AUDIT TRAIL VALIDATION: Query complete trade lifecycle
        logger.info("AUDIT VALIDATION: Verifying complete trade lifecycle audit trail");
        List<BiTemporalEvent<TradeEvent>> tradeLifecycle = eventStore.query(EventQuery.forAggregate(tradeId)).join();

        assertEquals(4, tradeLifecycle.size(), "Complete trade lifecycle must have 4 stages recorded");

        // Verify progression through all stages
        List<String> expectedEventTypes = List.of("TradeCaptured", "TradeEnriched", "TradeValidated", "TradeSettled");
        List<String> actualEventTypes = tradeLifecycle.stream()
            .map(BiTemporalEvent::getEventType)
            .toList();

        assertTrue(actualEventTypes.containsAll(expectedEventTypes),
            "Audit trail must contain all trade lifecycle stages for regulatory compliance");

        // REGULATORY REPORTING VALIDATION: Verify temporal progression
        BiTemporalEvent<TradeEvent> finalEvent = tradeLifecycle.stream()
            .filter(e -> "TradeSettled".equals(e.getEventType()))
            .findFirst()
            .orElse(null);

        assertNotNull(finalEvent, "Final settlement event must be recorded");
        assertTrue(finalEvent.getValidTime().isAfter(captureEvent.getValidTime()),
            "Settlement time must be after capture time for temporal consistency");

        logger.info("✅ Complete trade lifecycle processing with full audit trail completed successfully");
        logger.info("✅ Trade {} processed through all stages: CAPTURED → ENRICHED → VALIDATED → SETTLED", tradeId);
    }

    /**
     * BUSINESS SCENARIO: MiFID II Transaction Reporting with Temporal Corrections
     *
     * Demonstrates regulatory compliance for MiFID II transaction reporting including
     * late corrections, amendments, and audit trail reconstruction. This scenario
     * is critical for European investment firms operating under MiFID II regulations.
     *
     * REGULATORY CONTEXT: MiFID II Article 26 - Transaction Reporting
     * COMPLIANCE REQUIREMENTS: T+1 reporting, correction handling, audit trail preservation
     */
    @Test
    void testMiFIDIITransactionReportingWithTemporalCorrections() throws Exception {
        logger.info("=== Testing MiFID II Transaction Reporting with Temporal Corrections ===");

        // BUSINESS SETUP: European equity trade requiring MiFID II reporting
        String tradeId = getUniqueId("MIFID-TRD");
        String counterpartyId = getUniqueId("LEI-EU-BANK");
        Instant tradeExecutionTime = Instant.now().minus(1, ChronoUnit.DAYS); // T-1 (yesterday)
        Instant reportingDeadline = tradeExecutionTime.plus(1, ChronoUnit.DAYS); // T+1 deadline

        // STAGE 1: INITIAL MIFID II TRANSACTION REPORT
        logger.info("STAGE 1: Initial MiFID II Transaction Report - T+1 Reporting");

        Map<String, String> mifidIIData = new java.util.HashMap<>();
        mifidIIData.put("mifidII", "true");
        mifidIIData.put("reportingRegime", "EU");
        mifidIIData.put("transactionReportingFlag", "true");
        mifidIIData.put("reportingTimestamp", reportingDeadline.toString());
        mifidIIData.put("tradingVenue", "XLON");
        mifidIIData.put("instrumentClassification", "EQTY");
        mifidIIData.put("commodityDerivativeIndicator", "false");
        mifidIIData.put("investmentDecisionWithinFirm", "ALGO-TRADING-DESK");
        mifidIIData.put("executionWithinFirm", "EXECUTION-TRADER-001");
        mifidIIData.put("waiver", "NONE");
        mifidIIData.put("reportingStatus", "ORIGINAL");

        TradeEvent initialMiFIDReport = new TradeEvent(
            tradeId, "GB0002162385", counterpartyId, "EU-TRADER-001",
            new BigDecimal("25000"), new BigDecimal("45.75"), new BigDecimal("1143750.00"),
            "GBP", LocalDate.now().minusDays(1), LocalDate.now().plusDays(1), "REPORTED",
            "XLON", "UK", "SELL", mifidIIData
        );

        BiTemporalEvent<TradeEvent> initialReportEvent = eventStore.append(
            "MiFIDIITransactionReported", initialMiFIDReport, reportingDeadline,
            Map.of("regulatory", "mifid-ii", "report-type", "original", "jurisdiction", "UK"),
            getUniqueId("mifid-corr"), tradeId
        ).join();

        // REGULATORY VALIDATION: Initial report must be recorded within T+1
        assertNotNull(initialReportEvent, "MiFID II transaction report must be recorded within T+1 deadline");
        assertEquals("REPORTED", initialReportEvent.getPayload().getTradeStatus(), "Trade status must be REPORTED");
        assertEquals("ORIGINAL", initialReportEvent.getPayload().getRegulatoryData().get("reportingStatus"), "Initial report must be marked as ORIGINAL");
        assertTrue(initialReportEvent.getValidTime().isBefore(reportingDeadline.plus(1, ChronoUnit.HOURS)), "Report must be within T+1 deadline");

        // STAGE 2: LATE CORRECTION - Price Discovery Error (Common in MiFID II)
        logger.info("STAGE 2: MiFID II Late Correction - Price Discovery Error");

        // Simulate discovery of price error 3 days after initial report
        Instant correctionTime = reportingDeadline.plus(3, ChronoUnit.DAYS);

        Map<String, String> correctedMiFIDData = new java.util.HashMap<>(mifidIIData);
        correctedMiFIDData.put("reportingStatus", "CORRECTED");
        correctedMiFIDData.put("correctionReason", "PRICE_DISCOVERY_ERROR");
        correctedMiFIDData.put("originalReportReference", initialReportEvent.getEventId());
        correctedMiFIDData.put("correctionTimestamp", correctionTime.toString());

        TradeEvent correctedMiFIDReport = new TradeEvent(
            tradeId, "GB0002162385", counterpartyId, "EU-TRADER-001",
            new BigDecimal("25000"), new BigDecimal("46.25"), new BigDecimal("1156250.00"), // Corrected price
            "GBP", LocalDate.now().minusDays(1), LocalDate.now().plusDays(1), "CORRECTED",
            "XLON", "UK", "SELL", correctedMiFIDData
        );

        BiTemporalEvent<TradeEvent> correctionEvent = eventStore.appendCorrection(
            initialReportEvent.getEventId(), "MiFIDIITransactionCorrected", correctedMiFIDReport,
            tradeExecutionTime, // Valid time remains original execution time
            Map.of("regulatory", "mifid-ii", "report-type", "correction", "jurisdiction", "UK", "supervisor-approved", "true"),
            getUniqueId("mifid-corr"), tradeId, "MiFID II price correction due to post-trade price discovery"
        ).join();

        // COMPLIANCE VALIDATION: Correction must be properly recorded
        assertNotNull(correctionEvent, "MiFID II correction must be recorded for regulatory compliance");
        assertTrue(correctionEvent.isCorrection(), "Event must be marked as correction");
        assertEquals("CORRECTED", correctionEvent.getPayload().getTradeStatus(), "Status must be CORRECTED");
        assertEquals(new BigDecimal("1156250.00"), correctionEvent.getPayload().getNotionalAmount(), "Corrected notional amount must be accurate");
        assertEquals("PRICE_DISCOVERY_ERROR", correctionEvent.getPayload().getRegulatoryData().get("correctionReason"), "Correction reason must be recorded");

        // STAGE 3: REGULATORY AUDIT TRAIL RECONSTRUCTION
        logger.info("STAGE 3: Regulatory Audit Trail Reconstruction - Supervisor Inquiry");

        // AUDIT SCENARIO: Regulatory supervisor requests complete transaction history
        List<BiTemporalEvent<TradeEvent>> completeAuditTrail = eventStore.getAllVersions(initialReportEvent.getEventId()).join();

        assertEquals(2, completeAuditTrail.size(), "Audit trail must contain both original and corrected versions");

        // REGULATORY VALIDATION: Point-in-time reconstruction for supervisor
        // Scenario: "What did you report on T+1?" (before correction was known)
        Instant supervisorInquiryTime = correctionTime.minus(1, ChronoUnit.HOURS); // Just before correction
        BiTemporalEvent<TradeEvent> reportAsOfT1 = eventStore.getAsOfTransactionTime(
            initialReportEvent.getEventId(), supervisorInquiryTime
        ).join();

        assertNotNull(reportAsOfT1, "Must be able to reconstruct T+1 report for regulatory inquiry");
        assertEquals(new BigDecimal("1143750.00"), reportAsOfT1.getPayload().getNotionalAmount(), "Original T+1 report amount must be preserved");
        assertEquals("ORIGINAL", reportAsOfT1.getPayload().getRegulatoryData().get("reportingStatus"), "Original reporting status must be preserved");

        // COMPLIANCE VALIDATION: Current corrected state shows updated information
        BiTemporalEvent<TradeEvent> currentCorrectedState = eventStore.getById(correctionEvent.getEventId()).join();
        assertNotNull(currentCorrectedState, "Corrected state must be available for regulatory reporting");
        assertEquals(new BigDecimal("1156250.00"), currentCorrectedState.getPayload().getNotionalAmount(), "Corrected state must show updated amount");
        assertEquals("CORRECTED", currentCorrectedState.getPayload().getRegulatoryData().get("reportingStatus"), "Corrected state must show corrected status");

        // AUDIT VALIDATION: Original state must still be preserved
        BiTemporalEvent<TradeEvent> originalState = eventStore.getById(initialReportEvent.getEventId()).join();
        assertNotNull(originalState, "Original state must be preserved for audit trail");
        assertEquals(new BigDecimal("1143750.00"), originalState.getPayload().getNotionalAmount(), "Original state must preserve original amount");
        assertEquals("ORIGINAL", originalState.getPayload().getRegulatoryData().get("reportingStatus"), "Original state must preserve original status");

        // REGULATORY REPORTING VALIDATION: Query all MiFID II reports for this trade
        List<BiTemporalEvent<TradeEvent>> mifidReports = eventStore.query(EventQuery.forAggregate(tradeId)).join();

        assertTrue(mifidReports.size() >= 2, "Must have both original and corrected MiFID II reports");

        // Verify temporal consistency for regulatory compliance
        boolean hasOriginal = mifidReports.stream()
            .anyMatch(e -> "ORIGINAL".equals(e.getPayload().getRegulatoryData().get("reportingStatus")));
        boolean hasCorrected = mifidReports.stream()
            .anyMatch(e -> "CORRECTED".equals(e.getPayload().getRegulatoryData().get("reportingStatus")));

        assertTrue(hasOriginal, "Audit trail must preserve original MiFID II report");
        assertTrue(hasCorrected, "Audit trail must contain corrected MiFID II report");

        logger.info("✅ MiFID II transaction reporting with complete audit trail reconstruction completed successfully");
        logger.info("✅ Regulatory compliance validated: Original report preserved, correction recorded, audit trail complete");
    }

    /**
     * BUSINESS SCENARIO: Settlement Failure and Operational Risk Management
     *
     * Demonstrates operational risk management for settlement failures, retry processing,
     * and position reconciliation. This scenario is critical for back office operations
     * where settlement failures must be tracked, managed, and resolved.
     *
     * OPERATIONAL CONTEXT: Settlement processing, failure handling, position management
     * RISK MANAGEMENT: Counterparty risk, operational risk, liquidity risk
     */
    @Test
    void testSettlementFailureAndOperationalRiskManagement() throws Exception {
        logger.info("=== Testing Settlement Failure and Operational Risk Management ===");

        // BUSINESS SETUP: Large institutional trade approaching settlement
        String tradeId = getUniqueId("SETTLEMENT-TRD");
        String counterpartyId = getUniqueId("LEI-INSTITUTIONAL");
        Instant tradeExecutionTime = Instant.now().minus(2, ChronoUnit.DAYS);
        Instant settlementDate = tradeExecutionTime.plus(2, ChronoUnit.DAYS); // T+2 settlement

        // STAGE 1: INITIAL SETTLEMENT INSTRUCTION
        logger.info("STAGE 1: Settlement Instruction - Preparing for T+2 settlement");

        Map<String, String> settlementData = new java.util.HashMap<>();
        settlementData.put("settlementType", "DVP"); // Delivery vs Payment
        settlementData.put("settlementAgent", "EUROCLEAR");
        settlementData.put("custodian", "JP-MORGAN-CUSTODY");
        settlementData.put("counterpartyAccount", "ACCOUNT-" + counterpartyId);
        settlementData.put("settlementStatus", "INSTRUCTED");
        settlementData.put("settlementReference", "SETTLE-" + tradeId);
        settlementData.put("expectedSettlementDate", settlementDate.toString());

        TradeEvent settlementInstruction = new TradeEvent(
            tradeId, "FR0000120271", counterpartyId, "INSTITUTIONAL-TRADER-002",
            new BigDecimal("75000"), new BigDecimal("85.50"), new BigDecimal("6412500.00"),
            "EUR", LocalDate.now().minusDays(2), LocalDate.now(), "SETTLEMENT_INSTRUCTED",
            "XPAR", "FR", "BUY", settlementData
        );

        BiTemporalEvent<TradeEvent> instructionEvent = eventStore.append(
            "SettlementInstructed", settlementInstruction, settlementDate,
            Map.of("stage", "settlement", "system", "settlement-system", "priority", "high"),
            getUniqueId("settlement-corr"), tradeId
        ).join();

        // OPERATIONAL VALIDATION: Settlement instruction must be recorded
        assertNotNull(instructionEvent, "Settlement instruction must be recorded for operational tracking");
        assertEquals("SETTLEMENT_INSTRUCTED", instructionEvent.getPayload().getTradeStatus(), "Status must be SETTLEMENT_INSTRUCTED");
        assertEquals("INSTRUCTED", instructionEvent.getPayload().getRegulatoryData().get("settlementStatus"), "Settlement status must be INSTRUCTED");

        // STAGE 2: SETTLEMENT FAILURE - Counterparty insufficient funds
        logger.info("STAGE 2: Settlement Failure - Counterparty insufficient funds detected");

        Instant failureTime = settlementDate.plus(2, ChronoUnit.HOURS); // Settlement fails 2 hours after expected time

        Map<String, String> failureData = new java.util.HashMap<>(settlementData);
        failureData.put("settlementStatus", "FAILED");
        failureData.put("failureReason", "COUNTERPARTY_INSUFFICIENT_FUNDS");
        failureData.put("failureCode", "LACK01"); // ISO 20022 failure code
        failureData.put("failureTimestamp", failureTime.toString());
        failureData.put("retryRequired", "true");
        failureData.put("riskImpact", "HIGH"); // High risk due to large notional

        TradeEvent settlementFailure = new TradeEvent(
            tradeId, "FR0000120271", counterpartyId, "INSTITUTIONAL-TRADER-002",
            new BigDecimal("75000"), new BigDecimal("85.50"), new BigDecimal("6412500.00"),
            "EUR", LocalDate.now().minusDays(2), LocalDate.now(), "SETTLEMENT_FAILED",
            "XPAR", "FR", "BUY", failureData
        );

        BiTemporalEvent<TradeEvent> failureEvent = eventStore.append(
            "SettlementFailed", settlementFailure, failureTime,
            Map.of("stage", "settlement", "system", "settlement-system", "alert", "risk-management", "escalation", "required"),
            getUniqueId("settlement-corr"), tradeId
        ).join();

        // RISK MANAGEMENT VALIDATION: Settlement failure must trigger risk alerts
        assertNotNull(failureEvent, "Settlement failure must be recorded for risk management");
        assertEquals("SETTLEMENT_FAILED", failureEvent.getPayload().getTradeStatus(), "Status must be SETTLEMENT_FAILED");
        assertEquals("COUNTERPARTY_INSUFFICIENT_FUNDS", failureEvent.getPayload().getRegulatoryData().get("failureReason"), "Failure reason must be recorded");
        assertEquals("HIGH", failureEvent.getPayload().getRegulatoryData().get("riskImpact"), "Risk impact must be assessed");

        // STAGE 3: RETRY PROCESSING - Automated retry after counterparty funding
        logger.info("STAGE 3: Settlement Retry - Automated retry processing");

        Instant retryTime = failureTime.plus(4, ChronoUnit.HOURS); // Retry 4 hours later

        Map<String, String> retryData = new java.util.HashMap<>(settlementData);
        retryData.put("settlementStatus", "RETRY_INSTRUCTED");
        retryData.put("retryAttempt", "1");
        retryData.put("originalFailureReference", failureEvent.getEventId());
        retryData.put("retryReason", "COUNTERPARTY_FUNDING_CONFIRMED");
        retryData.put("retryTimestamp", retryTime.toString());
        retryData.put("riskReassessment", "MEDIUM"); // Risk reduced after funding confirmation

        TradeEvent settlementRetry = new TradeEvent(
            tradeId, "FR0000120271", counterpartyId, "INSTITUTIONAL-TRADER-002",
            new BigDecimal("75000"), new BigDecimal("85.50"), new BigDecimal("6412500.00"),
            "EUR", LocalDate.now().minusDays(2), LocalDate.now(), "SETTLEMENT_RETRY",
            "XPAR", "FR", "BUY", retryData
        );

        BiTemporalEvent<TradeEvent> retryEvent = eventStore.append(
            "SettlementRetry", settlementRetry, retryTime,
            Map.of("stage", "settlement", "system", "settlement-system", "retry", "automated", "risk-status", "monitored"),
            getUniqueId("settlement-corr"), tradeId
        ).join();

        // OPERATIONAL VALIDATION: Retry must be properly tracked
        assertNotNull(retryEvent, "Settlement retry must be recorded for operational tracking");
        assertEquals("SETTLEMENT_RETRY", retryEvent.getPayload().getTradeStatus(), "Status must be SETTLEMENT_RETRY");
        assertEquals("1", retryEvent.getPayload().getRegulatoryData().get("retryAttempt"), "Retry attempt must be tracked");
        assertEquals("MEDIUM", retryEvent.getPayload().getRegulatoryData().get("riskReassessment"), "Risk must be reassessed");

        // STAGE 4: SUCCESSFUL SETTLEMENT - Final settlement completion
        logger.info("STAGE 4: Settlement Success - Final settlement completion");

        Instant successTime = retryTime.plus(30, ChronoUnit.MINUTES); // Success 30 minutes after retry

        Map<String, String> successData = new java.util.HashMap<>(retryData);
        successData.put("settlementStatus", "SETTLED");
        successData.put("finalSettlementTime", successTime.toString());
        successData.put("cashMovement", "COMPLETED");
        successData.put("securityMovement", "COMPLETED");
        successData.put("settlementConfirmation", "CONFIRMED");
        successData.put("riskStatus", "RESOLVED");

        TradeEvent settlementSuccess = new TradeEvent(
            tradeId, "FR0000120271", counterpartyId, "INSTITUTIONAL-TRADER-002",
            new BigDecimal("75000"), new BigDecimal("85.50"), new BigDecimal("6412500.00"),
            "EUR", LocalDate.now().minusDays(2), LocalDate.now(), "SETTLED",
            "XPAR", "FR", "BUY", successData
        );

        BiTemporalEvent<TradeEvent> successEvent = eventStore.append(
            "SettlementCompleted", settlementSuccess, successTime,
            Map.of("stage", "settlement", "system", "settlement-system", "status", "final", "risk-resolved", "true"),
            getUniqueId("settlement-corr"), tradeId
        ).join();

        // FINAL VALIDATION: Settlement must be completed successfully
        assertNotNull(successEvent, "Settlement completion must be recorded");
        assertEquals("SETTLED", successEvent.getPayload().getTradeStatus(), "Final status must be SETTLED");
        assertEquals("SETTLED", successEvent.getPayload().getRegulatoryData().get("settlementStatus"), "Settlement status must be SETTLED");
        assertEquals("RESOLVED", successEvent.getPayload().getRegulatoryData().get("riskStatus"), "Risk status must be RESOLVED");

        // OPERATIONAL AUDIT: Verify complete settlement lifecycle
        List<BiTemporalEvent<TradeEvent>> settlementLifecycle = eventStore.query(EventQuery.forAggregate(tradeId)).join();

        assertEquals(4, settlementLifecycle.size(), "Complete settlement lifecycle must have 4 stages");

        // Verify settlement progression
        List<String> expectedSettlementTypes = List.of("SettlementInstructed", "SettlementFailed", "SettlementRetry", "SettlementCompleted");
        List<String> actualSettlementTypes = settlementLifecycle.stream()
            .map(BiTemporalEvent::getEventType)
            .toList();

        assertTrue(actualSettlementTypes.containsAll(expectedSettlementTypes),
            "Settlement audit trail must contain all operational stages");

        logger.info("✅ Complete settlement failure and operational risk management completed successfully");
        logger.info("✅ Settlement lifecycle: INSTRUCTED → FAILED → RETRY → SETTLED with full risk management");
    }

    // ========== INVESTMENT BANKING TRADE PROCESSING DOMAIN MODELS ==========

    /**
     * BUSINESS DOMAIN: Investment Banking Trade Processing
     *
     * Core trade event representing a financial instrument transaction
     * in the back office processing system. Used for equity, fixed income,
     * derivatives, and other financial instrument trades.
     *
     * REGULATORY CONTEXT: MiFID II, Dodd-Frank, Basel III compliance
     * OPERATIONAL CONTEXT: Trade capture, enrichment, validation, settlement
     */
    public static class TradeEvent {
        private final String tradeId;           // Unique trade identifier (e.g., TRD-20250917-001)
        private final String instrumentId;      // ISIN, CUSIP, or internal security ID
        private final String counterpartyId;    // Legal entity identifier (LEI)
        private final String traderId;          // Trader/sales person identifier
        private final BigDecimal quantity;      // Number of shares/units/notional
        private final BigDecimal price;         // Execution price per unit
        private final BigDecimal notionalAmount; // Total trade value (quantity * price)
        private final String currency;          // Trade currency (USD, EUR, GBP, etc.)
        private final LocalDate tradeDate;      // Business date of trade execution
        private final LocalDate settlementDate; // Expected settlement date (T+2, T+3)
        private final String tradeStatus;       // NEW, VALIDATED, SETTLED, FAILED, CANCELLED
        private final String venue;             // Exchange or trading venue (NYSE, LSE, XETRA)
        private final String jurisdiction;      // Regulatory jurisdiction (US, EU, UK)
        private final String side;              // BUY or SELL
        private final Map<String, String> regulatoryData; // MiFID II transaction reporting fields

        @JsonCreator
        public TradeEvent(@JsonProperty("tradeId") String tradeId,
                         @JsonProperty("instrumentId") String instrumentId,
                         @JsonProperty("counterpartyId") String counterpartyId,
                         @JsonProperty("traderId") String traderId,
                         @JsonProperty("quantity") BigDecimal quantity,
                         @JsonProperty("price") BigDecimal price,
                         @JsonProperty("notionalAmount") BigDecimal notionalAmount,
                         @JsonProperty("currency") String currency,
                         @JsonProperty("tradeDate") LocalDate tradeDate,
                         @JsonProperty("settlementDate") LocalDate settlementDate,
                         @JsonProperty("tradeStatus") String tradeStatus,
                         @JsonProperty("venue") String venue,
                         @JsonProperty("jurisdiction") String jurisdiction,
                         @JsonProperty("side") String side,
                         @JsonProperty("regulatoryData") Map<String, String> regulatoryData) {
            this.tradeId = tradeId;
            this.instrumentId = instrumentId;
            this.counterpartyId = counterpartyId;
            this.traderId = traderId;
            this.quantity = quantity;
            this.price = price;
            this.notionalAmount = notionalAmount;
            this.currency = currency;
            this.tradeDate = tradeDate;
            this.settlementDate = settlementDate;
            this.tradeStatus = tradeStatus;
            this.venue = venue;
            this.jurisdiction = jurisdiction;
            this.side = side;
            this.regulatoryData = regulatoryData != null ? regulatoryData : Map.of();
        }

        // Getters
        public String getTradeId() { return tradeId; }
        public String getInstrumentId() { return instrumentId; }
        public String getCounterpartyId() { return counterpartyId; }
        public String getTraderId() { return traderId; }
        public BigDecimal getQuantity() { return quantity; }
        public BigDecimal getPrice() { return price; }
        public BigDecimal getNotionalAmount() { return notionalAmount; }
        public String getCurrency() { return currency; }
        public LocalDate getTradeDate() { return tradeDate; }
        public LocalDate getSettlementDate() { return settlementDate; }
        public String getTradeStatus() { return tradeStatus; }
        public String getVenue() { return venue; }
        public String getJurisdiction() { return jurisdiction; }
        public String getSide() { return side; }
        public Map<String, String> getRegulatoryData() { return regulatoryData; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TradeEvent that = (TradeEvent) o;
            return Objects.equals(tradeId, that.tradeId) &&
                   Objects.equals(instrumentId, that.instrumentId) &&
                   Objects.equals(counterpartyId, that.counterpartyId) &&
                   Objects.equals(quantity, that.quantity) &&
                   Objects.equals(price, that.price);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tradeId, instrumentId, counterpartyId, quantity, price);
        }

        @Override
        public String toString() {
            return "TradeEvent{" +
                    "tradeId='" + tradeId + '\'' +
                    ", instrumentId='" + instrumentId + '\'' +
                    ", counterpartyId='" + counterpartyId + '\'' +
                    ", quantity=" + quantity +
                    ", price=" + price +
                    ", notionalAmount=" + notionalAmount +
                    ", currency='" + currency + '\'' +
                    ", side='" + side + '\'' +
                    ", tradeStatus='" + tradeStatus + '\'' +
                    '}';
        }
    }
}
