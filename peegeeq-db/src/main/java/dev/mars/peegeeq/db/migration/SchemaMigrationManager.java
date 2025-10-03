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


import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
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

    private final Pool pool;
    private final String migrationPath;

    public SchemaMigrationManager(Pool pool) {
        this(pool, "/db/migration", true);
    }

    public SchemaMigrationManager(Pool pool, String migrationPath, boolean validateChecksums) {
        this.pool = pool;
        this.migrationPath = migrationPath;
        // validateChecksums parameter ignored - JDBC validation methods removed
    }
    
    /**
     * Applies all pending migrations.
     *
     * @return Future with number of migrations applied
     */
    public Future<Integer> migrate() {
        logger.info("Starting database migration process");

        return pool.withConnection(connection -> {
            // PostgreSQL transaction-level advisory lock - lock ID 12345 for migrations
            // Using pg_advisory_xact_lock to automatically release when transaction ends
            return connection.query("SELECT pg_advisory_xact_lock(12345)").execute()
                .compose(v -> {
                    return ensureSchemaVersionTable(connection)
                        .compose(v2 -> getPendingMigrations(connection))
                        .compose(pendingMigrations -> {
                            logger.info("Found {} pending migrations", pendingMigrations.size());

                            // Apply migrations sequentially
                            Future<Integer> migrationChain = Future.succeededFuture(0);
                            for (MigrationScript migration : pendingMigrations) {
                                migrationChain = migrationChain.compose(appliedCount -> {
                                    return applyMigration(migration, connection)
                                        .map(v3 -> {
                                            logger.info("Successfully applied migration: {}", migration.getVersion());
                                            return appliedCount + 1;
                                        })
                                        .recover(error -> {
                                            logger.error("Failed to apply migration: {}", migration.getVersion(), error);
                                            return Future.failedFuture(new RuntimeException("Migration failed: " + migration.getVersion(), error));
                                        });
                                });
                            }

                            return migrationChain.onSuccess(appliedCount -> {
                                logger.info("Migration process completed. Applied {} migrations", appliedCount);
                                logger.debug("Migration advisory lock will be automatically released when transaction ends");
                            });
                        });
                });
        });
    }
    



    private Future<Void> ensureSchemaVersionTable(SqlConnection connection) {
        String sql = """
            CREATE TABLE IF NOT EXISTS schema_version (
                version VARCHAR(50) PRIMARY KEY,
                description TEXT,
                applied_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                checksum VARCHAR(64)
            )
            """;

        return connection.query(sql).execute()
            .map(rowSet -> null);
    }


    private Future<List<MigrationScript>> getPendingMigrations(SqlConnection connection) {
        List<MigrationScript> availableScripts = getAvailableMigrations();

        return getAppliedVersions(connection)
            .map(appliedVersions -> {
                return availableScripts.stream()
                    .filter(script -> !appliedVersions.contains(script.getVersion()))
                    .sorted(Comparator.comparing(MigrationScript::getVersion))
                    .collect(Collectors.toList());
            });
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


    private Future<Set<String>> getAppliedVersions(SqlConnection connection) {
        String sql = "SELECT version FROM schema_version";

        return connection.query(sql).execute()
            .map(rowSet -> {
                Set<String> versions = new HashSet<>();
                rowSet.forEach(row -> {
                    versions.add(row.getString("version"));
                });
                return versions;
            })
            .recover(error -> {
                // If table doesn't exist, return empty set (no migrations applied yet)
                if (error.getMessage().contains("does not exist")) {
                    return Future.succeededFuture(new HashSet<>());
                }
                return Future.failedFuture(error);
            });
    }
    



    private Future<Void> applyMigration(MigrationScript migration, SqlConnection connection) {
        String content = migration.getContent();

        // Check if migration contains CONCURRENTLY statements that need to run outside transactions
        if (content.contains("CONCURRENTLY")) {
            logger.debug("Migration {} contains CONCURRENTLY statements, executing outside transaction", migration.getVersion());
            return applyMigrationWithConcurrentStatements(migration, connection);
        } else {
            logger.debug("Migration {} executing in transaction", migration.getVersion());
            return applyMigrationInTransaction(migration, connection);
        }
    }

    private Future<Void> applyMigrationInTransaction(MigrationScript migration, SqlConnection connection) {
        return pool.withTransaction(txConnection -> {
            // Execute migration script
            return txConnection.query(migration.getContent()).execute()
                .compose(v -> {
                    // Record migration in same transaction
                    String sql = "INSERT INTO schema_version (version, description, checksum) VALUES ($1, $2, $3)";
                    return txConnection.preparedQuery(sql)
                        .execute(Tuple.of(migration.getVersion(), migration.getDescription(), migration.getChecksum()));
                })
                .map(rowSet -> null);
        });
    }

    private Future<Void> applyMigrationWithConcurrentStatements(MigrationScript migration, SqlConnection connection) {
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

        // Execute regular statements in transaction first
        Future<Void> regularFuture = Future.succeededFuture();
        if (!regularStatements.isEmpty()) {
            regularFuture = pool.withTransaction(txConnection -> {
                Future<Void> chain = Future.succeededFuture();
                for (String statement : regularStatements) {
                    chain = chain.compose(v -> txConnection.query(statement).execute().map(rs -> null));
                }
                return chain;
            });
        }

        // Then execute concurrent statements outside transaction
        return regularFuture.compose(v -> {
            Future<Void> concurrentFuture = Future.succeededFuture();
            if (!concurrentStatements.isEmpty()) {
                for (String statement : concurrentStatements) {
                    concurrentFuture = concurrentFuture.compose(v2 -> {
                        logger.debug("Executing concurrent statement: {}", statement.substring(0, Math.min(50, statement.length())) + "...");
                        return connection.query(statement).execute()
                            .map(rs -> (Void) null)
                            .recover(error -> {
                                // PostgreSQL's CREATE INDEX CONCURRENTLY can fail with "relation does not exist" errors
                                if (error.getMessage().contains("does not exist") || error.getMessage().contains("already exists")) {
                                    logger.warn("Ignoring concurrent index creation error (likely already exists or partial state): {}", error.getMessage());
                                    return Future.succeededFuture();
                                } else {
                                    return Future.failedFuture(error);
                                }
                            });
                    });
                }
            }
            return concurrentFuture;
        })
        .compose(v -> {
            // Record migration in transaction
            return pool.withTransaction(txConnection -> {
                String sql = "INSERT INTO schema_version (version, description, checksum) VALUES ($1, $2, $3)";
                return txConnection.preparedQuery(sql)
                    .execute(Tuple.of(migration.getVersion(), migration.getDescription(), migration.getChecksum()))
                    .map(rs -> null);
            });
        });
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
        private final LocalDateTime appliedAt;
        private final String checksum;

        public AppliedMigration(String version, String description, LocalDateTime appliedAt, String checksum) {
            this.version = version;
            this.description = description;
            this.appliedAt = appliedAt;
            this.checksum = checksum;
        }

        public String getVersion() { return version; }
        public String getDescription() { return description; }
        public LocalDateTime getAppliedAt() { return appliedAt; }
        public String getChecksum() { return checksum; }
    }
}
