package dev.mars.peegeeq.examples.fundscustody.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Impact analysis of a NAV correction.
 * 
 * <p>Compares NAV as originally reported vs corrected NAV to determine:
 * <ul>
 *   <li>Absolute difference in NAV per share</li>
 *   <li>Percentage error</li>
 *   <li>Whether investor compensation is required</li>
 * </ul>
 */
public record NAVCorrectionImpact(
    String fundId,
    LocalDate navDate,
    BigDecimal reportedNAV,
    BigDecimal correctedNAV,
    BigDecimal difference,
    BigDecimal percentageError,
    boolean requiresInvestorCompensation
) {
    /**
     * Standard threshold for investor compensation (0.5% = 0.005).
     */
    private static final BigDecimal COMPENSATION_THRESHOLD = new BigDecimal("0.005");
    
    /**
     * Create impact analysis from reported and corrected NAV.
     */
    public static NAVCorrectionImpact analyze(
            String fundId,
            LocalDate navDate,
            BigDecimal reportedNAV,
            BigDecimal correctedNAV) {
        
        BigDecimal difference = correctedNAV.subtract(reportedNAV);
        BigDecimal percentageError = reportedNAV.compareTo(BigDecimal.ZERO) > 0
            ? difference.divide(reportedNAV, 6, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        
        boolean requiresCompensation = percentageError.abs()
            .compareTo(COMPENSATION_THRESHOLD) > 0;
        
        return new NAVCorrectionImpact(
            fundId,
            navDate,
            reportedNAV,
            correctedNAV,
            difference,
            percentageError,
            requiresCompensation
        );
    }
    
    /**
     * Check if NAV increased (positive correction).
     */
    public boolean isPositiveCorrection() {
        return difference.compareTo(BigDecimal.ZERO) > 0;
    }
    
    /**
     * Check if NAV decreased (negative correction).
     */
    public boolean isNegativeCorrection() {
        return difference.compareTo(BigDecimal.ZERO) < 0;
    }
    
    /**
     * Get absolute percentage error.
     */
    public BigDecimal getAbsolutePercentageError() {
        return percentageError.abs();
    }
}

