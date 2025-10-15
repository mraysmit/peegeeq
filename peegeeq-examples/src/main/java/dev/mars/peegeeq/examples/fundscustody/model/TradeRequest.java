package dev.mars.peegeeq.examples.fundscustody.model;

import dev.mars.peegeeq.examples.fundscustody.domain.Currency;
import dev.mars.peegeeq.examples.fundscustody.domain.Trade;
import dev.mars.peegeeq.examples.fundscustody.domain.TradeType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Request DTO for recording a trade.
 */
public record TradeRequest(
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
    public TradeRequest {
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
     * Convert this request to a Trade domain model with a generated trade ID.
     * 
     * @return domain model
     */
    public Trade toDomain() {
        return new Trade(
            generateTradeId(),
            fundId,
            securityId,
            tradeType,
            quantity,
            price,
            currency,
            tradeDate,
            settlementDate,
            counterparty
        );
    }
    
    /**
     * Convert this request to a Trade domain model with a specific trade ID.
     * 
     * @param tradeId trade identifier
     * @return domain model
     */
    public Trade toDomain(String tradeId) {
        return new Trade(
            tradeId,
            fundId,
            securityId,
            tradeType,
            quantity,
            price,
            currency,
            tradeDate,
            settlementDate,
            counterparty
        );
    }
    
    private String generateTradeId() {
        return "TRD-" + UUID.randomUUID().toString();
    }
}

