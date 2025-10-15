package dev.mars.peegeeq.examples.fundscustody.model;

import dev.mars.peegeeq.examples.fundscustody.domain.Position;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Regulatory report snapshot.
 * 
 * <p>Captures the exact state of a fund as it was known and reported
 * on a specific regulatory reporting date.
 * 
 * <p>Critical for regulatory audits - must prove what was reported and when.
 */
public record RegulatoryReport(
    String fundId,
    LocalDate reportingDate,
    Instant asOfTransactionTime,
    String reportType,  // "AIFMD", "MiFID_II", "EMIR", etc.
    NAVSnapshot navSnapshot,
    List<Position> positions,
    List<TradeChange> tradesInPeriod,
    int totalTrades,
    int totalPositions
) {
    /**
     * Create a regulatory report.
     */
    public static RegulatoryReport create(
            String fundId,
            LocalDate reportingDate,
            Instant asOfTransactionTime,
            String reportType,
            NAVSnapshot navSnapshot,
            List<Position> positions,
            List<TradeChange> tradesInPeriod) {
        
        return new RegulatoryReport(
            fundId,
            reportingDate,
            asOfTransactionTime,
            reportType,
            navSnapshot,
            positions,
            tradesInPeriod,
            tradesInPeriod.size(),
            positions.size()
        );
    }
    
    /**
     * Check if this is an AIFMD report.
     */
    public boolean isAIFMD() {
        return "AIFMD".equals(reportType);
    }
    
    /**
     * Check if this is a MiFID II report.
     */
    public boolean isMiFIDII() {
        return "MiFID_II".equals(reportType);
    }
}

