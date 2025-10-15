package dev.mars.peegeeq.examples.fundscustody.service;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.TemporalRange;
import dev.mars.peegeeq.examples.fundscustody.domain.TradeType;
import dev.mars.peegeeq.examples.fundscustody.events.TradeCancelledEvent;
import dev.mars.peegeeq.examples.fundscustody.events.TradeEvent;
import dev.mars.peegeeq.examples.fundscustody.model.ChangeReport;
import dev.mars.peegeeq.examples.fundscustody.model.CorrectionAudit;
import dev.mars.peegeeq.examples.fundscustody.model.TradeChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Service for auditing trade corrections and changes.
 * 
 * <p>Implements high-priority bi-temporal use cases:
 * <ul>
 *   <li>Correction Audit Trail - Track all corrections made to trades</li>
 *   <li>Change Detection - Identify what changed between two points in time</li>
 *   <li>Trade History - Complete lineage of a trade including corrections</li>
 * </ul>
 * 
 * <p>All methods return CompletableFuture to maintain async/non-blocking behavior.
 */
public class TradeAuditService {
    private static final Logger logger = LoggerFactory.getLogger(TradeAuditService.class);
    
    private final EventStore<TradeEvent> tradeEventStore;
    private final EventStore<TradeCancelledEvent> cancellationEventStore;
    
    /**
     * Create a TradeAuditService with the given event stores.
     */
    public TradeAuditService(
            EventStore<TradeEvent> tradeEventStore,
            EventStore<TradeCancelledEvent> cancellationEventStore) {
        this.tradeEventStore = tradeEventStore;
        this.cancellationEventStore = cancellationEventStore;
    }
    
    /**
     * Get all corrections made within a transaction time period.
     * 
     * <p>Use Case: "Show me all corrections made this month"
     * 
     * <p>This answers: What corrections were made between two system dates?
     * Uses transaction time to filter when corrections were recorded.
     * 
     * @param fundId fund identifier
     * @param periodStart start of transaction time period
     * @param periodEnd end of transaction time period
     * @return future containing list of correction audits
     */
    public CompletableFuture<List<CorrectionAudit>> getCorrectionsInPeriod(
            String fundId,
            Instant periodStart,
            Instant periodEnd) {
        
        logger.debug("Finding corrections for fund {} between {} and {}", 
            fundId, periodStart, periodEnd);
        
        return cancellationEventStore.query(
            EventQuery.builder()
                .aggregateId("CANCELLATION:" + fundId)
                .transactionTimeRange(new TemporalRange(periodStart, periodEnd))
                .build()
        ).thenCompose(cancellations -> {
            // For each cancellation, find the original trade to build audit record
            List<CompletableFuture<CorrectionAudit>> auditFutures = cancellations.stream()
                .map(cancellation -> buildCorrectionAudit(cancellation))
                .collect(Collectors.toList());

            // Combine all futures into a single future of list
            return auditFutures.stream()
                .reduce(
                    CompletableFuture.completedFuture(new ArrayList<CorrectionAudit>()),
                    (acc, future) -> acc.thenCombine(future, (list, audit) -> {
                        list.add(audit);
                        return list;
                    }),
                    (f1, f2) -> f1.thenCombine(f2, (list1, list2) -> {
                        list1.addAll(list2);
                        return list1;
                    })
                );
        });
    }
    
    /**
     * Get all corrections that affected a specific valid time period.
     * 
     * <p>Use Case: "Show corrections made to Q3 transactions, regardless of when corrected"
     * 
     * <p>This answers: What corrections affected trades in a specific business period?
     * Uses valid time to filter which trades were affected.
     * 
     * @param fundId fund identifier
     * @param validStart start of valid time period (trade dates)
     * @param validEnd end of valid time period (trade dates)
     * @return future containing list of correction audits
     */
    public CompletableFuture<List<CorrectionAudit>> getCorrectionsAffectingValidPeriod(
            String fundId,
            LocalDate validStart,
            LocalDate validEnd) {
        
        Instant validStartInstant = validStart.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant validEndInstant = validEnd.atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
        
        logger.debug("Finding corrections affecting fund {} trades from {} to {}", 
            fundId, validStart, validEnd);
        
        return cancellationEventStore.query(
            EventQuery.builder()
                .aggregateId("CANCELLATION:" + fundId)
                .validTimeRange(new TemporalRange(validStartInstant, validEndInstant))
                .build()
        ).thenCompose(cancellations -> {
            List<CompletableFuture<CorrectionAudit>> auditFutures = cancellations.stream()
                .map(cancellation -> buildCorrectionAudit(cancellation))
                .collect(Collectors.toList());

            // Combine all futures into a single future of list
            return auditFutures.stream()
                .reduce(
                    CompletableFuture.completedFuture(new ArrayList<CorrectionAudit>()),
                    (acc, future) -> acc.thenCombine(future, (list, audit) -> {
                        list.add(audit);
                        return list;
                    }),
                    (f1, f2) -> f1.thenCombine(f2, (list1, list2) -> {
                        list1.addAll(list2);
                        return list1;
                    })
                );
        });
    }
    
