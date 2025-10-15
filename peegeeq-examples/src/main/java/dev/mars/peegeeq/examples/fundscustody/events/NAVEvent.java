package dev.mars.peegeeq.examples.fundscustody.events;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Event representing a NAV calculation.
 * 
 * <p>Stored with:
 * <ul>
 *   <li>Valid Time = NAV date (the business date for the NAV)</li>
 *   <li>Transaction Time = Calculation time (when NAV was calculated)</li>
 *   <li>Aggregate ID = Fund ID</li>
 * </ul>
 */
public record NAVEvent(
    String fundId,
    LocalDate navDate,
    BigDecimal totalAssets,
    BigDecimal totalLiabilities,
    BigDecimal sharesOutstanding,
    BigDecimal navPerShare,
    String currency,
    String calculatedBy
) {
}

