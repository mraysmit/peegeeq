package dev.mars.peegeeq.examples.springbootfinancialfabric.query;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.TemporalRange;
import dev.mars.peegeeq.bitemporal.PgBiTemporalEventStore;
import dev.mars.peegeeq.examples.springbootfinancialfabric.events.PositionUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Query service for position reconciliation.
 * 
 * Provides bi-temporal queries for:
 * - Position reconstruction from events
 * - Point-in-time position snapshots
 * - Position reconciliation with external sources
 * - Position break analysis
 */
@Service
public class PositionReconService {
    
    private static final Logger log = LoggerFactory.getLogger(PositionReconService.class);
    
    private final PgBiTemporalEventStore<PositionUpdateEvent> positionEventStore;
    
    public PositionReconService(
            @Qualifier("positionEventStore") PgBiTemporalEventStore<PositionUpdateEvent> positionEventStore) {
        this.positionEventStore = positionEventStore;
    }
    
    /**
     * Reconstruct current position for an account by replaying all position update events.
     */
    public CompletableFuture<PositionSnapshot> getCurrentPosition(String account) {
        log.info("Reconstructing current position for account: {}", account);
        
        return positionEventStore.query(
            EventQuery.builder()
                .eventType("position.update.completed")
                .sortOrder(EventQuery.SortOrder.VALID_TIME_ASC)
                .build()
        ).thenApply(events -> {
            // Filter events for this account
            List<BiTemporalEvent<PositionUpdateEvent>> accountEvents = events.stream()
                .filter(event -> account.equals(event.getPayload().getAccount()))
                .toList();
            
            return reconstructPosition(account, accountEvents, Instant.now());
        });
    }
    
    /**
     * Reconstruct position as it was at a specific point in time (transaction time).
     * This shows what the system knew about the position at that time.
     */
    public CompletableFuture<PositionSnapshot> getPositionAsOfTime(
            String account, Instant asOfTime) {
        log.info("Reconstructing position for account {} as of {}", account, asOfTime);
        
        return positionEventStore.query(
            EventQuery.builder()
                .eventType("position.update.completed")
                .transactionTimeRange(TemporalRange.until(asOfTime))
                .sortOrder(EventQuery.SortOrder.TRANSACTION_TIME_ASC)
                .build()
        ).thenApply(events -> {
            // Filter events for this account
            List<BiTemporalEvent<PositionUpdateEvent>> accountEvents = events.stream()
                .filter(event -> account.equals(event.getPayload().getAccount()))
                .toList();
            
            return reconstructPosition(account, accountEvents, asOfTime);
        });
    }
    
    /**
     * Reconstruct position for a specific business date (valid time).
     * This shows the actual position on that date.
     */
    public CompletableFuture<PositionSnapshot> getPositionForBusinessDate(
            String account, Instant businessDate) {
        log.info("Reconstructing position for account {} on business date {}", 
                account, businessDate);
        
        Instant endOfDay = businessDate.plus(1, ChronoUnit.DAYS);
        
        return positionEventStore.query(
            EventQuery.builder()
                .eventType("position.update.completed")
                .validTimeRange(TemporalRange.until(endOfDay))
                .sortOrder(EventQuery.SortOrder.VALID_TIME_ASC)
                .build()
        ).thenApply(events -> {
            // Filter events for this account
            List<BiTemporalEvent<PositionUpdateEvent>> accountEvents = events.stream()
                .filter(event -> account.equals(event.getPayload().getAccount()))
                .toList();
            
            return reconstructPosition(account, accountEvents, businessDate);
        });
    }
    
    /**
     * Reconcile position with external source.
     * Compares reconstructed position with external position and identifies breaks.
     */
    public CompletableFuture<ReconciliationResult> reconcilePosition(
            String account, Map<String, BigDecimal> externalPositions) {
        log.info("Reconciling position for account {} with external source", account);
        
        return getCurrentPosition(account).thenApply(snapshot -> {
            ReconciliationResult result = new ReconciliationResult();
            result.account = account;
            result.reconciliationTime = Instant.now();
            result.internalPositions = snapshot.positions;
            result.externalPositions = externalPositions;
            
            // Find breaks
            Set<String> allInstruments = new HashSet<>();
            allInstruments.addAll(snapshot.positions.keySet());
            allInstruments.addAll(externalPositions.keySet());
            
            for (String instrument : allInstruments) {
                BigDecimal internalQty = snapshot.positions.getOrDefault(instrument, BigDecimal.ZERO);
                BigDecimal externalQty = externalPositions.getOrDefault(instrument, BigDecimal.ZERO);
                BigDecimal difference = internalQty.subtract(externalQty);
                
                if (difference.compareTo(BigDecimal.ZERO) != 0) {
                    result.breaks.put(instrument, new PositionBreak(
                        instrument,
                        internalQty,
                        externalQty,
                        difference
                    ));
                }
            }
            
            result.hasBreaks = !result.breaks.isEmpty();
            
            if (result.hasBreaks) {
                log.warn("Position breaks found for account {}: {} breaks", 
                        account, result.breaks.size());
            } else {
                log.info("Position reconciliation successful for account {}: no breaks", account);
            }
            
            return result;
        });
    }
    
