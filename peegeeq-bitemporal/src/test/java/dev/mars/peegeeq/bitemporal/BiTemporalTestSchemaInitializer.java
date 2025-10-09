package dev.mars.peegeeq.bitemporal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Schema initializer for bi-temporal tests.
 * 
 * This class follows the exact same pattern as the outbox TestSchemaInitializer,
 * creating all required tables for bi-temporal event store tests.
 * 
 * Following established patterns from peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/TestSchemaInitializer.java
 */
public class BiTemporalTestSchemaInitializer {
    
    private static final Logger logger = LoggerFactory.getLogger(BiTemporalTestSchemaInitializer.class);
    
    /**
     * Initialize the database schema for bi-temporal tests.
     * Creates all required tables following the established schema from V001__Create_Base_Tables.sql
     *
     * @param jdbcUrl the JDBC URL
     * @param username the database username
     * @param password the database password
     */
    public static void initializeSchema(String jdbcUrl, String username, String password) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement()) {

            logger.debug("Initializing test schema for bi-temporal tests");

            // Create bitemporal_event_log table - following exact schema from V001__Create_Base_Tables.sql
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS bitemporal_event_log (
                    -- Primary key and identity
                    id BIGSERIAL PRIMARY KEY,
                    event_id VARCHAR(255) NOT NULL,
                    event_type VARCHAR(255) NOT NULL,

                    -- Bi-temporal dimensions
                    valid_time TIMESTAMP WITH TIME ZONE NOT NULL,
                    transaction_time TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,

                    -- Event data
                    payload JSONB NOT NULL,
                    headers JSONB DEFAULT '{}',

                    -- Versioning and corrections
                    version BIGINT DEFAULT 1 NOT NULL,
                    previous_version_id VARCHAR(255),
                    is_correction BOOLEAN DEFAULT FALSE NOT NULL,
                    correction_reason TEXT,

                    -- Grouping and correlation
                    correlation_id VARCHAR(255),
                    aggregate_id VARCHAR(255),

                    -- Metadata
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,

                    -- Constraints
                    CONSTRAINT chk_version_positive CHECK (version > 0),
                    CONSTRAINT chk_correction_reason CHECK (
                        (is_correction = FALSE AND correction_reason IS NULL) OR
                        (is_correction = TRUE AND correction_reason IS NOT NULL)
                    )
                )
                """);

            // Create indexes for performance - following established patterns
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_bitemporal_event_log_event_id 
                ON bitemporal_event_log(event_id)
                """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_bitemporal_event_log_event_type 
                ON bitemporal_event_log(event_type)
                """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_bitemporal_event_log_valid_time 
                ON bitemporal_event_log(valid_time)
                """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_bitemporal_event_log_transaction_time 
                ON bitemporal_event_log(transaction_time)
                """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_bitemporal_event_log_aggregate_id 
                ON bitemporal_event_log(aggregate_id)
                """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_bitemporal_event_log_correlation_id 
                ON bitemporal_event_log(correlation_id)
                """);

            // Create bi-temporal query optimization indexes
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_bitemporal_event_log_bitemporal_query
                ON bitemporal_event_log(event_type, aggregate_id, valid_time, transaction_time)
                """);

            // CRITICAL: Create PostgreSQL trigger function for NOTIFY - following exact V001__Create_Base_Tables.sql
            stmt.execute("""
                CREATE OR REPLACE FUNCTION notify_bitemporal_event() RETURNS TRIGGER AS $$
                BEGIN
                    -- Send notification with event details
                    PERFORM pg_notify(
                        'bitemporal_events',
                        json_build_object(
                            'event_id', NEW.event_id,
                            'event_type', NEW.event_type,
                            'aggregate_id', NEW.aggregate_id,
                            'correlation_id', NEW.correlation_id,
                            'is_correction', NEW.is_correction,
                            'transaction_time', extract(epoch from NEW.transaction_time)
                        )::text
                    );

                    -- Send type-specific notification
                    PERFORM pg_notify(
                        'bitemporal_events_' || NEW.event_type,
                        json_build_object(
                            'event_id', NEW.event_id,
                            'aggregate_id', NEW.aggregate_id,
                            'correlation_id', NEW.correlation_id,
                            'is_correction', NEW.is_correction,
                            'transaction_time', extract(epoch from NEW.transaction_time)
                        )::text
                    );

                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql;
                """);

            // CRITICAL: Create trigger for bi-temporal event notifications - following exact V001__Create_Base_Tables.sql
            stmt.execute("""
                CREATE TRIGGER trigger_notify_bitemporal_event
                    AFTER INSERT ON bitemporal_event_log
                    FOR EACH ROW
                    EXECUTE FUNCTION notify_bitemporal_event();
                """);

            logger.debug("Bi-temporal test schema initialized successfully");

        } catch (Exception e) {
            logger.error("Failed to initialize bi-temporal test schema", e);
            throw new RuntimeException("Bi-temporal schema initialization failed", e);
        }
    }

    /**
     * Initialize schema using PostgreSQL container.
     * Convenience method following the outbox pattern.
     *
     * @param postgres the PostgreSQL container
     */
    public static void initializeSchema(PostgreSQLContainer<?> postgres) {
        initializeSchema(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /**
     * Clean up test data from all bi-temporal tables.
     * Useful for test isolation.
     *
     * @param postgres the PostgreSQL container
     */
    public static void cleanupTestData(PostgreSQLContainer<?> postgres) {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("TRUNCATE TABLE bitemporal_event_log CASCADE");
            logger.debug("Bi-temporal test data cleaned up successfully");

        } catch (Exception e) {
            logger.warn("Failed to cleanup bi-temporal test data (table may not exist yet)", e);
        }
    }

    /**
     * Clean up test data using JDBC URL.
     * 
     * @param jdbcUrl the JDBC URL
     * @param username the database username
     * @param password the database password
     */
    public static void cleanupTestData(String jdbcUrl, String username, String password) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement()) {

            stmt.execute("TRUNCATE TABLE bitemporal_event_log CASCADE");
            logger.debug("Bi-temporal test data cleaned up successfully");

        } catch (Exception e) {
            logger.warn("Failed to cleanup bi-temporal test data (table may not exist yet)", e);
        }
    }
}
