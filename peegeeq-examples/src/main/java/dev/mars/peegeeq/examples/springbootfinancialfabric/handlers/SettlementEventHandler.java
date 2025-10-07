package dev.mars.peegeeq.examples.springbootfinancialfabric.handlers;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.bitemporal.PgBiTemporalEventStore;
import dev.mars.peegeeq.examples.springbootfinancialfabric.events.SettlementInstructionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Event handler for settlement domain events.
 * Subscribes to all instruction.settlement.* events and processes them asynchronously.
 * 
 * Pattern: instruction.settlement.{state}
 * Examples:
 * - instruction.settlement.submitted
 * - instruction.settlement.confirmed
 * - instruction.settlement.failed
 */
@Component
public class SettlementEventHandler {
    
    private static final Logger log = LoggerFactory.getLogger(SettlementEventHandler.class);
    
    private final PgBiTemporalEventStore<SettlementInstructionEvent> settlementEventStore;
    private final AtomicLong eventsProcessed = new AtomicLong(0);
    private final AtomicLong submittedEventsProcessed = new AtomicLong(0);
    private final AtomicLong confirmedEventsProcessed = new AtomicLong(0);
    
    public SettlementEventHandler(
            @Qualifier("settlementEventStore") PgBiTemporalEventStore<SettlementInstructionEvent> settlementEventStore) {
        this.settlementEventStore = settlementEventStore;
    }
    
    @PostConstruct
    public void initialize() {
        log.info("Initializing SettlementEventHandler - subscribing to instruction.settlement.* events");
        
        settlementEventStore.subscribe(null, null, new MessageHandler<BiTemporalEvent<SettlementInstructionEvent>>() {
            @Override
            public CompletableFuture<Void> handle(Message<BiTemporalEvent<SettlementInstructionEvent>> message) {
                return handleSettlementEvent(message.getPayload());
            }
        }).whenComplete((result, error) -> {
            if (error != null) {
                log.error("Failed to subscribe to settlement events", error);
            } else {
                log.info("Successfully subscribed to settlement events");
            }
        });
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down SettlementEventHandler - processed {} total events ({} submitted, {} confirmed)",
                eventsProcessed.get(), submittedEventsProcessed.get(), confirmedEventsProcessed.get());
        
        settlementEventStore.unsubscribe().whenComplete((result, error) -> {
            if (error != null) {
                log.error("Error unsubscribing from settlement events", error);
            } else {
                log.info("Successfully unsubscribed from settlement events");
            }
        });
    }
    
    /**
     * Handles all settlement events asynchronously.
     */
    private CompletableFuture<Void> handleSettlementEvent(BiTemporalEvent<SettlementInstructionEvent> event) {
        eventsProcessed.incrementAndGet();
        
        String eventType = event.getEventType();
        SettlementInstructionEvent instruction = event.getPayload();
        
        log.debug("Processing settlement event: {} for instruction: {}", eventType, instruction.getInstructionId());
        
        try {
            if (eventType.matches("instruction\\.settlement\\.submitted")) {
                return handleSettlementSubmitted(event);
            } else if (eventType.matches("instruction\\.settlement\\.confirmed")) {
                return handleSettlementConfirmed(event);
            } else {
                log.warn("Unknown settlement event type: {}", eventType);
                return CompletableFuture.completedFuture(null);
            }
        } catch (Exception e) {
            log.error("Error processing settlement event: {} for instruction: {}", 
                    eventType, instruction.getInstructionId(), e);
            return CompletableFuture.failedFuture(e);
        }
    }
    
    /**
     * Handles settlement instruction submitted events.
     */
    private CompletableFuture<Void> handleSettlementSubmitted(BiTemporalEvent<SettlementInstructionEvent> event) {
        submittedEventsProcessed.incrementAndGet();
        
        SettlementInstructionEvent instruction = event.getPayload();
        
        log.info("📤 Settlement Instruction Submitted: {} - Trade: {}, Custodian: {}, Settlement Date: {}",
                instruction.getInstructionId(),
                instruction.getTradeId(),
                instruction.getCustodian(),
                instruction.getSettlementDate());
        
        // Async processing tasks:
        // 1. Send to custodian
        sendToCustodian(instruction);
        
        // 2. Update settlement tracking system
        updateSettlementTracking(instruction);
        
        // 3. Notify operations team
        notifyOperations(instruction);
        
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Handles settlement confirmation events.
     */
    private CompletableFuture<Void> handleSettlementConfirmed(BiTemporalEvent<SettlementInstructionEvent> event) {
        confirmedEventsProcessed.incrementAndGet();
        
        SettlementInstructionEvent instruction = event.getPayload();
        
        log.info("✅ Settlement Confirmed: {} - Trade: {}, Custodian: {}",
                instruction.getInstructionId(),
                instruction.getTradeId(),
                instruction.getCustodian());
        
        // Async processing tasks:
        // 1. Update settlement status
        updateSettlementStatus(instruction);
        
        // 2. Trigger cash movement
        triggerCashMovement(instruction);
        
        // 3. Notify treasury team
        notifyTreasury(instruction);
        
        return CompletableFuture.completedFuture(null);
    }
    
    // Simulated async processing methods
    
    private void sendToCustodian(SettlementInstructionEvent instruction) {
        log.debug("Sending settlement instruction to custodian: {}", instruction.getCustodian());
        // In production: send SWIFT message or API call to custodian
    }
    
    private void updateSettlementTracking(SettlementInstructionEvent instruction) {
        log.debug("Updating settlement tracking for instruction: {}", instruction.getInstructionId());
        // In production: update settlement tracking system
    }
    
    private void notifyOperations(SettlementInstructionEvent instruction) {
        log.debug("Notifying operations team about settlement instruction: {}", instruction.getInstructionId());
        // In production: send notification to operations
    }
    
    private void updateSettlementStatus(SettlementInstructionEvent instruction) {
        log.debug("Updating settlement status for instruction: {}", instruction.getInstructionId());
        // In production: update settlement status in tracking system
    }
    
    private void triggerCashMovement(SettlementInstructionEvent instruction) {
        log.debug("Triggering cash movement for instruction: {}", instruction.getInstructionId());
        // In production: initiate cash movement workflow
    }
    
    private void notifyTreasury(SettlementInstructionEvent instruction) {
        log.debug("Notifying treasury team about confirmed settlement: {}", instruction.getInstructionId());
        // In production: send notification to treasury
    }
    
    // Metrics accessors
    
    public long getEventsProcessed() {
        return eventsProcessed.get();
    }
    
    public long getSubmittedEventsProcessed() {
        return submittedEventsProcessed.get();
    }
    
    public long getConfirmedEventsProcessed() {
        return confirmedEventsProcessed.get();
    }
}

