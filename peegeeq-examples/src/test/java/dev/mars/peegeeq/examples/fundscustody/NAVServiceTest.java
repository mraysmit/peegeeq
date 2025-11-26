package dev.mars.peegeeq.examples.fundscustody;

import dev.mars.peegeeq.examples.fundscustody.domain.Currency;
import dev.mars.peegeeq.examples.fundscustody.model.NAVCorrectionImpact;
import dev.mars.peegeeq.examples.fundscustody.model.NAVSnapshot;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for NAVService - NAV calculation and point-in-time reconstruction.
 */
@Tag(TestCategories.INTEGRATION)
class NAVServiceTest extends FundsCustodyTestBase {
    
    @Test
    void testCalculateNAV() throws Exception {
        String fundId = "FUND-001";
        LocalDate navDate = LocalDate.of(2025, 10, 1);
        
        // Calculate NAV
        navService.calculateNAV(
            fundId,
            navDate,
            new BigDecimal("10000000.00"),  // Total assets
            new BigDecimal("500000.00"),     // Total liabilities
            new BigDecimal("100000.00"),     // Shares outstanding
            Currency.USD,
            "nav-calculator"
        ).toCompletableFuture().get();
        
        Thread.sleep(100);
        
        // Get corrected NAV (latest)
        NAVSnapshot nav = navService.getNAVCorrected(fundId, navDate)
            .toCompletableFuture().get();
        
        assertNotNull(nav);
        assertEquals(fundId, nav.fundId());
        assertEquals(navDate, nav.navDate());
        // Use compareTo for BigDecimal to ignore scale differences
        assertEquals(0, new BigDecimal("10000000.00").compareTo(nav.totalAssets()));
        assertEquals(0, new BigDecimal("500000.00").compareTo(nav.totalLiabilities()));
        assertEquals(0, new BigDecimal("9500000.00").compareTo(nav.netAssets()));
        assertEquals(0, new BigDecimal("100000.00").compareTo(nav.sharesOutstanding()));
        assertEquals(0, new BigDecimal("95.000000").compareTo(nav.navPerShare()));
        assertEquals(Currency.USD, nav.currency());
    }
    
    @Test
    void testNAVAsReportedVsCorrected() throws Exception {
        String fundId = "FUND-002";
        LocalDate navDate = LocalDate.of(2025, 10, 1);
        
        // Calculate initial NAV (with error)
        navService.calculateNAV(
            fundId,
            navDate,
            new BigDecimal("10000000.00"),
            new BigDecimal("500000.00"),
            new BigDecimal("100000"),
            Currency.USD,
            "nav-calculator"
        ).toCompletableFuture().get();
        
        Thread.sleep(100);
        
        // Capture reporting time
        Instant reportingTime = Instant.now();
        Thread.sleep(100);
        
        // Recalculate NAV with correction (discovered asset valuation error)
        navService.calculateNAV(
            fundId,
            navDate,
            new BigDecimal("10100000.00"),  // Corrected assets (+100k)
            new BigDecimal("500000.00"),
            new BigDecimal("100000"),
            Currency.USD,
            "nav-auditor"
        ).toCompletableFuture().get();
        
        Thread.sleep(100);
        
        // Get NAV as reported
        NAVSnapshot reported = navService.getNAVAsReported(fundId, navDate, reportingTime)
            .toCompletableFuture().get();
        
        // Get corrected NAV
        NAVSnapshot corrected = navService.getNAVCorrected(fundId, navDate)
            .toCompletableFuture().get();
        
        // Verify reported NAV (original)
        assertNotNull(reported);
        assertEquals(0, new BigDecimal("95.000000").compareTo(reported.navPerShare()));
        
        // Verify corrected NAV (with fix)
        assertNotNull(corrected);
        assertEquals(0, new BigDecimal("96.000000").compareTo(corrected.navPerShare()));
        
        // Verify they are different
        assertNotEquals(reported.navPerShare(), corrected.navPerShare());
    }
    
    @Test
    void testAnalyzeNAVCorrection() throws Exception {
        String fundId = "FUND-003";
        LocalDate navDate = LocalDate.of(2025, 10, 1);
        
        // Calculate initial NAV
        navService.calculateNAV(
            fundId,
            navDate,
            new BigDecimal("10000000.00"),
            new BigDecimal("500000.00"),
            new BigDecimal("100000"),
            Currency.USD,
            "nav-calculator"
        ).toCompletableFuture().get();
        
        Thread.sleep(100);
        Instant reportingTime = Instant.now();
        Thread.sleep(100);
        
        // Recalculate with significant error (> 0.5% threshold)
        navService.calculateNAV(
            fundId,
            navDate,
            new BigDecimal("10060000.00"),  // +60k = 0.63% error
            new BigDecimal("500000.00"),
            new BigDecimal("100000"),
            Currency.USD,
            "nav-auditor"
        ).toCompletableFuture().get();
        
        Thread.sleep(100);
        
        // Analyze correction impact
        NAVCorrectionImpact impact = navService
            .analyzeNAVCorrection(fundId, navDate, reportingTime)
            .toCompletableFuture().get();
        
        assertNotNull(impact);
        assertEquals(fundId, impact.fundId());
        assertEquals(navDate, impact.navDate());
        assertEquals(0, new BigDecimal("95.000000").compareTo(impact.reportedNAV()));
        assertEquals(0, new BigDecimal("95.600000").compareTo(impact.correctedNAV()));
        assertEquals(0, new BigDecimal("0.600000").compareTo(impact.difference()));
        
        // Verify percentage error
        assertTrue(impact.percentageError().compareTo(new BigDecimal("0.005")) > 0); // > 0.5%

        // Verify investor compensation required
        assertTrue(impact.requiresInvestorCompensation());
        assertTrue(impact.isPositiveCorrection());
        assertFalse(impact.isNegativeCorrection());
    }
    
