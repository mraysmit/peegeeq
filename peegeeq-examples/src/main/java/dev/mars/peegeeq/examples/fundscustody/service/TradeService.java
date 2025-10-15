package dev.mars.peegeeq.examples.fundscustody.service;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.examples.fundscustody.domain.Trade;
import dev.mars.peegeeq.examples.fundscustody.events.TradeCancelledEvent;
import dev.mars.peegeeq.examples.fundscustody.events.TradeEvent;
import dev.mars.peegeeq.examples.fundscustody.model.CancellationRequest;
import dev.mars.peegeeq.examples.fundscustody.model.TradeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Service for managing trade lifecycle in a funds & custody system.
 * 
 * <p>This service demonstrates bi-temporal event store patterns:
 * <ul>
 *   <li>Valid Time = Trade Date (when the trade was executed)</li>
 *   <li>Transaction Time = Confirmation Time (when the trade was confirmed/recorded)</li>
 *   <li>Aggregate ID = Fund ID (enables efficient querying by fund)</li>
 * </ul>
 * 
 * <p>All methods return CompletableFuture to maintain async/non-blocking behavior.
 * No Spring dependencies - plain Java with constructor injection.
 */
public class TradeService {
    private static final Logger logger = LoggerFactory.getLogger(TradeService.class);
    
    private final EventStore<TradeEvent> tradeEventStore;
    private final EventStore<TradeCancelledEvent> cancellationEventStore;
    
    /**
     * Create a TradeService with the given event stores.
     * 
     * @param tradeEventStore event store for trade events
     * @param cancellationEventStore event store for cancellation events
     */
    public TradeService(
            EventStore<TradeEvent> tradeEventStore,
            EventStore<TradeCancelledEvent> cancellationEventStore) {
        this.tradeEventStore = tradeEventStore;
        this.cancellationEventStore = cancellationEventStore;
    }
    
    /**
     * Record a trade with trade date as valid time.
     * 
     * <p>The trade is stored with:
     * <ul>
     *   <li>Valid Time = Trade Date</li>
     *   <li>Transaction Time = Now (confirmation time)</li>
     *   <li>Aggregate ID = Fund ID</li>
     * </ul>
     * 
     * @param request trade details
     * @return future containing the stored event
     */
    public CompletableFuture<BiTemporalEvent<TradeEvent>> recordTrade(TradeRequest request) {
        Trade trade = request.toDomain();
        TradeEvent event = TradeEvent.from(trade);
        
        // Valid time = trade date (when trade was executed)
        Instant validTime = trade.tradeDate().atStartOfDay().toInstant(ZoneOffset.UTC);
        
        logger.info("Recording trade {} for fund {} on trade date {}", 
            trade.tradeId(), trade.fundId(), trade.tradeDate());
        
        return tradeEventStore.append(
            "TradeExecuted",
            event,
            validTime,
            Map.of(
                "tradeId", trade.tradeId(),
                "settlementDate", trade.settlementDate().toString(),
                "counterparty", trade.counterparty()
            ),
            null,
            "TRADE:" + trade.fundId()  // Aggregate by trade stream for this fund
        );
    }
    
    /**
     * Cancel a trade by recording a cancellation event.
     * 
     * <p>The cancellation is stored with:
     * <ul>
     *   <li>Valid Time = Original Trade Date (same as the trade being cancelled)</li>
     *   <li>Transaction Time = Now (when cancellation was processed)</li>
     *   <li>Aggregate ID = Fund ID (same as original trade)</li>
     * </ul>
     * 
     * @param tradeId ID of the trade to cancel
     * @param request cancellation details
     * @return future containing the cancellation event
     */
    public CompletableFuture<BiTemporalEvent<TradeCancelledEvent>> cancelTrade(
            String tradeId, CancellationRequest request) {
        
        return findTradeById(tradeId)
            .thenCompose(originalTrade -> {
                if (originalTrade == null) {
                    return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Trade not found: " + tradeId));
                }
                
                TradeEvent original = originalTrade.getPayload();
                TradeCancelledEvent cancellation = new TradeCancelledEvent(
                    tradeId,
                    original.fundId(),
                    original.securityId(),
                    original.tradeDate(),
                    request.reason(),
                    request.cancelledBy()
                );
                
                // Valid time = original trade date (backdated correction)
                Instant validTime = original.tradeDate().atStartOfDay().toInstant(ZoneOffset.UTC);
                
                logger.info("Cancelling trade {} for fund {} - reason: {}", 
                    tradeId, original.fundId(), request.reason());
                
                return cancellationEventStore.append(
                    "TradeCancelled",
                    cancellation,
                    validTime,
                    Map.of(
                        "tradeId", tradeId,
                        "reason", request.reason(),
                        "cancelledBy", request.cancelledBy()
                    ),
                    null,
                    "CANCELLATION:" + original.fundId()  // Separate stream for cancellations
                );
            });
    }
    
    /**
     * Query all trades for a fund.
     * 
     * @param fundId fund identifier
     * @return future containing list of trade events
     */
    public CompletableFuture<List<BiTemporalEvent<TradeEvent>>> queryTradesByFund(String fundId) {
        logger.debug("Querying trades for fund {}", fundId);
        return tradeEventStore.query(EventQuery.forAggregate("TRADE:" + fundId));
    }
    
    /**
     * Get late trade confirmations for a trading day.
     * 
     * <p>A trade is considered "late" if:
     * <ul>
     *   <li>Trade date is on the specified trading day</li>
     *   <li>Confirmation time (transaction time) is after the cutoff time</li>
     * </ul>
     * 
     * <p>Late trades typically require NAV recalculation and may need regulatory reporting.
     * 
     * @param fundId fund identifier
     * @param tradingDay the trading day to check
     * @param cutoffTime cutoff time for same-day confirmation (e.g., 18:00)
     * @return future containing list of late trade events
     */
    public CompletableFuture<List<BiTemporalEvent<TradeEvent>>> getLateTradeConfirmations(
            String fundId, LocalDate tradingDay, LocalTime cutoffTime) {
        
        Instant cutoffDateTime = tradingDay.atTime(cutoffTime).toInstant(ZoneOffset.UTC);
        
        logger.debug("Finding late trade confirmations for fund {} on {} after {}", 
            fundId, tradingDay, cutoffTime);
        
        return queryTradesByFund(fundId)
            .thenApply(trades -> trades.stream()
                .filter(trade -> {
                    // Trade date is on the trading day
                    LocalDate tradeDate = LocalDate.ofInstant(
                        trade.getValidTime(), ZoneOffset.UTC);
                    
                    // Confirmation arrived after cutoff
                    return tradeDate.equals(tradingDay) && 
                           trade.getTransactionTime().isAfter(cutoffDateTime);
                })
                .collect(Collectors.toList())
            );
    }
    
    /**
     * Find a trade by its ID.
     *
     * <p>Note: This queries all TRADE aggregates and filters by tradeId in the payload.
     * This is inefficient but necessary since we don't know which fund the trade belongs to.
     * In production, you might want to pass fundId as a parameter to make this more efficient.
     *
     * @param tradeId trade identifier
     * @return future containing the trade event, or null if not found
     */
    private CompletableFuture<BiTemporalEvent<TradeEvent>> findTradeById(String tradeId) {
        // Query only TradeExecuted events and filter by tradeId in payload
        // This is inefficient but works across all funds
        return tradeEventStore.query(EventQuery.forEventType("TradeExecuted"))
            .thenApply(trades -> trades.stream()
                .filter(trade -> tradeId.equals(trade.getPayload().tradeId()))
                .findFirst()
                .orElse(null)
            );
    }
}

