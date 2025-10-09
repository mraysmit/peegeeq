package dev.mars.peegeeq.test.metrics;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Comparison between two performance snapshots.
 * 
 * This class provides detailed comparison metrics between two test executions,
 * typically with different performance profiles, to analyze performance
 * differences and identify regressions or improvements.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-18
 * @version 1.0
 */
public class PerformanceComparison {
    private final PerformanceSnapshot baseline;
    private final PerformanceSnapshot comparison;
    private final ComparisonMetrics metrics;
    
    /**
     * Create a new PerformanceComparison.
     * 
     * @param baseline the baseline performance snapshot
     * @param comparison the comparison performance snapshot
     */
    public PerformanceComparison(PerformanceSnapshot baseline, PerformanceSnapshot comparison) {
        this.baseline = Objects.requireNonNull(baseline, "baseline cannot be null");
        this.comparison = Objects.requireNonNull(comparison, "comparison cannot be null");
        this.metrics = calculateMetrics();
    }
    
    /**
     * Get the baseline snapshot.
     * 
     * @return the baseline snapshot
     */
    public PerformanceSnapshot getBaseline() {
        return baseline;
    }
    
    /**
     * Get the comparison snapshot.
     * 
     * @return the comparison snapshot
     */
    public PerformanceSnapshot getComparison() {
        return comparison;
    }
    
    /**
     * Get the comparison metrics.
     * 
     * @return the comparison metrics
     */
    public ComparisonMetrics getMetrics() {
        return metrics;
    }
    
    /**
     * Check if the comparison shows a performance improvement.
     * 
     * @return true if comparison is faster than baseline
     */
    public boolean isImprovement() {
        return metrics.getDurationImprovementPercent() > 0;
    }
    
    /**
     * Check if the comparison shows a performance regression.
     * 
     * @param regressionThresholdPercent the threshold for considering a regression
     * @return true if comparison is significantly slower than baseline
     */
    public boolean isRegression(double regressionThresholdPercent) {
        return metrics.getDurationImprovementPercent() < -regressionThresholdPercent;
    }
    
    /**
     * Check if the performance difference is significant.
     * 
     * @param significanceThresholdPercent the threshold for significance
     * @return true if the difference is significant
     */
    public boolean isSignificantDifference(double significanceThresholdPercent) {
        return Math.abs(metrics.getDurationImprovementPercent()) > significanceThresholdPercent;
    }
    
    /**
     * Get a summary of the comparison.
     * 
     * @return a human-readable summary
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("PerformanceComparison{");
        sb.append("baseline=").append(baseline.getProfile().getDisplayName());
        sb.append(", comparison=").append(comparison.getProfile().getDisplayName());
        sb.append(", durationChange=").append(String.format("%.2f%%", metrics.getDurationImprovementPercent()));
        sb.append(", throughputChange=").append(String.format("%.2f%%", metrics.getThroughputImprovementPercent()));
        sb.append(", improvement=").append(isImprovement());
        sb.append("}");
        return sb.toString();
    }
    
    private ComparisonMetrics calculateMetrics() {
        // Duration comparison
        long baselineDurationMs = baseline.getDurationMs();
        long comparisonDurationMs = comparison.getDurationMs();
        double durationImprovementPercent = 0.0;
        
        if (baselineDurationMs > 0) {
            durationImprovementPercent = ((double) (baselineDurationMs - comparisonDurationMs) / baselineDurationMs) * 100.0;
        }
        
        // Throughput comparison
        double baselineThroughput = baseline.getThroughput();
        double comparisonThroughput = comparison.getThroughput();
        double throughputImprovementPercent = 0.0;
        
        if (baselineThroughput > 0) {
            throughputImprovementPercent = ((comparisonThroughput - baselineThroughput) / baselineThroughput) * 100.0;
        }
        
        // Additional metrics comparison
        Map<String, Double> additionalComparisons = new HashMap<>();
        Map<String, Object> baselineMetrics = baseline.getAdditionalMetrics();
        Map<String, Object> comparisonMetrics = comparison.getAdditionalMetrics();
        
        for (String key : baselineMetrics.keySet()) {
            if (comparisonMetrics.containsKey(key)) {
                Object baselineValue = baselineMetrics.get(key);
                Object comparisonValue = comparisonMetrics.get(key);
                
                if (baselineValue instanceof Number && comparisonValue instanceof Number) {
                    double baselineNum = ((Number) baselineValue).doubleValue();
                    double comparisonNum = ((Number) comparisonValue).doubleValue();
                    
                    if (baselineNum != 0) {
                        double improvementPercent = ((comparisonNum - baselineNum) / baselineNum) * 100.0;
                        additionalComparisons.put(key, improvementPercent);
                    }
                }
            }
        }
        
        return new ComparisonMetrics(
            durationImprovementPercent,
            throughputImprovementPercent,
            additionalComparisons
        );
    }
    
    @Override
    public String toString() {
        return getSummary();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PerformanceComparison that = (PerformanceComparison) o;
        return Objects.equals(baseline, that.baseline) &&
               Objects.equals(comparison, that.comparison);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(baseline, comparison);
    }
    
    /**
     * Metrics calculated from the comparison.
     */
    public static class ComparisonMetrics {
        private final double durationImprovementPercent;
        private final double throughputImprovementPercent;
        private final Map<String, Double> additionalComparisons;
        
        public ComparisonMetrics(double durationImprovementPercent, 
                               double throughputImprovementPercent,
                               Map<String, Double> additionalComparisons) {
            this.durationImprovementPercent = durationImprovementPercent;
            this.throughputImprovementPercent = throughputImprovementPercent;
            this.additionalComparisons = new HashMap<>(additionalComparisons);
        }
        
        /**
         * Get the duration improvement percentage.
         * Positive values indicate the comparison is faster than baseline.
         * 
         * @return duration improvement percentage
         */
        public double getDurationImprovementPercent() {
            return durationImprovementPercent;
        }
        
        /**
         * Get the throughput improvement percentage.
         * Positive values indicate the comparison has higher throughput than baseline.
         * 
         * @return throughput improvement percentage
         */
        public double getThroughputImprovementPercent() {
            return throughputImprovementPercent;
        }
        
        /**
         * Get additional metric comparisons.
         * 
         * @return map of metric name to improvement percentage
         */
        public Map<String, Double> getAdditionalComparisons() {
            return new HashMap<>(additionalComparisons);
        }
        
        /**
         * Get a specific additional comparison.
         * 
         * @param metricName the metric name
         * @return the improvement percentage, or null if not found
         */
        public Double getAdditionalComparison(String metricName) {
            return additionalComparisons.get(metricName);
        }
        
        /**
         * Check if a specific metric shows improvement.
         * 
         * @param metricName the metric name
         * @return true if the metric shows improvement
         */
        public boolean isMetricImproved(String metricName) {
            Double improvement = additionalComparisons.get(metricName);
            return improvement != null && improvement > 0;
        }
        
        @Override
        public String toString() {
            return "ComparisonMetrics{" +
                   "durationImprovement=" + String.format("%.2f%%", durationImprovementPercent) +
                   ", throughputImprovement=" + String.format("%.2f%%", throughputImprovementPercent) +
                   ", additionalComparisons=" + additionalComparisons +
                   "}";
        }
    }
}
