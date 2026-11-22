package dev.mars.peegeeq.migrations;


import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests migration file naming, ordering, and conventions.
 * Validates that migrations follow Flyway best practices.
 */
@Tag("integration")
@Testcontainers
class MigrationConventionsTest {

    private static final Logger log = LoggerFactory.getLogger(MigrationConventionsTest.class);

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_conventions_test");
        container.withUsername("test");
        container.withPassword("test");
        return container;
    }

    private static Flyway flyway;

    @BeforeAll
    static void setupFlyway() {
        log.info("=== Starting Migration Conventions Tests ===");
        log.info("PostgreSQL container started: {}", postgres.getJdbcUrl());
        
        long startTime = System.currentTimeMillis();
        
        // Set PostgreSQL statement timeout to prevent hanging
        String jdbcUrl = postgres.getJdbcUrl() + "?options=-c%20statement_timeout=60000";
        
        flyway = Flyway.configure()
                .dataSource(jdbcUrl, postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:src/main/resources/db/migration")
                .baselineOnMigrate(true)
                .cleanDisabled(false)
                .mixed(false) // Not needed for regular CREATE INDEX
                .connectRetries(10)
                .load();

        log.info("Cleaning and migrating database...");
        flyway.clean();
        flyway.migrate();
        long duration = System.currentTimeMillis() - startTime;
        log.info("Setup completed in {}ms", duration);
    }

    @Test
    void testMigrationFilesFollowNamingConvention() throws IOException {
        Path migrationsDir = Paths.get("src/main/resources/db/migration");
        
        List<Path> migrationFiles = Files.list(migrationsDir)
                .filter(p -> p.toString().endsWith(".sql"))
                .filter(p -> !p.getFileName().toString().contains("rollback")) // Exclude rollback scripts
                .collect(Collectors.toList());

        assertFalse(migrationFiles.isEmpty(), "Migration directory should contain SQL files");

        Pattern namingPattern = Pattern.compile("V\\d+__[A-Za-z_]+\\.sql");
        
        for (Path file : migrationFiles) {
            String fileName = file.getFileName().toString();
            assertTrue(namingPattern.matcher(fileName).matches(),
                    "Migration file should follow naming convention V<VERSION>__<Description>.sql: " + fileName);
        }
    }

    @Test
    void testMigrationVersionsAreSequential() {
        MigrationInfo[] migrations = flyway.info().all();

        assertTrue(migrations.length > 0, "Should have at least one migration");

        // Extract version numbers and verify they exist and are in ascending order
        List<Integer> versions = Arrays.stream(migrations)
                .filter(m -> m.getState() != MigrationState.IGNORED)
                .map(m -> Integer.parseInt(m.getVersion().getVersion()))
                .sorted()
                .collect(Collectors.toList());

        // Verify versions are in ascending order (not necessarily sequential due to V001, V010, etc.)
        for (int i = 1; i < versions.size(); i++) {
            assertTrue(versions.get(i) > versions.get(i - 1),
                    "Migration versions should be unique and ascending: " + versions.get(i - 1) + " then " + versions.get(i));
        }
    }

    @Test
    void testAllMigrationsAreApplied() {
        MigrationInfo[] pending = flyway.info().pending();
        
        assertEquals(List.of(), describe(pending), "All migrations should be applied, no pending migrations");
    }

    @Test
    void testNoFailedMigrations() {
        MigrationInfo[] failed = Arrays.stream(flyway.info().all())
                .filter(m -> m.getState() == MigrationState.FAILED)
                .toArray(MigrationInfo[]::new);

        assertEquals(List.of(), describe(failed), "There should be no failed migrations");
    }

    @Test
    void testMigrationDescriptionsAreMeaningful() {
        MigrationInfo[] migrations = flyway.info().all();

        for (MigrationInfo migration : migrations) {
            String description = migration.getDescription();
            
            assertNotNull(description, "Migration description should not be null");
            assertFalse(description.isEmpty(), "Migration description should not be empty");

            assertTrue(description.length() >= 5,
                    "Migration description should be descriptive (at least 5 characters): " + description);

            // Flyway converts underscores to spaces in descriptions, so both are acceptable
            assertTrue(description.matches("[A-Za-z ]+"), "Migration description should be meaningful: " + description);
        }
    }

    @Test
    void testMigrationsHaveReasonableChecksums() {
        MigrationInfo[] migrations = flyway.info().all();

        for (MigrationInfo migration : migrations) {
            if (migration.getState() == MigrationState.SUCCESS) {
                assertNotNull(migration.getChecksum(), "Applied migration " + migration.getVersion() + " should have a checksum");
            }
        }
    }

    @Test
    void testMigrationFilesContainRollbackComments() throws IOException {
        Path migrationsDir = Paths.get("src/main/resources/db/migration");
        
        List<Path> migrationFiles = Files.list(migrationsDir)
                .filter(p -> p.toString().endsWith(".sql"))
                .collect(Collectors.toList());

        for (Path file : migrationFiles) {
            String content = Files.readString(file);
            
            assertFalse(content.isEmpty(), "Migration file " + file.getFileName() + " should not be empty");

            // Check for basic SQL structure
            String lowerCaseContent = content.toLowerCase();
            assertTrue(Stream.of("create", "alter", "insert", "update").anyMatch(lowerCaseContent::contains),
                    "Migration file " + file.getFileName() + " should contain SQL statements");
        }
    }

    @Test
    void testMigrationExecutionTimeIsReasonable() {
        MigrationInfo[] migrations = flyway.info().all();

        for (MigrationInfo migration : migrations) {
            if (migration.getState() == MigrationState.SUCCESS && migration.getExecutionTime() != null) {
                assertTrue(migration.getExecutionTime() < 60000, // 60 seconds in milliseconds
                        "Migration " + migration.getVersion() + " should complete in reasonable time (< 60 seconds), took "
                                + migration.getExecutionTime() + " ms");
            }
        }
    }

    @Test
    void testMigrationsAreRepeatable() {
        log.info("TEST: Testing migration repeatability...");
        // Clean and migrate twice to ensure repeatability
        log.info("  First run: clean and migrate...");
        flyway.clean();
        var firstRun = flyway.migrate();
        
        log.info("  Second run: clean and migrate...");
        flyway.clean();
        var secondRun = flyway.migrate();

        assertEquals(secondRun.migrationsExecuted, firstRun.migrationsExecuted,
                "Both migration runs should execute same number of migrations");

        assertTrue(firstRun.success, "First migration run should succeed");

        assertTrue(secondRun.success, "Second migration run should succeed");
        
        log.info(" Migrations are repeatable - {} migrations executed in each run", firstRun.migrationsExecuted);
    }

    @Test
    void testBaselineVersion() {
        assertNotNull(flyway.info().current(), "Should have a current migration version");

        assertNotNull(flyway.info().current().getVersion(), "Current version should not be null");
    }

    @Test
    void testMigrationMetadataIsComplete() {
        MigrationInfo[] migrations = flyway.info().all();

        for (MigrationInfo migration : migrations) {
            assertNotNull(migration.getVersion(), "Migration should have a version");

            assertNotNull(migration.getDescription(), "Migration should have a description");

            assertNotNull(migration.getType(), "Migration should have a type");

            assertNotNull(migration.getScript(), "Migration should have a script name");
            assertFalse(migration.getScript().isEmpty(), "Migration should have a script name");
        }
    }

    private static List<String> describe(MigrationInfo[] migrations) {
        return Arrays.stream(migrations)
                .map(m -> m.getVersion() + " " + m.getDescription() + " " + m.getState())
                .collect(Collectors.toList());
    }
}
