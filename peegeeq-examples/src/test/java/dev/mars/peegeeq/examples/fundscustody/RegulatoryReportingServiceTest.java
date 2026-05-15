package dev.mars.peegeeq.examples.fundscustody;

import dev.mars.peegeeq.examples.fundscustody.domain.Currency;
import dev.mars.peegeeq.examples.fundscustody.domain.TradeType;
import dev.mars.peegeeq.examples.fundscustody.model.TradeRequest;
import dev.mars.peegeeq.examples.fundscustody.model.RegulatoryReport;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RegulatoryReportingService - regulatory snapshots and compliance reporting.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
class RegulatoryReportingServiceTest extends FundsCustodyTestBase {

    private Future<Void> delay(Vertx vertx, long ms) {
        Promise<Void> p = Promise.promise();
        vertx.setTimer(ms, id -> p.complete());
        return p.future();
    }
    
    @Test
    void testGetRegulatorySnapshot(Vertx vertx, VertxTestContext testContext) {
        String fundId = "FUND-001";
        LocalDate reportingDate = LocalDate.of(2025, 10, 31);

        setupFundDataAsync(fundId, reportingDate, vertx)
            .compose(v -> delay(vertx, 200))
            .compose(v -> regulatoryService.getRegulatorySnapshot(fundId, reportingDate, "AIFMD"))
            .onComplete(testContext.succeeding(report -> testContext.verify(() -> {
                assertNotNull(report);
                assertEquals(fundId, report.fundId());
                assertEquals(reportingDate, report.reportingDate());
                assertEquals("AIFMD", report.reportType());
                assertTrue(report.isAIFMD());
                assertFalse(report.isMiFIDII());
                assertNotNull(report.navSnapshot());
                assertEquals(fundId, report.navSnapshot().fundId());
                assertNotNull(report.positions());
                assertFalse(report.positions().isEmpty());
                assertNotNull(report.tradesInPeriod());
                assertFalse(report.tradesInPeriod().isEmpty());
                testContext.completeNow();
            })));
    }
    
    @Test
    void testGetAIFMDReport(Vertx vertx, VertxTestContext testContext) {
        String fundId = "FUND-002";
        LocalDate reportingDate = LocalDate.of(2025, 9, 30);

        setupFundDataAsync(fundId, reportingDate, vertx)
            .compose(v -> delay(vertx, 200))
            .compose(v -> regulatoryService.getAIFMDReport(fundId, reportingDate))
            .onComplete(testContext.succeeding(report -> testContext.verify(() -> {
                assertNotNull(report);
                assertEquals("AIFMD", report.reportType());
                assertTrue(report.isAIFMD());
                assertNotNull(report.navSnapshot());
                assertNotNull(report.positions());
                assertNotNull(report.tradesInPeriod());
                testContext.completeNow();
            })));
    }
    
    @Test
    void testGetMiFIDTransactionReport(Vertx vertx, VertxTestContext testContext) {
        String fundId = "FUND-003";
        LocalDate tradingDay = LocalDate.of(2025, 10, 15);

        recordTradeAsync(fundId, "AAPL", tradingDay, TradeType.BUY, "100", "150.00")
            .compose(v -> recordTradeAsync(fundId, "MSFT", tradingDay, TradeType.BUY, "200", "300.00"))
            .compose(v -> recordTradeAsync(fundId, "GOOGL", tradingDay, TradeType.SELL, "50", "2800.00"))
            .compose(v -> delay(vertx, 200))
            .compose(v -> navService.calculateNAV(fundId, tradingDay,
                    new BigDecimal("5000000.00"), new BigDecimal("100000.00"),
                    new BigDecimal("50000"), Currency.USD, "nav-calc"))
            .compose(v -> delay(vertx, 100))
            .compose(v -> regulatoryService.getMiFIDTransactionReport(fundId, tradingDay))
            .onComplete(testContext.succeeding(report -> testContext.verify(() -> {
                assertNotNull(report);
                assertEquals(fundId, report.fundId());
                assertEquals(tradingDay, report.reportingDate());
                assertEquals("MiFID_II", report.reportType());
                assertTrue(report.isMiFIDII());
                assertFalse(report.isAIFMD());
                assertNotNull(report.tradesInPeriod());
                assertEquals(3, report.tradesInPeriod().size());
                assertNotNull(report.navSnapshot());
                assertNotNull(report.positions());
                testContext.completeNow();
            })));
    }
    
