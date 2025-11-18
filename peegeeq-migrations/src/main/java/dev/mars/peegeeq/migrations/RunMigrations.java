package dev.mars.peegeeq.migrations;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.output.MigrateResult;

import java.util.Arrays;

/**
 * Standalone CLI for running PeeGeeQ database migrations.
 * 
 * <p>This class provides a Java-based migration runner that can be:
 * <ul>
 *   <li>Run as a standalone JAR: {@code java -jar peegeeq-migrations.jar migrate}</li>
 *   <li>Used in Docker containers as a migration job</li>
 *   <li>Executed in Kubernetes InitContainers or Jobs</li>
 *   <li>Called from CI/CD pipelines</li>
 * </ul>
 * 
 * <p><b>Configuration via Environment Variables:</b>
 * <ul>
 *   <li>{@code DB_JDBC_URL} - JDBC connection URL (required)</li>
 *   <li>{@code DB_USER} - Database username (required)</li>
 *   <li>{@code DB_PASSWORD} - Database password (required)</li>
 *   <li>{@code DB_CLEAN_ON_START} - Set to "true" to clean database before migration (dev only!)</li>
 *   <li>{@code FLYWAY_BASELINE_VERSION} - Baseline version (default: 1)</li>
 *   <li>{@code FLYWAY_BASELINE_DESCRIPTION} - Baseline description</li>
 * </ul>
 * 
 * <p><b>Commands:</b>
 * <ul>
 *   <li>{@code migrate} - Apply pending migrations (default)</li>
 *   <li>{@code info} - Show migration status and history</li>
 *   <li>{@code validate} - Validate applied migrations</li>
 *   <li>{@code baseline} - Baseline an existing database</li>
 *   <li>{@code repair} - Repair metadata table</li>
 *   <li>{@code clean} - Clean database (dev only - requires DB_CLEAN_ON_START=true)</li>
 * </ul>
 * 
 * <p><b>Examples:</b>
 * <pre>
 * # Migrate with environment variables
 * export DB_JDBC_URL=jdbc:postgresql://localhost:5432/peegeeq_dev
 * export DB_USER=peegeeq_dev
 * export DB_PASSWORD=peegeeq_dev
 * java -jar peegeeq-migrations.jar migrate
 * 
 * # Clean and migrate (dev only)
 * export DB_CLEAN_ON_START=true
 * java -jar peegeeq-migrations.jar migrate
 * 
 * # Show migration info
 * java -jar peegeeq-migrations.jar info
 * 
 * # Kubernetes Job
 * kubectl run peegeeq-migrations --image=peegeeq-migrations:latest \
 *   --env="DB_JDBC_URL=jdbc:postgresql://postgres:5432/peegeeq" \
 *   --env="DB_USER=peegeeq" \
 *   --env="DB_PASSWORD=secret" \
 *   --restart=Never \
 *   -- migrate
 * </pre>
 */
public class RunMigrations {
    
    private static final String DEFAULT_COMMAND = "migrate";
    
    public static void main(String[] args) {
        try {
            // Parse command
            String command = args.length > 0 ? args[0].toLowerCase() : DEFAULT_COMMAND;
            
            // Load configuration from environment
            String jdbcUrl = getRequiredEnv("DB_JDBC_URL");
            String user = getRequiredEnv("DB_USER");
            String password = getRequiredEnv("DB_PASSWORD");
            boolean cleanOnStart = Boolean.parseBoolean(System.getenv().getOrDefault("DB_CLEAN_ON_START", "false"));
            
            System.out.println("╔════════════════════════════════════════════════════════════════╗");
            System.out.println("║           PeeGeeQ Database Migration Runner                    ║");
            System.out.println("╚════════════════════════════════════════════════════════════════╝");
            System.out.println();
            System.out.println("Command:  " + command);
            System.out.println("Database: " + maskPassword(jdbcUrl));
            System.out.println("User:     " + user);
            System.out.println();
            
            // Configure Flyway
            Flyway flyway = Flyway.configure()
                    .dataSource(jdbcUrl, user, password)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .outOfOrder(false)
                    .validateOnMigrate(true)
                    .mixed(true) // Allow CREATE INDEX CONCURRENTLY
                    .load();
            
            // Execute command
            switch (command) {
                case "migrate":
                    if (cleanOnStart) {
                        System.out.println("⚠️  WARNING: DB_CLEAN_ON_START=true - Cleaning database!");
                        System.out.println("⚠️  This will DELETE ALL DATA!");
                        System.out.println();
                        flyway.clean();
                        System.out.println("✓ Database cleaned");
                        System.out.println();
                    }
                    
                    MigrateResult result = flyway.migrate();
                    System.out.println("✓ Migration completed successfully");
                    System.out.println("  Migrations executed: " + result.migrationsExecuted);
                    System.out.println("  Target version:      " + (result.targetSchemaVersion != null ? result.targetSchemaVersion : "latest"));
                    break;
                    
                case "info":
                    MigrationInfoService info = flyway.info();
                    System.out.println("Migration Status:");
                    System.out.println("─────────────────────────────────────────────────────────────────");
                    for (MigrationInfo migration : info.all()) {
                        System.out.printf("%-10s | %-40s | %s%n",
                                migration.getVersion(),
                                migration.getDescription(),
                                migration.getState());
                    }
                    break;
                    
                case "validate":
                    flyway.validate();
                    System.out.println("✓ Validation successful - all migrations are consistent");
                    break;
                    
                case "baseline":
                    flyway.baseline();
                    System.out.println("✓ Database baselined successfully");
                    break;
                    
                case "repair":
                    flyway.repair();
                    System.out.println("✓ Metadata table repaired successfully");
                    break;
                    
                case "clean":
                    if (!cleanOnStart) {
                        System.err.println("❌ ERROR: 'clean' command requires DB_CLEAN_ON_START=true");
                        System.err.println("   This is a safety measure to prevent accidental data loss");
                        System.exit(1);
                    }
                    flyway.clean();
                    System.out.println("✓ Database cleaned successfully");
                    break;
                    
                default:
                    System.err.println("❌ ERROR: Unknown command: " + command);
                    System.err.println("   Valid commands: migrate, info, validate, baseline, repair, clean");
                    System.exit(1);
            }
            
            System.out.println();
            System.out.println("✓ Operation completed successfully");
            System.exit(0);
            
        } catch (Exception e) {
            System.err.println();
            System.err.println("❌ ERROR: Migration failed");
            System.err.println("   " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static String getRequiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Required environment variable not set: " + name + "\n" +
                    "Please set: DB_JDBC_URL, DB_USER, DB_PASSWORD");
        }
        return value;
    }
    
    private static String maskPassword(String jdbcUrl) {
        return jdbcUrl.replaceAll("password=[^&;]+", "password=***");
    }
}

