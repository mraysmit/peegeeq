package dev.mars.peegeeq.examples.fundscustody.events;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Event payload for cash movement in the Treasury domain.
 * Maps to event name: {@code cash.movement.completed}
 *
 * <p>Represents a completed transfer of funds between two accounts. Captured as an immutable
 * event so that corrections (e.g. wrong amount posted) are recorded as new corrective events
 * rather than by overwriting the original.
 */
public record CashMovementEvent(
    String movementId,
    String fromAccount,
    String toAccount,
    BigDecimal amount,
    String currency,          // ISO 4217 code, e.g. USD, EUR, GBP
    String movementType       // e.g. WIRE, BOOK_TRANSFER, FX_SETTLEMENT, REPO_OPEN
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
