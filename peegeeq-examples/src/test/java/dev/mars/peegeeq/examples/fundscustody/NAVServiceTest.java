package dev.mars.peegeeq.examples.fundscustody;

import dev.mars.peegeeq.examples.fundscustody.domain.Currency;
import dev.mars.peegeeq.examples.fundscustody.model.NAVCorrectionImpact;
import dev.mars.peegeeq.examples.fundscustody.model.NAVSnapshot;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for NAVService - NAV calculation and point-in-time reconstruction.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
class NAVServiceTest extends FundsCustodyTestBase {

    private Future<Void> delay(Vertx vertx, long ms) {
        Promise<Void> p = Promise.promise();
        vertx.setTimer(ms, id -> p.complete());
        return p.future();
    }

    @Test
    void testCalculateNAV(Vertx vertx, VertxTestContext testContext) {
        String fundId = "FUND-001";
        LocalDate navDate = LocalDate.of(2025, 10, 1);

        navService.calculateNAV(fundId, navDate,
                new BigDecimal("10000000.00"),
                new BigDecimal("500000.00"),
                new BigDecimal("100000.00"),
                Currency.USD, "nav-calculator")
            .compose(v -> delay(vertx, 100))
            .compose(v -> navService.getNAVCorrected(fundId, navDate))
            .onSuccess(nav -> testContext.verify(() -> {
                assertNotNull(nav);
                assertEquals(fundId, nav.fundId());
                assertEquals(navDate, nav.navDate());
                assertEquals(0, new BigDecimal("10000000.00").compareTo(nav.totalAssets()));
                assertEquals(0, new BigDecimal("500000.00").compareTo(nav.totalLiabilities()));
                assertEquals(0, new BigDecimal("9500000.00").compareTo(nav.netAssets()));
                assertEquals(0, new BigDecimal("100000.00").compareTo(nav.sharesOutstanding()));
                assertEquals(0, new BigDecimal("95.000000").compareTo(nav.navPerShare()));
                assertEquals(Currency.USD, nav.currency());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
    
    @Test
    void testNAVAsReportedVsCorrected(Vertx vertx, VertxTestContext testContext) {
        String fundId = "FUND-002";
        LocalDate navDate = LocalDate.of(2025, 10, 1);

        navService.calculateNAV(fundId, navDate,
                new BigDecimal("10000000.00"), new BigDecimal("500000.00"),
                new BigDecimal("100000"), Currency.USD, "nav-calculator")
            .compose(v -> delay(vertx, 100))
            .compose(v -> {
                Instant reportingTime = Instant.now();
                return delay(vertx, 100)
                    .compose(v2 -> navService.calculateNAV(fundId, navDate,
                            new BigDecimal("10100000.00"), new BigDecimal("500000.00"),
                            new BigDecimal("100000"), Currency.USD, "nav-auditor"))
                    .compose(v2 -> delay(vertx, 100))
                    .compose(v2 -> Future.all(
                        navService.getNAVAsReported(fundId, navDate, reportingTime),
                        navService.getNAVCorrected(fundId, navDate)
                    ));
            })
            .onSuccess(composite -> testContext.verify(() -> {
                NAVSnapshot reported = composite.resultAt(0);
                NAVSnapshot corrected = composite.resultAt(1);
                assertNotNull(reported);
                assertEquals(0, new BigDecimal("95.000000").compareTo(reported.navPerShare()));
                assertNotNull(corrected);
                assertEquals(0, new BigDecimal("96.000000").compareTo(corrected.navPerShare()));
                assertNotEquals(reported.navPerShare(), corrected.navPerShare());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testAnalyzeNAVCorrection(Vertx vertx, VertxTestContext testContext) {
        String fundId = "FUND-003";
        LocalDate navDate = LocalDate.of(2025, 10, 1);

        navService.calculateNAV(fundId, navDate,
                new BigDecimal("10000000.00"), new BigDecimal("500000.00"),
                new BigDecimal("100000"), Currency.USD, "nav-calculator")
            .compose(v -> delay(vertx, 100))
            .compose(v -> {
                Instant reportingTime = Instant.now();
                return delay(vertx, 100)
                    .compose(v2 -> navService.calculateNAV(fundId, navDate,
                            new BigDecimal("10060000.00"), new BigDecimal("500000.00"),
                            new BigDecimal("100000"), Currency.USD, "nav-auditor"))
                    .compose(v2 -> delay(vertx, 100))
                    .compose(v2 -> navService.analyzeNAVCorrection(fundId, navDate, reportingTime));
            })
            .onSuccess(impact -> testContext.verify(() -> {
                assertNotNull(impact);
                assertEquals(fundId, impact.fundId());
                assertEquals(navDate, impact.navDate());
                assertEquals(0, new BigDecimal("95.000000").compareTo(impact.reportedNAV()));
                assertEquals(0, new BigDecimal("95.600000").compareTo(impact.correctedNAV()));
                assertEquals(0, new BigDecimal("0.600000").compareTo(impact.difference()));
                assertTrue(impact.percentageError().compareTo(new BigDecimal("0.005")) > 0);
                assertTrue(impact.requiresInvestorCompensation());
                assertTrue(impact.isPositiveCorrection());
                assertFalse(impact.isNegativeCorrection());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testNAVCorrectionBelowThreshold(Vertx vertx, VertxTestContext testContext) {
        String fundId = "FUND-004";
        LocalDate navDate = LocalDate.of(2025, 10, 1);

        navService.calculateNAV(fundId, navDate,
                new BigDecimal("10000000.00"), new BigDecimal("500000.00"),
                new BigDecimal("100000"), Currency.USD, "nav-calculator")
            .compose(v -> delay(vertx, 100))
            .compose(v -> {
                Instant reportingTime = Instant.now();
                return delay(vertx, 100)
                    .compose(v2 -> navService.calculateNAV(fundId, navDate,
                            new BigDecimal("10020000.00"), new BigDecimal("500000.00"),
                            new BigDecimal("100000"), Currency.USD, "nav-auditor"))
                    .compose(v2 -> delay(vertx, 100))
                    .compose(v2 -> navService.analyzeNAVCorrection(fundId, navDate, reportingTime));
            })
            .onSuccess(impact -> testContext.verify(() -> {
                assertNotNull(impact);
                assertFalse(impact.requiresInvestorCompensation());
                assertTrue(impact.percentageError().compareTo(new BigDecimal("0.005")) < 0);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testGetNAVHistory(Vertx vertx, VertxTestContext testContext) {
        String fundId = "FUND-005";
        LocalDate date1 = LocalDate.of(2025, 10, 1);
        LocalDate date2 = LocalDate.of(2025, 10, 2);
        LocalDate date3 = LocalDate.of(2025, 10, 3);

        navService.calculateNAV(fundId, date1,
                new BigDecimal("10000000"), new BigDecimal("500000"),
                new BigDecimal("100000"), Currency.USD, "calc1")
            .compose(v -> delay(vertx, 50))
            .compose(v -> navService.calculateNAV(fundId, date2,
                    new BigDecimal("10100000"), new BigDecimal("500000"),
                    new BigDecimal("100000"), Currency.USD, "calc2"))
            .compose(v -> delay(vertx, 50))
            .compose(v -> navService.calculateNAV(fundId, date3,
                    new BigDecimal("10200000"), new BigDecimal("500000"),
                    new BigDecimal("100000"), Currency.USD, "calc3"))
            .compose(v -> delay(vertx, 100))
            .compose(v -> navService.getNAVHistory(fundId, date1, date3))
            .onSuccess(history -> testContext.verify(() -> {
                assertNotNull(history);
                assertEquals(3, history.size());
                assertEquals(date1, history.get(0).navDate());
                assertEquals(date2, history.get(1).navDate());
                assertEquals(date3, history.get(2).navDate());
                assertEquals(0, new BigDecimal("95.000000").compareTo(history.get(0).navPerShare()));
                assertEquals(0, new BigDecimal("96.000000").compareTo(history.get(1).navPerShare()));
                assertEquals(0, new BigDecimal("97.000000").compareTo(history.get(2).navPerShare()));
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testNAVWithZeroShares(Vertx vertx, VertxTestContext testContext) {
        String fundId = "FUND-006";
        LocalDate navDate = LocalDate.of(2025, 10, 1);

        navService.calculateNAV(fundId, navDate,
                new BigDecimal("10000000.00"), new BigDecimal("500000.00"),
                BigDecimal.ZERO, Currency.USD, "nav-calculator")
            .compose(v -> delay(vertx, 100))
            .compose(v -> navService.getNAVCorrected(fundId, navDate))
            .onSuccess(nav -> testContext.verify(() -> {
                assertNotNull(nav);
                assertEquals(BigDecimal.ZERO, nav.navPerShare());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
}

