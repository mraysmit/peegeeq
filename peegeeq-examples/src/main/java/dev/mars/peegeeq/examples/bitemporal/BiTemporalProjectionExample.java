package dev.mars.peegeeq.examples.bitemporal;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.TemporalRange;
import dev.mars.peegeeq.examples.fundscustody.events.CashMovementEvent;
import io.vertx.core.Future;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Demonstrates how to build CQRS read-model projections in a bi-temporal system.
 * It explicitly handles the distinction between:
 * 1. Current Business View ("What do we believe is true now?")
 * 2. As-At Audit View ("What did we believe on a specific past date?")
 */
public class BiTemporalProjectionExample {

    private final EventStore<CashMovementEvent> eventStore;

    public BiTemporalProjectionExample(EventStore<CashMovementEvent> eventStore) {
        this.eventStore = eventStore;
    }

    /**
     * Rebuilds the "Current Business View" projection for an account.
     * This view uses all events known to the system right now, applied in Business Time (Valid Time) order.
     * Corrections and backdated events are automatically folded into their proper timeline position.
     */
    public Future<BigDecimal> rebuildCurrentBusinessView(String accountId) {
        // Query all effective events for the account
        EventQuery query = EventQuery.forAggregate("ACCOUNT:" + accountId);

        return eventStore.query(query).map(events -> {
            // Sort by Valid Time to process them in the order they occurred in the real world
            events.sort((e1, e2) -> e1.getValidTime().compareTo(e2.getValidTime()));
            
            return projectBalance(accountId, events);
        });
    }

    /**
     * Rebuilds the "As-At Audit View" projection.
     * This view reconstructs exactly what the projection looked like on a past date.
     * 
     * @param accountId The account to project
     * @param asAtTime The transaction time (system time) horizon
     */
    public Future<BigDecimal> rebuildAsAtAuditView(String accountId, Instant asAtTime) {
        // Query events, but filter out anything the system learned *after* the asAtTime
        EventQuery query = EventQuery.builder()
            .aggregateId("ACCOUNT:" + accountId)
            .transactionTimeRange(TemporalRange.until(asAtTime))
            .build();

        return eventStore.query(query).map(events -> {
            // Sort by Valid Time, just like the current business view
            events.sort((e1, e2) -> e1.getValidTime().compareTo(e2.getValidTime()));
            
            return projectBalance(accountId, events);
        });
    }

    /**
     * Pure function that folds a stream of cash movement events into a final balance state.
     * This is the core logic of the projection.
     */
    private BigDecimal projectBalance(String targetAccountId, List<BiTemporalEvent<CashMovementEvent>> events) {
        BigDecimal balance = BigDecimal.ZERO;
        
        for (BiTemporalEvent<CashMovementEvent> event : events) {
            CashMovementEvent payload = event.getPayload();
            
            if (targetAccountId.equals(payload.fromAccount())) {
                balance = balance.subtract(payload.amount());
            } else if (targetAccountId.equals(payload.toAccount())) {
                balance = balance.add(payload.amount());
            }
        }
        
        return balance;
    }
}
