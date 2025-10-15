package dev.mars.peegeeq.examples.fundscustody.model;

import dev.mars.peegeeq.examples.fundscustody.domain.TradeType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Represents a change to a trade (new or corrected).
 */
public record TradeChange(
    String tradeId,
    String fundId,
    String securityId,
    LocalDate tradeDate,
    TradeType tradeType,
    BigDecimal quantity,
    BigDecimal price,
    String changeType,  // "NEW" or "CORRECTED"
    Instant transactionTime
) {
    /**
     * Create a new trade change.
     */
    public static TradeChange newTrade(
            String tradeId,
            String fundId,
            String securityId,
            LocalDate tradeDate,
            TradeType tradeType,
            BigDecimal quantity,
            BigDecimal price,
            Instant transactionTime) {
        
        return new TradeChange(
            tradeId,
            fundId,
            securityId,
            tradeDate,
            tradeType,
            quantity,
            price,
            "NEW",
            transactionTime
        );
    }
    
    /**
     * Create a corrected trade change.
     */
    public static TradeChange correctedTrade(
            String tradeId,
            String fundId,
            String securityId,
            LocalDate tradeDate,
            TradeType tradeType,
            BigDecimal quantity,
            BigDecimal price,
            Instant transactionTime) {
        
        return new TradeChange(
            tradeId,
            fundId,
            securityId,
            tradeDate,
            tradeType,
            quantity,
            price,
            "CORRECTED",
            transactionTime
        );
    }
    
    /**
     * Check if this is a new trade.
     */
    public boolean isNew() {
        return "NEW".equals(changeType);
    }
    
    /**
     * Check if this is a correction.
     */
    public boolean isCorrection() {
        return "CORRECTED".equals(changeType);
    }
}