    @Test
    void testRegulatorySnapshotPointInTime(Vertx vertx, VertxTestContext testContext) {
        String fundId = "FUND-004";
        LocalDate reportingDate = LocalDate.of(2025, 10, 1);

        navService.calculateNAV(fundId, reportingDate,
                new BigDecimal("10000000.00"), new BigDecimal("500000.00"),
                new BigDecimal("100000.00"), Currency.USD, "nav-calc")
            .compose(v -> delay(vertx, 200))
            .compose(v -> recordTradeAsync(fundId, "AAPL", reportingDate, TradeType.BUY, "100", "150.00"))
            .compose(v -> delay(vertx, 100))
            .compose(v -> regulatoryService.getRegulatorySnapshot(fundId, reportingDate, "AIFMD"))
            .compose(report1 -> delay(vertx, 100)
                .compose(v -> navService.calculateNAV(fundId, reportingDate,
                        new BigDecimal("10100000.00"), new BigDecimal("500000.00"),
                        new BigDecimal("100000.00"), Currency.USD, "nav-auditor"))
                .compose(v -> delay(vertx, 200))
                .compose(v -> regulatoryService.getRegulatorySnapshot(fundId, reportingDate, "AIFMD"))
                .map(report2 -> new RegulatoryReport[]{ report1, report2 })
            )
            .onComplete(testContext.succeeding(reports -> testContext.verify(() -> {
                RegulatoryReport report1 = reports[0];
                RegulatoryReport report2 = reports[1];
                assertNotNull(report1.navSnapshot());
                assertNotNull(report2.navSnapshot());
                assertEquals(0, new BigDecimal("95.000000").compareTo(report1.navSnapshot().navPerShare()));
                assertEquals(0, new BigDecimal("96.000000").compareTo(report2.navSnapshot().navPerShare()));
                testContext.completeNow();
            })));
    }
    
    @Test
    void testEmptyRegulatoryReport(Vertx vertx, VertxTestContext testContext) {
        String fundId = "FUND-EMPTY";
        LocalDate reportingDate = LocalDate.of(2025, 10, 1);

        regulatoryService.getRegulatorySnapshot(fundId, reportingDate, "AIFMD")
            .onComplete(testContext.succeeding(report -> testContext.verify(() -> {
                assertNotNull(report);
                assertEquals(fundId, report.fundId());
                assertNotNull(report.positions());
                assertTrue(report.positions().isEmpty());
                assertNotNull(report.tradesInPeriod());
                assertTrue(report.tradesInPeriod().isEmpty());
                testContext.completeNow();
            })));
    }
    
    @Test
    void testMiFIDReportOnlyIncludesTradingDayTrades(Vertx vertx, VertxTestContext testContext) {
        String fundId = "FUND-005";
        LocalDate tradingDay = LocalDate.of(2025, 10, 15);
        LocalDate previousDay = tradingDay.minusDays(1);
        LocalDate nextDay = tradingDay.plusDays(1);

        recordTradeAsync(fundId, "AAPL", previousDay, TradeType.BUY, "100", "150.00")
            .compose(v -> recordTradeAsync(fundId, "MSFT", tradingDay, TradeType.BUY, "200", "300.00"))
            .compose(v -> recordTradeAsync(fundId, "GOOGL", nextDay, TradeType.SELL, "50", "2800.00"))
            .compose(v -> delay(vertx, 200))
            .compose(v -> regulatoryService.getMiFIDTransactionReport(fundId, tradingDay))
            .onComplete(testContext.succeeding(report -> testContext.verify(() -> {
                assertNotNull(report);
                assertNotNull(report.tradesInPeriod());
                assertEquals(1, report.tradesInPeriod().size());
                assertEquals("MSFT", report.tradesInPeriod().get(0).securityId());
                testContext.completeNow();
            })));
    }
    
    // Helper methods

    private Future<Void> setupFundDataAsync(String fundId, LocalDate date, Vertx vertx) {
        LocalDate startDate = date.minusDays(15);
        return navService.calculateNAV(fundId, date,
                new BigDecimal("10000000.00"), new BigDecimal("500000.00"),
                new BigDecimal("100000.00"), Currency.USD, "nav-calculator")
            .compose(v -> delay(vertx, 200))
            .compose(v -> recordTradeAsync(fundId, "AAPL", startDate, TradeType.BUY, "100", "150.00"))
            .compose(v -> delay(vertx, 50))
            .compose(v -> recordTradeAsync(fundId, "MSFT", startDate.plusDays(5), TradeType.BUY, "200", "300.00"))
            .compose(v -> delay(vertx, 50))
            .compose(v -> recordTradeAsync(fundId, "GOOGL", startDate.plusDays(10), TradeType.SELL, "50", "2800.00"));
    }

    private Future<Void> recordTradeAsync(String fundId, String securityId, LocalDate tradeDate,
                                          TradeType tradeType, String quantity, String price) {
        TradeRequest request = new TradeRequest(
            fundId, securityId, tradeType,
            new BigDecimal(quantity), new BigDecimal(price),
            Currency.USD, tradeDate, tradeDate.plusDays(2), "BROKER-A"
        );
        return tradeService.recordTrade(request).mapEmpty();
    }
}

