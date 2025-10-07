package dev.mars.peegeeq.examples.springbootfinancialfabric.handlers;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.bitemporal.PgBiTemporalEventStore;
import dev.mars.peegeeq.examples.springbootfinancialfabric.events.PositionUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Event handler for position management domain events.
 * Subscribes to all position.* events and processes them asynchronously.
 * 
 * Pattern: position.{action}.{state}
 * Examples:
 * - position.update.completed
 * - position.reconciliation.completed
 * - position.reconciliation.failed
 */
@Component
public class PositionEventHandler {
    
    private static final Logger log = LoggerFactory.getLogger(PositionEventHandler.class);
    
    private final PgBiTemporalEventStore<PositionUpdateEvent> positionEventStore;
    private final AtomicLong eventsProcessed = new AtomicLong(0);
    private final AtomicLong updateEventsProcessed = new AtomicLong(0);
    
    public PositionEventHandler(
            @Qualifier("positionEventStore") PgBiTemporalEventStore<PositionUpdateEvent> positionEventStore) {
        this.positionEventStore = positionEventStore;
    }
    
    @PostConstruct
    public void initialize() {
        log.info("Initializing PositionEventHandler - subscribing to position.* events");
        
        positionEventStore.subscribe(null, null, new MessageHandler<BiTemporalEvent<PositionUpdateEvent>>() {
            @Override
            public CompletableFuture<Void> handle(Message<BiTemporalEvent<PositionUpdateEvent>> message) {
                return handlePositionEvent(message.getPayload());
            }
        }).whenComplete((result, error) -> {
            if (error != null) {
                log.error("Failed to subscribe to position events", error);
            } else {
                log.info("Successfully subscribed to position events");
            }
        });
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down PositionEventHandler - processed {} total events ({} updates)",
                eventsProcessed.get(), updateEventsProcessed.get());
        
        positionEventStore.unsubscribe().whenComplete((result, error) -> {
            if (error != null) {
                log.error("Error unsubscribing from position events", error);
            } else {
                log.info("Successfully unsubscribed from position events");
            }
        });
    }
    
    /**
     * Handles all position events asynchronously.
     */
    private CompletableFuture<Void> handlePositionEvent(BiTemporalEvent<PositionUpdateEvent> event) {
        eventsProcessed.incrementAndGet();
        
        String eventType = event.getEventType();
        PositionUpdateEvent positionUpdate = event.getPayload();
        
        log.debug("Processing position event: {} for update: {}", eventType, positionUpdate.getUpdateId());
        
        try {
            if (eventType.matches("position\\.update\\.completed")) {
                return handlePositionUpdate(event);
            } else {
                log.warn("Unknown position event type: {}", eventType);
                return CompletableFuture.completedFuture(null);
            }
        } catch (Exception e) {
            log.error("Error processing position event: {} for update: {}", 
                    eventType, positionUpdate.getUpdateId(), e);
            return CompletableFuture.failedFuture(e);
        }
    }
    
    /**
     * Handles position update events.
     */
    private CompletableFuture<Void> handlePositionUpdate(BiTemporalEvent<PositionUpdateEvent> event) {
        updateEventsProcessed.incrementAndGet();
        
        PositionUpdateEvent positionUpdate = event.getPayload();
        
        log.info("📈 Position Updated: {} - Account: {}, Instrument: {}, Quantity Change: {}",
                positionUpdate.getUpdateId(),
                positionUpdate.getAccount(),
                positionUpdate.getInstrument(),
                positionUpdate.getQuantityChange());
        
        // Async processing tasks:
        // 1. Update position cache
        updatePositionCache(positionUpdate);
        
        // 2. Recalculate risk metrics
        recalculateRiskMetrics(positionUpdate);
        
        // 3. Check position limits
        checkPositionLimits(positionUpdate);
        
        // 4. Update position dashboard
        updatePositionDashboard(positionUpdate);
        
        // 5. Notify risk team if significant change
        notifyRiskTeamIfSignificant(positionUpdate);
        
        return CompletableFuture.completedFuture(null);
    }
    
    // Simulated async processing methods
    
    private void updatePositionCache(PositionUpdateEvent positionUpdate) {
        log.debug("Updating position cache for account: {}", positionUpdate.getAccount());
        // In production: update position cache (Redis, Hazelcast, etc.)
    }

    private void recalculateRiskMetrics(PositionUpdateEvent positionUpdate) {
        log.debug("Recalculating risk metrics for position update: {}", positionUpdate.getUpdateId());
        // In production: recalculate VaR, Greeks, exposure, etc.)
    }

    private void checkPositionLimits(PositionUpdateEvent positionUpdate) {
        log.debug("Checking position limits for account: {}", positionUpdate.getAccount());
        // In production: check against position limits and risk thresholds
    }

    private void updatePositionDashboard(PositionUpdateEvent positionUpdate) {
        log.debug("Updating position dashboard for update: {}", positionUpdate.getUpdateId());
        // In production: update real-time position dashboard
    }

    private void notifyRiskTeamIfSignificant(PositionUpdateEvent positionUpdate) {
        // Check if position change is significant (e.g., > 10% of total position)
        if (positionUpdate.getQuantityChange().abs().compareTo(positionUpdate.getQuantityChange().abs()) > 0) {
            log.info("Significant position change detected for account: {}", positionUpdate.getAccount());
            // In production: send notification to risk team
        }
    }
    
    // Metrics accessors
    
    public long getEventsProcessed() {
        return eventsProcessed.get();
    }
    
    public long getUpdateEventsProcessed() {
        return updateEventsProcessed.get();
    }
}

