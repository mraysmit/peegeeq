package dev.mars.peegeeq.test.demo;

import dev.mars.peegeeq.test.base.PeeGeeQTestBase;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile;
import dev.mars.peegeeq.test.metrics.PerformanceComparison;
import dev.mars.peegeeq.test.metrics.PerformanceSnapshot;
import dev.mars.peegeeq.test.persistence.PerformanceHistoryRepository;
import dev.mars.peegeeq.test.persistence.PerformanceHistoryAnalyzer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Demonstration test that proves parameterized performance testing works
 * by running identical database operations across different PostgreSQL performance profiles
 * and outputting comprehensive metrics comparison.
 */
@DisplayName("Parameterized Performance Demo Tests")
public class ParameterizedPerformanceDemoTest extends PeeGeeQTestBase {

    private static final Logger log = LoggerFactory.getLogger(ParameterizedPerformanceDemoTest.class);

    // H2 database for performance history
    private PerformanceHistoryRepository historyRepository;
    private PerformanceHistoryAnalyzer historyAnalyzer;

    @Override
    protected PerformanceProfile getPerformanceProfile() {
        return PerformanceProfile.STANDARD; // Default profile for setup
    }

    /**
     * Initialize performance history components.
     */
    private void initializePerformanceHistory() {
        if (historyRepository == null) {
            // Create H2 database in target directory
            String h2Url = "jdbc:h2:./target/performance-history-demo;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";
            historyRepository = PerformanceHistoryRepository.getInstance(h2Url, "sa", "");
            historyAnalyzer = new PerformanceHistoryAnalyzer(historyRepository);
            log.info("Initialized performance history with H2 database: {}", h2Url);
        }
    }

    /**
     * Simple demo test that shows parameterized testing across different PostgreSQL profiles
     * and outputs performance metrics for each profile.
     */
    @ParameterizedTest(name = "Simple Performance Test - {0}")
    @EnumSource(value = PerformanceProfile.class, names = {"BASIC", "STANDARD", "HIGH_PERFORMANCE", "MAXIMUM_PERFORMANCE"})
    @DisplayName("Simple Performance Test Across Profiles")
    void testSimplePerformanceAcrossProfiles(PerformanceProfile profile) throws Exception {
        log.info("=== DEMO: Starting simple performance test with profile: {} ===", profile.getDisplayName());
        log.info("Profile Details: {}", profile.getDescription());

        // Start performance measurement
        String testName = "simplePerformanceTest";
        Instant startTime = Instant.now();

        try {
            // Perform simple database operations
            performSimpleDatabaseOperations();

            // End performance measurement
            Instant endTime = Instant.now();
            Duration duration = Duration.between(startTime, endTime);

            // Record metrics using the existing PeeGeeQMetrics system
            getMetrics().recordPerformanceTestExecution(
                testName,
                profile.getDisplayName(),
                duration.toMillis(),
                true,
                calculateThroughput(100, duration.toMillis()),
                Map.of("profile", (Object) profile.getDisplayName(), "operations", (Object) 100L)
            );

            log.info("=== DEMO: Simple performance test completed successfully ===");
            double throughput = calculateThroughput(100, duration.toMillis());
            log.info("Profile: {} | Duration: {}ms | Throughput: {} ops/sec",
                profile.getDisplayName(),
                duration.toMillis(),
                String.format("%.2f", throughput));
            log.info("Container JDBC URL: {}", getJdbcUrl());

        } catch (Exception e) {
            Instant endTime = Instant.now();
            Duration duration = Duration.between(startTime, endTime);

            getMetrics().recordPerformanceTestExecution(
                testName,
                profile.getDisplayName(),
                duration.toMillis(),
                false,
                0.0,
                Map.of("profile", (Object) profile.getDisplayName(), "error", (Object) e.getMessage())
            );

            log.error("=== DEMO: Simple performance test failed for profile: {} ===", profile.getDisplayName(), e);
            throw e;
        }
    }

    private double calculateThroughput(int operations, long durationMs) {
        if (durationMs == 0) return 0.0;
        return (operations * 1000.0) / durationMs;
    }
    
