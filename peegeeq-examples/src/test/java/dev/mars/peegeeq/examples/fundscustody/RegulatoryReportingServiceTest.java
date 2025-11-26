package dev.mars.peegeeq.examples.fundscustody;

import dev.mars.peegeeq.examples.fundscustody.domain.Currency;
import dev.mars.peegeeq.examples.fundscustody.domain.TradeType;
import dev.mars.peegeeq.examples.fundscustody.model.TradeRequest;
import dev.mars.peegeeq.examples.fundscustody.model.RegulatoryReport;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RegulatoryReportingService - regulatory snapshots and compliance reporting.
 */
@Tag(TestCategories.INTEGRATION)
class RegulatoryReportingServiceTest extends FundsCustodyTestBase {
    
    @Test
    void testGetRegulatorySnapshot() throws Exception {
        String fundId = "FUND-001";
        LocalDate reportingDate = LocalDate.of(2025, 10, 31);
        
        // Set up fund data
        setupFundData(fundId, reportingDate);
        
        Thread.sleep(200);
        
        // Generate regulatory snapshot
        RegulatoryReport report = regulatoryService
            .getRegulatorySnapshot(fundId, reportingDate, "AIFMD")
            .toCompletableFuture().get();
        
        assertNotNull(report);
        assertEquals(fundId, report.fundId());
        assertEquals(reportingDate, report.reportingDate());
        assertEquals("AIFMD", report.reportType());
        assertTrue(report.isAIFMD());
        assertFalse(report.isMiFIDII());
        
        // Verify NAV snapshot included
        assertNotNull(report.navSnapshot());
        assertEquals(fundId, report.navSnapshot().fundId());
        
        // Verify positions included
        assertNotNull(report.positions());
        assertFalse(report.positions().isEmpty());
        
        // Verify trades included
        assertNotNull(report.tradesInPeriod());
        assertFalse(report.tradesInPeriod().isEmpty());
    }
    
    @Test
    void testGetAIFMDReport() throws Exception {
        String fundId = "FUND-002";
        LocalDate reportingDate = LocalDate.of(2025, 9, 30);
        
        // Set up fund data
        setupFundData(fundId, reportingDate);
        
        Thread.sleep(200);
        
        // Generate AIFMD report
        RegulatoryReport report = regulatoryService
            .getAIFMDReport(fundId, reportingDate)
            .toCompletableFuture().get();
        
        assertNotNull(report);
        assertEquals("AIFMD", report.reportType());
        assertTrue(report.isAIFMD());
        
        // Verify report contains required AIFMD data
        assertNotNull(report.navSnapshot());
        assertNotNull(report.positions());
        assertNotNull(report.tradesInPeriod());
    }
    
    @Test
    void testGetMiFIDTransactionReport() throws Exception {
        String fundId = "FUND-003";
        LocalDate tradingDay = LocalDate.of(2025, 10, 15);
        
        // Record trades on the trading day
        recordTrade(fundId, "AAPL", tradingDay, TradeType.BUY, "100", "150.00");
        recordTrade(fundId, "MSFT", tradingDay, TradeType.BUY, "200", "300.00");
        recordTrade(fundId, "GOOGL", tradingDay, TradeType.SELL, "50", "2800.00");
        
        Thread.sleep(200);
        
        // Calculate NAV for the day
        navService.calculateNAV(
            fundId,
            tradingDay,
            new BigDecimal("5000000.00"),
            new BigDecimal("100000.00"),
            new BigDecimal("50000"),
            Currency.USD,
            "nav-calc"
        ).toCompletableFuture().get();
        
        Thread.sleep(100);
        
        // Generate MiFID II transaction report
        RegulatoryReport report = regulatoryService
            .getMiFIDTransactionReport(fundId, tradingDay)
            .toCompletableFuture().get();
        
        assertNotNull(report);
        assertEquals(fundId, report.fundId());
        assertEquals(tradingDay, report.reportingDate());
        assertEquals("MiFID_II", report.reportType());
        assertTrue(report.isMiFIDII());
        assertFalse(report.isAIFMD());
        
        // Verify all trades on trading day are included
        assertNotNull(report.tradesInPeriod());
        assertEquals(3, report.tradesInPeriod().size());
        
        // Verify NAV included
        assertNotNull(report.navSnapshot());
        
        // Verify positions included
        assertNotNull(report.positions());
    }
    
