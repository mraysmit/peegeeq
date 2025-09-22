package dev.mars.peegeeq.test.hardware;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Database storage service for hardware-aware performance results.
 * 
 * This service provides persistent storage for hardware profiles, resource usage
 * snapshots, and performance test results with hardware context. It enables
 * historical performance analysis, cross-environment comparison, and regression
 * detection accounting for hardware differences.
 * 
 * Key Features:
 * - Hardware profile deduplication using SHA-256 hashing
 * - Resource usage snapshot storage with detailed sample data
 * - Hardware-aware performance result persistence
 * - Performance regression analysis and tracking
 * - Efficient querying with optimized indexes
 * 
 * Usage:
 * ```java
 * HardwareAwarePerformanceStorage storage = new HardwareAwarePerformanceStorage(dataSource);
 * 
 * // Store hardware profile (deduplicated)
 * Long profileId = storage.storeHardwareProfile(hardwareProfile);
 * 
 * // Store resource usage snapshot
 * Long snapshotId = storage.storeResourceUsageSnapshot(testRunId, resourceSnapshot);
 * 
 * // Store complete performance result
 * storage.storePerformanceResult(performanceResult, profileId, snapshotId);
 * ```
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-19
 * @version 1.0
 */
public class HardwareAwarePerformanceStorage {
    private static final Logger logger = LoggerFactory.getLogger(HardwareAwarePerformanceStorage.class);
    
    private final DataSource dataSource;
    
    /**
     * Create a new hardware-aware performance storage service.
     * 
     * @param dataSource the database connection pool
     */
    public HardwareAwarePerformanceStorage(DataSource dataSource) {
        this.dataSource = dataSource;
        logger.debug("HardwareAwarePerformanceStorage initialized");
    }
    
