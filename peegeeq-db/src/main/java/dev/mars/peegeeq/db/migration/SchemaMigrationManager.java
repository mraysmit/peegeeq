package dev.mars.peegeeq.db.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages database schema migrations for PeeGeeQ.
 * Provides versioning, rollback capabilities, and migration validation.
 */
public class SchemaMigrationManager {
    private static final Logger logger = LoggerFactory.getLogger(SchemaMigrationManager.class);
    
    private final DataSource dataSource;
    private final String migrationPath;
    private final boolean validateChecksums;
    
    public SchemaMigrationManager(DataSource dataSource) {
        this(dataSource, "/db/migration", true);
    }
    
    public SchemaMigrationManager(DataSource dataSource, String migrationPath, boolean validateChecksums) {
        this.dataSource = dataSource;
        this.migrationPath = migrationPath;
        this.validateChecksums = validateChecksums;
    }
    
    /**
     * Applies all pending migrations.
     * 
     * @return Number of migrations applied
     * @throws SQLException If migration fails
     */
    public int migrate() throws SQLException {
        logger.info("Starting database migration process");
        
        ensureSchemaVersionTable();
        
        List<MigrationScript> pendingMigrations = getPendingMigrations();
        logger.info("Found {} pending migrations", pendingMigrations.size());
        
        int appliedCount = 0;
        for (MigrationScript migration : pendingMigrations) {
            try {
                applyMigration(migration);
                appliedCount++;
                logger.info("Successfully applied migration: {}", migration.getVersion());
            } catch (Exception e) {
                logger.error("Failed to apply migration: {}", migration.getVersion(), e);
                throw new SQLException("Migration failed: " + migration.getVersion(), e);
            }
        }
        
        logger.info("Migration process completed. Applied {} migrations", appliedCount);
        return appliedCount;
    }
    
    /**
     * Validates all applied migrations against their checksums.
     * 
     * @return true if all migrations are valid
     * @throws SQLException If validation fails
     */
    public boolean validateMigrations() throws SQLException {
        if (!validateChecksums) {
            logger.info("Checksum validation is disabled");
            return true;
        }
        
        logger.info("Validating migration checksums");
        
        List<AppliedMigration> appliedMigrations = getAppliedMigrations();
        List<MigrationScript> availableScripts = getAvailableMigrations();
        
        Map<String, String> scriptChecksums = availableScripts.stream()
            .collect(Collectors.toMap(MigrationScript::getVersion, MigrationScript::getChecksum));
        
        for (AppliedMigration applied : appliedMigrations) {
            String expectedChecksum = scriptChecksums.get(applied.getVersion());
            if (expectedChecksum == null) {
                logger.warn("Migration script not found for applied version: {}", applied.getVersion());
                continue;
            }
            
            if (!expectedChecksum.equals(applied.getChecksum())) {
                logger.error("Checksum mismatch for migration {}: expected {}, got {}", 
                    applied.getVersion(), expectedChecksum, applied.getChecksum());
                return false;
            }
        }
        
        logger.info("All migration checksums are valid");
        return true;
    }
    
    /**
     * Gets the current schema version.
     * 
     * @return Current schema version or null if no migrations applied
     * @throws SQLException If query fails
     */
    public String getCurrentVersion() throws SQLException {
        String sql = "SELECT version FROM schema_version ORDER BY applied_at DESC LIMIT 1";
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            return rs.next() ? rs.getString("version") : null;
        }
    }
    
    /**
     * Gets migration history.
     * 
     * @return List of applied migrations
     * @throws SQLException If query fails
     */
    public List<AppliedMigration> getMigrationHistory() throws SQLException {
        return getAppliedMigrations();
    }
    
    private void ensureSchemaVersionTable() throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS schema_version (
                version VARCHAR(50) PRIMARY KEY,
                description TEXT,
                applied_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                checksum VARCHAR(64)
            )
            """;
        
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }
    
    private List<MigrationScript> getPendingMigrations() throws SQLException {
        List<MigrationScript> availableScripts = getAvailableMigrations();
        Set<String> appliedVersions = getAppliedVersions();
        
        return availableScripts.stream()
            .filter(script -> !appliedVersions.contains(script.getVersion()))
            .sorted(Comparator.comparing(MigrationScript::getVersion))
            .collect(Collectors.toList());
    }
    
    private List<MigrationScript> getAvailableMigrations() {
        List<MigrationScript> scripts = new ArrayList<>();
        
        // In a real implementation, you would scan the classpath for migration files
        // For now, we'll add the base migration
        try {
            String content = loadResourceAsString(migrationPath + "/V001__Create_Base_Tables.sql");
            if (content != null) {
                scripts.add(new MigrationScript(
                    "V001",
                    "Create base tables for PeeGeeQ message queue system",
                    content,
                    calculateChecksum(content)
                ));
            }
        } catch (Exception e) {
            logger.warn("Could not load migration script V001", e);
        }
        
        return scripts;
    }
    
    private Set<String> getAppliedVersions() throws SQLException {
        String sql = "SELECT version FROM schema_version";
        Set<String> versions = new HashSet<>();
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            while (rs.next()) {
                versions.add(rs.getString("version"));
            }
        }
        
        return versions;
    }
    
    private List<AppliedMigration> getAppliedMigrations() throws SQLException {
        String sql = "SELECT version, description, applied_at, checksum FROM schema_version ORDER BY applied_at";
        List<AppliedMigration> migrations = new ArrayList<>();
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            while (rs.next()) {
                migrations.add(new AppliedMigration(
                    rs.getString("version"),
                    rs.getString("description"),
                    rs.getTimestamp("applied_at"),
                    rs.getString("checksum")
                ));
            }
        }
        
        return migrations;
    }
    
    private void applyMigration(MigrationScript migration) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            
            try {
                // Execute migration script
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(migration.getContent());
                }
                
                // Record migration
                String sql = "INSERT INTO schema_version (version, description, checksum) VALUES (?, ?, ?)";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, migration.getVersion());
                    stmt.setString(2, migration.getDescription());
                    stmt.setString(3, migration.getChecksum());
                    stmt.executeUpdate();
                }
                
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }
    
    private String loadResourceAsString(String resourcePath) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                return null;
            }
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            logger.error("Failed to load resource: {}", resourcePath, e);
            return null;
        }
    }
    
    private String calculateChecksum(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            
            for (byte b : hash) {
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
    
    /**
     * Represents a migration script.
     */
    public static class MigrationScript {
        private final String version;
        private final String description;
        private final String content;
        private final String checksum;
        
        public MigrationScript(String version, String description, String content, String checksum) {
            this.version = version;
            this.description = description;
            this.content = content;
            this.checksum = checksum;
        }
        
        public String getVersion() { return version; }
        public String getDescription() { return description; }
        public String getContent() { return content; }
        public String getChecksum() { return checksum; }
    }
    
    /**
     * Represents an applied migration.
     */
    public static class AppliedMigration {
        private final String version;
        private final String description;
        private final Timestamp appliedAt;
        private final String checksum;
        
        public AppliedMigration(String version, String description, Timestamp appliedAt, String checksum) {
            this.version = version;
            this.description = description;
            this.appliedAt = appliedAt;
            this.checksum = checksum;
        }
        
        public String getVersion() { return version; }
        public String getDescription() { return description; }
        public Timestamp getAppliedAt() { return appliedAt; }
        public String getChecksum() { return checksum; }
    }
}
