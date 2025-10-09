package dev.mars.peegeeq.test.persistence;

import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile;
import dev.mars.peegeeq.test.metrics.PerformanceSnapshot;
import dev.mars.peegeeq.test.metrics.PerformanceComparison;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Repository for persisting and retrieving performance test history using H2 database.
 * 
 * This class provides methods to store performance snapshots, comparisons, and trends
 * for historical analysis and regression detection.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-18
 * @version 1.0
 */
public class PerformanceHistoryRepository {
    private static final Logger logger = LoggerFactory.getLogger(PerformanceHistoryRepository.class);
    
    private final DataSource dataSource;
    private static final Map<String, PerformanceHistoryRepository> instances = new ConcurrentHashMap<>();

    // Singleton pattern for each database URL
    public static PerformanceHistoryRepository getInstance(String databaseUrl) {
        return getInstance(databaseUrl, null, null);
    }

    public static PerformanceHistoryRepository getInstance(String databaseUrl, String username, String password) {
        String key = databaseUrl + ":" + username;
        return instances.computeIfAbsent(key, k -> new PerformanceHistoryRepository(databaseUrl, username, password));
    }
    
    private PerformanceHistoryRepository(String databaseUrl, String username, String password) {
        this.dataSource = createDataSource(databaseUrl, username, password);
        initializeSchema();
    }
    
    /**
     * Create H2 DataSource for performance history storage.
     */
    private DataSource createDataSource(String databaseUrl, String username, String password) {
        try {
            // Use H2 embedded database
            String h2Url = databaseUrl != null ? databaseUrl : "jdbc:h2:./target/performance-history;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";
            
            org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
            ds.setURL(h2Url);
            ds.setUser(username != null ? username : "sa");
            ds.setPassword(password != null ? password : "");
            
            logger.info("Created H2 DataSource for performance history: {}", h2Url);
            return ds;
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to create H2 DataSource for performance history", e);
        }
    }
    
    /**
     * Initialize the database schema.
     */
    private void initializeSchema() {
        try (Connection conn = dataSource.getConnection()) {
            // Create tables directly in code for better control
            createTables(conn);
            logger.info("Performance history database schema initialized successfully");

        } catch (SQLException e) {
            logger.error("Failed to initialize performance history database schema", e);
            throw new RuntimeException("Failed to initialize performance history database schema", e);
        }
    }

    /**
     * Create database tables programmatically.
     */
    private void createTables(Connection conn) throws SQLException {
        // Create performance_test_runs table
        String createTestRuns = """
            CREATE TABLE IF NOT EXISTS performance_test_runs (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                run_id VARCHAR(255) NOT NULL UNIQUE,
                test_name VARCHAR(255) NOT NULL,
                run_timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                total_duration_ms BIGINT NOT NULL,
                profiles_tested INT NOT NULL DEFAULT 0,
                success BOOLEAN NOT NULL DEFAULT TRUE,
                environment_info TEXT,
                git_commit VARCHAR(255),
                notes TEXT
            )
            """;

        // Create performance_snapshots table
        String createSnapshots = """
            CREATE TABLE IF NOT EXISTS performance_snapshots (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                run_id VARCHAR(255) NOT NULL,
                test_name VARCHAR(255) NOT NULL,
                profile_name VARCHAR(100) NOT NULL,
                profile_display_name VARCHAR(255) NOT NULL,
                start_time TIMESTAMP WITH TIME ZONE NOT NULL,
                end_time TIMESTAMP WITH TIME ZONE NOT NULL,
                duration_ms BIGINT NOT NULL,
                success BOOLEAN NOT NULL,
                throughput_ops_per_sec DOUBLE PRECISION,
                additional_metrics TEXT,
                created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
            )
            """;

        // Create performance_comparisons table
        String createComparisons = """
            CREATE TABLE IF NOT EXISTS performance_comparisons (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                run_id VARCHAR(255) NOT NULL,
                baseline_snapshot_id BIGINT NOT NULL,
                comparison_snapshot_id BIGINT NOT NULL,
                duration_improvement_percent DOUBLE PRECISION NOT NULL,
                throughput_improvement_percent DOUBLE PRECISION NOT NULL,
                is_improvement BOOLEAN NOT NULL,
                is_regression BOOLEAN NOT NULL,
                regression_threshold_percent DOUBLE PRECISION DEFAULT 10.0,
                additional_comparisons TEXT,
                created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
            )
            """;

        // Execute table creation statements
        try (PreparedStatement stmt1 = conn.prepareStatement(createTestRuns)) {
            stmt1.execute();
            logger.debug("Created performance_test_runs table");
        }

        try (PreparedStatement stmt2 = conn.prepareStatement(createSnapshots)) {
            stmt2.execute();
            logger.debug("Created performance_snapshots table");
        }

        try (PreparedStatement stmt3 = conn.prepareStatement(createComparisons)) {
            stmt3.execute();
            logger.debug("Created performance_comparisons table");
        }
    }
    