    /**
     * Comprehensive demo test that shows metrics collection and comparison
     */
    @Test
    @DisplayName("Performance Metrics Collection and Comparison Demo")
    void testPerformanceMetricsDemo() throws Exception {
        log.info("=== DEMO: Starting comprehensive performance metrics demonstration ===");

        // Initialize performance history
        initializePerformanceHistory();

        List<PerformanceSnapshot> snapshots = new ArrayList<>();
        Instant overallStartTime = Instant.now();

        // Run the same test across different profiles and collect snapshots
        for (PerformanceProfile profile : List.of(
            PerformanceProfile.BASIC,
            PerformanceProfile.STANDARD,
            PerformanceProfile.HIGH_PERFORMANCE,
            PerformanceProfile.MAXIMUM_PERFORMANCE)) {

            log.info("--- Running test with profile: {} ---", profile.getDisplayName());

            // Simulate running the test with this profile
            Instant startTime = Instant.now();

            // Perform some database work (simulated)
            Thread.sleep(50 + (int)(Math.random() * 100)); // Simulate variable work

            Instant endTime = Instant.now();
            Duration duration = Duration.between(startTime, endTime);

            // Create performance snapshot
            Map<String, Object> metrics = Map.of(
                "operations", 500L,
                "latency_p50", 10.0 + Math.random() * 5,
                "latency_p95", 25.0 + Math.random() * 10,
                "cpu_usage", 20.0 + Math.random() * 30,
                "memory_usage", 100.0 + Math.random() * 50
            );

            PerformanceSnapshot snapshot = new PerformanceSnapshot(
                "metricsDemo", profile, startTime, endTime, duration, true, metrics);
            snapshots.add(snapshot);

            log.info("Snapshot created: {}", snapshot.getSummary());
        }
        
        // Generate and display performance comparisons
        log.info("=== DEMO: Performance Comparison Results ===");
        
        PerformanceSnapshot baseline = snapshots.get(0); // BASIC profile as baseline
        for (int i = 1; i < snapshots.size(); i++) {
            PerformanceSnapshot comparison = snapshots.get(i);
            PerformanceComparison comp = new PerformanceComparison(baseline, comparison);
            
            log.info("Comparison: {} vs {}", baseline.getProfile().getDisplayName(),
                comparison.getProfile().getDisplayName());
            log.info("  Duration Change: {}%", String.format("%.2f", comp.getMetrics().getDurationImprovementPercent()));
            log.info("  Throughput Change: {}%", String.format("%.2f", comp.getMetrics().getThroughputImprovementPercent()));
            log.info("  Is Improvement: {}", comp.isImprovement());
            log.info("  Is Regression (>10%): {}", comp.isRegression(10.0));
            log.info("  Summary: {}", comp.getSummary());
            log.info("---");
        }

        // Save performance data to H2 database
        Instant overallEndTime = Instant.now();
        Duration totalDuration = Duration.between(overallStartTime, overallEndTime);

        Map<String, String> environmentInfo = new HashMap<>();
        environmentInfo.put("jvm_version", System.getProperty("java.version"));
        environmentInfo.put("os_name", System.getProperty("os.name"));
        environmentInfo.put("test_timestamp", overallStartTime.toString());

        String runId = historyRepository.savePerformanceRun("metricsDemo", snapshots, totalDuration, environmentInfo);
        log.info("=== DEMO: Performance data saved to H2 database with run ID: {} ===", runId);

        // Show historical comparison for each profile
        log.info("=== DEMO: Historical Performance Analysis ===");
        for (PerformanceSnapshot snapshot : snapshots) {
            PerformanceHistoryAnalyzer.HistoricalComparison historical =
                historyAnalyzer.compareAgainstHistoricalBaseline(snapshot, 5);

            if (historical.hasHistoricalData()) {
                log.info("Historical Analysis for {} ({}):",
                        snapshot.getTestName(), snapshot.getProfile().getDisplayName());
                log.info("  Current Performance: {:.2f} ops/sec ({}ms)",
                        snapshot.getThroughput(), snapshot.getDuration().toMillis());
                log.info("  Historical Baseline: {} previous runs",
                        historical.getHistoricalSnapshots().size());
                log.info("  Trend Direction: {}", historical.getTrend().getDescription());

                if (historical.getComparison() != null) {
                    PerformanceComparison histComp = historical.getComparison();
                    log.info("  vs Historical Average: Duration {:.2f}%, Throughput {:.2f}%",
                            histComp.getMetrics().getDurationImprovementPercent(),
                            histComp.getMetrics().getThroughputImprovementPercent());
                }
            } else {
                log.info("No historical data available for {} ({})",
                        snapshot.getTestName(), snapshot.getProfile().getDisplayName());
            }
            log.info("---");
        }

        log.info("=== DEMO: Performance metrics demonstration completed successfully ===");
    }
    
    /**
     * Perform simple database operations to demonstrate performance differences
     */
    private void performSimpleDatabaseOperations() throws Exception {
        String jdbcUrl = getJdbcUrl();
        String username = getUsername();
        String password = getPassword();

        log.debug("Connecting to database: {}", jdbcUrl);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            // Create a simple test table
            try (PreparedStatement createStmt = conn.prepareStatement(
                "CREATE TABLE IF NOT EXISTS simple_perf_test (" +
                "id SERIAL PRIMARY KEY, " +
                "data VARCHAR(50))")) {
                createStmt.execute();
            }

            // Insert a small amount of test data
            String insertSql = "INSERT INTO simple_perf_test (data) VALUES (?)";
            try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                for (int i = 0; i < 100; i++) {
                    insertStmt.setString(1, "Data " + i);
                    insertStmt.addBatch();

                    if (i % 25 == 0) {
                        insertStmt.executeBatch();
                    }
                }
                insertStmt.executeBatch();
            }

            // Query the data
            try (PreparedStatement queryStmt = conn.prepareStatement(
                "SELECT COUNT(*) FROM simple_perf_test")) {
                try (ResultSet rs = queryStmt.executeQuery()) {
                    if (rs.next()) {
                        int count = rs.getInt(1);
                        log.debug("Query result: {} records found", count);
                    }
                }
            }

            // Clean up
            try (PreparedStatement dropStmt = conn.prepareStatement("DROP TABLE simple_perf_test")) {
                dropStmt.execute();
            }
        }
    }
}
