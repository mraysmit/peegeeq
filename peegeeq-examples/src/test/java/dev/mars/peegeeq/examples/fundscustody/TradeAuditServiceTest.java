package dev.mars.peegeeq.examples.fundscustody;

import dev.mars.peegeeq.examples.fundscustody.domain.Currency;
import dev.mars.peegeeq.examples.fundscustody.domain.TradeType;
import dev.mars.peegeeq.examples.fundscustody.model.CancellationRequest;
import dev.mars.peegeeq.examples.fundscustody.model.TradeRequest;
import dev.mars.peegeeq.examples.fundscustody.model.ChangeReport;
import dev.mars.peegeeq.examples.fundscustody.model.CorrectionAudit;
import dev.mars.peegeeq.examples.fundscustody.service.TradeAuditService;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TradeAuditService - correction audit trail and change detection.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
class TradeAuditServiceTest extends FundsCustodyTestBase {
    
    private TradeAuditService auditService;
    
    @BeforeEach
    @Override
    void setUp(Vertx vertx) throws Exception {
        super.setUp(vertx);
        auditService = new TradeAuditService(tradeEventStore, cancellationEventStore);
    }
    
    @Test
    void testGetCorrectionsInPeriod(Vertx vertx, VertxTestContext testContext) throws Exception {
        String fundId = "FUND-001";
        LocalDate tradeDate = LocalDate.of(2025, 10, 1);
        
        // Record a trade
        TradeRequest tradeRequest = new TradeRequest(
            fundId,
            "AAPL",
            TradeType.BUY,
            new BigDecimal("100"),
            new BigDecimal("150.00"),
            Currency.USD,
            tradeDate,
            tradeDate.plusDays(2),  // T+2 settlement
            "BROKER-A"
        );

        String tradeId = tradeService.recordTrade(tradeRequest)
            .map(event -> event.getPayload().tradeId())
            .await();

        vertx.setTimer(100, id1 -> {
            try {
                // Cancel the trade (correction)
                Instant correctionTime = Instant.now();
                CancellationRequest cancelRequest = new CancellationRequest(
                    "Price error - should be 155.00",
                    "auditor1"
                );

                tradeService.cancelTrade(tradeId, cancelRequest)
                    .await();
                
                vertx.setTimer(100, id2 -> {
                    try {
                        // Query corrections in period
                        Instant periodStart = correctionTime.minus(1, ChronoUnit.HOURS);
                        Instant periodEnd = correctionTime.plus(1, ChronoUnit.HOURS);
                        
                        List<CorrectionAudit> corrections = auditService
                            .getCorrectionsInPeriod(fundId, periodStart, periodEnd)
                            .await();
                        
                        // Verify
                        assertNotNull(corrections);
                        assertEquals(1, corrections.size());
                        
                        CorrectionAudit audit = corrections.get(0);
                        assertEquals(tradeId, audit.tradeId());
                        assertEquals(fundId, audit.fundId());
                        assertEquals("AAPL", audit.securityId());
                        assertEquals(tradeDate, audit.tradeDate());
                        assertEquals("Price error - should be 155.00", audit.reason());
                        assertEquals("auditor1", audit.correctedBy());
                        assertNotNull(audit.correctedAt());
                        testContext.completeNow();
                    } catch (Exception e) {
                        testContext.failNow(e);
                    }
                });
            } catch (Exception e) {
                testContext.failNow(e);
            }
        });
    }
    
    @Test
    void testGetCorrectionsAffectingValidPeriod(Vertx vertx, VertxTestContext testContext) throws Exception {
        String fundId = "FUND-002";
        
        // Record trades on different dates
        LocalDate date1 = LocalDate.of(2025, 9, 15);
        LocalDate date2 = LocalDate.of(2025, 9, 20);
        LocalDate date3 = LocalDate.of(2025, 10, 5);
        
        String tradeId1 = recordAndGetTradeId(fundId, "MSFT", date1);
        String tradeId2 = recordAndGetTradeId(fundId, "GOOGL", date2);
        String tradeId3 = recordAndGetTradeId(fundId, "AMZN", date3);
        
        vertx.setTimer(100, id1 -> {
            try {
                // Cancel trades (corrections)
                cancelTrade(tradeId1, "Correction 1");
                cancelTrade(tradeId2, "Correction 2");
                cancelTrade(tradeId3, "Correction 3");
                
                vertx.setTimer(100, id2 -> {
                    try {
                        // Query corrections affecting September trades
                        LocalDate validStart = LocalDate.of(2025, 9, 1);
                        LocalDate validEnd = LocalDate.of(2025, 9, 30);
                        
                        List<CorrectionAudit> corrections = auditService
                            .getCorrectionsAffectingValidPeriod(fundId, validStart, validEnd)
                            .await();
                        
                        // Verify - should only include September trades
                        assertNotNull(corrections);
                        assertEquals(2, corrections.size());
                        
                        List<String> correctedTradeIds = corrections.stream()
                            .map(CorrectionAudit::tradeId)
                            .toList();
                        
                        assertTrue(correctedTradeIds.contains(tradeId1));
                        assertTrue(correctedTradeIds.contains(tradeId2));
                        assertFalse(correctedTradeIds.contains(tradeId3)); // October trade excluded
                        testContext.completeNow();
                    } catch (Exception e) {
                        testContext.failNow(e);
                    }
                });
            } catch (Exception e) {
                testContext.failNow(e);
            }
        });
    }
    
