package dev.mars.peegeeq.examples.fundscustody.service;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.TemporalRange;
import dev.mars.peegeeq.examples.fundscustody.domain.Position;
import dev.mars.peegeeq.examples.fundscustody.domain.TradeType;
import dev.mars.peegeeq.examples.fundscustody.events.NAVEvent;
import dev.mars.peegeeq.examples.fundscustody.events.TradeEvent;
import dev.mars.peegeeq.examples.fundscustody.model.NAVSnapshot;
import dev.mars.peegeeq.examples.fundscustody.model.RegulatoryReport;
import dev.mars.peegeeq.examples.fundscustody.model.TradeChange;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import java.util.stream.Collectors;

/**
 * Service for regulatory reporting and compliance.
 * 
 * <p>Implements high-priority bi-temporal use cases:
 * <ul>
 *   <li>Regulatory Snapshots - State as reported on specific dates</li>
 *   <li>AIFMD Reporting - Alternative Investment Fund Managers Directive</li>
 *   <li>MiFID II Reporting - Markets in Financial Instruments Directive</li>
 * </ul>
 * 
 * <p>All methods return Future to maintain async/non-blocking behavior.
 */
public class RegulatoryReportingService {
    private static final Logger logger = LoggerFactory.getLogger(RegulatoryReportingService.class);
    
    private final EventStore<TradeEvent> tradeEventStore;
    private final EventStore<NAVEvent> navEventStore;
    private final PositionService positionService;
    private final NAVService navService;
    
    /**
     * Create a RegulatoryReportingService with the given dependencies.
     */
    public RegulatoryReportingService(
            EventStore<TradeEvent> tradeEventStore,
            EventStore<NAVEvent> navEventStore,
            PositionService positionService,
            NAVService navService) {
        this.tradeEventStore = tradeEventStore;
        this.navEventStore = navEventStore;
        this.positionService = positionService;
        this.navService = navService;
    }
    
    /**
     * Generate regulatory report showing exact state as reported on filing date.
     * 
     * <p>Use Case: "Generate report as it was filed with regulators"
     * 
     * <p>Critical for regulatory audits - must prove what was reported and when.
     * Uses transaction time to capture state as it was known at reporting time.
     * 
     * @param fundId fund identifier
     * @param reportingDate date of regulatory filing
     * @param reportType type of report (AIFMD, MiFID_II, etc.)
     * @return future containing regulatory report
     */
    public Future<RegulatoryReport> getRegulatorySnapshot(
            String fundId,
            LocalDate reportingDate,
            String reportType) {

        // Use current time to capture state "as of now"
        Instant filingDateTime = Instant.now();

        logger.info("Generating {} regulatory snapshot for fund {} as of {}",
            reportType, fundId, reportingDate);
        
        // Get NAV as reported
        Future<NAVSnapshot> navSnapshot = 
            navService.getNAVAsReported(fundId, reportingDate, filingDateTime);
        
        // Get positions as reported
        Future<List<Position>> positions = 
            positionService.getPositionsByFund(fundId, reportingDate);
        
        // Get trades in period (last 30 days for example)
        LocalDate periodStart = reportingDate.minusDays(30);
        Future<List<TradeChange>> trades = getTradesInPeriod(
            fundId, periodStart, reportingDate, filingDateTime);
        
        return Future.all(navSnapshot, positions, trades).map(cf -> {
            NAVSnapshot nav = navSnapshot.result();
            List<Position> pos = positions.result();
            List<TradeChange> tradeList = trades.result();
            
            return RegulatoryReport.create(
                fundId,
                reportingDate,
                filingDateTime,
                reportType,
                nav,
                pos,
                tradeList
            );
        });
    }
    
