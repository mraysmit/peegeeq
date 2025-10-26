package dev.mars.peegeeq.examples.springbootfinancialfabric.handlers;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.bitemporal.PgBiTemporalEventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralized exception event handler for cross-domain failure handling.
 * 
 * Uses wildcard pattern matching to handle failures across all domains:
 * - trading.*.*.failed
 * - instruction.settlement.failed
 * - cash.*.failed
 * - position.*.failed
 * - regulatory.*.failed
 * 
 * This handler demonstrates cross-cutting concern handling using event patterns.
 */
@Component
public class ExceptionEventHandler {
    
    private static final Logger log = LoggerFactory.getLogger(ExceptionEventHandler.class);
    
    // Subscribe to all event stores to catch failures
    private final PgBiTemporalEventStore<?> tradingEventStore;
    private final PgBiTemporalEventStore<?> settlementEventStore;
    private final PgBiTemporalEventStore<?> cashEventStore;
    private final PgBiTemporalEventStore<?> positionEventStore;
    private final PgBiTemporalEventStore<?> regulatoryEventStore;
    
    private final AtomicLong totalExceptionsHandled = new AtomicLong(0);
    private final Map<String, AtomicLong> exceptionsByDomain = new ConcurrentHashMap<>();
    private final Map<String, ExceptionRecord> recentExceptions = new ConcurrentHashMap<>();
    
    public ExceptionEventHandler(
            @Qualifier("tradingEventStore") PgBiTemporalEventStore<?> tradingEventStore,
            @Qualifier("settlementEventStore") PgBiTemporalEventStore<?> settlementEventStore,
            @Qualifier("cashEventStore") PgBiTemporalEventStore<?> cashEventStore,
            @Qualifier("positionEventStore") PgBiTemporalEventStore<?> positionEventStore,
            @Qualifier("regulatoryEventStore") PgBiTemporalEventStore<?> regulatoryEventStore) {
        this.tradingEventStore = tradingEventStore;
        this.settlementEventStore = settlementEventStore;
        this.cashEventStore = cashEventStore;
        this.positionEventStore = positionEventStore;
        this.regulatoryEventStore = regulatoryEventStore;
        
        // Initialize domain counters
        exceptionsByDomain.put("trading", new AtomicLong(0));
        exceptionsByDomain.put("settlement", new AtomicLong(0));
        exceptionsByDomain.put("cash", new AtomicLong(0));
        exceptionsByDomain.put("position", new AtomicLong(0));
        exceptionsByDomain.put("regulatory", new AtomicLong(0));
    }
    
    @PostConstruct
    public void initialize() {
        log.info("Initializing ExceptionEventHandler - subscribing to failure events across all domains");
        
        // Subscribe to all event stores to catch any failures
        // In production, you would filter by event type pattern "*.*.*.failed"
        subscribeToEventStore(tradingEventStore, "trading");
        subscribeToEventStore(settlementEventStore, "settlement");
        subscribeToEventStore(cashEventStore, "cash");
        subscribeToEventStore(positionEventStore, "position");
        subscribeToEventStore(regulatoryEventStore, "regulatory");
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ExceptionEventHandler - handled {} total exceptions", totalExceptionsHandled.get());
        log.info("Exceptions by domain: {}", exceptionsByDomain);
        
        // Unsubscribe from all event stores
        tradingEventStore.unsubscribe();
        settlementEventStore.unsubscribe();
        cashEventStore.unsubscribe();
        positionEventStore.unsubscribe();
        regulatoryEventStore.unsubscribe();
    }
    
    /**
     * Subscribe to an event store and filter for failure events.
     */
    @SuppressWarnings({})
    private void subscribeToEventStore(PgBiTemporalEventStore<?> eventStore, String domain) {
        eventStore.subscribe(null, null, (MessageHandler) new MessageHandler<BiTemporalEvent<?>>() {
            @Override
            public CompletableFuture<Void> handle(Message<BiTemporalEvent<?>> message) {
                BiTemporalEvent<?> event = message.getPayload();

                // Wildcard pattern matching: check if event type ends with ".failed"
                if (isFailureEvent(event.getEventType())) {
                    return handleFailureEvent(event, domain);
                }

                return CompletableFuture.completedFuture(null);
            }
        }).whenComplete((result, error) -> {
            if (error != null) {
                log.error("Failed to subscribe to {} events for exception handling", domain, error);
            } else {
                log.info("Successfully subscribed to {} events for exception handling", domain);
            }
        });
    }
    
    /**
     * Wildcard pattern matching: check if event type matches failure pattern.
     * Patterns:
     * - *.*.*.failed
     * - *.*.failed
     * - *.failed
     */
    private boolean isFailureEvent(String eventType) {
        return eventType != null && eventType.endsWith(".failed");
    }
    
