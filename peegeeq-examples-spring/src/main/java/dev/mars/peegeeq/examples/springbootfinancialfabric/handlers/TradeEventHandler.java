package dev.mars.peegeeq.examples.springbootfinancialfabric.handlers;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.bitemporal.PgBiTemporalEventStore;
import dev.mars.peegeeq.examples.springbootfinancialfabric.events.TradeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Event handler for trading domain events.
 * Subscribes to all trading.* events and processes them asynchronously.
 * 
 * Pattern: trading.{asset-class}.{action}.{state}
 * Examples:
 * - trading.equities.capture.completed
 * - trading.equities.confirmation.matched
 * - trading.fx.capture.completed
 */
@Component
public class TradeEventHandler {
    
    private static final Logger log = LoggerFactory.getLogger(TradeEventHandler.class);
    
    private final PgBiTemporalEventStore<TradeEvent> tradingEventStore;
    private final AtomicLong eventsProcessed = new AtomicLong(0);
    private final AtomicLong captureEventsProcessed = new AtomicLong(0);
    private final AtomicLong confirmationEventsProcessed = new AtomicLong(0);
    
    public TradeEventHandler(
            @Qualifier("tradingEventStore") PgBiTemporalEventStore<TradeEvent> tradingEventStore) {
        this.tradingEventStore = tradingEventStore;
    }
    
    @PostConstruct
    public void initialize() {
        log.info("Initializing TradeEventHandler - subscribing to trading.* events");
        
        // Subscribe to all trading events using wildcard pattern
        // The subscribe method filters by eventType pattern
        tradingEventStore.subscribe(null, null, new MessageHandler<BiTemporalEvent<TradeEvent>>() {
            @Override
            public CompletableFuture<Void> handle(Message<BiTemporalEvent<TradeEvent>> message) {
                return handleTradingEvent(message.getPayload());
            }
        }).whenComplete((result, error) -> {
            if (error != null) {
                log.error("Failed to subscribe to trading events", error);
            } else {
                log.info("Successfully subscribed to trading events");
            }
        });
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down TradeEventHandler - processed {} total events ({} captures, {} confirmations)",
                eventsProcessed.get(), captureEventsProcessed.get(), confirmationEventsProcessed.get());
        
        tradingEventStore.unsubscribe().whenComplete((result, error) -> {
            if (error != null) {
                log.error("Error unsubscribing from trading events", error);
            } else {
                log.info("Successfully unsubscribed from trading events");
            }
        });
    }
    
    /**
     * Handles all trading events asynchronously.
     * Routes to specific handlers based on event type pattern.
     */
    private CompletableFuture<Void> handleTradingEvent(BiTemporalEvent<TradeEvent> event) {
        eventsProcessed.incrementAndGet();
        
        String eventType = event.getEventType();
        TradeEvent trade = event.getPayload();
        
        log.debug("Processing trading event: {} for trade: {}", eventType, trade.getTradeId());
        
        try {
            // Route based on event type pattern
            if (eventType.matches("trading\\..+\\.capture\\.completed")) {
                return handleTradeCapture(event);
            } else if (eventType.matches("trading\\..+\\.confirmation\\.matched")) {
                return handleTradeConfirmation(event);
            } else {
                log.warn("Unknown trading event type: {}", eventType);
                return CompletableFuture.completedFuture(null);
            }
        } catch (Exception e) {
            log.error("Error processing trading event: {} for trade: {}", eventType, trade.getTradeId(), e);
            return CompletableFuture.failedFuture(e);
        }
    }
    
    /**
     * Handles trade capture events.
     * Pattern: trading.{asset-class}.capture.completed
     */
    private CompletableFuture<Void> handleTradeCapture(BiTemporalEvent<TradeEvent> event) {
        captureEventsProcessed.incrementAndGet();
        
        TradeEvent trade = event.getPayload();
        
        log.info("📊 Trade Captured: {} - {} {} shares of {} @ {} (Counterparty: {})",
                trade.getTradeId(),
                trade.getTradeType(),
                trade.getQuantity(),
                trade.getInstrument(),
                trade.getPrice(),
                trade.getCounterparty());
        
        // Async processing tasks:
        // 1. Update risk positions
        updateRiskPositions(trade);
        
        // 2. Check trading limits
        checkTradingLimits(trade);
        
        // 3. Notify trading desk
        notifyTradingDesk(trade);
        
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Handles trade confirmation events.
     * Pattern: trading.{asset-class}.confirmation.matched
     */
    private CompletableFuture<Void> handleTradeConfirmation(BiTemporalEvent<TradeEvent> event) {
        confirmationEventsProcessed.incrementAndGet();
        
        TradeEvent trade = event.getPayload();
        
        log.info("✅ Trade Confirmed: {} - Matched with counterparty {}",
                trade.getTradeId(),
                trade.getCounterparty());
        
        // Async processing tasks:
        // 1. Update confirmation status
        updateConfirmationStatus(trade);
        
        // 2. Trigger settlement workflow
        triggerSettlementWorkflow(trade);
        
        // 3. Notify operations team
        notifyOperationsTeam(trade);
        
        return CompletableFuture.completedFuture(null);
    }
    
    // Simulated async processing methods
    
    private void updateRiskPositions(TradeEvent trade) {
        log.debug("Updating risk positions for trade: {}", trade.getTradeId());
        // In production: call risk management service
    }
    
    private void checkTradingLimits(TradeEvent trade) {
        log.debug("Checking trading limits for trade: {}", trade.getTradeId());
        // In production: validate against trading limits
    }
    
    private void notifyTradingDesk(TradeEvent trade) {
        log.debug("Notifying trading desk about trade: {}", trade.getTradeId());
        // In production: send notification to trading desk
    }
    
    private void updateConfirmationStatus(TradeEvent trade) {
        log.debug("Updating confirmation status for trade: {}", trade.getTradeId());
        // In production: update trade status in trading system
    }
    
    private void triggerSettlementWorkflow(TradeEvent trade) {
        log.debug("Triggering settlement workflow for trade: {}", trade.getTradeId());
        // In production: initiate settlement instruction generation
    }
    
    private void notifyOperationsTeam(TradeEvent trade) {
        log.debug("Notifying operations team about confirmed trade: {}", trade.getTradeId());
        // In production: send notification to operations
    }
    
    // Metrics accessors for monitoring
    
    public long getEventsProcessed() {
        return eventsProcessed.get();
    }
    
    public long getCaptureEventsProcessed() {
        return captureEventsProcessed.get();
    }
    
    public long getConfirmationEventsProcessed() {
        return confirmationEventsProcessed.get();
    }
}

