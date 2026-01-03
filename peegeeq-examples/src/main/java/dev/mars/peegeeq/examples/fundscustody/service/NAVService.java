package dev.mars.peegeeq.examples.fundscustody.service;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.TemporalRange;
import dev.mars.peegeeq.examples.fundscustody.domain.Currency;
import dev.mars.peegeeq.examples.fundscustody.events.NAVEvent;
import dev.mars.peegeeq.examples.fundscustody.model.NAVCorrectionImpact;
import dev.mars.peegeeq.examples.fundscustody.model.NAVSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Service for NAV (Net Asset Value) calculation and point-in-time reconstruction.
 * 
 * <p>Implements high-priority bi-temporal use cases:
 * <ul>
 *   <li>Point-in-Time Reconstruction - NAV as reported vs NAV corrected</li>
 *   <li>NAV Correction Impact Analysis - Calculate investor compensation</li>
 *   <li>Historical NAV Queries - NAV at any point in time</li>
 * </ul>
 * 
 * <p>All methods return CompletableFuture to maintain async/non-blocking behavior.
 */
public class NAVService {
    private static final Logger logger = LoggerFactory.getLogger(NAVService.class);
    
    private final EventStore<NAVEvent> navEventStore;
    
    /**
     * Create a NAVService with the given event store.
     */
    public NAVService(EventStore<NAVEvent> navEventStore) {
        this.navEventStore = navEventStore;
    }
    
