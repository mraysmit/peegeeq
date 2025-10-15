package dev.mars.peegeeq.examples.fundscustody.model;

import dev.mars.peegeeq.examples.fundscustody.domain.Currency;
import dev.mars.peegeeq.examples.fundscustody.domain.Position;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Response DTO for position queries.
 * 
 * <p>Includes both the position data and metadata about when it was calculated.
 */
public record PositionSnapshot(
    String fundId,
    String securityId,
    BigDecimal quantity,
    BigDecimal averagePrice,
    Currency currency,
    LocalDate asOfDate,
    Instant calculatedAt  // Transaction time when calculated
) {
    public PositionSnapshot {
        Objects.requireNonNull(fundId, "fundId cannot be null");
        Objects.requireNonNull(securityId, "securityId cannot be null");
        Objects.requireNonNull(quantity, "quantity cannot be null");
        Objects.requireNonNull(currency, "currency cannot be null");
        Objects.requireNonNull(asOfDate, "asOfDate cannot be null");
        Objects.requireNonNull(calculatedAt, "calculatedAt cannot be null");
    }
    
    /**
     * Create a PositionSnapshot from a Position domain model.
     * 
     * @param position domain model
     * @param calculatedAt when the position was calculated
     * @return snapshot
     */
    public static PositionSnapshot from(Position position, Instant calculatedAt) {
        return new PositionSnapshot(
            position.fundId(),
            position.securityId(),
            position.quantity(),
            position.averagePrice(),
            position.currency(),
            position.asOfDate(),
            calculatedAt
        );
    }
    
    /**
     * Convert this snapshot to a Position domain model.
     * 
     * @return domain model
     */
    public Position toDomain() {
        return new Position(
            fundId,
            securityId,
            quantity,
            averagePrice,
            currency,
            asOfDate
        );
    }
}

