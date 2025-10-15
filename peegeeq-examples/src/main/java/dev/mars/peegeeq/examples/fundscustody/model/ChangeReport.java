package dev.mars.peegeeq.examples.fundscustody.model;

import java.time.Instant;
import java.util.List;

/**
 * Report of changes between two transaction times.
 * 
 * <p>Captures what changed in the system between two points in time:
 * <ul>
 *   <li>New events added</li>
 *   <li>Events corrected/modified</li>
 *   <li>Time period analyzed</li>
 * </ul>
 */
public record ChangeReport(
    String fundId,
    Instant fromTransactionTime,
    Instant toTransactionTime,
    List<TradeChange> newTrades,
    List<TradeChange> correctedTrades,
    int totalChanges
) {
    /**
     * Create a change report.
     */
    public static ChangeReport create(
            String fundId,
            Instant fromTransactionTime,
            Instant toTransactionTime,
            List<TradeChange> newTrades,
            List<TradeChange> correctedTrades) {
        
        int totalChanges = newTrades.size() + correctedTrades.size();
        
        return new ChangeReport(
            fundId,
            fromTransactionTime,
            toTransactionTime,
            newTrades,
            correctedTrades,
            totalChanges
        );
    }
    
    /**
     * Check if there were any changes.
     */
    public boolean hasChanges() {
        return totalChanges > 0;
    }
    
    /**
     * Check if there were new trades.
     */
    public boolean hasNewTrades() {
        return !newTrades.isEmpty();
    }
    
    /**
     * Check if there were corrections.
     */
    public boolean hasCorrections() {
        return !correctedTrades.isEmpty();
    }
}

