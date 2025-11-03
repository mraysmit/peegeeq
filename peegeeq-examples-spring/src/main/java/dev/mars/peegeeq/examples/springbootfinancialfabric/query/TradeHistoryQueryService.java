package dev.mars.peegeeq.examples.springbootfinancialfabric.query;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.TemporalRange;
import dev.mars.peegeeq.bitemporal.PgBiTemporalEventStore;
import dev.mars.peegeeq.examples.springbootfinancialfabric.events.TradeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Query service for trade history and audit trails.
 * 
 * Provides bi-temporal queries for:
 * - Complete trade audit trails
 * - Point-in-time trade reconstruction
 * - Trade corrections and amendments
 * - Regulatory audit queries
 */
@Service
public class TradeHistoryQueryService {
    
    private static final Logger log = LoggerFactory.getLogger(TradeHistoryQueryService.class);
    
    private final PgBiTemporalEventStore<TradeEvent> tradingEventStore;
    
    public TradeHistoryQueryService(
            @Qualifier("tradingEventStore") PgBiTemporalEventStore<TradeEvent> tradingEventStore) {
        this.tradingEventStore = tradingEventStore;
    }
    
    /**
     * Get complete audit trail for a specific trade.
     * Returns all events (including corrections) in chronological order.
     */
    public CompletableFuture<TradeAuditTrail> getTradeAuditTrail(String tradeId) {
        log.info("Querying audit trail for trade: {}", tradeId);
        
        return tradingEventStore.query(
            EventQuery.builder()
                .aggregateId(tradeId)
                .includeCorrections(true)
                .sortOrder(EventQuery.SortOrder.TRANSACTION_TIME_ASC)
                .build()
        ).thenApply(events -> {
            log.debug("Found {} events for trade: {}", events.size(), tradeId);
            return new TradeAuditTrail(tradeId, events);
        });
    }
    
    /**
     * Get trade state as it was known at a specific point in time.
     * Uses transaction time (system time) for point-in-time reconstruction.
     */
    public CompletableFuture<BiTemporalEvent<TradeEvent>> getTradeAsOfTime(
            String tradeId, Instant asOfTime) {
        log.info("Querying trade {} as of {}", tradeId, asOfTime);
        
        return tradingEventStore.query(
            EventQuery.builder()
                .aggregateId(tradeId)
                .transactionTimeRange(TemporalRange.until(asOfTime))
                .sortOrder(EventQuery.SortOrder.TRANSACTION_TIME_DESC)
                .limit(1)
                .build()
        ).thenApply(events -> {
            if (events.isEmpty()) {
                log.warn("No trade found for {} as of {}", tradeId, asOfTime);
                return null;
            }
            return events.get(0);
        });
    }
    
    /**
     * Get all trades executed within a specific time range (valid time).
     * Used for daily/monthly reporting.
     */
    public CompletableFuture<List<BiTemporalEvent<TradeEvent>>> getTradesByExecutionTime(
            Instant startTime, Instant endTime) {
        log.info("Querying trades executed between {} and {}", startTime, endTime);
        
        return tradingEventStore.query(
            EventQuery.builder()
                .eventType("trading.equities.capture.completed")
                .validTimeRange(new TemporalRange(startTime, endTime))
                .sortOrder(EventQuery.SortOrder.VALID_TIME_ASC)
                .build()
        );
    }
    
    /**
     * Get all trades for a specific counterparty.
     */
    public CompletableFuture<List<BiTemporalEvent<TradeEvent>>> getTradesByCounterparty(
            String counterparty) {
        log.info("Querying trades for counterparty: {}", counterparty);
        
        return tradingEventStore.query(
            EventQuery.builder()
                .eventType("trading.equities.capture.completed")
                .sortOrder(EventQuery.SortOrder.VALID_TIME_DESC)
                .limit(1000)
                .build()
        ).thenApply(events -> 
            events.stream()
                .filter(event -> counterparty.equals(event.getPayload().getCounterparty()))
                .collect(Collectors.toList())
        );
    }
    
