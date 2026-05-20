package dev.mars.peegeeq.examples.fundscustody.events;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Event payload for a liquidity sufficiency check in the Treasury domain.
 * Maps to event name: {@code cash.sufficiency.checked}
 *
 * <p>Recorded each time the system verifies that an account holds sufficient funds
 * before allowing a settlement or payment to proceed. The outcome is stored as an
 * immutable event so that the sequence of checks for an account can be replayed
 * and audited over any time range.
 */
public record LiquidityCheckEvent(
    String checkId,
    String accountId,
    BigDecimal requiredAmount,
    BigDecimal availableAmount,
    String checkResult        // PASSED, FAILED, INSUFFICIENT_FUNDS
) {
    public LiquidityCheckEvent {
        Objects.requireNonNull(checkId, "checkId cannot be null");
        Objects.requireNonNull(accountId, "accountId cannot be null");
        Objects.requireNonNull(requiredAmount, "requiredAmount cannot be null");
        Objects.requireNonNull(availableAmount, "availableAmount cannot be null");
        Objects.requireNonNull(checkResult, "checkResult cannot be null");
    }
}
