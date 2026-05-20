package dev.mars.peegeeq.examples.fundscustody.service;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.TemporalRange;
import io.vertx.core.Future;

import java.time.Instant;
import java.util.List;

/**
 * Service demonstrating advanced Bi-temporal queries.
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
