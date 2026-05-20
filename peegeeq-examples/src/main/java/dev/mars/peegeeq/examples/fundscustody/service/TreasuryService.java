package dev.mars.peegeeq.examples.fundscustody.service;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.examples.fundscustody.events.CashMovementEvent;
import dev.mars.peegeeq.examples.fundscustody.events.LiquidityCheckEvent;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Service for managing cash movement and liquidity in a treasury system.
 * Demonstrates the Treasury Domain from the Financial Services Event Catalogue.
 */
public class TreasuryService {
    private static final Logger logger = LoggerFactory.getLogger(TreasuryService.class);
    
    private final EventStore<CashMovementEvent> cashMovementStore;
    private final EventStore<LiquidityCheckEvent> liquidityCheckStore;

    public TreasuryService(
            EventStore<CashMovementEvent> cashMovementStore,
            EventStore<LiquidityCheckEvent> liquidityCheckStore) {
        this.cashMovementStore = cashMovementStore;
        this.liquidityCheckStore = liquidityCheckStore;
    }

    /**
     * Records a completed cash movement between accounts.
     * 
     * @param event The cash movement payload
     * @param validTime The business effective time of the movement
     * @return future containing the stored event
     */
    public Future<BiTemporalEvent<CashMovementEvent>> recordCashMovement(CashMovementEvent event, Instant validTime) {
        logger.info("Recording cash movement {} from {} to {}", 
            event.movementId(), event.fromAccount(), event.toAccount());
            
        return cashMovementStore.append(
            "cash.movement.completed",
            event,
            validTime,
            Map.of("movementType", event.movementType(), "currency", event.currency()),
            null,
            null,
            "ACCOUNT:" + event.fromAccount() // Aggregate by source account
        );
    }

    /**
     * Records a liquidity sufficiency check.
     * 
     * @param event The liquidity check payload
     * @param validTime The business effective time of the check
     * @return future containing the stored event
     */
    public Future<BiTemporalEvent<LiquidityCheckEvent>> recordLiquidityCheck(LiquidityCheckEvent event, Instant validTime) {
        logger.info("Recording liquidity check {} for account {} - Result: {}", 
            event.checkId(), event.accountId(), event.checkResult());
            
        return liquidityCheckStore.append(
            "cash.sufficiency.checked",
            event,
            validTime,
            Map.of("checkResult", event.checkResult()),
            null,
            null,
            "ACCOUNT:" + event.accountId() // Aggregate by account
        );
    }
}
