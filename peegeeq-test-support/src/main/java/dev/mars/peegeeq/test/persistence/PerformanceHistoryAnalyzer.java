package dev.mars.peegeeq.test.persistence;

import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile;
import dev.mars.peegeeq.test.metrics.PerformanceSnapshot;
import dev.mars.peegeeq.test.metrics.PerformanceComparison;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzer for historical performance data trends and comparisons.
 * 
 * This class provides methods to analyze performance trends over time,
 * detect regressions against historical baselines, and generate insights
 * about performance characteristics.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-18
 * @version 1.0
 */
public class PerformanceHistoryAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(PerformanceHistoryAnalyzer.class);
    
    private final PerformanceHistoryRepository repository;
    
    public PerformanceHistoryAnalyzer(PerformanceHistoryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
    }
    
    /**
     * Compare current performance against historical baseline.
     */
    public HistoricalComparison compareAgainstHistoricalBaseline(PerformanceSnapshot currentSnapshot, int baselineCount) {
        List<PerformanceSnapshot> historicalSnapshots = repository.getHistoricalSnapshots(
            currentSnapshot.getTestName(), 
            currentSnapshot.getProfile(), 
            baselineCount
        );
        
        if (historicalSnapshots.isEmpty()) {
            logger.info("No historical data found for test: {} profile: {}", 
                       currentSnapshot.getTestName(), currentSnapshot.getProfile());
            return new HistoricalComparison(currentSnapshot, Collections.emptyList(), null, TrendDirection.UNKNOWN);
        }
        
        // Calculate baseline statistics
        PerformanceBaseline baseline = calculateBaseline(historicalSnapshots);
        
        // Compare current against baseline
        PerformanceComparison comparison = new PerformanceComparison(baseline.getRepresentativeSnapshot(), currentSnapshot);
        
        // Determine trend direction
        TrendDirection trend = determineTrendDirection(currentSnapshot, historicalSnapshots);
        
        logger.info("Historical comparison for {} ({}): {} vs baseline of {} runs", 
                   currentSnapshot.getTestName(), currentSnapshot.getProfile().getDisplayName(),
                   formatPerformance(currentSnapshot), historicalSnapshots.size());
        
        return new HistoricalComparison(currentSnapshot, historicalSnapshots, comparison, trend);
    }
    
    /**
     * Calculate performance baseline from historical data.
     */
    private PerformanceBaseline calculateBaseline(List<PerformanceSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            throw new IllegalArgumentException("Cannot calculate baseline from empty snapshots");
        }
        
        // Calculate statistics
        double avgDurationMs = snapshots.stream()
            .mapToLong(s -> s.getDuration().toMillis())
            .average()
            .orElse(0.0);
            
        double avgThroughput = snapshots.stream()
            .mapToDouble(PerformanceSnapshot::getThroughput)
            .average()
            .orElse(0.0);
            
        double minDurationMs = snapshots.stream()
            .mapToLong(s -> s.getDuration().toMillis())
            .min()
            .orElse(0L);
            
        double maxDurationMs = snapshots.stream()
            .mapToLong(s -> s.getDuration().toMillis())
            .max()
            .orElse(0L);
            
        double minThroughput = snapshots.stream()
            .mapToDouble(PerformanceSnapshot::getThroughput)
            .min()
            .orElse(0.0);
            
        double maxThroughput = snapshots.stream()
            .mapToDouble(PerformanceSnapshot::getThroughput)
            .max()
            .orElse(0.0);
        
        // Create representative snapshot using average values
        PerformanceSnapshot representative = snapshots.get(0);
        Duration avgDuration = Duration.ofMillis((long) avgDurationMs);
        Map<String, Object> avgMetrics = new HashMap<>();
        avgMetrics.put("throughput", avgThroughput);
        
        PerformanceSnapshot representativeSnapshot = new PerformanceSnapshot(
            representative.getTestName(),
            representative.getProfile(),
            representative.getStartTime(),
            representative.getStartTime().plus(avgDuration),
            avgDuration,
            true,
            avgMetrics
        );
        
        return new PerformanceBaseline(
            snapshots.size(),
            avgDurationMs, minDurationMs, maxDurationMs,
            avgThroughput, minThroughput, maxThroughput,
            representativeSnapshot
        );
    }
    
    /**
     * Determine trend direction based on recent performance.
     */
    private TrendDirection determineTrendDirection(PerformanceSnapshot current, List<PerformanceSnapshot> historical) {
        if (historical.size() < 3) {
            return TrendDirection.INSUFFICIENT_DATA;
        }
        
        // Take last 3 snapshots for trend analysis
        List<PerformanceSnapshot> recent = historical.stream()
            .limit(3)
            .collect(Collectors.toList());
        
        // Calculate trend in throughput (higher is better)
        double[] throughputs = recent.stream()
            .mapToDouble(PerformanceSnapshot::getThroughput)
            .toArray();
        
        // Simple linear trend calculation
        double trend = calculateLinearTrend(throughputs);
        
        if (trend > 0.05) { // 5% improvement trend
            return TrendDirection.IMPROVING;
        } else if (trend < -0.05) { // 5% degradation trend
            return TrendDirection.DEGRADING;
        } else {
            return TrendDirection.STABLE;
        }
    }
    
    /**
     * Calculate linear trend coefficient.
     */
    private double calculateLinearTrend(double[] values) {
        if (values.length < 2) return 0.0;
        
        int n = values.length;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += values[i];
            sumXY += i * values[i];
            sumX2 += i * i;
        }
        
        double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
        double avgY = sumY / n;
        
        return avgY != 0 ? slope / avgY : 0.0; // Normalized slope
    }
    
    /**
     * Format performance for display.
     */
    private String formatPerformance(PerformanceSnapshot snapshot) {
        return String.format("%.2f ops/sec (%dms)", snapshot.getThroughput(), snapshot.getDuration().toMillis());
    }
    
    /**
     * Generate performance insights report.
     */
    public String generateInsightsReport(String testName, PerformanceProfile profile) {
        Map<String, Object> summary = repository.getPerformanceSummary(testName, profile);
        
        if (summary.isEmpty()) {
            return "No historical performance data available for " + testName + " (" + profile.getDisplayName() + ")";
        }
        
        StringBuilder report = new StringBuilder();
        report.append("## Performance Insights: ").append(testName).append(" (").append(profile.getDisplayName()).append(")\n\n");
        
        report.append("**Historical Summary:**\n");
        report.append("- Total Runs: ").append(summary.get("totalRuns")).append("\n");
        report.append("- Average Duration: ").append(String.format("%.1f ms", (Double) summary.get("avgDurationMs"))).append("\n");
        report.append("- Duration Range: ").append(String.format("%.1f - %.1f ms", 
                     (Double) summary.get("minDurationMs"), (Double) summary.get("maxDurationMs"))).append("\n");
        report.append("- Average Throughput: ").append(String.format("%.2f ops/sec", (Double) summary.get("avgThroughput"))).append("\n");
        report.append("- Throughput Range: ").append(String.format("%.2f - %.2f ops/sec", 
                     (Double) summary.get("minThroughput"), (Double) summary.get("maxThroughput"))).append("\n");
        report.append("- First Run: ").append(summary.get("firstRun")).append("\n");
        report.append("- Last Run: ").append(summary.get("lastRun")).append("\n");
        
        return report.toString();
    }
    
    /**
     * Performance baseline statistics.
     */
    public static class PerformanceBaseline {
        private final int sampleCount;
        private final double avgDurationMs, minDurationMs, maxDurationMs;
        private final double avgThroughput, minThroughput, maxThroughput;
        private final PerformanceSnapshot representativeSnapshot;
        
        public PerformanceBaseline(int sampleCount, double avgDurationMs, double minDurationMs, double maxDurationMs,
                                 double avgThroughput, double minThroughput, double maxThroughput,
                                 PerformanceSnapshot representativeSnapshot) {
            this.sampleCount = sampleCount;
            this.avgDurationMs = avgDurationMs;
            this.minDurationMs = minDurationMs;
            this.maxDurationMs = maxDurationMs;
            this.avgThroughput = avgThroughput;
            this.minThroughput = minThroughput;
            this.maxThroughput = maxThroughput;
            this.representativeSnapshot = representativeSnapshot;
        }
        
        // Getters
        public int getSampleCount() { return sampleCount; }
        public double getAvgDurationMs() { return avgDurationMs; }
        public double getMinDurationMs() { return minDurationMs; }
        public double getMaxDurationMs() { return maxDurationMs; }
        public double getAvgThroughput() { return avgThroughput; }
        public double getMinThroughput() { return minThroughput; }
        public double getMaxThroughput() { return maxThroughput; }
        public PerformanceSnapshot getRepresentativeSnapshot() { return representativeSnapshot; }
    }
    
    /**
     * Historical comparison result.
     */
    public static class HistoricalComparison {
        private final PerformanceSnapshot currentSnapshot;
        private final List<PerformanceSnapshot> historicalSnapshots;
        private final PerformanceComparison comparison;
        private final TrendDirection trend;
        
        public HistoricalComparison(PerformanceSnapshot currentSnapshot, List<PerformanceSnapshot> historicalSnapshots,
                                  PerformanceComparison comparison, TrendDirection trend) {
            this.currentSnapshot = currentSnapshot;
            this.historicalSnapshots = new ArrayList<>(historicalSnapshots);
            this.comparison = comparison;
            this.trend = trend;
        }
        
        // Getters
        public PerformanceSnapshot getCurrentSnapshot() { return currentSnapshot; }
        public List<PerformanceSnapshot> getHistoricalSnapshots() { return new ArrayList<>(historicalSnapshots); }
        public PerformanceComparison getComparison() { return comparison; }
        public TrendDirection getTrend() { return trend; }
        public boolean hasHistoricalData() { return !historicalSnapshots.isEmpty(); }
    }
    
    /**
     * Trend direction enumeration.
     */
    public enum TrendDirection {
        IMPROVING("Performance is improving over time"),
        DEGRADING("Performance is degrading over time"),
        STABLE("Performance is stable"),
        INSUFFICIENT_DATA("Insufficient data for trend analysis"),
        UNKNOWN("Trend direction unknown");
        
        private final String description;
        
        TrendDirection(String description) {
            this.description = description;
        }
        
        public String getDescription() { return description; }
    }
}