    /**
     * Centralized failure event handler.
     * Handles failures from all domains using the same logic.
     */
    private CompletableFuture<Void> handleFailureEvent(BiTemporalEvent<?> event, String domain) {
        totalExceptionsHandled.incrementAndGet();
        exceptionsByDomain.get(domain).incrementAndGet();
        
        String eventType = event.getEventType();
        String aggregateId = event.getAggregateId();
        String correlationId = event.getCorrelationId();
        
        log.error("🚨 FAILURE DETECTED - Domain: {}, Event Type: {}, Aggregate: {}, Correlation: {}",
                domain, eventType, aggregateId, correlationId);
        
        try {
            // Create exception record
            ExceptionRecord exceptionRecord = new ExceptionRecord(
                    event.getEventId(),
                    domain,
                    eventType,
                    aggregateId,
                    correlationId,
                    Instant.now(),
                    extractErrorMessage(event)
            );
            
            recentExceptions.put(event.getEventId(), exceptionRecord);
            
            // Centralized exception handling tasks:
            // 1. Log to exception tracking system
            logToExceptionTrackingSystem(exceptionRecord);
            
            // 2. Create incident ticket
            createIncidentTicket(exceptionRecord);
            
            // 3. Send alert to operations team
            sendAlertToOperations(exceptionRecord);
            
            // 4. Check if this is a recurring failure
            checkForRecurringFailures(exceptionRecord);
            
            // 5. Trigger automated remediation if applicable
            triggerAutomatedRemediation(exceptionRecord);
            
            return CompletableFuture.completedFuture(null);
            
        } catch (Exception e) {
            log.error("Error handling failure event: {}", eventType, e);
            return CompletableFuture.failedFuture(e);
        }
    }
    
    /**
     * Extract error message from event headers.
     */
    private String extractErrorMessage(BiTemporalEvent<?> event) {
        Map<String, String> headers = event.getHeaders();
        if (headers != null) {
            return headers.getOrDefault("errorMessage", "Unknown error");
        }
        return "Unknown error";
    }
    
    // Simulated exception handling methods
    
    private void logToExceptionTrackingSystem(ExceptionRecord record) {
        log.debug("Logging exception to tracking system: {}", record.eventId);
        // In production: log to exception tracking system (e.g., Jira, ServiceNow)
    }
    
    private void createIncidentTicket(ExceptionRecord record) {
        log.debug("Creating incident ticket for exception: {}", record.eventId);
        // In production: create incident ticket in ticketing system
    }
    
    private void sendAlertToOperations(ExceptionRecord record) {
        log.warn("⚠️ ALERT: {} failure in {} domain - Aggregate: {}, Correlation: {}",
                record.eventType, record.domain, record.aggregateId, record.correlationId);
        // In production: send alert via email, Slack, PagerDuty, etc.
    }
    
    private void checkForRecurringFailures(ExceptionRecord record) {
        // Check if same aggregate has failed multiple times
        long failureCount = recentExceptions.values().stream()
                .filter(r -> r.aggregateId.equals(record.aggregateId))
                .count();
        
        if (failureCount > 3) {
            log.error("🔥 RECURRING FAILURE DETECTED: Aggregate {} has failed {} times",
                    record.aggregateId, failureCount);
            // In production: escalate to senior operations team
        }
    }
    
    private void triggerAutomatedRemediation(ExceptionRecord record) {
        log.debug("Checking for automated remediation for exception: {}", record.eventId);
        // In production: trigger automated remediation workflows
        // e.g., retry logic, circuit breaker reset, cache invalidation
    }
    
    // Metrics and monitoring
    
    public long getTotalExceptionsHandled() {
        return totalExceptionsHandled.get();
    }
    
    public Map<String, Long> getExceptionsByDomain() {
        Map<String, Long> result = new ConcurrentHashMap<>();
        exceptionsByDomain.forEach((domain, counter) -> result.put(domain, counter.get()));
        return result;
    }
    
    public Map<String, ExceptionRecord> getRecentExceptions() {
        return new ConcurrentHashMap<>(recentExceptions);
    }
    
    /**
     * Exception record for tracking failures.
     */
    public static class ExceptionRecord {
        public final String eventId;
        public final String domain;
        public final String eventType;
        public final String aggregateId;
        public final String correlationId;
        public final Instant timestamp;
        public final String errorMessage;
        
        public ExceptionRecord(String eventId, String domain, String eventType,
                             String aggregateId, String correlationId,
                             Instant timestamp, String errorMessage) {
            this.eventId = eventId;
            this.domain = domain;
            this.eventType = eventType;
            this.aggregateId = aggregateId;
            this.correlationId = correlationId;
            this.timestamp = timestamp;
            this.errorMessage = errorMessage;
        }
    }
}

