package dev.mars.peegeeq.examples.fundscustody.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Immutable domain model representing a trade transaction.
 * 
 * <p>In funds & custody operations, a trade has:
 * <ul>
 *   <li>Trade Date - when the trade was executed (Valid Time)</li>
 *   <li>Settlement Date - when the trade settles (typically T+2)</li>
 *   <li>Confirmation Time - when the trade was confirmed (Transaction Time)</li>
 * </ul>
 */
public record Trade(
    String tradeId,
    String fundId,
    String securityId,
    TradeType tradeType,
    BigDecimal quantity,
    BigDecimal price,
    Currency currency,
    LocalDate tradeDate,
    LocalDate settlementDate,
    String counterparty
) {
    public Trade {
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
        
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("price must be positive");
        }
        if (settlementDate.isBefore(tradeDate)) {
            throw new IllegalArgumentException("settlementDate cannot be before tradeDate");
        }
    }
    
    /**
     * Calculate the gross amount of the trade (quantity * price).
     * 
     * @return gross trade amount
     */
    public BigDecimal grossAmount() {
        return quantity.multiply(price);
    }
    
    /**
     * Get the signed quantity based on trade type.
     * BUY = positive, SELL = negative.
     * 
     * @return signed quantity
     */
    public BigDecimal signedQuantity() {
        return tradeType == TradeType.BUY ? quantity : quantity.negate();
    }
}