    /**
     * Get position movements for a specific instrument within a time range.
     */
    public CompletableFuture<List<BiTemporalEvent<PositionUpdateEvent>>> getPositionMovements(
            String account, String instrument, Instant startTime, Instant endTime) {
        log.info("Querying position movements for account {} instrument {} between {} and {}", 
                account, instrument, startTime, endTime);
        
        return positionEventStore.query(
            EventQuery.builder()
                .eventType("position.update.completed")
                .validTimeRange(new TemporalRange(startTime, endTime))
                .sortOrder(EventQuery.SortOrder.VALID_TIME_ASC)
                .build()
        ).thenApply(events -> 
            events.stream()
                .filter(event -> account.equals(event.getPayload().getAccount()))
                .filter(event -> instrument.equals(event.getPayload().getInstrument()))
                .toList()
        );
    }
    
    /**
     * Get all position updates for today.
     */
    public CompletableFuture<List<BiTemporalEvent<PositionUpdateEvent>>> getTodaysPositionUpdates(
            String account) {
        Instant startOfDay = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Instant endOfDay = startOfDay.plus(1, ChronoUnit.DAYS);
        
        log.info("Querying today's position updates for account: {}", account);
        
        return positionEventStore.query(
            EventQuery.builder()
                .eventType("position.update.completed")
                .validTimeRange(new TemporalRange(startOfDay, endOfDay))
                .sortOrder(EventQuery.SortOrder.VALID_TIME_ASC)
                .build()
        ).thenApply(events -> 
            events.stream()
                .filter(event -> account.equals(event.getPayload().getAccount()))
                .toList()
        );
    }
    
    /**
     * Reconstruct position from events.
     */
    private PositionSnapshot reconstructPosition(
            String account, 
            List<BiTemporalEvent<PositionUpdateEvent>> events,
            Instant asOfTime) {
        
        PositionSnapshot snapshot = new PositionSnapshot();
        snapshot.account = account;
        snapshot.asOfTime = asOfTime;
        snapshot.eventCount = events.size();
        
        // Replay events to reconstruct position
        for (BiTemporalEvent<PositionUpdateEvent> event : events) {
            PositionUpdateEvent update = event.getPayload();
            String instrument = update.getInstrument();
            BigDecimal quantityChange = update.getQuantityChange();
            
            BigDecimal currentPosition = snapshot.positions.getOrDefault(instrument, BigDecimal.ZERO);
            BigDecimal newPosition = currentPosition.add(quantityChange);
            snapshot.positions.put(instrument, newPosition);
        }
        
        log.debug("Reconstructed position for account {}: {} instruments, {} events", 
                account, snapshot.positions.size(), events.size());
        
        return snapshot;
    }
    
    /**
     * Position snapshot at a specific point in time.
     */
    public static class PositionSnapshot {
        public String account;
        public Instant asOfTime;
        public Map<String, BigDecimal> positions = new HashMap<>();
        public int eventCount;
        
        public BigDecimal getPosition(String instrument) {
            return positions.getOrDefault(instrument, BigDecimal.ZERO);
        }
        
        public int getInstrumentCount() {
            return positions.size();
        }
    }
    
    /**
     * Reconciliation result comparing internal vs external positions.
     */
    public static class ReconciliationResult {
        public String account;
        public Instant reconciliationTime;
        public Map<String, BigDecimal> internalPositions;
        public Map<String, BigDecimal> externalPositions;
        public Map<String, PositionBreak> breaks = new HashMap<>();
        public boolean hasBreaks;
        
        public int getBreakCount() {
            return breaks.size();
        }
    }
    
    /**
     * Position break (difference between internal and external).
     */
    public static class PositionBreak {
        public final String instrument;
        public final BigDecimal internalQuantity;
        public final BigDecimal externalQuantity;
        public final BigDecimal difference;
        
        public PositionBreak(String instrument, BigDecimal internalQuantity, 
                           BigDecimal externalQuantity, BigDecimal difference) {
            this.instrument = instrument;
            this.internalQuantity = internalQuantity;
            this.externalQuantity = externalQuantity;
            this.difference = difference;
        }
    }
}

