package dev.mars.peegeeq.examples.fundscustody.model;

import dev.mars.peegeeq.examples.fundscustody.domain.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Net Asset Value (NAV) snapshot for a fund.
 * 
 * <p>Captures NAV calculation at a specific point in time:
 * <ul>
 *   <li>NAV date (valid time) - the business date for the NAV</li>
 *   <li>Calculation time (transaction time) - when NAV was calculated</li>
 *   <li>NAV per share - the calculated NAV value</li>
 *   <li>Total assets, liabilities, shares outstanding</li>
 * </ul>
 */
public record NAVSnapshot(
    String fundId,
    LocalDate navDate,
    Instant calculationTime,
    BigDecimal totalAssets,
    BigDecimal totalLiabilities,
    BigDecimal netAssets,
    BigDecimal sharesOutstanding,
    BigDecimal navPerShare,
    Currency currency
) {
    /**
     * Create a NAV snapshot with calculated values.
     */
    public static NAVSnapshot create(
            String fundId,
            LocalDate navDate,
            Instant calculationTime,
            BigDecimal totalAssets,
            BigDecimal totalLiabilities,
            BigDecimal sharesOutstanding,
            Currency currency) {
        
        BigDecimal netAssets = totalAssets.subtract(totalLiabilities);
        BigDecimal navPerShare = sharesOutstanding.compareTo(BigDecimal.ZERO) > 0
            ? netAssets.divide(sharesOutstanding, 6, java.math.RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        
        return new NAVSnapshot(
            fundId,
            navDate,
            calculationTime,
            totalAssets,
            totalLiabilities,
            netAssets,
            sharesOutstanding,
            navPerShare,
            currency
        );
    }
}

