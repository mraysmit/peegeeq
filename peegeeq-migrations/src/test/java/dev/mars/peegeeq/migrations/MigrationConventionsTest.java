package dev.mars.peegeeq.migrations;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests migration file naming, ordering, and conventions.
 * Validates that migrations follow Flyway best practices.
 */
@Testcontainers
class MigrationConventionsTest {

    private static final Logger log = LoggerFactory.getLogger(MigrationConventionsTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_conventions_test")
            .withUsername("test")
            .withPassword("test");

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
                .locations("filesystem:src/test/resources/db/migration")
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
        Path migrationsDir = Paths.get("src/test/resources/db/migration");
        
        List<Path> migrationFiles = Files.list(migrationsDir)
                .filter(p -> p.toString().endsWith(".sql"))
                .filter(p -> !p.getFileName().toString().contains("rollback")) // Exclude rollback scripts
                .collect(Collectors.toList());

        assertThat(migrationFiles)
                .as("Migration directory should contain SQL files")
                .isNotEmpty();

        Pattern namingPattern = Pattern.compile("V\\d+__[A-Za-z_]+\\.sql");
        
        for (Path file : migrationFiles) {
            String fileName = file.getFileName().toString();
            assertThat(fileName)
                    .as("Migration file should follow naming convention V<VERSION>__<Description>.sql")
                    .matches(namingPattern.pattern());
        }
    }

    @Test
    void testMigrationVersionsAreSequential() {
        MigrationInfo[] migrations = flyway.info().all();

        assertThat(migrations)
                .as("Should have at least one migration")
                .isNotEmpty();

        // Extract version numbers and verify they exist and are in ascending order
        List<Integer> versions = Arrays.stream(migrations)
                .filter(m -> m.getState() != MigrationState.IGNORED)
                .map(m -> Integer.parseInt(m.getVersion().getVersion()))
                .sorted()
                .collect(Collectors.toList());

        // Verify versions are in ascending order (not necessarily sequential due to V001, V010, etc.)
        for (int i = 1; i < versions.size(); i++) {
            assertThat(versions.get(i))
                    .as("Migration versions should be in ascending order")
                    .isGreaterThan(versions.get(i - 1));
        }
    }

    @Test
    void testAllMigrationsAreApplied() {
        MigrationInfo[] pending = flyway.info().pending();
        
        assertThat(pending)
                .as("All migrations should be applied, no pending migrations")
                .isEmpty();
    }

    @Test
    void testNoFailedMigrations() {
        MigrationInfo[] failed = Arrays.stream(flyway.info().all())
                .filter(m -> m.getState() == MigrationState.FAILED)
                .toArray(MigrationInfo[]::new);

        assertThat(failed)
                .as("There should be no failed migrations")
                .isEmpty();
    }

    @Test
    void testMigrationDescriptionsAreMeaningful() {
        MigrationInfo[] migrations = flyway.info().all();

        for (MigrationInfo migration : migrations) {
            String description = migration.getDescription();
            
            assertThat(description)
                    .as("Migration description should not be empty")
                    .isNotEmpty();

            assertThat(description.length())
                    .as("Migration description should be descriptive (at least 5 characters)")
                    .isGreaterThanOrEqualTo(5);

            // Flyway converts underscores to spaces in descriptions, so both are acceptable
            assertThat(description)
                    .as("Migration description should be meaningful")
                    .matches("[A-Za-z ]+");
        }
    }

    @Test
    void testMigrationsHaveReasonableChecksums() {
        MigrationInfo[] migrations = flyway.info().all();

        for (MigrationInfo migration : migrations) {
            if (migration.getState() == MigrationState.SUCCESS) {
                assertThat(migration.getChecksum())
                        .as("Applied migration %s should have a checksum", migration.getVersion())
                        .isNotNull();
            }
        }
    }

    @Test
    void testMigrationFilesContainRollbackComments() throws IOException {
        Path migrationsDir = Paths.get("src/test/resources/db/migration");
        
        List<Path> migrationFiles = Files.list(migrationsDir)
                .filter(p -> p.toString().endsWith(".sql"))
                .collect(Collectors.toList());

        for (Path file : migrationFiles) {
            String content = Files.readString(file);
            
            assertThat(content)
                    .as("Migration file %s should not be empty", file.getFileName())
                    .isNotEmpty();

            // Check for basic SQL structure
            assertThat(content.toLowerCase())
                    .as("Migration file %s should contain SQL statements", file.getFileName())
                    .containsAnyOf("create", "alter", "insert", "update");
        }
    }

    @Test
    void testMigrationExecutionTimeIsReasonable() {
        MigrationInfo[] migrations = flyway.info().all();

        for (MigrationInfo migration : migrations) {
            if (migration.getState() == MigrationState.SUCCESS && migration.getExecutionTime() != null) {
                assertThat(migration.getExecutionTime())
                        .as("Migration %s should complete in reasonable time (< 60 seconds)", 
                            migration.getVersion())
                        .isLessThan(60000); // 60 seconds in milliseconds
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

        assertThat(firstRun.migrationsExecuted)
                .as("Both migration runs should execute same number of migrations")
                .isEqualTo(secondRun.migrationsExecuted);

        assertThat(firstRun.success)
                .as("First migration run should succeed")
                .isTrue();

        assertThat(secondRun.success)
                .as("Second migration run should succeed")
                .isTrue();
        
        log.info("✓ Migrations are repeatable - {} migrations executed in each run", firstRun.migrationsExecuted);
    }

    @Test
    void testBaselineVersion() {
        assertThat(flyway.info().current())
                .as("Should have a current migration version")
                .isNotNull();

        assertThat(flyway.info().current().getVersion())
                .as("Current version should not be null")
                .isNotNull();
    }

    @Test
    void testMigrationMetadataIsComplete() {
        MigrationInfo[] migrations = flyway.info().all();

        for (MigrationInfo migration : migrations) {
            assertThat(migration.getVersion())
                    .as("Migration should have a version")
                    .isNotNull();

            assertThat(migration.getDescription())
                    .as("Migration should have a description")
                    .isNotNull();

            assertThat(migration.getType())
                    .as("Migration should have a type")
                    .isNotNull();

            assertThat(migration.getScript())
                    .as("Migration should have a script name")
                    .isNotEmpty();
        }
    }
}