    /**
     * Save a performance test run with its snapshots.
     */
    public String savePerformanceRun(String testName, List<PerformanceSnapshot> snapshots, 
                                   Duration totalDuration, Map<String, String> environmentInfo) {
        String runId = generateRunId(testName);
        
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            
            try {
                // Insert test run
                insertTestRun(conn, runId, testName, totalDuration, snapshots.size(), environmentInfo);
                
                // Insert snapshots
                Map<PerformanceSnapshot, Long> snapshotIds = new HashMap<>();
                for (PerformanceSnapshot snapshot : snapshots) {
                    long snapshotId = insertSnapshot(conn, runId, snapshot);
                    snapshotIds.put(snapshot, snapshotId);
                }
                
                // Insert comparisons
                insertComparisons(conn, runId, snapshots, snapshotIds);
                
                conn.commit();
                logger.info("Saved performance run: {} with {} snapshots", runId, snapshots.size());
                return runId;
                
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
            
        } catch (SQLException e) {
            logger.error("Failed to save performance run: {}", runId, e);
            throw new RuntimeException("Failed to save performance run", e);
        }
    }
    
    /**
     * Generate unique run ID.
     */
    private String generateRunId(String testName) {
        return testName + "_" + Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    /**
     * Insert test run record.
     */
    private void insertTestRun(Connection conn, String runId, String testName, Duration totalDuration, 
                              int profilesCount, Map<String, String> environmentInfo) throws SQLException {
        String sql = "INSERT INTO performance_test_runs (run_id, test_name, total_duration_ms, profiles_tested, environment_info) VALUES (?, ?, ?, ?, ?)";
        
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, runId);
            stmt.setString(2, testName);
            stmt.setLong(3, totalDuration.toMillis());
            stmt.setInt(4, profilesCount);
            stmt.setString(5, environmentInfo != null ? environmentInfo.toString() : "{}");
            stmt.executeUpdate();
        }
    }
    
    /**
     * Insert performance snapshot.
     */
    private long insertSnapshot(Connection conn, String runId, PerformanceSnapshot snapshot) throws SQLException {
        String sql = "INSERT INTO performance_snapshots (run_id, test_name, profile_name, profile_display_name, " +
                    "start_time, end_time, duration_ms, success, throughput_ops_per_sec, additional_metrics) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        
        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, runId);
            stmt.setString(2, snapshot.getTestName());
            stmt.setString(3, snapshot.getProfile().name());
            stmt.setString(4, snapshot.getProfile().getDisplayName());
            stmt.setTimestamp(5, Timestamp.from(snapshot.getStartTime()));
            stmt.setTimestamp(6, Timestamp.from(snapshot.getEndTime()));
            stmt.setLong(7, snapshot.getDuration().toMillis());
            stmt.setBoolean(8, snapshot.isSuccess());
            stmt.setDouble(9, snapshot.getThroughput());
            stmt.setString(10, snapshot.getAdditionalMetrics().toString());
            
            stmt.executeUpdate();
            
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                throw new SQLException("Failed to get generated snapshot ID");
            }
        }
    }
    
    /**
     * Insert performance comparisons.
     */
    private void insertComparisons(Connection conn, String runId, List<PerformanceSnapshot> snapshots, 
                                 Map<PerformanceSnapshot, Long> snapshotIds) throws SQLException {
        if (snapshots.size() < 2) return;
        
        String sql = "INSERT INTO performance_comparisons (run_id, baseline_snapshot_id, comparison_snapshot_id, " +
                    "duration_improvement_percent, throughput_improvement_percent, is_improvement, is_regression, " +
                    "additional_comparisons) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            PerformanceSnapshot baseline = snapshots.get(0);
            
            for (int i = 1; i < snapshots.size(); i++) {
                PerformanceSnapshot comparison = snapshots.get(i);
                PerformanceComparison comp = new PerformanceComparison(baseline, comparison);
                
                stmt.setString(1, runId);
                stmt.setLong(2, snapshotIds.get(baseline));
                stmt.setLong(3, snapshotIds.get(comparison));
                stmt.setDouble(4, comp.getMetrics().getDurationImprovementPercent());
                stmt.setDouble(5, comp.getMetrics().getThroughputImprovementPercent());
                stmt.setBoolean(6, comp.isImprovement());
                stmt.setBoolean(7, comp.isRegression(10.0));
                stmt.setString(8, comp.getMetrics().getAdditionalComparisons().toString());
                
                stmt.addBatch();
            }
            
            stmt.executeBatch();
        }
    }

    /**
     * Get historical performance data for a test and profile.
     */
    public List<PerformanceSnapshot> getHistoricalSnapshots(String testName, PerformanceProfile profile, int limit) {
        String sql = "SELECT * FROM performance_snapshots WHERE test_name = ? AND profile_name = ? " +
                    "ORDER BY start_time DESC LIMIT ?";

        List<PerformanceSnapshot> snapshots = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, testName);
            stmt.setString(2, profile.name());
            stmt.setInt(3, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    snapshots.add(createSnapshotFromResultSet(rs));
                }
            }

        } catch (SQLException e) {
            logger.error("Failed to retrieve historical snapshots for test: {} profile: {}", testName, profile, e);
        }

        return snapshots;
    }

    /**
     * Get performance summary statistics.
     */
    public Map<String, Object> getPerformanceSummary(String testName, PerformanceProfile profile) {
        String sql = "SELECT * FROM performance_summary WHERE test_name = ? AND profile_name = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, testName);
            stmt.setString(2, profile.name());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> summary = new HashMap<>();
                    summary.put("totalRuns", rs.getInt("total_runs"));
                    summary.put("avgDurationMs", rs.getDouble("avg_duration_ms"));
                    summary.put("minDurationMs", rs.getDouble("min_duration_ms"));
                    summary.put("maxDurationMs", rs.getDouble("max_duration_ms"));
                    summary.put("avgThroughput", rs.getDouble("avg_throughput"));
                    summary.put("minThroughput", rs.getDouble("min_throughput"));
                    summary.put("maxThroughput", rs.getDouble("max_throughput"));
                    summary.put("firstRun", rs.getTimestamp("first_run"));
                    summary.put("lastRun", rs.getTimestamp("last_run"));
                    return summary;
                }
            }

        } catch (SQLException e) {
            logger.error("Failed to retrieve performance summary for test: {} profile: {}", testName, profile, e);
        }

        return new HashMap<>();
    }

    /**
     * Create PerformanceSnapshot from ResultSet.
     */
    private PerformanceSnapshot createSnapshotFromResultSet(ResultSet rs) throws SQLException {
        String testName = rs.getString("test_name");
        PerformanceProfile profile = PerformanceProfile.valueOf(rs.getString("profile_name"));
        Instant startTime = rs.getTimestamp("start_time").toInstant();
        Instant endTime = rs.getTimestamp("end_time").toInstant();
        Duration duration = Duration.ofMillis(rs.getLong("duration_ms"));
        boolean success = rs.getBoolean("success");

        // Parse additional metrics (simplified - in real implementation, parse JSON)
        Map<String, Object> additionalMetrics = new HashMap<>();
        additionalMetrics.put("throughput", rs.getDouble("throughput_ops_per_sec"));

        return new PerformanceSnapshot(testName, profile, startTime, endTime, duration, success, additionalMetrics);
    }

    /**
     * Close the repository and clean up resources.
     */
    public void close() {
        // H2 embedded database will be closed when JVM exits
        logger.info("Performance history repository closed");
    }
}
