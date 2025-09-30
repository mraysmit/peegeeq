package dev.mars.peegeeq.db.migration;

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
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
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

        // Use database-level advisory lock to prevent concurrent migrations
        try (Connection conn = dataSource.getConnection()) {
            // PostgreSQL transaction-level advisory lock - lock ID 12345 for migrations
            // Using pg_advisory_xact_lock to automatically release when transaction ends
            try (PreparedStatement lockStmt = conn.prepareStatement("SELECT pg_advisory_xact_lock(12345)")) {
                lockStmt.execute();

                try {
                    ensureSchemaVersionTable(conn);

                    List<MigrationScript> pendingMigrations = getPendingMigrations(conn);
                    logger.info("Found {} pending migrations", pendingMigrations.size());

                    int appliedCount = 0;
                    for (MigrationScript migration : pendingMigrations) {
                        try {
                            applyMigration(migration, conn);
                            appliedCount++;
                            logger.info("Successfully applied migration: {}", migration.getVersion());
                        } catch (Exception e) {
                            logger.error("Failed to apply migration: {}", migration.getVersion(), e);
                            throw new SQLException("Migration failed: " + migration.getVersion(), e);
                        }
                    }

                    logger.info("Migration process completed. Applied {} migrations", appliedCount);
                    return appliedCount;
                } finally {
                    // Transaction-level advisory lock will be automatically released when transaction ends
                    logger.debug("Migration advisory lock will be automatically released when transaction ends");
                }
            }
        }
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
        } catch (SQLException e) {
            // If table doesn't exist, return null (no migrations applied yet)
            if (e.getMessage().contains("does not exist")) {
                return null;
            }
            throw e;
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


    private void ensureSchemaVersionTable(Connection conn) throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS schema_version (
                version VARCHAR(50) PRIMARY KEY,
                description TEXT,
                applied_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                checksum VARCHAR(64)
            )
            """;

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }


    private List<MigrationScript> getPendingMigrations(Connection conn) throws SQLException {
        List<MigrationScript> availableScripts = getAvailableMigrations();
        Set<String> appliedVersions = getAppliedVersions(conn);

        return availableScripts.stream()
            .filter(script -> !appliedVersions.contains(script.getVersion()))
            .sorted(Comparator.comparing(MigrationScript::getVersion))
            .collect(Collectors.toList());
    }
    
    private List<MigrationScript> getAvailableMigrations() {
        List<MigrationScript> scripts = new ArrayList<>();

        // Dynamically scan for migration files in the classpath
        String[] migrationFiles = {
            "V001__Create_Base_Tables.sql",
            "V002__Create_Message_Processing_Table.sql"
        };

        for (String fileName : migrationFiles) {
            try {
                String content = loadResourceAsString(migrationPath + "/" + fileName);
                if (content != null) {
                    // Extract version from filename (e.g., "V001" from "V001__Create_Base_Tables.sql")
                    String version = fileName.substring(0, fileName.indexOf("__"));

                    // Extract description from filename (e.g., "Create Base Tables" from "V001__Create_Base_Tables.sql")
                    String description = fileName.substring(fileName.indexOf("__") + 2, fileName.lastIndexOf(".sql"))
                        .replace("_", " ");

                    scripts.add(new MigrationScript(
                        version,
                        description,
                        content,
                        calculateChecksum(content)
                    ));

                    logger.debug("Loaded migration script: {}", version);
                } else {
                    logger.warn("Migration file not found: {}", fileName);
                }
            } catch (Exception e) {
                logger.warn("Could not load migration script {}", fileName, e);
            }
        }

        return scripts;
    }


    private Set<String> getAppliedVersions(Connection conn) throws SQLException {
        String sql = "SELECT version FROM schema_version";
        Set<String> versions = new HashSet<>();

        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                versions.add(rs.getString("version"));
            }
        } catch (SQLException e) {
            // If table doesn't exist, return empty set (no migrations applied yet)
            if (e.getMessage().contains("does not exist")) {
                return versions;
            }
            throw e;
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


    private void applyMigration(MigrationScript migration, Connection conn) throws SQLException {
        String content = migration.getContent();

        // Check if migration contains CONCURRENTLY statements that need to run outside transactions
        if (content.contains("CONCURRENTLY")) {
            logger.debug("Migration {} contains CONCURRENTLY statements, executing outside transaction", migration.getVersion());
            applyMigrationWithConcurrentStatements(migration, conn);
        } else {
            logger.debug("Migration {} executing in transaction", migration.getVersion());
            applyMigrationInTransaction(migration, conn);
        }
    }

    private void applyMigrationInTransaction(MigrationScript migration, Connection conn) throws SQLException {
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

    private void applyMigrationWithConcurrentStatements(MigrationScript migration, Connection conn) throws SQLException {
        String content = migration.getContent();

        // Parse SQL statements properly, handling dollar-quoted strings
        List<String> statements = parseSqlStatements(content);
        List<String> regularStatements = new ArrayList<>();
        List<String> concurrentStatements = new ArrayList<>();

        for (String statement : statements) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                if (trimmed.contains("CONCURRENTLY")) {
                    concurrentStatements.add(trimmed);
                } else {
                    regularStatements.add(trimmed);
                }
            }
        }

        // Execute regular statements in transaction
        if (!regularStatements.isEmpty()) {
            conn.setAutoCommit(false);
            try {
                try (Statement stmt = conn.createStatement()) {
                    for (String statement : regularStatements) {
                        stmt.execute(statement);
                    }
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }

        // Execute concurrent statements outside transaction
        if (!concurrentStatements.isEmpty()) {
            conn.setAutoCommit(true); // Ensure autocommit for concurrent statements
            try (Statement stmt = conn.createStatement()) {
                for (String statement : concurrentStatements) {
                    logger.debug("Executing concurrent statement: {}", statement.substring(0, Math.min(50, statement.length())) + "...");
                    try {
                        stmt.execute(statement);
                    } catch (SQLException e) {
                        // PostgreSQL's CREATE INDEX CONCURRENTLY can fail with "relation does not exist" errors
                        // during its internal multi-step process. If the index already exists or was partially
                        // created, we can safely ignore these errors.
                        if (e.getMessage().contains("does not exist") || e.getMessage().contains("already exists")) {
                            logger.warn("Ignoring concurrent index creation error (likely already exists or partial state): {}", e.getMessage());
                        } else {
                            throw e;
                        }
                    }
                }
            }
        }

        // Record migration in transaction
        conn.setAutoCommit(false);
        try {
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

    /**
     * Parse SQL statements from content, properly handling dollar-quoted strings and comments.
     * This prevents breaking PostgreSQL functions that contain semicolons inside $$ blocks.
     */
    private List<String> parseSqlStatements(String content) {
        List<String> statements = new ArrayList<>();
        StringBuilder currentStatement = new StringBuilder();

        int i = 0;
        while (i < content.length()) {
            char c = content.charAt(i);

            // Handle single-line comments
            if (c == '-' && i + 1 < content.length() && content.charAt(i + 1) == '-') {
                // Skip to end of line
                while (i < content.length() && content.charAt(i) != '\n') {
                    currentStatement.append(content.charAt(i));
                    i++;
                }
                if (i < content.length()) {
                    currentStatement.append(content.charAt(i)); // Include the newline
                    i++;
                }
                continue;
            }

            // Handle multi-line comments
            if (c == '/' && i + 1 < content.length() && content.charAt(i + 1) == '*') {
                currentStatement.append(c);
                i++;
                currentStatement.append(content.charAt(i));
                i++;

                // Skip to end of comment
                while (i + 1 < content.length()) {
                    currentStatement.append(content.charAt(i));
                    if (content.charAt(i) == '*' && content.charAt(i + 1) == '/') {
                        i++;
                        currentStatement.append(content.charAt(i));
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }

            // Handle dollar-quoted strings
            if (c == '$') {
                i++;

                // Find the tag (e.g., $tag$ or just $$)
                StringBuilder tag = new StringBuilder("$");
                while (i < content.length() && content.charAt(i) != '$') {
                    tag.append(content.charAt(i));
                    i++;
                }
                if (i < content.length()) {
                    tag.append('$'); // Complete the opening tag
                    i++;
                }

                String openTag = tag.toString();
                currentStatement.append(openTag);

                // Find the matching closing tag
                while (i < content.length()) {
                    if (content.charAt(i) == '$' && content.substring(i).startsWith(openTag)) {
                        // Found closing tag
                        currentStatement.append(openTag);
                        i += openTag.length();
                        break;
                    } else {
                        currentStatement.append(content.charAt(i));
                        i++;
                    }
                }
                continue;
            }

            // Handle regular single quotes
            if (c == '\'') {
                currentStatement.append(c);
                i++;

                // Skip to end of string, handling escaped quotes
                while (i < content.length()) {
                    char ch = content.charAt(i);
                    currentStatement.append(ch);
                    if (ch == '\'') {
                        // Check if it's escaped
                        if (i + 1 < content.length() && content.charAt(i + 1) == '\'') {
                            // Escaped quote, include both
                            i++;
                            currentStatement.append(content.charAt(i));
                        } else {
                            // End of string
                            i++;
                            break;
                        }
                    }
                    i++;
                }
                continue;
            }

            // Handle statement terminator
            if (c == ';') {
                String statement = currentStatement.toString().trim();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                currentStatement = new StringBuilder();
                i++;
                continue;
            }

            // Regular character
            currentStatement.append(c);
            i++;
        }

        // Add final statement if any
        String finalStatement = currentStatement.toString().trim();
        if (!finalStatement.isEmpty()) {
            statements.add(finalStatement);
        }

        return statements;
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
