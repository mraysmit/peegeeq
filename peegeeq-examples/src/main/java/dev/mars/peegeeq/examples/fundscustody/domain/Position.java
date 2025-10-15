package dev.mars.peegeeq.examples.fundscustody.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Immutable domain model representing a calculated position.
 * 
 * <p>A position is calculated from trade events and represents:
 * <ul>
 *   <li>Net quantity held (sum of all buys minus sells)</li>
 *   <li>Average price paid (weighted average)</li>
 *   <li>As-of date (point in time for the calculation)</li>
 * </ul>
 */
public record Position(
    String fundId,
    String securityId,
    BigDecimal quantity,
    BigDecimal averagePrice,
    Currency currency,
    LocalDate asOfDate
) {
    public Position {
        Objects.requireNonNull(fundId, "fundId cannot be null");
        Objects.requireNonNull(securityId, "securityId cannot be null");
        Objects.requireNonNull(quantity, "quantity cannot be null");
        Objects.requireNonNull(currency, "currency cannot be null");
        Objects.requireNonNull(asOfDate, "asOfDate cannot be null");
        
        // averagePrice can be null if quantity is zero
        if (quantity.compareTo(BigDecimal.ZERO) != 0 && averagePrice == null) {
            throw new IllegalArgumentException("averagePrice cannot be null when quantity is non-zero");
        }
    }
    
    /**
     * Calculate the market value of the position at a given price.
     * 
     * @param currentPrice current market price
     * @return market value (quantity * currentPrice)
     */
    public BigDecimal marketValue(BigDecimal currentPrice) {
        Objects.requireNonNull(currentPrice, "currentPrice cannot be null");
        return quantity.multiply(currentPrice);
    }
    
    /**
     * Calculate the cost basis of the position.
     * 
     * @return cost basis (quantity * averagePrice)
     */
    public BigDecimal costBasis() {
        if (averagePrice == null) {
            return BigDecimal.ZERO;
        }
        return quantity.multiply(averagePrice);
    }
    
    /**
     * Check if the position is flat (zero quantity).
     * 
     * @return true if quantity is zero
     */
    public boolean isFlat() {
        return quantity.compareTo(BigDecimal.ZERO) == 0;
    }
    
    /**
     * Check if the position is long (positive quantity).
     * 
     * @return true if quantity is positive
     */
    public boolean isLong() {
        return quantity.compareTo(BigDecimal.ZERO) > 0;
    }
    
    /**
     * Check if the position is short (negative quantity).
     * 
     * @return true if quantity is negative
     */
    public boolean isShort() {
        return quantity.compareTo(BigDecimal.ZERO) < 0;
    }
}

