package dev.mars.peegeeq.examples.fundscustody.events;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Event payload for trade cancellation.
 * 
 * <p>This event is stored in the bi-temporal event store with:
 * <ul>
 *   <li>Valid Time = Original Trade Date (same as the trade being cancelled)</li>
 *   <li>Transaction Time = Cancellation Time (when the cancellation was processed)</li>
 *   <li>Aggregate ID = Fund ID (same as the original trade)</li>
 * </ul>
 * 
 * <p>The cancellation event maintains the audit trail while correcting the position.
 */
public record TradeCancelledEvent(
    String tradeId,
    String fundId,
    String securityId,
    LocalDate originalTradeDate,
    String reason,
    String cancelledBy
) {
    public TradeCancelledEvent {
        Objects.requireNonNull(tradeId, "tradeId cannot be null");
        Objects.requireNonNull(fundId, "fundId cannot be null");
        Objects.requireNonNull(securityId, "securityId cannot be null");
        Objects.requireNonNull(originalTradeDate, "originalTradeDate cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");
        Objects.requireNonNull(cancelledBy, "cancelledBy cannot be null");
    }
}

