package dev.mars.peegeeq.test.schema;

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
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.nio.file.Path;
import java.util.Set;
import java.util.EnumSet;

/**
 * Centralized schema initializer for all PeeGeeQ tests.
 *
 * IMPORTANT: schema creation is delegated to Flyway migrations under `db/migration`.
 * This avoids duplicated DDL definitions in Java and keeps test schema aligned with
 * production migration scripts.
 * 
 * Usage:
 * ```java
 * // Initialize complete schema
 * PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
 * 
 * // Initialize only specific components
 * PeeGeeQTestSchemaInitializer.initializeSchema(postgres, 
 *     SchemaComponent.OUTBOX, SchemaComponent.BITEMPORAL);
 * ```
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-09
 * @version 1.0
 */
public class PeeGeeQTestSchemaInitializer {
    
    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQTestSchemaInitializer.class);
    
    /**
     * Schema components that can be initialized independently.
     */
    public enum SchemaComponent {
        /** Core schema version tracking */
        SCHEMA_VERSION,
        /** Outbox pattern tables and functions */
        OUTBOX,
        /** Native queue tables and functions */
        NATIVE_QUEUE,
        /** Dead letter queue table */
        DEAD_LETTER_QUEUE,
        /** Bi-temporal event log with triggers */
        BITEMPORAL,
        /** Metrics and monitoring tables */
        METRICS,
        /** Consumer group fanout tables and functions */
        CONSUMER_GROUP_FANOUT,
        /**
         * All queue-related components (OUTBOX, NATIVE_QUEUE, DEAD_LETTER_QUEUE).
         * Use this when tests use PeeGeeQManager which requires all queue tables for health checks.
         */
        QUEUE_ALL,
        /** All components */
        ALL
    }
    
    /**
     * Initialize database schema with specified components in the default "public" schema.
     *
     * @param postgres the PostgreSQL container
     * @param components the schema components to initialize
     */
    public static void initializeSchema(PostgreSQLContainer<?> postgres, SchemaComponent... components) {
        initializeSchema(postgres, "public", components);
    }

    /**
     * Initialize database schema with specified components in the default "public" schema.
     *
     * @param jdbcUrl the JDBC URL
     * @param username the database username
     * @param password the database password
     * @param components the schema components to initialize
     */
    public static void initializeSchema(String jdbcUrl, String username, String password, SchemaComponent... components) {
        initializeSchema(jdbcUrl, username, password, "public", components);
    }

    /**
     * Initialize database schema with specified components in a custom schema.
     *
     * @param postgres the PostgreSQL container
     * @param schema the schema name to use
     * @param components the schema components to initialize
     */
    public static void initializeSchema(PostgreSQLContainer<?> postgres, String schema, SchemaComponent... components) {
        initializeSchema(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), schema, components);
    }

    /**
     * Initialize database schema with specified components in a custom schema.
     *
     * @param jdbcUrl the JDBC URL
     * @param username the database username
     * @param password the database password
     * @param schema the schema name to use
     * @param components the schema components to initialize
     */
    public static void initializeSchema(String jdbcUrl, String username, String password, String schema, SchemaComponent... components) {
        validateSchema(schema);
        Set<SchemaComponent> componentSet = normalizeComponentSet(components);

        // Components are retained in the API for compatibility with existing tests,
        // but schema creation now always comes from Flyway migrations.
        if (!componentSet.contains(SchemaComponent.ALL)) {
            logger.debug("Requested schema components: {} (Flyway applies full migration set)", componentSet);
        }

        long start = System.nanoTime();
        try {
            Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations(resolveMigrationLocation())
                .baselineOnMigrate(true)
                .validateOnMigrate(true)
                .load();

            logMigrationPlan(flyway, jdbcUrl, schema);

            flyway.migrate();

            // Safety net: enforce critical bitemporal column expected by notification triggers.
            ensureBitemporalCompatibility(jdbcUrl, username, password, schema);

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            logger.debug("Initialized schema '{}' via Flyway in {}ms", schema, elapsedMs);
        } catch (Exception e) {
            logger.error("Failed to initialize PeeGeeQ test schema '{}' via Flyway", schema, e);
            throw new RuntimeException("PeeGeeQ schema initialization failed", e);
        }
    }
    
    /**
     * Clean up test data from specified schema components in the default "public" schema.
     *
     * @param postgres the PostgreSQL container
     * @param components the schema components to clean
     */
    public static void cleanupTestData(PostgreSQLContainer<?> postgres, SchemaComponent... components) {
        cleanupTestData(postgres, "public", components);
    }

    /**
     * Clean up test data from specified schema components in the default "public" schema.
     *
     * @param jdbcUrl the JDBC URL
     * @param username the database username
     * @param password the database password
     * @param components the schema components to clean
     */
    public static void cleanupTestData(String jdbcUrl, String username, String password, SchemaComponent... components) {
        cleanupTestData(jdbcUrl, username, password, "public", components);
    }

    /**
     * Clean up test data from specified schema components in a custom schema.
     *
     * @param postgres the PostgreSQL container
     * @param schema the schema name to use
     * @param components the schema components to clean
     */
    public static void cleanupTestData(PostgreSQLContainer<?> postgres, String schema, SchemaComponent... components) {
        cleanupTestData(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), schema, components);
    }

    /**
     * Clean up test data from specified schema components in a custom schema.
     *
     * @param jdbcUrl the JDBC URL
     * @param username the database username
     * @param password the database password
     * @param schema the schema name to use
     * @param components the schema components to clean
     */
    public static void cleanupTestData(String jdbcUrl, String username, String password, String schema, SchemaComponent... components) {
        validateSchema(schema);
        Set<SchemaComponent> componentSet = normalizeComponentSet(components);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement()) {

            logger.debug("Cleaning up test data for schema '{}' components: {}", schema, componentSet);

            // Set search_path to the target schema
            stmt.execute("SET search_path TO " + schema);

            // Clean in reverse dependency order to avoid foreign key conflicts
            if (componentSet.contains(SchemaComponent.OUTBOX)) {
                stmt.execute("TRUNCATE TABLE outbox_consumer_groups CASCADE");
                stmt.execute("TRUNCATE TABLE outbox CASCADE");
            }

            if (componentSet.contains(SchemaComponent.NATIVE_QUEUE)) {
                stmt.execute("TRUNCATE TABLE message_processing CASCADE");
                stmt.execute("TRUNCATE TABLE queue_messages CASCADE");
            }

            if (componentSet.contains(SchemaComponent.DEAD_LETTER_QUEUE)) {
                stmt.execute("TRUNCATE TABLE dead_letter_queue CASCADE");
            }

            if (componentSet.contains(SchemaComponent.BITEMPORAL)) {
                stmt.execute("TRUNCATE TABLE bitemporal_subscriptions CASCADE");
                stmt.execute("TRUNCATE TABLE bitemporal_event_log CASCADE");
            }

            if (componentSet.contains(SchemaComponent.METRICS)) {
                stmt.execute("TRUNCATE TABLE queue_metrics CASCADE");
                stmt.execute("TRUNCATE TABLE connection_pool_metrics CASCADE");
            }

            if (componentSet.contains(SchemaComponent.CONSUMER_GROUP_FANOUT)) {
                stmt.execute("TRUNCATE TABLE processed_ledger CASCADE");
                stmt.execute("TRUNCATE TABLE consumer_group_index CASCADE");
                stmt.execute("TRUNCATE TABLE outbox_topic_subscriptions CASCADE");
                stmt.execute("TRUNCATE TABLE outbox_topics CASCADE");
            }

            logger.debug("Test data cleanup completed for schema '{}' components: {}", schema, componentSet);

        } catch (Exception e) {
            logger.warn("Failed to cleanup test data for schema '{}' (tables may not exist yet)", schema, e);
        }
    }

    private static void validateSchema(String schema) {
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("Schema parameter is required and cannot be null or blank");
        }
        if (!schema.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid schema name: " + schema +
                " - must start with letter or underscore, followed by alphanumeric or underscore");
        }
        if (schema.startsWith("pg_") || schema.equals("information_schema")) {
            throw new IllegalArgumentException("Reserved schema name: " + schema +
                " - cannot use PostgreSQL system schemas (pg_*, information_schema)");
        }
    }

    private static String resolveMigrationLocation() {
        Path cwd = Path.of("").toAbsolutePath().normalize();

        Path local = cwd.resolve("peegeeq-migrations")
            .resolve("src")
            .resolve("main")
            .resolve("resources")
            .resolve("db")
            .resolve("migration");
        if (local.toFile().isDirectory()) {
            return "filesystem:" + local;
        }

        Path sibling = cwd.resolve("..")
            .resolve("peegeeq-migrations")
            .resolve("src")
            .resolve("main")
            .resolve("resources")
            .resolve("db")
            .resolve("migration")
            .normalize();
        if (sibling.toFile().isDirectory()) {
            return "filesystem:" + sibling;
        }

        throw new IllegalStateException("Could not locate Flyway migrations directory from: " + cwd);
    }

    private static void ensureBitemporalCompatibility(String jdbcUrl, String username, String password, String schema) {
        String sql = "ALTER TABLE IF EXISTS " + schema + ".bitemporal_event_log " +
            "ADD COLUMN IF NOT EXISTS causation_id VARCHAR(255)";

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException("Failed to enforce bitemporal compatibility in schema '" + schema + "'", e);
        }
    }

    private static void logMigrationPlan(Flyway flyway, String jdbcUrl, String schema) {
        MigrationInfo[] pending = flyway.info().pending();
        String dbName = parseDatabaseName(jdbcUrl);
        logger.info("Flyway migrate context: database='{}', schema='{}', pendingMigrations={}",
            dbName, schema, pending.length);

        for (MigrationInfo migration : pending) {
            logger.info("  Pending migration: version='{}', description='{}', script='{}'",
                migration.getVersion(), migration.getDescription(), migration.getScript());
        }
    }

    private static String parseDatabaseName(String jdbcUrl) {
        int slash = jdbcUrl.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= jdbcUrl.length()) {
            return jdbcUrl;
        }

        int query = jdbcUrl.indexOf('?', slash);
        if (query < 0) {
            return jdbcUrl.substring(slash + 1);
        }
        return jdbcUrl.substring(slash + 1, query);
    }

    private static Set<SchemaComponent> normalizeComponentSet(SchemaComponent... components) {
        Set<SchemaComponent> componentSet = EnumSet.noneOf(SchemaComponent.class);
        if (components == null || components.length == 0) {
            componentSet.add(SchemaComponent.ALL);
            return componentSet;
        }

        for (SchemaComponent component : components) {
            if (component == SchemaComponent.ALL) {
                componentSet.add(SchemaComponent.ALL);
                continue;
            }
            if (component == SchemaComponent.QUEUE_ALL) {
                componentSet.add(SchemaComponent.OUTBOX);
                componentSet.add(SchemaComponent.NATIVE_QUEUE);
                componentSet.add(SchemaComponent.DEAD_LETTER_QUEUE);
                continue;
            }
            componentSet.add(component);
        }

        if (componentSet.isEmpty()) {
            componentSet.add(SchemaComponent.ALL);
        }
        return componentSet;
    }

}