    @Test
    void testNAVCorrectionBelowThreshold() throws Exception {
        String fundId = "FUND-004";
        LocalDate navDate = LocalDate.of(2025, 10, 1);
        
        // Calculate initial NAV
        navService.calculateNAV(
            fundId,
            navDate,
            new BigDecimal("10000000.00"),
            new BigDecimal("500000.00"),
            new BigDecimal("100000"),
            Currency.USD,
            "nav-calculator"
        ).toCompletableFuture().get();
        
        Thread.sleep(100);
        Instant reportingTime = Instant.now();
        Thread.sleep(100);
        
        // Small correction (< 0.5% threshold)
        navService.calculateNAV(
            fundId,
            navDate,
            new BigDecimal("10020000.00"),  // +20k = 0.21% error
            new BigDecimal("500000.00"),
            new BigDecimal("100000"),
            Currency.USD,
            "nav-auditor"
        ).toCompletableFuture().get();
        
        Thread.sleep(100);
        
        // Analyze correction impact
        NAVCorrectionImpact impact = navService
            .analyzeNAVCorrection(fundId, navDate, reportingTime)
            .toCompletableFuture().get();
        
        assertNotNull(impact);

        // Verify no investor compensation required
        assertFalse(impact.requiresInvestorCompensation());
        assertTrue(impact.percentageError().compareTo(new BigDecimal("0.005")) < 0); // < 0.5%
    }
    
    @Test
    void testGetNAVHistory() throws Exception {
        String fundId = "FUND-005";
        
        // Calculate NAV for multiple dates
        LocalDate date1 = LocalDate.of(2025, 10, 1);
        LocalDate date2 = LocalDate.of(2025, 10, 2);
        LocalDate date3 = LocalDate.of(2025, 10, 3);
        
        navService.calculateNAV(fundId, date1, 
            new BigDecimal("10000000"), new BigDecimal("500000"), 
            new BigDecimal("100000"), Currency.USD, "calc1")
            .toCompletableFuture().get();
        
        Thread.sleep(50);
        
        navService.calculateNAV(fundId, date2, 
            new BigDecimal("10100000"), new BigDecimal("500000"), 
            new BigDecimal("100000"), Currency.USD, "calc2")
            .toCompletableFuture().get();
        
        Thread.sleep(50);
        
        navService.calculateNAV(fundId, date3, 
            new BigDecimal("10200000"), new BigDecimal("500000"), 
            new BigDecimal("100000"), Currency.USD, "calc3")
            .toCompletableFuture().get();
        
        Thread.sleep(100);
        
        // Get NAV history
        List<NAVSnapshot> history = navService
            .getNAVHistory(fundId, date1, date3)
            .toCompletableFuture().get();
        
        assertNotNull(history);
        assertEquals(3, history.size());
        
        // Verify chronological order
        assertEquals(date1, history.get(0).navDate());
        assertEquals(date2, history.get(1).navDate());
        assertEquals(date3, history.get(2).navDate());
        
        // Verify NAV progression
        assertEquals(0, new BigDecimal("95.000000").compareTo(history.get(0).navPerShare()));
        assertEquals(0, new BigDecimal("96.000000").compareTo(history.get(1).navPerShare()));
        assertEquals(0, new BigDecimal("97.000000").compareTo(history.get(2).navPerShare()));
    }
    
    @Test
    void testNAVWithZeroShares() throws Exception {
        String fundId = "FUND-006";
        LocalDate navDate = LocalDate.of(2025, 10, 1);
        
        // Calculate NAV with zero shares
        navService.calculateNAV(
            fundId,
            navDate,
            new BigDecimal("10000000.00"),
            new BigDecimal("500000.00"),
            BigDecimal.ZERO,  // Zero shares
            Currency.USD,
            "nav-calculator"
        ).toCompletableFuture().get();
        
        Thread.sleep(100);
        
        NAVSnapshot nav = navService.getNAVCorrected(fundId, navDate)
            .toCompletableFuture().get();
        
        assertNotNull(nav);
        assertEquals(BigDecimal.ZERO, nav.navPerShare());
    }
}