    /**
     * Calculate and record NAV for a fund on a specific date.
     * 
     * <p>Stores NAV with:
     * <ul>
     *   <li>Valid Time = NAV date (business date)</li>
     *   <li>Transaction Time = Now (when calculated)</li>
     *   <li>Aggregate ID = Fund ID</li>
     * </ul>
     * 
     * @param fundId fund identifier
     * @param navDate NAV calculation date
     * @param totalAssets total fund assets
     * @param totalLiabilities total fund liabilities
     * @param sharesOutstanding number of shares outstanding
     * @param currency fund currency
     * @param calculatedBy user who calculated NAV
     * @return future containing the NAV event
     */
    public CompletableFuture<BiTemporalEvent<NAVEvent>> calculateNAV(
            String fundId,
            LocalDate navDate,
            BigDecimal totalAssets,
            BigDecimal totalLiabilities,
            BigDecimal sharesOutstanding,
            Currency currency,
            String calculatedBy) {
        
        BigDecimal netAssets = totalAssets.subtract(totalLiabilities);
        BigDecimal navPerShare = sharesOutstanding.compareTo(BigDecimal.ZERO) > 0
            ? netAssets.divide(sharesOutstanding, 6, java.math.RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        
        NAVEvent event = new NAVEvent(
            fundId,
            navDate,
            totalAssets,
            totalLiabilities,
            sharesOutstanding,
            navPerShare,
            currency.name(),
            calculatedBy
        );
        
        // Valid time = NAV date (business date)
        Instant validTime = navDate.atStartOfDay().toInstant(ZoneOffset.UTC);
        
        logger.info("Calculating NAV for fund {} on {} = {}", fundId, navDate, navPerShare);
        
        return navEventStore.append(
            "NAVCalculated",
            event,
            validTime,
            Map.of(
                "navPerShare", navPerShare.toString(),
                "calculatedBy", calculatedBy
            ),
            null,
            null,
            "NAV:" + fundId  // Aggregate by NAV stream for this fund
        );
    }
    
    /**
     * Get NAV as it was reported on a specific date.
     * 
     * <p>Use Case: "What NAV did we report to investors on Dec 31st?"
     * 
     * <p>This answers: What did we know at the time of reporting?
     * Uses transaction time to get NAV as it was known at reporting time.
     * 
     * <p>Critical for regulatory compliance - must prove what was reported.
     * 
     * @param fundId fund identifier
     * @param navDate NAV date (business date)
     * @param asOfTransactionTime when the NAV was reported (system time)
     * @return future containing NAV snapshot as reported
     */
    public CompletableFuture<NAVSnapshot> getNAVAsReported(
            String fundId,
            LocalDate navDate,
            Instant asOfTransactionTime) {
        
        Instant navDateTime = navDate.atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
        
        logger.debug("Getting NAV as reported for fund {} on {} as of transaction time {}", 
            fundId, navDate, asOfTransactionTime);
        
        return navEventStore.query(
            EventQuery.builder()
                .aggregateId("NAV:" + fundId)
                .validTimeRange(TemporalRange.until(navDateTime))
                .transactionTimeRange(TemporalRange.until(asOfTransactionTime))
                .build()
        ).thenApply(events -> {
            // Find the NAV event for this specific date
            BiTemporalEvent<NAVEvent> navEvent = events.stream()
                .filter(e -> navDate.equals(e.getPayload().navDate()))
                .findFirst()
                .orElse(null);
            
            if (navEvent == null) {
                logger.warn("No NAV found for fund {} on {} as of {}", 
                    fundId, navDate, asOfTransactionTime);
                return null;
            }
            
            NAVEvent nav = navEvent.getPayload();
            return NAVSnapshot.create(
                fundId,
                navDate,
                navEvent.getTransactionTime(),
                nav.totalAssets(),
                nav.totalLiabilities(),
                nav.sharesOutstanding(),
                Currency.valueOf(nav.currency())
            );
        });
    }
    
    /**
     * Get corrected NAV with all subsequent corrections applied.
     * 
     * <p>Use Case: "What is the correct NAV for Dec 31st with all corrections?"
     * 
     * <p>This answers: What is the actual NAV with all known corrections?
     * Uses latest transaction time to get most recent NAV calculation.
     * 
     * @param fundId fund identifier
     * @param navDate NAV date (business date)
     * @return future containing corrected NAV snapshot
     */
    public CompletableFuture<NAVSnapshot> getNAVCorrected(
            String fundId,
            LocalDate navDate) {
        
        Instant navDateTime = navDate.atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
        
        logger.debug("Getting corrected NAV for fund {} on {}", fundId, navDate);
        
        return navEventStore.query(
            EventQuery.builder()
                .aggregateId("NAV:" + fundId)
                .validTimeRange(TemporalRange.until(navDateTime))
                // No transaction time filter = use latest knowledge
                .build()
        ).thenApply(events -> {
            // Find the most recent NAV event for this date
            BiTemporalEvent<NAVEvent> navEvent = events.stream()
                .filter(e -> navDate.equals(e.getPayload().navDate()))
                .max((a, b) -> a.getTransactionTime().compareTo(b.getTransactionTime()))
                .orElse(null);
            
            if (navEvent == null) {
                logger.warn("No NAV found for fund {} on {}", fundId, navDate);
                return null;
            }
            
            NAVEvent nav = navEvent.getPayload();
            return NAVSnapshot.create(
                fundId,
                navDate,
                navEvent.getTransactionTime(),
                nav.totalAssets(),
                nav.totalLiabilities(),
                nav.sharesOutstanding(),
                Currency.valueOf(nav.currency())
            );
        });
    }
    
    /**
     * Analyze NAV correction impact.
     * 
     * <p>Use Case: "Calculate investor compensation for NAV correction"
     * 
     * <p>Compares NAV as reported vs corrected NAV to determine:
     * <ul>
     *   <li>Absolute difference in NAV per share</li>
     *   <li>Percentage error</li>
     *   <li>Whether investor compensation is required (> 0.5% threshold)</li>
     * </ul>
     * 
     * @param fundId fund identifier
     * @param navDate NAV date to analyze
     * @param reportingTime when NAV was originally reported
     * @return future containing correction impact analysis
     */
    public CompletableFuture<NAVCorrectionImpact> analyzeNAVCorrection(
            String fundId,
            LocalDate navDate,
            Instant reportingTime) {
        
        logger.debug("Analyzing NAV correction impact for fund {} on {}", fundId, navDate);
        
        CompletableFuture<NAVSnapshot> reported = getNAVAsReported(fundId, navDate, reportingTime);
        CompletableFuture<NAVSnapshot> corrected = getNAVCorrected(fundId, navDate);
        
        return reported.thenCombine(corrected, (rep, cor) -> {
            if (rep == null || cor == null) {
                logger.warn("Cannot analyze NAV correction - missing NAV data");
                return null;
            }
            
            return NAVCorrectionImpact.analyze(
                fundId,
                navDate,
                rep.navPerShare(),
                cor.navPerShare()
            );
        });
    }
    
    /**
     * Get NAV history for a fund over a date range.
     * 
     * <p>Returns the most recent NAV for each date in the range.
     * 
     * @param fundId fund identifier
     * @param startDate start of date range
     * @param endDate end of date range
     * @return future containing list of NAV snapshots
     */
    public CompletableFuture<java.util.List<NAVSnapshot>> getNAVHistory(
            String fundId,
            LocalDate startDate,
            LocalDate endDate) {
        
        Instant startInstant = startDate.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant endInstant = endDate.atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
        
        logger.debug("Getting NAV history for fund {} from {} to {}", fundId, startDate, endDate);
        
        return navEventStore.query(
            EventQuery.builder()
                .aggregateId("NAV:" + fundId)
                .validTimeRange(new TemporalRange(startInstant, endInstant))
                .build()
        ).thenApply(events -> {
            // Group by NAV date and take most recent for each date
            return events.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                    e -> e.getPayload().navDate(),
                    java.util.stream.Collectors.maxBy(
                        (BiTemporalEvent<NAVEvent> a, BiTemporalEvent<NAVEvent> b) ->
                            a.getTransactionTime().compareTo(b.getTransactionTime())
                    )
                ))
                .values().stream()
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .map(e -> {
                    NAVEvent nav = e.getPayload();
                    return NAVSnapshot.create(
                        fundId,
                        nav.navDate(),
                        e.getTransactionTime(),
                        nav.totalAssets(),
                        nav.totalLiabilities(),
                        nav.sharesOutstanding(),
                        Currency.valueOf(nav.currency())
                    );
                })
                .sorted((a, b) -> a.navDate().compareTo(b.navDate()))
                .collect(java.util.stream.Collectors.toList());
        });
    }
}

