package dev.mars.peegeeq.examples.springbootfinancialfabric.query;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.TemporalRange;
import dev.mars.peegeeq.bitemporal.PgBiTemporalEventStore;
import dev.mars.peegeeq.examples.springbootfinancialfabric.events.RegulatoryReportEvent;
import dev.mars.peegeeq.examples.springbootfinancialfabric.events.TradeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Query service for regulatory reporting and compliance.
 * 
 * Provides bi-temporal queries for:
 * - MiFID II transaction reporting
 * - Trade reconstruction for regulatory audits
 * - Compliance audit trails
 * - Regulatory report generation
 */
@Service
public class RegulatoryQueryService {
    
    private static final Logger log = LoggerFactory.getLogger(RegulatoryQueryService.class);
    
    private final PgBiTemporalEventStore<TradeEvent> tradingEventStore;
    private final PgBiTemporalEventStore<RegulatoryReportEvent> regulatoryEventStore;
    
    public RegulatoryQueryService(
            @Qualifier("tradingEventStore") PgBiTemporalEventStore<TradeEvent> tradingEventStore,
            @Qualifier("regulatoryEventStore") PgBiTemporalEventStore<RegulatoryReportEvent> regulatoryEventStore) {
        this.tradingEventStore = tradingEventStore;
        this.regulatoryEventStore = regulatoryEventStore;
    }
    
    /**
     * Generate MiFID II transaction report for a specific date.
     * Returns all trades executed on that date with full audit trail.
     */
    public CompletableFuture<MiFIDIIReport> generateMiFIDIIReport(Instant reportDate) {
        log.info("Generating MiFID II report for date: {}", reportDate);
        
        Instant startOfDay = reportDate.truncatedTo(ChronoUnit.DAYS);
        Instant endOfDay = startOfDay.plus(1, ChronoUnit.DAYS);
        
        return tradingEventStore.query(
            EventQuery.builder()
                .eventType("trading.equities.capture.completed")
                .validTimeRange(new TemporalRange(startOfDay, endOfDay))
                .includeCorrections(true)
                .sortOrder(EventQuery.SortOrder.VALID_TIME_ASC)
                .build()
        ).thenApply(events -> {
            MiFIDIIReport report = new MiFIDIIReport();
            report.reportDate = reportDate;
            report.generatedAt = Instant.now();
            report.totalTransactions = events.size();
            
            for (BiTemporalEvent<TradeEvent> event : events) {
                TradeEvent trade = event.getPayload();
                
                MiFIDIITransaction transaction = new MiFIDIITransaction();
                transaction.tradeId = trade.getTradeId();
                transaction.instrument = trade.getInstrument();
                transaction.quantity = trade.getQuantity().toString();
                transaction.price = trade.getPrice().toString();
                transaction.tradeType = trade.getTradeType();
                transaction.counterparty = trade.getCounterparty();
                transaction.executionTime = event.getValidTime();
                transaction.reportingTime = event.getTransactionTime();
                transaction.isCorrection = event.isCorrection();
                transaction.correctionReason = event.getCorrectionReason();
                
                report.transactions.add(transaction);
            }
            
            log.info("Generated MiFID II report: {} transactions", report.totalTransactions);
            return report;
        });
    }
    
    /**
     * Get complete audit trail for regulatory investigation.
     * Returns all events across all domains for a specific correlation ID.
     */
    public CompletableFuture<RegulatoryAuditTrail> getRegulatoryAuditTrail(
            String correlationId) {
        log.info("Generating regulatory audit trail for correlation ID: {}", correlationId);
        
        // Query trading events
        CompletableFuture<List<BiTemporalEvent<TradeEvent>>> tradingEvents = 
            tradingEventStore.query(
                EventQuery.builder()
                    .correlationId(correlationId)
                    .includeCorrections(true)
                    .sortOrder(EventQuery.SortOrder.TRANSACTION_TIME_ASC)
                    .build()
            );
        
        // Query regulatory events
        CompletableFuture<List<BiTemporalEvent<RegulatoryReportEvent>>> regulatoryEvents = 
            regulatoryEventStore.query(
                EventQuery.builder()
                    .correlationId(correlationId)
                    .includeCorrections(true)
                    .sortOrder(EventQuery.SortOrder.TRANSACTION_TIME_ASC)
                    .build()
            );
        
        return CompletableFuture.allOf(tradingEvents, regulatoryEvents)
            .thenApply(v -> {
                RegulatoryAuditTrail trail = new RegulatoryAuditTrail();
                trail.correlationId = correlationId;
                trail.generatedAt = Instant.now();
                trail.tradingEvents = tradingEvents.join();
                trail.regulatoryEvents = regulatoryEvents.join();
                trail.totalEvents = trail.tradingEvents.size() + trail.regulatoryEvents.size();
                
                log.info("Generated regulatory audit trail: {} total events", trail.totalEvents);
                return trail;
            });
    }
    
    /**
     * Get all regulatory reports submitted within a time range.
     */
    public CompletableFuture<List<BiTemporalEvent<RegulatoryReportEvent>>> getRegulatoryReports(
            Instant startTime, Instant endTime) {
        log.info("Querying regulatory reports between {} and {}", startTime, endTime);
        
        return regulatoryEventStore.query(
            EventQuery.builder()
                .eventType("regulatory.mifid2.submitted")
                .validTimeRange(new TemporalRange(startTime, endTime))
                .sortOrder(EventQuery.SortOrder.VALID_TIME_ASC)
                .build()
        );
    }
    