    @Test
    void testGetTradeCorrectionHistory(Vertx vertx, VertxTestContext testContext) throws Exception {
        String fundId = "FUND-003";
        LocalDate tradeDate = LocalDate.of(2025, 10, 1);
        
        // Record a trade
        String tradeId = recordAndGetTradeId(fundId, "TSLA", tradeDate);
        
        vertx.setTimer(100, id1 -> {
            try {
                // Cancel it (first correction)
                cancelTrade(tradeId, "First correction - wrong quantity");
                
                vertx.setTimer(100, id2 -> {
                    try {
                        // Query correction history
                        List<CorrectionAudit> history = auditService
                            .getTradeCorrectionHistory(tradeId)
                            .await();
                        
                        // Verify
                        assertNotNull(history);
                        assertEquals(1, history.size());
                        
                        CorrectionAudit audit = history.get(0);
                        assertEquals(tradeId, audit.tradeId());
                        assertEquals("First correction - wrong quantity", audit.reason());
                        testContext.completeNow();
                    } catch (Exception e) {
                        testContext.failNow(e);
                    }
                });
            } catch (Exception e) {
                testContext.failNow(e);
            }
        });
    }
    
    @Test
    void testGetChangesBetween(Vertx vertx, VertxTestContext testContext) throws Exception {
        String fundId = "FUND-004";
        LocalDate tradeDate = LocalDate.of(2025, 10, 1);
        
        // Capture initial state
        Instant time1 = Instant.now();
        
        vertx.setTimer(100, id1 -> {
            try {
                // Record first trade
                String tradeId1 = recordAndGetTradeId(fundId, "NVDA", tradeDate);
                
                vertx.setTimer(100, id2 -> {
                    try {
                        // Capture state after first trade
                        Instant time2 = Instant.now();
                        
                        vertx.setTimer(100, id3 -> {
                            try {
                                // Record second trade
                                String tradeId2 = recordAndGetTradeId(fundId, "AMD", tradeDate);
                                
                                vertx.setTimer(100, id4 -> {
                                    try {
                                        // Cancel first trade
                                        cancelTrade(tradeId1, "Correction");
                                        
                                        vertx.setTimer(100, id5 -> {
                                            try {
                                                // Capture final state
                                                Instant time3 = Instant.now();
                                                
                                                // Query changes between time1 and time2 (should show 1 new trade)
                                                ChangeReport changes1 = auditService
                                                    .getChangesBetween(fundId, time1, time2)
                                                    .await();
                                                
                                                assertNotNull(changes1);
                                                assertTrue(changes1.hasNewTrades());
                                                assertFalse(changes1.hasCorrections());
                                                assertEquals(1, changes1.newTrades().size());
                                                assertEquals(0, changes1.correctedTrades().size());
                                                
                                                // Query changes between time2 and time3 (should show 1 new trade + 1 correction)
                                                ChangeReport changes2 = auditService
                                                    .getChangesBetween(fundId, time2, time3)
                                                    .await();
                                                
                                                assertNotNull(changes2);
                                                assertTrue(changes2.hasChanges());
                                                assertTrue(changes2.hasNewTrades());
                                                assertTrue(changes2.hasCorrections());
                                                assertEquals(1, changes2.newTrades().size());
                                                assertEquals(1, changes2.correctedTrades().size());
                                                testContext.completeNow();
                                            } catch (Exception e) {
                                                testContext.failNow(e);
                                            }
                                        });
                                    } catch (Exception e) {
                                        testContext.failNow(e);
                                    }
                                });
                            } catch (Exception e) {
                                testContext.failNow(e);
                            }
                        });
                    } catch (Exception e) {
                        testContext.failNow(e);
                    }
                });
            } catch (Exception e) {
                testContext.failNow(e);
            }
        });
    }
    
    @Test
    void testNoCorrectionsInPeriod() throws Exception {
        String fundId = "FUND-005";
        
        // Query empty period
        Instant periodStart = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant periodEnd = Instant.now();
        
        List<CorrectionAudit> corrections = auditService
            .getCorrectionsInPeriod(fundId, periodStart, periodEnd)
            .await();
        
        assertNotNull(corrections);
        assertTrue(corrections.isEmpty());
    }
    
    // Helper methods
    
    private String recordAndGetTradeId(String fundId, String securityId, LocalDate tradeDate)
            throws Exception {
        TradeRequest request = new TradeRequest(
            fundId,
            securityId,
            TradeType.BUY,
            new BigDecimal("100"),
            new BigDecimal("100.00"),
            Currency.USD,
            tradeDate,
            tradeDate.plusDays(2),  // T+2 settlement
            "BROKER-A"
        );

        return tradeService.recordTrade(request)
            .map(event -> event.getPayload().tradeId())
            .await();
    }

    private void cancelTrade(String tradeId, String reason) throws Exception {
        CancellationRequest request = new CancellationRequest(reason, "auditor1");
        tradeService.cancelTrade(tradeId, request).await();
    }
}

