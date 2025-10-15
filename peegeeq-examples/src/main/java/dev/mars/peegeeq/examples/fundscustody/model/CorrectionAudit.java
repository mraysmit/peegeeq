package dev.mars.peegeeq.examples.fundscustody.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Audit record for a trade correction.
 * 
 * <p>Captures the details of a correction including:
 * <ul>
 *   <li>What was corrected (original vs corrected values)</li>
 *   <li>When it was corrected (transaction time)</li>
 *   <li>Why it was corrected (reason)</li>
 *   <li>Who corrected it (corrected by)</li>
 * </ul>
 */
public record CorrectionAudit(
    String tradeId,
    String fundId,
    String securityId,
    LocalDate tradeDate,
    BigDecimal originalQuantity,
    BigDecimal correctedQuantity,
    BigDecimal originalPrice,
    BigDecimal correctedPrice,
    String reason,
    String correctedBy,
    Instant correctedAt
) {
    /**
     * Check if quantity was corrected.
     */
    public boolean isQuantityCorrected() {
        return originalQuantity.compareTo(correctedQuantity) != 0;
    }
    
    /**
     * Check if price was corrected.
     */
    public boolean isPriceCorrected() {
        return originalPrice.compareTo(correctedPrice) != 0;
    }
    
    /**
     * Get the quantity difference (corrected - original).
     */
    public BigDecimal getQuantityDifference() {
        return correctedQuantity.subtract(originalQuantity);
    }
    
    /**
     * Get the price difference (corrected - original).
     */
    public BigDecimal getPriceDifference() {
        return correctedPrice.subtract(originalPrice);
    }
}

