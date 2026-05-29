package dev.mars.peegeeq.examples.bitemporal;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.examples.fundscustody.events.TradeEvent;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import dev.mars.peegeeq.api.TemporalRange;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Example demonstrating how to record a backdated correction in a bi-temporal event store.
 *
 * <p>In financial operations, errors are discovered after the fact. A trade booked on Monday
 * may turn out to have the wrong quantity when reconciled on Wednesday. The naive approach 
 * overwriting the original record  destroys the audit trail. Regulators and internal
 * compliance teams need to answer both "What is the correct position?" and
 * "What did the system believe on Tuesday morning, before the correction was made?"
 *
 * <p>A bi-temporal store preserves both answers by tracking two independent time axes:
 * <ul>
 *   <li><b>Valid Time</b>: when the event was effective in the business (the trade date)</li>
 *   <li><b>Transaction Time</b>: when the system first recorded or corrected it</li>
 * </ul>
 *
 * <p>This example records an original trade, issues a backdated correction on a later date,
 * then demonstrates querying the current corrected view versus the historical view
 * that existed in the system before the correction was known.
 */
public class BiTemporalCorrectionsExample {
    private static final Logger logger = LoggerFactory.getLogger(BiTemporalCorrectionsExample.class);

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
            logger.info("Recorded original trade on Day 1.");
            
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
            logger.info("Recorded backdated correction today.");
            
            // 3. Query the current view of the world (includes the correction)
            EventQuery currentQuery = EventQuery.forAggregate("TRADE:" + tradeId);
            
            return tradeStore.query(currentQuery);
        }).compose(currentEvents -> {
            logger.info("Current View contains {} effective events.", currentEvents.size());
            
            // 4. Query the historical "As-At" view (what did we believe on Day 2?)
            Instant day2 = Instant.now().minus(2, ChronoUnit.DAYS);
            EventQuery asAtQuery = EventQuery.builder()
                .aggregateId("TRADE:" + tradeId)
                .transactionTimeRange(TemporalRange.until(day2)) // Transaction time filter
                .build();
                
            return tradeStore.query(asAtQuery);
        }).map(asAtEvents -> {
            logger.info("As-At Day 2 View contains {} events (pre-correction).", asAtEvents.size());
            return null;
        });
    }
}
