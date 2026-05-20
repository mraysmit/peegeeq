package dev.mars.peegeeq.examples.fundscustody.events;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Event payload for liquidity check in the Treasury domain.
 * Maps to event name: cash.sufficiency.checked
 */
public record LiquidityCheckEvent(
    String checkId,
    String accountId,
    BigDecimal requiredAmount,
    BigDecimal availableAmount,
    String checkResult
) {
    public LiquidityCheckEvent {
        Objects.requireNonNull(checkId, "checkId cannot be null");
        Objects.requireNonNull(accountId, "accountId cannot be null");
        Objects.requireNonNull(requiredAmount, "requiredAmount cannot be null");
        Objects.requireNonNull(availableAmount, "availableAmount cannot be null");
        Objects.requireNonNull(checkResult, "checkResult cannot be null");
    }
}
