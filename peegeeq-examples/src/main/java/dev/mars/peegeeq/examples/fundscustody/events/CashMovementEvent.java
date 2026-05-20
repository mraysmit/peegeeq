package dev.mars.peegeeq.examples.fundscustody.events;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Event payload for cash movement in the Treasury domain.
 * Maps to event name: cash.movement.completed
 */
public record CashMovementEvent(
    String movementId,
    String fromAccount,
    String toAccount,
    BigDecimal amount,
    String currency,
    String movementType
) {
    public CashMovementEvent {
        Objects.requireNonNull(movementId, "movementId cannot be null");
        Objects.requireNonNull(fromAccount, "fromAccount cannot be null");
        Objects.requireNonNull(toAccount, "toAccount cannot be null");
        Objects.requireNonNull(amount, "amount cannot be null");
        Objects.requireNonNull(currency, "currency cannot be null");
        Objects.requireNonNull(movementType, "movementType cannot be null");
    }
}
