package dev.mars.peegeeq.examples.springbootfinancialfabric.handlers;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.bitemporal.PgBiTemporalEventStore;
import dev.mars.peegeeq.examples.springbootfinancialfabric.events.CashMovementEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Event handler for cash management domain events.
 * Subscribes to all cash.* events and processes them asynchronously.
 * 
 * Pattern: cash.{action}.{state}
 * Examples:
 * - cash.movement.completed
 * - cash.liquidity.checked
 * - cash.funding.required
 */
@Component
public class CashEventHandler {
    
    private static final Logger log = LoggerFactory.getLogger(CashEventHandler.class);
    
    private final PgBiTemporalEventStore<CashMovementEvent> cashEventStore;
    private final AtomicLong eventsProcessed = new AtomicLong(0);
    private final AtomicLong movementEventsProcessed = new AtomicLong(0);
    
    public CashEventHandler(
            @Qualifier("cashEventStore") PgBiTemporalEventStore<CashMovementEvent> cashEventStore) {
        this.cashEventStore = cashEventStore;
    }
    
    @PostConstruct
    public void initialize() {
        log.info("Initializing CashEventHandler - subscribing to cash.* events");
        
        cashEventStore.subscribe(null, null, new MessageHandler<BiTemporalEvent<CashMovementEvent>>() {
            @Override
            public CompletableFuture<Void> handle(Message<BiTemporalEvent<CashMovementEvent>> message) {
                return handleCashEvent(message.getPayload());
            }
        }).whenComplete((result, error) -> {
            if (error != null) {
                log.error("Failed to subscribe to cash events", error);
            } else {
                log.info("Successfully subscribed to cash events");
            }
        });
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down CashEventHandler - processed {} total events ({} movements)",
                eventsProcessed.get(), movementEventsProcessed.get());
        
        cashEventStore.unsubscribe().whenComplete((result, error) -> {
            if (error != null) {
                log.error("Error unsubscribing from cash events", error);
            } else {
                log.info("Successfully unsubscribed from cash events");
            }
        });
    }
    
    /**
     * Handles all cash events asynchronously.
     */
    private CompletableFuture<Void> handleCashEvent(BiTemporalEvent<CashMovementEvent> event) {
        eventsProcessed.incrementAndGet();
        
        String eventType = event.getEventType();
        CashMovementEvent cashMovement = event.getPayload();
        
        log.debug("Processing cash event: {} for movement: {}", eventType, cashMovement.getMovementId());
        
        try {
            if (eventType.matches("cash\\.movement\\.completed")) {
                return handleCashMovement(event);
            } else {
                log.warn("Unknown cash event type: {}", eventType);
                return CompletableFuture.completedFuture(null);
            }
        } catch (Exception e) {
            log.error("Error processing cash event: {} for movement: {}", 
                    eventType, cashMovement.getMovementId(), e);
            return CompletableFuture.failedFuture(e);
        }
    }
    
    /**
     * Handles cash movement events.
     */
    private CompletableFuture<Void> handleCashMovement(BiTemporalEvent<CashMovementEvent> event) {
        movementEventsProcessed.incrementAndGet();
        
        CashMovementEvent cashMovement = event.getPayload();
        
        log.info("💰 Cash Movement Completed: {} - {} {} in {} (Account: {})",
                cashMovement.getMovementId(),
                cashMovement.getMovementType(),
                cashMovement.getAmount(),
                cashMovement.getCurrency(),
                cashMovement.getAccount());
        
        // Async processing tasks:
        // 1. Update cash balances
        updateCashBalances(cashMovement);
        
        // 2. Check liquidity requirements
        checkLiquidityRequirements(cashMovement);
        
        // 3. Update treasury dashboard
        updateTreasuryDashboard(cashMovement);
        
        // 4. Notify treasury team
        notifyTreasuryTeam(cashMovement);
        
        return CompletableFuture.completedFuture(null);
    }
    
    // Simulated async processing methods
    
    private void updateCashBalances(CashMovementEvent cashMovement) {
        log.debug("Updating cash balances for account: {}", cashMovement.getAccount());
        // In production: update cash balance in treasury system
    }
    
    private void checkLiquidityRequirements(CashMovementEvent cashMovement) {
        log.debug("Checking liquidity requirements for movement: {}", cashMovement.getMovementId());
        // In production: check if liquidity requirements are met
    }
    
    private void updateTreasuryDashboard(CashMovementEvent cashMovement) {
        log.debug("Updating treasury dashboard for movement: {}", cashMovement.getMovementId());
        // In production: update real-time treasury dashboard
    }
    
    private void notifyTreasuryTeam(CashMovementEvent cashMovement) {
        log.debug("Notifying treasury team about cash movement: {}", cashMovement.getMovementId());
        // In production: send notification to treasury team
    }
    
    // Metrics accessors
    
    public long getEventsProcessed() {
        return eventsProcessed.get();
    }
    
    public long getMovementEventsProcessed() {
        return movementEventsProcessed.get();
    }
}

