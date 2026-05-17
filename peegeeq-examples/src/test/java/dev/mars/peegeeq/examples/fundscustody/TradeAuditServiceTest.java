package dev.mars.peegeeq.examples.fundscustody;

import dev.mars.peegeeq.examples.fundscustody.domain.Currency;
import dev.mars.peegeeq.examples.fundscustody.domain.TradeType;
import dev.mars.peegeeq.examples.fundscustody.model.CancellationRequest;
import dev.mars.peegeeq.examples.fundscustody.model.TradeRequest;
import dev.mars.peegeeq.examples.fundscustody.model.ChangeReport;
import dev.mars.peegeeq.examples.fundscustody.model.CorrectionAudit;
import dev.mars.peegeeq.examples.fundscustody.service.TradeAuditService;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
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

    private Future<Void> delay(Vertx vertx, long ms) {
        return vertx.timer(ms).<Void>mapEmpty();
    }
    
    private TradeAuditService auditService;
    
    @BeforeEach
    void initAuditService() {
        // Base @BeforeEach (FundsCustodyTestBase.setUp) is invoked by JUnit before this method.
        // We just rebind the local auditService reference to the freshly-constructed stores.
        auditService = new TradeAuditService(tradeEventStore, cancellationEventStore);
    }
    
    @Test
    void testGetCorrectionsInPeriod(Vertx vertx, VertxTestContext testContext) {
        String fundId = "FUND-001";
        LocalDate tradeDate = LocalDate.of(2025, 10, 1);

        TradeRequest tradeRequest = new TradeRequest(
            fundId, "AAPL", TradeType.BUY,
            new BigDecimal("100"), new BigDecimal("150.00"),
            Currency.USD, tradeDate, tradeDate.plusDays(2), "BROKER-A"
        );

        tradeService.recordTrade(tradeRequest)
            .map(event -> event.getPayload().tradeId())
            .compose(tradeId -> {
                Instant correctionTime = Instant.now();
                return delay(vertx, 100)
                    .compose(v -> cancelTradeAsync(tradeId, "Price error - should be 155.00"))
                    .compose(v -> delay(vertx, 100))
                    .compose(v -> {
                        Instant periodStart = correctionTime.minus(1, ChronoUnit.HOURS);
                        Instant periodEnd = correctionTime.plus(1, ChronoUnit.HOURS);
                        return auditService.getCorrectionsInPeriod(fundId, periodStart, periodEnd);
                    })
                    .map(corrections -> new Object[]{ tradeId, corrections });
            })
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                String tradeId = (String) result[0];
                @SuppressWarnings("unchecked")
                List<CorrectionAudit> corrections = (List<CorrectionAudit>) result[1];
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
            })));
    }
    
    @Test
    void testGetCorrectionsAffectingValidPeriod(Vertx vertx, VertxTestContext testContext) {
        String fundId = "FUND-002";

        LocalDate date1 = LocalDate.of(2025, 9, 15);
        LocalDate date2 = LocalDate.of(2025, 9, 20);
        LocalDate date3 = LocalDate.of(2025, 10, 5);

        recordAndGetTradeIdAsync(fundId, "MSFT", date1)
            .compose(tradeId1 -> recordAndGetTradeIdAsync(fundId, "GOOGL", date2)
                .compose(tradeId2 -> recordAndGetTradeIdAsync(fundId, "AMZN", date3)
                    .compose(tradeId3 -> delay(vertx, 100)
                        .compose(v -> cancelTradeAsync(tradeId1, "Correction 1"))
                        .compose(v -> cancelTradeAsync(tradeId2, "Correction 2"))
                        .compose(v -> cancelTradeAsync(tradeId3, "Correction 3"))
                        .compose(v -> delay(vertx, 100))
                        .compose(v -> {
                            LocalDate validStart = LocalDate.of(2025, 9, 1);
                            LocalDate validEnd = LocalDate.of(2025, 9, 30);
                            return auditService.getCorrectionsAffectingValidPeriod(fundId, validStart, validEnd);
                        })
                        .map(corrections -> new Object[]{ tradeId1, tradeId2, tradeId3, corrections })
                    )
                )
            )
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                String tradeId1 = (String) result[0];
                String tradeId2 = (String) result[1];
                String tradeId3 = (String) result[2];
                @SuppressWarnings("unchecked")
                List<CorrectionAudit> corrections = (List<CorrectionAudit>) result[3];
                assertNotNull(corrections);
                assertEquals(2, corrections.size());
                List<String> correctedTradeIds = corrections.stream().map(CorrectionAudit::tradeId).toList();
                assertTrue(correctedTradeIds.contains(tradeId1));
                assertTrue(correctedTradeIds.contains(tradeId2));
                assertFalse(correctedTradeIds.contains(tradeId3));
                testContext.completeNow();
            })));
    }
    
    @Test
    void testGetTradeCorrectionHistory(Vertx vertx, VertxTestContext testContext) {
        String fundId = "FUND-003";
        LocalDate tradeDate = LocalDate.of(2025, 10, 1);

        recordAndGetTradeIdAsync(fundId, "TSLA", tradeDate)
            .compose(tradeId -> delay(vertx, 100)
                .compose(v -> cancelTradeAsync(tradeId, "First correction - wrong quantity"))
                .compose(v -> delay(vertx, 100))
                .compose(v -> auditService.getTradeCorrectionHistory(tradeId))
                .map(history -> new Object[]{ tradeId, history })
            )
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                String tradeId = (String) result[0];
                @SuppressWarnings("unchecked")
                List<CorrectionAudit> history = (List<CorrectionAudit>) result[1];
                assertNotNull(history);
                assertEquals(1, history.size());
                CorrectionAudit audit = history.get(0);
                assertEquals(tradeId, audit.tradeId());
                assertEquals("First correction - wrong quantity", audit.reason());
                testContext.completeNow();
            })));
    }
    
    @Test
    void testGetChangesBetween(Vertx vertx, VertxTestContext testContext) {
        String fundId = "FUND-004";
        LocalDate tradeDate = LocalDate.of(2025, 10, 1);

        Instant time1 = Instant.now();

        delay(vertx, 100)
            .compose(v -> recordAndGetTradeIdAsync(fundId, "NVDA", tradeDate))
            .compose(tradeId1 -> {
                Instant time2 = Instant.now();
                return delay(vertx, 100)
                    .compose(v -> recordAndGetTradeIdAsync(fundId, "AMD", tradeDate))
                    .compose(tradeId2 -> delay(vertx, 100)
                        .compose(v -> cancelTradeAsync(tradeId1, "Correction"))
                        .compose(v -> delay(vertx, 100))
                        .compose(v -> {
                            Instant time3 = Instant.now();
                            return auditService.getChangesBetween(fundId, time1, time2)
                                .compose(changes1 -> auditService.getChangesBetween(fundId, time2, time3)
                                    .map(changes2 -> new Object[]{ changes1, changes2 }));
                        })
                    );
            })
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                ChangeReport changes1 = (ChangeReport) result[0];
                ChangeReport changes2 = (ChangeReport) result[1];
                assertNotNull(changes1);
                assertTrue(changes1.hasNewTrades());
                assertFalse(changes1.hasCorrections());
                assertEquals(1, changes1.newTrades().size());
                assertEquals(0, changes1.correctedTrades().size());
                assertNotNull(changes2);
                assertTrue(changes2.hasChanges());
                assertTrue(changes2.hasNewTrades());
                assertTrue(changes2.hasCorrections());
                assertEquals(1, changes2.newTrades().size());
                assertEquals(1, changes2.correctedTrades().size());
                testContext.completeNow();
            })));
    }
    
    @Test
    void testNoCorrectionsInPeriod(Vertx vertx, VertxTestContext testContext) {
        String fundId = "FUND-005";

        Instant periodStart = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant periodEnd = Instant.now();

        auditService.getCorrectionsInPeriod(fundId, periodStart, periodEnd)
            .onComplete(testContext.succeeding(corrections -> testContext.verify(() -> {
                assertNotNull(corrections);
                assertTrue(corrections.isEmpty());
                testContext.completeNow();
            })));
    }
    
    // Helper methods

    private Future<String> recordAndGetTradeIdAsync(String fundId, String securityId, LocalDate tradeDate) {
        TradeRequest request = new TradeRequest(
            fundId, securityId, TradeType.BUY,
            new BigDecimal("100"), new BigDecimal("100.00"),
            Currency.USD, tradeDate, tradeDate.plusDays(2), "BROKER-A"
        );
        return tradeService.recordTrade(request)
            .map(event -> event.getPayload().tradeId());
    }

    private Future<Void> cancelTradeAsync(String tradeId, String reason) {
        CancellationRequest request = new CancellationRequest(reason, "auditor1");
        return tradeService.cancelTrade(tradeId, request).mapEmpty();
    }
}