    /**
     * Generate AIFMD (Alternative Investment Fund Managers Directive) report.
     * 
     * <p>AIFMD requires reporting of:
     * <ul>
     *   <li>Fund NAV and composition</li>
     *   <li>Portfolio positions</li>
     *   <li>Leverage and risk metrics</li>
     * </ul>
     * 
     * @param fundId fund identifier
     * @param reportingDate quarterly reporting date
     * @return future containing AIFMD report
     */
    public Future<RegulatoryReport> getAIFMDReport(
            String fundId,
            LocalDate reportingDate) {
        
        logger.info("Generating AIFMD report for fund {} for {}", fundId, reportingDate);
        return getRegulatorySnapshot(fundId, reportingDate, "AIFMD");
    }
    
    /**
     * Generate MiFID II (Markets in Financial Instruments Directive) transaction report.
     * 
     * <p>MiFID II requires reporting of:
     * <ul>
     *   <li>All trades executed on a specific date</li>
     *   <li>Trade details (instrument, quantity, price, time)</li>
     *   <li>Counterparty information</li>
     * </ul>
     * 
     * <p>Must include all trades with trade date on reporting date,
     * regardless of when they were confirmed.
     * 
     * @param fundId fund identifier
     * @param tradingDay the trading day to report
     * @return future containing MiFID II report
     */
    public Future<RegulatoryReport> getMiFIDTransactionReport(
            String fundId,
            LocalDate tradingDay) {
        
        Instant dayStart = tradingDay.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant dayEnd = tradingDay.atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
        
        logger.info("Generating MiFID II transaction report for fund {} for {}", 
            fundId, tradingDay);
        
        // Get all trades with valid time on trading day
        Future<List<TradeChange>> trades = tradeEventStore.query(
            EventQuery.builder()
                .aggregateId("TRADE:" + fundId)
                .validTimeRange(new TemporalRange(dayStart, dayEnd))
                .build()
        ).map(events -> events.stream()
            .map(e -> {
                TradeEvent trade = e.getPayload();
                return TradeChange.newTrade(
                    trade.tradeId(),
                    trade.fundId(),
                    trade.securityId(),
                    trade.tradeDate(),
                    TradeType.valueOf(trade.tradeType()),
                    trade.quantity(),
                    trade.price(),
                    e.getTransactionTime()
                );
            })
            .collect(Collectors.toList())
        );
        
        // Get NAV for the day (if available)
        Future<NAVSnapshot> navSnapshot = 
            navService.getNAVCorrected(fundId, tradingDay);
        
        // Get positions at end of day
        Future<List<Position>> positions = 
            positionService.getPositionsByFund(fundId, tradingDay);
        
        return Future.all(navSnapshot, positions, trades).map(cf -> {
            NAVSnapshot nav = navSnapshot.result();
            List<Position> pos = positions.result();
            List<TradeChange> tradeList = trades.result();
            
            return RegulatoryReport.create(
                fundId,
                tradingDay,
                Instant.now(),
                "MiFID_II",
                nav,
                pos,
                tradeList
            );
        });
    }
    
    /**
     * Get trades in a period as they were known at a specific transaction time.
     */
    private Future<List<TradeChange>> getTradesInPeriod(
            String fundId,
            LocalDate startDate,
            LocalDate endDate,
            Instant asOfTransactionTime) {
        
        Instant startInstant = startDate.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant endInstant = endDate.atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
        
        return tradeEventStore.query(
            EventQuery.builder()
                .aggregateId("TRADE:" + fundId)
                .validTimeRange(new TemporalRange(startInstant, endInstant))
                .transactionTimeRange(TemporalRange.until(asOfTransactionTime))
                .build()
        ).map(events -> events.stream()
            .map(e -> {
                TradeEvent trade = e.getPayload();
                return TradeChange.newTrade(
                    trade.tradeId(),
                    trade.fundId(),
                    trade.securityId(),
                    trade.tradeDate(),
                    TradeType.valueOf(trade.tradeType()),
                    trade.quantity(),
                    trade.price(),
                    e.getTransactionTime()
                );
            })
            .collect(Collectors.toList())
        );
    }
}

