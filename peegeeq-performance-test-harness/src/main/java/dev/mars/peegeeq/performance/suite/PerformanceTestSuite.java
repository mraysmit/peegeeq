package dev.mars.peegeeq.performance.suite;

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

import dev.mars.peegeeq.performance.config.PerformanceTestConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Base interface for performance test suites.
 * 
 * Each performance test suite implements this interface to provide
 * standardized execution, reporting, and cleanup capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-13
 * @version 1.0
 */
public interface PerformanceTestSuite {
    
    /**
     * Get the name of this test suite.
     */
    String getName();
    
    /**
     * Get a description of what this test suite measures.
     */
    String getDescription();
    
    /**
     * Execute the performance test suite with the given configuration.
     */
    CompletableFuture<Results> execute(PerformanceTestConfig config);
    
    /**
     * Clean up any resources used by this test suite.
     */
    default void cleanup() {
        // Default implementation does nothing
    }
    
    /**
     * Results container for performance test execution.
     */
    class Results {
        private int totalTests = 0;
        private int successfulTests = 0;
        private int failedTests = 0;
        private final List<String> failures = new ArrayList<>();
        private final Map<String, Object> metrics = new HashMap<>();
        private Instant startTime;
        private Instant endTime;
        
        // Specific result types
        private BitemporalResults bitemporalResults;
        private OutboxResults outboxResults;
        private NativeResults nativeResults;
        private DatabaseResults databaseResults;
        
        public Results() {
            this.startTime = Instant.now();
        }
        
        public void addSuccess() {
            totalTests++;
            successfulTests++;
        }
        
        public void addFailure(String testName, Throwable error) {
            totalTests++;
            failedTests++;
            failures.add(testName + ": " + error.getMessage());
        }
        
        public void addMetric(String name, Object value) {
            metrics.put(name, value);
        }
        
        public void setEndTime() {
            this.endTime = Instant.now();
        }
        
        public Duration getTotalDuration() {
            return endTime != null ? Duration.between(startTime, endTime) : Duration.ZERO;
        }
        
        public boolean hasFailures() {
            return failedTests > 0;
        }
        
        public void merge(Results other) {
            this.totalTests += other.totalTests;
            this.successfulTests += other.successfulTests;
            this.failedTests += other.failedTests;
            this.failures.addAll(other.failures);
            this.metrics.putAll(other.metrics);
            
            // Merge specific results
            if (other.bitemporalResults != null) {
                this.bitemporalResults = other.bitemporalResults;
            }
            if (other.outboxResults != null) {
                this.outboxResults = other.outboxResults;
            }
            if (other.nativeResults != null) {
                this.nativeResults = other.nativeResults;
            }
            if (other.databaseResults != null) {
                this.databaseResults = other.databaseResults;
            }
        }
        
        // Getters
        public int getTotalTests() { return totalTests; }
        public int getSuccessfulTests() { return successfulTests; }
        public int getFailedTests() { return failedTests; }
        public List<String> getFailures() { return new ArrayList<>(failures); }
        public Map<String, Object> getMetrics() { return new HashMap<>(metrics); }
        public Instant getStartTime() { return startTime; }
        public Instant getEndTime() { return endTime; }
        
        // Specific result getters
        public BitemporalResults getBitemporalResults() { return bitemporalResults; }
        public OutboxResults getOutboxResults() { return outboxResults; }
        public NativeResults getNativeResults() { return nativeResults; }
        public DatabaseResults getDatabaseResults() { return databaseResults; }
        
        // Specific result setters
        public void setBitemporalResults(BitemporalResults results) { this.bitemporalResults = results; }
        public void setOutboxResults(OutboxResults results) { this.outboxResults = results; }
        public void setNativeResults(NativeResults results) { this.nativeResults = results; }
        public void setDatabaseResults(DatabaseResults results) { this.databaseResults = results; }
    }
    
    /**
     * Bi-temporal specific performance results.
     */
    class BitemporalResults {
        private final double queryThroughput;
        private final double appendThroughput;
        private final double averageQueryLatency;
        private final double averageAppendLatency;
        private final int totalEvents;
        
        public BitemporalResults(double queryThroughput, double appendThroughput, 
                               double averageQueryLatency, double averageAppendLatency, int totalEvents) {
            this.queryThroughput = queryThroughput;
            this.appendThroughput = appendThroughput;
            this.averageQueryLatency = averageQueryLatency;
            this.averageAppendLatency = averageAppendLatency;
            this.totalEvents = totalEvents;
        }
        
        public double getQueryThroughput() { return queryThroughput; }
        public double getAppendThroughput() { return appendThroughput; }
        public double getAverageQueryLatency() { return averageQueryLatency; }
        public double getAverageAppendLatency() { return averageAppendLatency; }
        public int getTotalEvents() { return totalEvents; }
    }
    
    /**
     * Outbox pattern specific performance results.
     */
    class OutboxResults {
        private final double sendThroughput;
        private final double totalThroughput;
        private final double averageLatency;
        private final int totalMessages;
        
        public OutboxResults(double sendThroughput, double totalThroughput, 
                           double averageLatency, int totalMessages) {
            this.sendThroughput = sendThroughput;
            this.totalThroughput = totalThroughput;
            this.averageLatency = averageLatency;
            this.totalMessages = totalMessages;
        }
        
        public double getSendThroughput() { return sendThroughput; }
        public double getTotalThroughput() { return totalThroughput; }
        public double getAverageLatency() { return averageLatency; }
        public int getTotalMessages() { return totalMessages; }
    }
    
    /**
     * Native queue specific performance results.
     */
    class NativeResults {
        private final double throughput;
        private final double averageLatency;
        private final double maxLatency;
        private final double minLatency;
        private final int totalMessages;
        
        public NativeResults(double throughput, double averageLatency, double maxLatency, 
                           double minLatency, int totalMessages) {
            this.throughput = throughput;
            this.averageLatency = averageLatency;
            this.maxLatency = maxLatency;
            this.minLatency = minLatency;
            this.totalMessages = totalMessages;
        }
        
        public double getThroughput() { return throughput; }
        public double getAverageLatency() { return averageLatency; }
        public double getMaxLatency() { return maxLatency; }
        public double getMinLatency() { return minLatency; }
        public int getTotalMessages() { return totalMessages; }
    }
    
    /**
     * Database specific performance results.
     */
    class DatabaseResults {
        private final double queryThroughput;
        private final double averageLatency;
        private final double connectionPoolUtilization;
        private final int totalQueries;
        
        public DatabaseResults(double queryThroughput, double averageLatency, 
                             double connectionPoolUtilization, int totalQueries) {
            this.queryThroughput = queryThroughput;
            this.averageLatency = averageLatency;
            this.connectionPoolUtilization = connectionPoolUtilization;
            this.totalQueries = totalQueries;
        }
        
        public double getQueryThroughput() { return queryThroughput; }
        public double getAverageLatency() { return averageLatency; }
        public double getConnectionPoolUtilization() { return connectionPoolUtilization; }
        public int getTotalQueries() { return totalQueries; }
    }
}