    /**
     * Get all trade corrections for a specific trade.
     * Shows the complete amendment history.
     */
    public CompletableFuture<List<BiTemporalEvent<TradeEvent>>> getTradeCorrections(
            String tradeId) {
        log.info("Querying corrections for trade: {}", tradeId);
        
        return tradingEventStore.query(
            EventQuery.builder()
                .aggregateId(tradeId)
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
     * Get all confirmed trades within a time range.
     * Used for settlement processing.
     */
    public CompletableFuture<List<BiTemporalEvent<TradeEvent>>> getConfirmedTrades(
            Instant startTime, Instant endTime) {
        log.info("Querying confirmed trades between {} and {}", startTime, endTime);
        
        return tradingEventStore.query(
            EventQuery.builder()
                .eventType("trading.equities.confirmation.matched")
                .validTimeRange(new TemporalRange(startTime, endTime))
                .sortOrder(EventQuery.SortOrder.VALID_TIME_ASC)
                .build()
        );
    }
    
    /**
     * Get trades by instrument within a time range.
     */
    public CompletableFuture<List<BiTemporalEvent<TradeEvent>>> getTradesByInstrument(
            String instrument, Instant startTime, Instant endTime) {
        log.info("Querying trades for instrument {} between {} and {}", 
                instrument, startTime, endTime);
        
        return tradingEventStore.query(
            EventQuery.builder()
                .eventType("trading.equities.capture.completed")
                .validTimeRange(new TemporalRange(startTime, endTime))
                .sortOrder(EventQuery.SortOrder.VALID_TIME_ASC)
                .build()
        ).thenApply(events -> 
            events.stream()
                .filter(event -> instrument.equals(event.getPayload().getInstrument()))
                .collect(Collectors.toList())
        );
    }
    
    /**
     * Get today's trades (valid time = today).
     */
    public CompletableFuture<List<BiTemporalEvent<TradeEvent>>> getTodaysTrades() {
        Instant startOfDay = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant endOfDay = startOfDay.plus(1, ChronoUnit.DAYS);
        
        log.info("Querying today's trades");
        
        return getTradesByExecutionTime(startOfDay, endOfDay);
    }
    
    /**
     * Get trade statistics for a time period.
     */
    public CompletableFuture<TradeStatistics> getTradeStatistics(
            Instant startTime, Instant endTime) {
        log.info("Calculating trade statistics between {} and {}", startTime, endTime);
        
        return getTradesByExecutionTime(startTime, endTime)
            .thenApply(events -> {
                TradeStatistics stats = new TradeStatistics();
                stats.totalTrades = events.size();
                stats.startTime = startTime;
                stats.endTime = endTime;
                
                for (BiTemporalEvent<TradeEvent> event : events) {
                    TradeEvent trade = event.getPayload();
                    
                    // Count by trade type
                    if ("BUY".equals(trade.getTradeType())) {
                        stats.buyTrades++;
                    } else if ("SELL".equals(trade.getTradeType())) {
                        stats.sellTrades++;
                    }
                    
                    // Track unique instruments
                    stats.instruments.add(trade.getInstrument());
                    
                    // Track unique counterparties
                    stats.counterparties.add(trade.getCounterparty());
                }
                
                return stats;
            });
    }
    
    /**
     * Trade audit trail containing all events for a trade.
     */
    public static class TradeAuditTrail {
        public final String tradeId;
        public final List<BiTemporalEvent<TradeEvent>> events;
        public final int totalEvents;
        public final int corrections;
        
        public TradeAuditTrail(String tradeId, List<BiTemporalEvent<TradeEvent>> events) {
            this.tradeId = tradeId;
            this.events = events;
            this.totalEvents = events.size();
            this.corrections = (int) events.stream()
                .filter(BiTemporalEvent::isCorrection)
                .count();
        }
        
        public BiTemporalEvent<TradeEvent> getLatestEvent() {
            return events.stream()
                .max(Comparator.comparing(BiTemporalEvent::getTransactionTime))
                .orElse(null);
        }
        
        public BiTemporalEvent<TradeEvent> getOriginalEvent() {
            return events.stream()
                .min(Comparator.comparing(BiTemporalEvent::getTransactionTime))
                .orElse(null);
        }
    }
    
    /**
     * Trade statistics for a time period.
     */
    public static class TradeStatistics {
        public int totalTrades;
        public int buyTrades;
        public int sellTrades;
        public Instant startTime;
        public Instant endTime;
        public List<String> instruments = new ArrayList<>();
        public List<String> counterparties = new ArrayList<>();
        
        public int getUniqueInstruments() {
            return instruments.size();
        }
        
        public int getUniqueCounterparties() {
            return counterparties.size();
        }
    }
}

