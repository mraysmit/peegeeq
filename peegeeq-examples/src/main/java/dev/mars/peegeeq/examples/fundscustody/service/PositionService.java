package dev.mars.peegeeq.examples.fundscustody.service;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.examples.fundscustody.domain.Currency;
import dev.mars.peegeeq.examples.fundscustody.domain.Position;
import dev.mars.peegeeq.examples.fundscustody.events.TradeEvent;
import dev.mars.peegeeq.examples.fundscustody.model.PositionSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Service for calculating positions from trade events.
 * 
 * <p>Positions are calculated by:
 * <ul>
 *   <li>Querying trade events up to a specific date (valid time)</li>
 *   <li>Summing signed quantities (BUY = positive, SELL = negative)</li>
 *   <li>Calculating weighted average price</li>
 * </ul>
 * 
 * <p>All methods return CompletableFuture to maintain async/non-blocking behavior.
 * No Spring dependencies - plain Java with constructor injection.
 */
public class PositionService {
    private static final Logger logger = LoggerFactory.getLogger(PositionService.class);
    
    private final EventStore<TradeEvent> tradeEventStore;
    
    /**
     * Create a PositionService with the given event store.
     * 
     * @param tradeEventStore event store for trade events
     */
    public PositionService(EventStore<TradeEvent> tradeEventStore) {
        this.tradeEventStore = tradeEventStore;
    }
    
    /**
     * Get position for a specific security as of a date.
     * 
     * <p>Calculates position from all trades with valid time <= asOfDate.
     * 
     * @param fundId fund identifier
     * @param securityId security identifier
     * @param asOfDate date for position calculation
     * @return future containing the position
     */
    public CompletableFuture<Position> getPositionAsOf(
            String fundId, String securityId, LocalDate asOfDate) {
        
        Instant asOfInstant = asOfDate.atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
        
        logger.debug("Calculating position for fund {} security {} as of {}", 
            fundId, securityId, asOfDate);
        
        return tradeEventStore.query(
            EventQuery.builder()
                .aggregateId("TRADE:" + fundId)
                .validTimeRange(dev.mars.peegeeq.api.TemporalRange.until(asOfInstant))
                .build()
        ).thenApply(trades -> {
            List<TradeEvent> securityTrades = trades.stream()
                .map(BiTemporalEvent::getPayload)
                .filter(trade -> securityId.equals(trade.securityId()))
                .collect(Collectors.toList());
            
            return calculatePosition(fundId, securityId, securityTrades, asOfDate);
        });
    }
    
    /**
     * Get all positions for a fund as of a date.
     * 
     * @param fundId fund identifier
     * @param asOfDate date for position calculation
     * @return future containing list of positions
     */
    public CompletableFuture<List<Position>> getPositionsByFund(
            String fundId, LocalDate asOfDate) {
        
        Instant asOfInstant = asOfDate.atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
        
        logger.debug("Calculating all positions for fund {} as of {}", fundId, asOfDate);
        
        return tradeEventStore.query(
            EventQuery.builder()
                .aggregateId("TRADE:" + fundId)
                .validTimeRange(dev.mars.peegeeq.api.TemporalRange.until(asOfInstant))
                .build()
        ).thenApply(trades -> {
            // Group trades by security
            Map<String, List<TradeEvent>> tradesByecurity = trades.stream()
                .map(BiTemporalEvent::getPayload)
                .collect(Collectors.groupingBy(TradeEvent::securityId));
            
            // Calculate position for each security
            return tradesByecurity.entrySet().stream()
                .map(entry -> calculatePosition(
                    fundId, 
                    entry.getKey(), 
                    entry.getValue(), 
                    asOfDate))
                .filter(position -> !position.isFlat())  // Exclude zero positions
                .collect(Collectors.toList());
        });
    }
    
    /**
     * Get position history for a security over a date range.
     * 
     * <p>Returns daily position snapshots from startDate to endDate (inclusive).
     * 
     * @param fundId fund identifier
     * @param securityId security identifier
     * @param startDate start of date range
     * @param endDate end of date range
     * @return future containing list of position snapshots
     */
    public CompletableFuture<List<PositionSnapshot>> getPositionHistory(
            String fundId, String securityId, LocalDate startDate, LocalDate endDate) {

        logger.debug("Calculating position history for fund {} security {} from {} to {}",
            fundId, securityId, startDate, endDate);

        List<CompletableFuture<PositionSnapshot>> futures = new ArrayList<>();
        LocalDate currentDate = startDate;

        while (!currentDate.isAfter(endDate)) {
            LocalDate date = currentDate;  // Effectively final for lambda
            CompletableFuture<PositionSnapshot> future = getPositionAsOf(fundId, securityId, date)
                .thenApply(position -> PositionSnapshot.from(position, Instant.now()));
            futures.add(future);
            currentDate = currentDate.plusDays(1);
        }

        // Combine all futures into a single future of list
        return futures.stream()
            .reduce(
                CompletableFuture.completedFuture(new ArrayList<PositionSnapshot>()),
                (acc, future) -> acc.thenCombine(future, (list, snapshot) -> {
                    list.add(snapshot);
                    return list;
                }),
                (f1, f2) -> f1.thenCombine(f2, (list1, list2) -> {
                    list1.addAll(list2);
                    return list1;
                })
            );
    }
    
    /**
     * Calculate position from a list of trades.
     * 
     * <p>Algorithm:
     * <ul>
     *   <li>Sum signed quantities (BUY = +, SELL = -)</li>
     *   <li>Calculate weighted average price</li>
     *   <li>Use currency from first trade (assumes all trades in same currency)</li>
     * </ul>
     * 
     * @param fundId fund identifier
     * @param securityId security identifier
     * @param trades list of trade events
     * @param asOfDate date for the position
     * @return calculated position
     */
    private Position calculatePosition(
            String fundId, 
            String securityId, 
            List<TradeEvent> trades, 
            LocalDate asOfDate) {
        
        if (trades.isEmpty()) {
            // No trades - return flat position with default currency
            return new Position(fundId, securityId, BigDecimal.ZERO, null, Currency.USD, asOfDate);
        }
        
        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        Currency currency = Currency.valueOf(trades.get(0).currency());
        
        for (TradeEvent trade : trades) {
            BigDecimal signedQty = trade.signedQuantity();
            BigDecimal tradeCost = signedQty.multiply(trade.price());
            
            totalQuantity = totalQuantity.add(signedQty);
            totalCost = totalCost.add(tradeCost);
        }
        
        // Calculate average price (null if position is flat)
        BigDecimal averagePrice = null;
        if (totalQuantity.compareTo(BigDecimal.ZERO) != 0) {
            averagePrice = totalCost.divide(totalQuantity, 6, RoundingMode.HALF_UP);
        }
        
        return new Position(
            fundId,
            securityId,
            totalQuantity,
            averagePrice,
            currency,
            asOfDate
        );
    }
}