    @Test
    void testRegulatorySnapshotPointInTime() throws Exception {
        String fundId = "FUND-004";
        LocalDate reportingDate = LocalDate.of(2025, 10, 1);
        
        // Calculate initial NAV
        navService.calculateNAV(
            fundId,
            reportingDate,
            new BigDecimal("10000000.00"),
            new BigDecimal("500000.00"),
            new BigDecimal("100000.00"),
            Currency.USD,
            "nav-calc"
        ).toCompletableFuture().get();

        Thread.sleep(200);
        
        // Record trade
        recordTrade(fundId, "AAPL", reportingDate, TradeType.BUY, "100", "150.00");
        
        Thread.sleep(100);
        
        // Generate report (captures state at this point)
        RegulatoryReport report1 = regulatoryService
            .getRegulatorySnapshot(fundId, reportingDate, "AIFMD")
            .toCompletableFuture().get();
        
        Thread.sleep(100);
        
        // Make correction after reporting
        navService.calculateNAV(
            fundId,
            reportingDate,
            new BigDecimal("10100000.00"),  // Corrected
            new BigDecimal("500000.00"),
            new BigDecimal("100000.00"),
            Currency.USD,
            "nav-auditor"
        ).toCompletableFuture().get();

        Thread.sleep(200);
        
        // Generate new report (should show corrected data)
        RegulatoryReport report2 = regulatoryService
            .getRegulatorySnapshot(fundId, reportingDate, "AIFMD")
            .toCompletableFuture().get();
        
        // Verify reports show different NAV values
        assertNotNull(report1.navSnapshot());
        assertNotNull(report2.navSnapshot());
        
        // First report shows original NAV
        assertEquals(0, new BigDecimal("95.000000")
            .compareTo(report1.navSnapshot().navPerShare()));
        
        // Second report shows corrected NAV
        assertEquals(0, new BigDecimal("96.000000")
            .compareTo(report2.navSnapshot().navPerShare()));
    }
    
    @Test
    void testEmptyRegulatoryReport() throws Exception {
        String fundId = "FUND-EMPTY";
        LocalDate reportingDate = LocalDate.of(2025, 10, 1);
        
        // Generate report with no data
        RegulatoryReport report = regulatoryService
            .getRegulatorySnapshot(fundId, reportingDate, "AIFMD")
            .toCompletableFuture().get();
        
        assertNotNull(report);
        assertEquals(fundId, report.fundId());
        
        // NAV may be null if not calculated
        // Positions and trades should be empty lists
        assertNotNull(report.positions());
        assertTrue(report.positions().isEmpty());
        assertNotNull(report.tradesInPeriod());
        assertTrue(report.tradesInPeriod().isEmpty());
    }
    
    @Test
    void testMiFIDReportOnlyIncludesTradingDayTrades() throws Exception {
        String fundId = "FUND-005";
        LocalDate tradingDay = LocalDate.of(2025, 10, 15);
        LocalDate previousDay = tradingDay.minusDays(1);
        LocalDate nextDay = tradingDay.plusDays(1);
        
        // Record trades on different days
        recordTrade(fundId, "AAPL", previousDay, TradeType.BUY, "100", "150.00");
        recordTrade(fundId, "MSFT", tradingDay, TradeType.BUY, "200", "300.00");
        recordTrade(fundId, "GOOGL", nextDay, TradeType.SELL, "50", "2800.00");
        
        Thread.sleep(200);
        
        // Generate MiFID report for trading day
        RegulatoryReport report = regulatoryService
            .getMiFIDTransactionReport(fundId, tradingDay)
            .toCompletableFuture().get();
        
        assertNotNull(report);
        assertNotNull(report.tradesInPeriod());

        // Should only include trading day trades
        assertEquals(1, report.tradesInPeriod().size());
        assertEquals("MSFT", report.tradesInPeriod().get(0).securityId());
    }
    
    // Helper methods
    
    private void setupFundData(String fundId, LocalDate date) throws Exception {
        // Calculate NAV
        navService.calculateNAV(
            fundId,
            date,
            new BigDecimal("10000000.00"),
            new BigDecimal("500000.00"),
            new BigDecimal("100000.00"),
            Currency.USD,
            "nav-calculator"
        ).toCompletableFuture().get();

        Thread.sleep(200);
        
        // Record some trades in the period
        LocalDate startDate = date.minusDays(15);
        recordTrade(fundId, "AAPL", startDate, TradeType.BUY, "100", "150.00");
        Thread.sleep(50);
        recordTrade(fundId, "MSFT", startDate.plusDays(5), TradeType.BUY, "200", "300.00");
        Thread.sleep(50);
        recordTrade(fundId, "GOOGL", startDate.plusDays(10), TradeType.SELL, "50", "2800.00");
    }
    
    private void recordTrade(String fundId, String securityId, LocalDate tradeDate,
                            TradeType tradeType, String quantity, String price) throws Exception {
        TradeRequest request = new TradeRequest(
            fundId,
            securityId,
            tradeType,
            new BigDecimal(quantity),
            new BigDecimal(price),
            Currency.USD,
            tradeDate,
            tradeDate.plusDays(2),  // T+2 settlement
            "BROKER-A"
        );

        tradeService.recordTrade(request).toCompletableFuture().get();
    }
}