    /**
     * Store a hardware profile, returning existing ID if profile already exists.
     * 
     * Hardware profiles are deduplicated using SHA-256 hash of specifications.
     * 
     * @param profile the hardware profile to store
     * @return the database ID of the stored profile
     * @throws SQLException if database operation fails
     */
    public Long storeHardwareProfile(HardwareProfile profile) throws SQLException {
        String profileHash = calculateHardwareProfileHash(profile);
        
        // Check if profile already exists
        Optional<Long> existingId = findHardwareProfileByHash(profileHash);
        if (existingId.isPresent()) {
            logger.debug("Hardware profile already exists with ID: {}", existingId.get());
            return existingId.get();
        }
        
        // Insert new hardware profile
        String sql = """
            INSERT INTO hardware_profiles (
                profile_hash, system_description, capture_time, hostname, os_name, os_version, os_architecture,
                cpu_model, cpu_cores, cpu_logical_processors, cpu_max_frequency_hz, 
                cpu_l1_cache_size, cpu_l2_cache_size, cpu_l3_cache_size,
                total_memory_bytes, memory_type, memory_speed_mhz, memory_modules,
                storage_type, total_storage_bytes, storage_interface,
                network_interface, network_speed_bps,
                java_version, java_vendor, jvm_name, jvm_version, 
                jvm_max_heap_bytes, jvm_initial_heap_bytes, gc_algorithm,
                is_containerized, container_runtime, container_memory_limit_bytes, container_cpu_limit,
                additional_properties
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            RETURNING id
            """;
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            int paramIndex = 1;
            stmt.setString(paramIndex++, profileHash);
            stmt.setString(paramIndex++, profile.getSystemDescription());
            stmt.setTimestamp(paramIndex++, Timestamp.from(profile.getCaptureTime()));
            stmt.setString(paramIndex++, profile.getHostname());
            stmt.setString(paramIndex++, profile.getOsName());
            stmt.setString(paramIndex++, profile.getOsVersion());
            stmt.setString(paramIndex++, profile.getOsArchitecture());
            
            stmt.setString(paramIndex++, profile.getCpuModel());
            stmt.setInt(paramIndex++, profile.getCpuCores());
            stmt.setInt(paramIndex++, profile.getCpuLogicalProcessors());
            stmt.setLong(paramIndex++, profile.getCpuMaxFrequencyHz());
            stmt.setLong(paramIndex++, profile.getCpuL1CacheSize());
            stmt.setLong(paramIndex++, profile.getCpuL2CacheSize());
            stmt.setLong(paramIndex++, profile.getCpuL3CacheSize());
            
            stmt.setLong(paramIndex++, profile.getTotalMemoryBytes());
            stmt.setString(paramIndex++, profile.getMemoryType());
            stmt.setLong(paramIndex++, profile.getMemorySpeedMHz());
            stmt.setInt(paramIndex++, profile.getMemoryModules());
            
            stmt.setString(paramIndex++, profile.getStorageType());
            stmt.setLong(paramIndex++, profile.getTotalStorageBytes());
            stmt.setString(paramIndex++, profile.getStorageInterface());
            
            stmt.setString(paramIndex++, profile.getNetworkInterface());
            stmt.setLong(paramIndex++, profile.getNetworkSpeedBps());
            
            stmt.setString(paramIndex++, profile.getJavaVersion());
            stmt.setString(paramIndex++, profile.getJavaVendor());
            stmt.setString(paramIndex++, profile.getJvmName());
            stmt.setString(paramIndex++, profile.getJvmVersion());
            stmt.setLong(paramIndex++, profile.getJvmMaxHeapBytes());
            stmt.setLong(paramIndex++, profile.getJvmInitialHeapBytes());
            stmt.setString(paramIndex++, profile.getGcAlgorithm());
            
            stmt.setBoolean(paramIndex++, profile.isContainerized());
            stmt.setString(paramIndex++, profile.getContainerRuntime());
            stmt.setLong(paramIndex++, profile.getContainerMemoryLimitBytes());
            stmt.setDouble(paramIndex++, profile.getContainerCpuLimit());
            
            // Convert additional properties to JSON string
            stmt.setString(paramIndex++, convertMapToJson(profile.getAdditionalProperties()));
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Long id = rs.getLong(1);
                    logger.info("Stored new hardware profile with ID: {} (hash: {})", id, profileHash);
                    return id;
                } else {
                    throw new SQLException("Failed to get generated ID for hardware profile");
                }
            }
        }
    }
    
    /**
     * Store a resource usage snapshot.
     * 
     * @param testRunId the test run identifier
     * @param snapshot the resource usage snapshot
     * @return the database ID of the stored snapshot
     * @throws SQLException if database operation fails
     */
    public Long storeResourceUsageSnapshot(String testRunId, ResourceUsageSnapshot snapshot) throws SQLException {
        String sql = """
            INSERT INTO resource_usage_snapshots (
                test_run_id, start_time, end_time, duration_ms, sample_count,
                peak_cpu_usage_percent, avg_cpu_usage_percent,
                peak_memory_usage_percent, avg_memory_usage_percent,
                peak_jvm_memory_usage_percent, avg_jvm_memory_usage_percent,
                peak_disk_read_rate_bps, peak_disk_write_rate_bps,
                peak_network_receive_rate_bps, peak_network_send_rate_bps,
                peak_system_load, avg_system_load, peak_thread_count,
                is_high_resource_usage, has_resource_constraints
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """;
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            int paramIndex = 1;
            stmt.setString(paramIndex++, testRunId);
            stmt.setTimestamp(paramIndex++, Timestamp.from(snapshot.getStartTime()));
            stmt.setTimestamp(paramIndex++, Timestamp.from(snapshot.getEndTime()));
            stmt.setLong(paramIndex++, snapshot.getDuration().toMillis());
            stmt.setInt(paramIndex++, snapshot.getSampleCount());
            
            stmt.setDouble(paramIndex++, snapshot.getPeakCpuUsage());
            stmt.setDouble(paramIndex++, snapshot.getAvgCpuUsage());
            stmt.setDouble(paramIndex++, snapshot.getPeakMemoryUsage());
            stmt.setDouble(paramIndex++, snapshot.getAvgMemoryUsage());
            stmt.setDouble(paramIndex++, snapshot.getPeakJvmMemoryUsage());
            stmt.setDouble(paramIndex++, snapshot.getAvgJvmMemoryUsage());
            
            stmt.setLong(paramIndex++, snapshot.getPeakDiskReadRate());
            stmt.setLong(paramIndex++, snapshot.getPeakDiskWriteRate());
            stmt.setLong(paramIndex++, snapshot.getPeakNetworkReceiveRate());
            stmt.setLong(paramIndex++, snapshot.getPeakNetworkSendRate());
            
            stmt.setDouble(paramIndex++, snapshot.getPeakSystemLoad());
            stmt.setDouble(paramIndex++, snapshot.getAvgSystemLoad());
            stmt.setInt(paramIndex++, snapshot.getPeakThreadCount());
            
            stmt.setBoolean(paramIndex++, snapshot.isHighResourceUsage());
            stmt.setBoolean(paramIndex++, snapshot.hasResourceConstraints());
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Long snapshotId = rs.getLong(1);
                    logger.debug("Stored resource usage snapshot with ID: {}", snapshotId);
                    
                    // Store individual resource samples
                    storeResourceSamples(snapshotId, snapshot.getSamples());
                    
                    return snapshotId;
                } else {
                    throw new SQLException("Failed to get generated ID for resource usage snapshot");
                }
            }
        }
    }
    
    /**
     * Store a complete hardware-aware performance result.
     * 
     * @param result the performance result
     * @param hardwareProfileId the hardware profile ID
     * @param resourceSnapshotId the resource snapshot ID
     * @return the database ID of the stored result
     * @throws SQLException if database operation fails
     */
    public Long storePerformanceResult(HardwareAwarePerformanceResult result, 
                                     Long hardwareProfileId, Long resourceSnapshotId) throws SQLException {
        String sql = """
            INSERT INTO hardware_aware_performance_results (
                test_run_id, test_name, test_configuration, 
                test_start_time, test_end_time, test_duration_ms,
                hardware_profile_id, resource_snapshot_id,
                performance_metrics, test_parameters,
                has_resource_constraints, has_high_resource_usage
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
            RETURNING id
            """;
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            int paramIndex = 1;
            stmt.setString(paramIndex++, generateTestRunId(result));
            stmt.setString(paramIndex++, result.getTestName());
            stmt.setString(paramIndex++, result.getTestConfiguration());
            stmt.setTimestamp(paramIndex++, Timestamp.from(result.getTestStartTime()));
            stmt.setTimestamp(paramIndex++, Timestamp.from(result.getTestEndTime()));
            stmt.setLong(paramIndex++, result.getTestDuration().toMillis());
            
            stmt.setLong(paramIndex++, hardwareProfileId);
            stmt.setLong(paramIndex++, resourceSnapshotId);
            
            stmt.setString(paramIndex++, convertMapToJson(result.getPerformanceMetrics()));
            stmt.setString(paramIndex++, convertMapToJson(result.getTestParameters()));
            
            stmt.setBoolean(paramIndex++, result.hasResourceConstraints());
            stmt.setBoolean(paramIndex++, result.hasHighResourceUsage());
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Long id = rs.getLong(1);
                    logger.info("Stored hardware-aware performance result with ID: {} for test: {}", 
                               id, result.getTestName());
                    return id;
                } else {
                    throw new SQLException("Failed to get generated ID for performance result");
                }
            }
        }
    }
    
    /**
     * Find recent performance results for the same test and hardware.
     * 
     * @param testName the test name
     * @param hardwareProfileId the hardware profile ID
     * @param limit maximum number of results to return
     * @return list of recent performance result IDs
     * @throws SQLException if database operation fails
     */
    public List<Long> findRecentResults(String testName, Long hardwareProfileId, int limit) throws SQLException {
        String sql = """
            SELECT id FROM hardware_aware_performance_results 
            WHERE test_name = ? AND hardware_profile_id = ?
            ORDER BY test_start_time DESC 
            LIMIT ?
            """;
        
        List<Long> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, testName);
            stmt.setLong(2, hardwareProfileId);
            stmt.setInt(3, limit);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(rs.getLong("id"));
                }
            }
        }
        
        logger.debug("Found {} recent results for test: {} on hardware profile: {}", 
                    results.size(), testName, hardwareProfileId);
        return results;
    }
    
    // Private helper methods
    
    private Optional<Long> findHardwareProfileByHash(String profileHash) throws SQLException {
        String sql = "SELECT id FROM hardware_profiles WHERE profile_hash = ?";
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, profileHash);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getLong("id"));
                }
            }
        }
        
        return Optional.empty();
    }
    
    private void storeResourceSamples(Long snapshotId, List<SystemResourceMonitor.ResourceSample> samples) throws SQLException {
        if (samples.isEmpty()) {
            return;
        }
        
        String sql = """
            INSERT INTO resource_samples (
                snapshot_id, timestamp, cpu_usage_percent, memory_usage_percent,
                used_memory_bytes, total_memory_bytes, jvm_memory_usage_percent,
                jvm_used_memory_bytes, jvm_max_memory_bytes, disk_read_rate_bps,
                disk_write_rate_bps, network_receive_rate_bps, network_send_rate_bps,
                system_load, thread_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            for (SystemResourceMonitor.ResourceSample sample : samples) {
                int paramIndex = 1;
                stmt.setLong(paramIndex++, snapshotId);
                stmt.setTimestamp(paramIndex++, Timestamp.from(sample.getTimestamp()));
                stmt.setDouble(paramIndex++, sample.getCpuUsage());
                stmt.setDouble(paramIndex++, sample.getMemoryUsage());
                stmt.setLong(paramIndex++, sample.getUsedMemoryBytes());
                stmt.setLong(paramIndex++, sample.getTotalMemoryBytes());
                stmt.setDouble(paramIndex++, sample.getJvmMemoryUsage());
                stmt.setLong(paramIndex++, sample.getJvmUsedMemoryBytes());
                stmt.setLong(paramIndex++, sample.getJvmMaxMemoryBytes());
                stmt.setLong(paramIndex++, sample.getDiskReadRate());
                stmt.setLong(paramIndex++, sample.getDiskWriteRate());
                stmt.setLong(paramIndex++, sample.getNetworkReceiveRate());
                stmt.setLong(paramIndex++, sample.getNetworkSendRate());
                stmt.setDouble(paramIndex++, sample.getSystemLoad());
                stmt.setInt(paramIndex++, sample.getThreadCount());
                
                stmt.addBatch();
            }
            
            int[] results = stmt.executeBatch();
            logger.debug("Stored {} resource samples for snapshot: {}", results.length, snapshotId);
        }
    }
    
    private String calculateHardwareProfileHash(HardwareProfile profile) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            
            // Include key hardware specifications in hash
            StringBuilder hashInput = new StringBuilder();
            hashInput.append(profile.getCpuModel()).append("|");
            hashInput.append(profile.getCpuCores()).append("|");
            hashInput.append(profile.getCpuMaxFrequencyHz()).append("|");
            hashInput.append(profile.getTotalMemoryBytes()).append("|");
            hashInput.append(profile.getMemoryType()).append("|");
            hashInput.append(profile.getStorageType()).append("|");
            hashInput.append(profile.getOsName()).append("|");
            hashInput.append(profile.getOsArchitecture()).append("|");
            hashInput.append(profile.getJavaVersion()).append("|");
            hashInput.append(profile.getJvmMaxHeapBytes()).append("|");
            hashInput.append(profile.isContainerized());
            
            byte[] hashBytes = digest.digest(hashInput.toString().getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
    
    private String generateTestRunId(HardwareAwarePerformanceResult result) {
        return result.getTestName() + "_" + result.getTestStartTime().toEpochMilli();
    }
    
    private String convertMapToJson(Map<String, ?> map) {
        // Simple JSON conversion - in production, use a proper JSON library
        if (map.isEmpty()) {
            return "{}";
        }
        
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            if (!first) {
                json.append(",");
            }
            json.append("\"").append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof String) {
                json.append("\"").append(value).append("\"");
            } else {
                json.append(value);
            }
            first = false;
        }
        json.append("}");
        
        return json.toString();
    }
}
