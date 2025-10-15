package dev.mars.peegeeq.examples.fundscustody.events;

import dev.mars.peegeeq.examples.fundscustody.domain.Currency;
import dev.mars.peegeeq.examples.fundscustody.domain.Trade;
import dev.mars.peegeeq.examples.fundscustody.domain.TradeType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Event payload for trade execution.
 * 
 * <p>This event is stored in the bi-temporal event store with:
 * <ul>
 *   <li>Valid Time = Trade Date (when the trade was executed)</li>
 *   <li>Transaction Time = Confirmation Time (when the trade was confirmed/recorded)</li>
 *   <li>Aggregate ID = Fund ID (enables efficient querying by fund)</li>
 * </ul>
 * 
 * <p>All fields are stored as strings or primitives for JSON serialization.
 */
public record TradeEvent(
    String tradeId,
    String fundId,
    String securityId,
    String tradeType,      // BUY/SELL as string for JSON
    BigDecimal quantity,
    BigDecimal price,
    String currency,
    LocalDate tradeDate,
    LocalDate settlementDate,
    String counterparty
) {
    public TradeEvent {
        Objects.requireNonNull(tradeId, "tradeId cannot be null");
        Objects.requireNonNull(fundId, "fundId cannot be null");
        Objects.requireNonNull(securityId, "securityId cannot be null");
        Objects.requireNonNull(tradeType, "tradeType cannot be null");
        Objects.requireNonNull(quantity, "quantity cannot be null");
        Objects.requireNonNull(price, "price cannot be null");
        Objects.requireNonNull(currency, "currency cannot be null");
        Objects.requireNonNull(tradeDate, "tradeDate cannot be null");
        Objects.requireNonNull(settlementDate, "settlementDate cannot be null");
        Objects.requireNonNull(counterparty, "counterparty cannot be null");
    }
    
    /**
     * Create a TradeEvent from a Trade domain model.
     * 
     * @param trade domain model
     * @return event payload
     */
    public static TradeEvent from(Trade trade) {
        return new TradeEvent(
            trade.tradeId(),
            trade.fundId(),
            trade.securityId(),
            trade.tradeType().name(),
            trade.quantity(),
            trade.price(),
            trade.currency().name(),
            trade.tradeDate(),
            trade.settlementDate(),
            trade.counterparty()
        );
    }
    
    /**
     * Convert this event to a Trade domain model.
     * 
     * @return domain model
     */
    public Trade toDomain() {
        return new Trade(
            tradeId,
            fundId,
            securityId,
            TradeType.valueOf(tradeType),
            quantity,
            price,
            Currency.valueOf(currency),
            tradeDate,
            settlementDate,
            counterparty
        );
    }
    
    /**
     * Get the signed quantity based on trade type.
     * BUY = positive, SELL = negative.
     * 
     * @return signed quantity
     */
    public BigDecimal signedQuantity() {
        return "BUY".equals(tradeType) ? quantity : quantity.negate();
    }
}