    /**
     * Get complete correction history for a specific trade.
     * 
     * <p>Use Case: "Show me all changes made to this trade"
     * 
     * <p>Returns all events related to a trade ordered by transaction time,
     * showing the complete evolution of the trade.
     * 
     * @param tradeId trade identifier
     * @return future containing list of correction audits
     */
    public CompletableFuture<List<CorrectionAudit>> getTradeCorrectionHistory(String tradeId) {
        logger.debug("Finding correction history for trade {}", tradeId);

        return cancellationEventStore.query(EventQuery.all())
            .thenCompose(cancellations -> {
                List<BiTemporalEvent<TradeCancelledEvent>> tradeCancellations = cancellations.stream()
                    .filter(c -> tradeId.equals(c.getPayload().tradeId()))
                    .sorted((a, b) -> a.getTransactionTime().compareTo(b.getTransactionTime()))
                    .collect(Collectors.toList());
                
                List<CompletableFuture<CorrectionAudit>> auditFutures = tradeCancellations.stream()
                    .map(cancellation -> buildCorrectionAudit(cancellation))
                    .collect(Collectors.toList());

                // Combine all futures into a single future of list
                return auditFutures.stream()
                    .reduce(
                        CompletableFuture.completedFuture(new ArrayList<CorrectionAudit>()),
                        (acc, future) -> acc.thenCombine(future, (list, audit) -> {
                            list.add(audit);
                            return list;
                        }),
                        (f1, f2) -> f1.thenCombine(f2, (list1, list2) -> {
                            list1.addAll(list2);
                            return list1;
                        })
                    );
            });
    }
    
    /**
     * Detect changes between two transaction times.
     * 
     * <p>Use Case: "What changed between two reporting periods?"
     * 
     * <p>Compares the state of trades as known at two different transaction times
     * to identify new trades and corrections.
     * 
     * @param fundId fund identifier
     * @param fromTransactionTime earlier transaction time
     * @param toTransactionTime later transaction time
     * @return future containing change report
     */
    public CompletableFuture<ChangeReport> getChangesBetween(
            String fundId,
            Instant fromTransactionTime,
            Instant toTransactionTime) {
        
        logger.debug("Detecting changes for fund {} between {} and {}", 
            fundId, fromTransactionTime, toTransactionTime);
        
        // Get state as of earlier time
        CompletableFuture<List<BiTemporalEvent<TradeEvent>>> beforeState =
            tradeEventStore.query(
                EventQuery.builder()
                    .aggregateId("TRADE:" + fundId)
                    .transactionTimeRange(TemporalRange.until(fromTransactionTime))
                    .build()
            );

        // Get state as of later time
        CompletableFuture<List<BiTemporalEvent<TradeEvent>>> afterState =
            tradeEventStore.query(
                EventQuery.builder()
                    .aggregateId("TRADE:" + fundId)
                    .transactionTimeRange(TemporalRange.until(toTransactionTime))
                    .build()
            );

        // Get cancellations in the period
        CompletableFuture<List<BiTemporalEvent<TradeCancelledEvent>>> corrections =
            cancellationEventStore.query(
                EventQuery.builder()
                    .aggregateId("CANCELLATION:" + fundId)
                    .transactionTimeRange(new TemporalRange(fromTransactionTime, toTransactionTime))
                    .build()
            );
        
        return beforeState.thenCombine(afterState, (before, after) -> {
            // Find new trades (in after but not in before)
            List<String> beforeTradeIds = before.stream()
                .map(e -> e.getPayload().tradeId())
                .collect(Collectors.toList());
            
            List<TradeChange> newTrades = after.stream()
                .filter(e -> !beforeTradeIds.contains(e.getPayload().tradeId()))
                .filter(e -> e.getTransactionTime().isAfter(fromTransactionTime))
                .filter(e -> !e.getTransactionTime().isAfter(toTransactionTime))
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
                .collect(Collectors.toList());

            return newTrades;
        }).thenCombine(corrections, (newTrades, correctionList) -> {
            // Build correction changes
            List<TradeChange> correctedTrades = correctionList.stream()
                .map(c -> {
                    TradeCancelledEvent cancel = c.getPayload();
                    return TradeChange.correctedTrade(
                        cancel.tradeId(),
                        cancel.fundId(),
                        cancel.securityId(),
                        cancel.originalTradeDate(),
                        null,  // Type not available in cancellation
                        null,  // Quantity not available
                        null,  // Price not available
                        c.getTransactionTime()
                    );
                })
                .collect(Collectors.toList());
            
            return ChangeReport.create(
                fundId,
                fromTransactionTime,
                toTransactionTime,
                newTrades,
                correctedTrades
            );
        });
    }
    
    /**
     * Build a correction audit record from a cancellation event.
     */
    private CompletableFuture<CorrectionAudit> buildCorrectionAudit(
            BiTemporalEvent<TradeCancelledEvent> cancellation) {
        
        TradeCancelledEvent cancel = cancellation.getPayload();

        // Find original trade to get original values
        return tradeEventStore.query(EventQuery.all())
            .thenApply(trades -> {
                BiTemporalEvent<TradeEvent> original = trades.stream()
                    .filter(t -> cancel.tradeId().equals(t.getPayload().tradeId()))
                    .filter(t -> t.getTransactionTime().isBefore(cancellation.getTransactionTime()))
                    .findFirst()
                    .orElse(null);
                
                if (original == null) {
                    // No original found - create audit with nulls
                    return new CorrectionAudit(
                        cancel.tradeId(),
                        cancel.fundId(),
                        cancel.securityId(),
                        cancel.originalTradeDate(),
                        null,  // Original quantity unknown
                        null,  // Corrected quantity unknown
                        null,  // Original price unknown
                        null,  // Corrected price unknown
                        cancel.reason(),
                        cancel.cancelledBy(),
                        cancellation.getTransactionTime()
                    );
                }

                TradeEvent originalTrade = original.getPayload();
                return new CorrectionAudit(
                    cancel.tradeId(),
                    cancel.fundId(),
                    cancel.securityId(),
                    cancel.originalTradeDate(),
                    originalTrade.quantity(),
                    null,  // Cancelled - no corrected quantity
                    originalTrade.price(),
                    null,  // Cancelled - no corrected price
                    cancel.reason(),
                    cancel.cancelledBy(),
                    cancellation.getTransactionTime()
                );
            });
    }
}