    /**
     * Get all trade corrections within a time range.
     * Used for regulatory oversight of trade amendments.
     */
    public CompletableFuture<List<BiTemporalEvent<TradeEvent>>> getTradeCorrections(
            Instant startTime, Instant endTime) {
        log.info("Querying trade corrections between {} and {}", startTime, endTime);
        
        return tradingEventStore.query(
            EventQuery.builder()
                .transactionTimeRange(new TemporalRange(startTime, endTime))
                .includeCorrections(true)
                .sortOrder(EventQuery.SortOrder.TRANSACTION_TIME_ASC)
                .build()
        ).thenApply(events -> 
            events.stream()
                .filter(BiTemporalEvent::isCorrection)
                .collect(Collectors.toList())
        );
    }
    
    /**
     * Reconstruct trade as it was reported to regulator at a specific time.
     * Uses transaction time to show what was known at that point.
     */
    public CompletableFuture<BiTemporalEvent<TradeEvent>> getTradeAsReportedAt(
            String tradeId, Instant reportingTime) {
        log.info("Reconstructing trade {} as reported at {}", tradeId, reportingTime);
        
        return tradingEventStore.query(
            EventQuery.builder()
                .aggregateId(tradeId)
                .transactionTimeRange(TemporalRange.until(reportingTime))
                .sortOrder(EventQuery.SortOrder.TRANSACTION_TIME_DESC)
                .limit(1)
                .build()
        ).thenApply(events -> {
            if (events.isEmpty()) {
                log.warn("No trade found for {} as reported at {}", tradeId, reportingTime);
                return null;
            }
            return events.get(0);
        });
    }
    
    /**
     * Get all trades that were corrected after initial reporting.
     * Used for regulatory compliance monitoring.
     */
    public CompletableFuture<List<String>> getTradesWithCorrections(
            Instant startTime, Instant endTime) {
        log.info("Querying trades with corrections between {} and {}", startTime, endTime);
        
        return tradingEventStore.query(
            EventQuery.builder()
                .validTimeRange(new TemporalRange(startTime, endTime))
                .includeCorrections(true)
                .sortOrder(EventQuery.SortOrder.VALID_TIME_ASC)
                .build()
        ).thenApply(events -> {
            Set<String> tradesWithCorrections = new HashSet<>();
            
            for (BiTemporalEvent<TradeEvent> event : events) {
                if (event.isCorrection()) {
                    tradesWithCorrections.add(event.getAggregateId());
                }
            }
            
            log.info("Found {} trades with corrections", tradesWithCorrections.size());
            return new ArrayList<>(tradesWithCorrections);
        });
    }
    
    /**
     * Generate compliance report showing all regulatory submissions.
     */
    public CompletableFuture<ComplianceReport> generateComplianceReport(
            Instant startTime, Instant endTime) {
        log.info("Generating compliance report between {} and {}", startTime, endTime);
        
        CompletableFuture<List<BiTemporalEvent<RegulatoryReportEvent>>> reports = 
            getRegulatoryReports(startTime, endTime);
        
        CompletableFuture<List<BiTemporalEvent<TradeEvent>>> corrections = 
            getTradeCorrections(startTime, endTime);
        
        return CompletableFuture.allOf(reports, corrections)
            .thenApply(v -> {
                ComplianceReport report = new ComplianceReport();
                report.startTime = startTime;
                report.endTime = endTime;
                report.generatedAt = Instant.now();
                report.regulatoryReports = reports.join();
                report.tradeCorrections = corrections.join();
                report.totalReports = report.regulatoryReports.size();
                report.totalCorrections = report.tradeCorrections.size();
                
                log.info("Generated compliance report: {} reports, {} corrections", 
                        report.totalReports, report.totalCorrections);
                return report;
            });
    }
    
    /**
     * MiFID II transaction report.
     */
    public static class MiFIDIIReport {
        public Instant reportDate;
        public Instant generatedAt;
        public int totalTransactions;
        public List<MiFIDIITransaction> transactions = new ArrayList<>();
    }
    
    /**
     * MiFID II transaction details.
     */
    public static class MiFIDIITransaction {
        public String tradeId;
        public String instrument;
        public String quantity;
        public String price;
        public String tradeType;
        public String counterparty;
        public Instant executionTime;
        public Instant reportingTime;
        public boolean isCorrection;
        public String correctionReason;
    }
    
    /**
     * Regulatory audit trail for a specific workflow.
     */
    public static class RegulatoryAuditTrail {
        public String correlationId;
        public Instant generatedAt;
        public int totalEvents;
        public List<BiTemporalEvent<TradeEvent>> tradingEvents;
        public List<BiTemporalEvent<RegulatoryReportEvent>> regulatoryEvents;
    }
    
    /**
     * Compliance report showing regulatory submissions and corrections.
     */
    public static class ComplianceReport {
        public Instant startTime;
        public Instant endTime;
        public Instant generatedAt;
        public int totalReports;
        public int totalCorrections;
        public List<BiTemporalEvent<RegulatoryReportEvent>> regulatoryReports;
        public List<BiTemporalEvent<TradeEvent>> tradeCorrections;
    }
}

