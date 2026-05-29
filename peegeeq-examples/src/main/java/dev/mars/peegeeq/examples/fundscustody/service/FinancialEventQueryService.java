package dev.mars.peegeeq.examples.fundscustody.service;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.TemporalRange;
import io.vertx.core.Future;

import java.time.Instant;
import java.util.List;

/**
 * Example demonstrating how to query a bi-temporal event store to answer the distinct
 * business questions that arise in financial operations and regulatory reporting.
 *
 * <p><b>Problem 1  Regulatory reconstruction:</b> A regulator asks "Reconstruct your
 * end-of-day positions as they were calculated yesterday." The system must reproduce the
 * read-model exactly as it stood at that timestamp, not the current corrected view.
 * Solved by filtering on Transaction Time (the system recording horizon).
 * See {@link #getEventsAsOfSystemTime}.
 *
 * <p><b>Problem 2  Business period analysis:</b> An operations team asks "Show all cash
 * movements that were effective during January." This filters on the business date the
 * event actually occurred, regardless of when it was recorded or later corrected.
 * Solved by filtering on Valid Time (the business effective date).
 * See {@link #getEventsValidDuring}.
 *
 * <p><b>Problem 3  Correction audit trail:</b> A compliance officer asks "Show every
 * version of this trade, including the original erroneous record." By default the store
 * returns only the current effective view; this query opts in to also return superseded events.
 * See {@link #getAuditTrail}.
 */
public class FinancialEventQueryService {
    
    private final EventStore<Object> eventStore;

    public FinancialEventQueryService(EventStore<Object> eventStore) {
        this.eventStore = eventStore;
    }

    /**
     * Query events as they were known at a specific point in time in the past.
     * "What did we believe on a specific past date?"
     */
    public Future<List<BiTemporalEvent<Object>>> getEventsAsOfSystemTime(String aggregateId, Instant systemTime) {
        EventQuery query = EventQuery.builder()
            .aggregateId(aggregateId)
            // Filters based on Transaction Time (System Time)
            .transactionTimeRange(TemporalRange.until(systemTime))
            .build();
            
        return eventStore.query(query);
    }

    /**
     * Query events that were valid during a specific business time period.
     * "What was effective between January 1st and January 31st?"
     */
    public Future<List<BiTemporalEvent<Object>>> getEventsValidDuring(String aggregateId, Instant validFrom, Instant validTo) {
        EventQuery query = EventQuery.builder()
            .aggregateId(aggregateId)
            // Filters based on Valid Time (Business Time)
            .validTimeRange(TemporalRange.between(validFrom, validTo))
            .build();
            
        return eventStore.query(query);
    }

    /**
     * Query for audit trail - show all corrections and their history.
     */
    public Future<List<BiTemporalEvent<Object>>> getAuditTrail(String aggregateId) {
        EventQuery query = EventQuery.builder()
            .aggregateId(aggregateId)
            // Instructs the store to return replaced/corrected events
            .includeCorrections(true)
            .build();
            
        return eventStore.query(query);
    }
}
