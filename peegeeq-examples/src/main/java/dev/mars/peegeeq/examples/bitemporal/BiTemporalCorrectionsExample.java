package dev.mars.peegeeq.examples.bitemporal;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.examples.fundscustody.events.TradeEvent;
import io.vertx.core.Future;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Demonstrates recording a backdated correction in a bi-temporal system.
 */
public class BiTemporalCorrectionsExample {
    
    private final EventStore<TradeEvent> tradeStore;

    public BiTemporalCorrectionsExample(EventStore<TradeEvent> tradeStore) {
        this.tradeStore = tradeStore;
    }

    /**
     * Executes the correction scenario.
     * 1. A trade is recorded normally on Day 1.
     * 2. On Day 3, we realize the trade from Day 1 had the wrong quantity.
     * 3. We issue a correction. The new event has a Valid Time of Day 1, but a Transaction Time of Day 3.
     */
    public Future<Void> demonstrateCorrection(String tradeId, TradeEvent incorrectTrade, TradeEvent correctedTrade) {
        
        Instant day1 = Instant.now().minus(3, ChronoUnit.DAYS);
        
        // 1. Record the original (incorrect) trade. Valid Time = Day 1, Transaction Time = Day 1
        return tradeStore.append(
            "TradeExecuted",
            incorrectTrade,
            day1, // Valid Time
            Map.of("tradeId", tradeId),
            null,
            null,
            "TRADE:" + tradeId
        ).compose(originalEvent -> {
            System.out.println("Recorded original trade on Day 1.");
            
            // 2. We discover the error today. Issue a correction.
            // The correction is "effective" on Day 1, but recorded "now".
            return tradeStore.append(
                "TradeCorrected",
                correctedTrade,
                day1, // Backdated Valid Time to match original trade
                Map.of("tradeId", tradeId, "correctionReason", "Incorrect quantity"),
                null,
                originalEvent.getEventId(), // causation ID links to the event being corrected
                "TRADE:" + tradeId
            );
        }).compose(correctionEvent -> {
            System.out.println("Recorded backdated correction today.");
            
            // 3. Query the current view of the world (includes the correction)
            EventQuery currentQuery = EventQuery.forAggregate("TRADE:" + tradeId);
            
            return tradeStore.query(currentQuery);
        }).compose(currentEvents -> {
            System.out.println("Current View contains " + currentEvents.size() + " effective events.");
            
            // 4. Query the historical "As-At" view (what did we believe on Day 2?)
            Instant day2 = Instant.now().minus(2, ChronoUnit.DAYS);
            EventQuery asAtQuery = EventQuery.builder()
                .aggregateId("TRADE:" + tradeId)
                .transactionTimeRange(dev.mars.peegeeq.api.TemporalRange.until(day2)) // Transaction time filter
                .build();
                
            return tradeStore.query(asAtQuery);
        }).map(asAtEvents -> {
            System.out.println("As-At Day 2 View contains " + asAtEvents.size() + " events (pre-correction).");
            return null;
        });
    }
}
