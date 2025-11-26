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
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Set;
import java.util.EnumSet;

/**
 * Centralized schema initializer for all PeeGeeQ tests.
 * 
 * This class replaces all the scattered TestSchemaInitializer classes across modules
 * and provides a single source of truth for database schema initialization.
 * 
 * Based on the complete schema from peegeeq-migrations/src/main/resources/db/migration/V001__Create_Base_Tables.sql
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
     * Initialize database schema with specified components.
     * 
     * @param postgres the PostgreSQL container
     * @param components the schema components to initialize
     */
    public static void initializeSchema(PostgreSQLContainer<?> postgres, SchemaComponent... components) {
        initializeSchema(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), components);
    }
    
    /**
     * Initialize database schema with specified components.
     * 
     * @param jdbcUrl the JDBC URL
     * @param username the database username
     * @param password the database password
     * @param components the schema components to initialize
     */
    public static void initializeSchema(String jdbcUrl, String username, String password, SchemaComponent... components) {
        Set<SchemaComponent> componentSet = EnumSet.noneOf(SchemaComponent.class);

        for (SchemaComponent component : components) {
            if (component == SchemaComponent.ALL) {
                componentSet = EnumSet.allOf(SchemaComponent.class);
                componentSet.remove(SchemaComponent.ALL); // Remove the ALL marker
                componentSet.remove(SchemaComponent.QUEUE_ALL); // Remove the QUEUE_ALL marker
                break;
            }
            if (component == SchemaComponent.QUEUE_ALL) {
                // Expand QUEUE_ALL into its component parts
                componentSet.add(SchemaComponent.OUTBOX);
                componentSet.add(SchemaComponent.NATIVE_QUEUE);
                componentSet.add(SchemaComponent.DEAD_LETTER_QUEUE);
            } else {
                componentSet.add(component);
            }
        }

        // Remove marker components that shouldn't be processed directly
        componentSet.remove(SchemaComponent.ALL);
        componentSet.remove(SchemaComponent.QUEUE_ALL);
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement()) {

            logger.debug("Initializing PeeGeeQ test schema with components: {}", componentSet);

            // Initialize components in dependency order
            if (componentSet.contains(SchemaComponent.SCHEMA_VERSION)) {
                initializeSchemaVersion(stmt);
            }
            
            if (componentSet.contains(SchemaComponent.OUTBOX)) {
                initializeOutboxSchema(stmt);
            }
            
            if (componentSet.contains(SchemaComponent.NATIVE_QUEUE)) {
                initializeNativeQueueSchema(stmt);
            }
            
            if (componentSet.contains(SchemaComponent.DEAD_LETTER_QUEUE)) {
                initializeDeadLetterQueueSchema(stmt);
            }
            
            if (componentSet.contains(SchemaComponent.BITEMPORAL)) {
                initializeBitemporalSchema(stmt);
            }

            if (componentSet.contains(SchemaComponent.METRICS)) {
                initializeMetricsSchema(stmt);
            }

            if (componentSet.contains(SchemaComponent.CONSUMER_GROUP_FANOUT)) {
                initializeConsumerGroupFanoutSchema(stmt);
            }

            logger.debug("PeeGeeQ test schema initialized successfully with components: {}", componentSet);

        } catch (Exception e) {
            logger.error("Failed to initialize PeeGeeQ test schema", e);
            throw new RuntimeException("PeeGeeQ schema initialization failed", e);
        }
    }
    
    /**
     * Clean up test data from specified schema components.
     * 
     * @param postgres the PostgreSQL container
     * @param components the schema components to clean
     */
    public static void cleanupTestData(PostgreSQLContainer<?> postgres, SchemaComponent... components) {
        cleanupTestData(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), components);
    }
    
    /**
     * Clean up test data from specified schema components.
     * 
     * @param jdbcUrl the JDBC URL
     * @param username the database username
     * @param password the database password
     * @param components the schema components to clean
     */
    public static void cleanupTestData(String jdbcUrl, String username, String password, SchemaComponent... components) {
        Set<SchemaComponent> componentSet = EnumSet.noneOf(SchemaComponent.class);
        
        for (SchemaComponent component : components) {
            if (component == SchemaComponent.ALL) {
                componentSet = EnumSet.allOf(SchemaComponent.class);
                componentSet.remove(SchemaComponent.ALL);
                break;
            }
            componentSet.add(component);
        }
        
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement()) {

            logger.debug("Cleaning up test data for components: {}", componentSet);

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

            logger.debug("Test data cleanup completed for components: {}", componentSet);

        } catch (Exception e) {
            logger.warn("Failed to cleanup test data (tables may not exist yet)", e);
        }
    }
    
    // Private initialization methods for each component

    private static void initializeSchemaVersion(Statement stmt) throws Exception {
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS schema_version (
                version VARCHAR(50) PRIMARY KEY,
                description TEXT,
                applied_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                checksum VARCHAR(64)
            )
            """);
    }

    private static void initializeOutboxSchema(Statement stmt) throws Exception {
        // Outbox pattern table for reliable message delivery (in public schema)
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS outbox (
                id BIGSERIAL PRIMARY KEY,
                topic VARCHAR(255) NOT NULL,
                payload JSONB NOT NULL,
                created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                processed_at TIMESTAMP WITH TIME ZONE,
                processing_started_at TIMESTAMP WITH TIME ZONE,
                status VARCHAR(50) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD_LETTER')),
                retry_count INT DEFAULT 0,
                max_retries INT DEFAULT 3,
                next_retry_at TIMESTAMP WITH TIME ZONE,
                version INT DEFAULT 0,
                headers JSONB DEFAULT '{}',
                error_message TEXT,
                correlation_id VARCHAR(255),
                message_group VARCHAR(255),
                priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10)
            )
            """);

        // Table to track which consumer groups have processed which messages
        // Note: Using message_id and group_name (not outbox_message_id and consumer_group_name)
        // to match the Consumer Group Fanout schema naming convention
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS outbox_consumer_groups (
                id BIGSERIAL PRIMARY KEY,
                message_id BIGINT NOT NULL REFERENCES outbox(id) ON DELETE CASCADE,
                group_name VARCHAR(255) NOT NULL,
                status VARCHAR(50) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
                processed_at TIMESTAMP WITH TIME ZONE,
                processing_started_at TIMESTAMP WITH TIME ZONE,
                retry_count INT DEFAULT 0,
                error_message TEXT,
                created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                UNIQUE(message_id, group_name)
            )
            """);

        // Performance indexes for outbox table
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_outbox_status_created ON outbox(status, created_at)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_outbox_next_retry ON outbox(status, next_retry_at) WHERE status = 'FAILED'");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_outbox_topic ON outbox(topic)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_outbox_correlation_id ON outbox(correlation_id) WHERE correlation_id IS NOT NULL");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_outbox_message_group ON outbox(message_group) WHERE message_group IS NOT NULL");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_outbox_priority ON outbox(priority, created_at)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_outbox_processing_started ON outbox(processing_started_at) WHERE processing_started_at IS NOT NULL");

        // Performance indexes for outbox_consumer_groups table
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_outbox_consumer_groups_message_id ON outbox_consumer_groups(message_id)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_outbox_consumer_groups_status ON outbox_consumer_groups(status, created_at)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_outbox_consumer_groups_consumer_group ON outbox_consumer_groups(group_name)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_outbox_consumer_groups_processing ON outbox_consumer_groups(status, processing_started_at) WHERE status = 'PROCESSING'");
    }

    private static void initializeNativeQueueSchema(Statement stmt) throws Exception {
        // Native queue messages table
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS queue_messages (
                id BIGSERIAL PRIMARY KEY,
                topic VARCHAR(255) NOT NULL,
                payload JSONB NOT NULL,
                visible_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                lock_id BIGINT,
                lock_until TIMESTAMP WITH TIME ZONE,
                retry_count INT DEFAULT 0,
                max_retries INT DEFAULT 3,
                status VARCHAR(50) DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'LOCKED', 'PROCESSED', 'FAILED', 'DEAD_LETTER')),
                headers JSONB DEFAULT '{}',
                error_message TEXT,
                correlation_id VARCHAR(255),
                message_group VARCHAR(255),
                priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10)
            )
            """);

        // Message processing table for INSERT-only message processing
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS message_processing (
                id BIGSERIAL PRIMARY KEY,
                message_id BIGINT NOT NULL,
                consumer_id VARCHAR(255) NOT NULL,
                topic VARCHAR(255) NOT NULL,
                status VARCHAR(50) NOT NULL DEFAULT 'PROCESSING',
                started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                completed_at TIMESTAMP WITH TIME ZONE,
                error_message TEXT,
                retry_count INTEGER NOT NULL DEFAULT 0,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                CONSTRAINT fk_message_processing_message_id
                    FOREIGN KEY (message_id) REFERENCES queue_messages(id) ON DELETE CASCADE,
                CONSTRAINT chk_message_processing_status
                    CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED', 'RETRYING'))
            )
            """);

        // Performance indexes for queue_messages table
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_queue_messages_topic_visible ON queue_messages(topic, visible_at, status)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_queue_messages_lock ON queue_messages(lock_id) WHERE lock_id IS NOT NULL");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_queue_messages_status ON queue_messages(status, created_at)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_queue_messages_correlation_id ON queue_messages(correlation_id) WHERE correlation_id IS NOT NULL");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_queue_messages_priority ON queue_messages(priority, created_at)");

        // Performance indexes for message_processing table
        stmt.execute("""
            CREATE UNIQUE INDEX IF NOT EXISTS idx_message_processing_unique
                ON message_processing (message_id, consumer_id)
                WHERE status IN ('PROCESSING', 'COMPLETED')
            """);
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_message_processing_status_topic ON message_processing (status, topic, started_at)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_message_processing_completed ON message_processing (completed_at) WHERE status = 'COMPLETED'");
    }

    private static void initializeDeadLetterQueueSchema(Statement stmt) throws Exception {
        // Dead letter queue for failed messages
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS dead_letter_queue (
                id BIGSERIAL PRIMARY KEY,
                original_table VARCHAR(50) NOT NULL,
                original_id BIGINT NOT NULL,
                topic VARCHAR(255) NOT NULL,
                payload JSONB NOT NULL,
                original_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                failed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                failure_reason TEXT NOT NULL,
                retry_count INT NOT NULL,
                headers JSONB DEFAULT '{}',
                correlation_id VARCHAR(255),
                message_group VARCHAR(255)
            )
            """);

        // Performance indexes for dead letter queue
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_dlq_original ON dead_letter_queue(original_table, original_id)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_dlq_topic ON dead_letter_queue(topic)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_dlq_failed_at ON dead_letter_queue(failed_at)");
    }

    private static void initializeBitemporalSchema(Statement stmt) throws Exception {
        // Bi-temporal event log table
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
                ),
                CONSTRAINT chk_previous_version CHECK (
                    (version = 1 AND previous_version_id IS NULL) OR
                    (version > 1 AND previous_version_id IS NOT NULL)
                )
            )
            """);

        // Indexes for bi-temporal event log
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_bitemporal_event_log_event_id ON bitemporal_event_log(event_id)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_bitemporal_event_log_event_type ON bitemporal_event_log(event_type)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_bitemporal_event_log_valid_time ON bitemporal_event_log(valid_time)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_bitemporal_event_log_transaction_time ON bitemporal_event_log(transaction_time)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_bitemporal_event_log_aggregate_id ON bitemporal_event_log(aggregate_id)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_bitemporal_event_log_correlation_id ON bitemporal_event_log(correlation_id)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_bitemporal_event_log_bitemporal_query ON bitemporal_event_log(event_type, aggregate_id, valid_time, transaction_time)");

        // CRITICAL: PostgreSQL trigger function for NOTIFY - following exact V001__Create_Base_Tables.sql
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

        // CRITICAL: Create trigger for bi-temporal event notifications
        stmt.execute("""
            DROP TRIGGER IF EXISTS trigger_notify_bitemporal_event ON bitemporal_event_log;
            CREATE TRIGGER trigger_notify_bitemporal_event
                AFTER INSERT ON bitemporal_event_log
                FOR EACH ROW
                EXECUTE FUNCTION notify_bitemporal_event();
            """);

        // Bi-temporal views
        stmt.execute("""
            CREATE OR REPLACE VIEW bitemporal_current_state AS
            SELECT DISTINCT ON (event_id)
                id, event_id, event_type, valid_time, transaction_time,
                payload, headers, version, previous_version_id,
                is_correction, correction_reason, correlation_id, aggregate_id, created_at
            FROM bitemporal_event_log
            WHERE transaction_time <= NOW()
            ORDER BY event_id, transaction_time DESC
            """);

        stmt.execute("""
            CREATE OR REPLACE VIEW bitemporal_latest_events AS
            SELECT
                id, event_id, event_type, valid_time, transaction_time,
                payload, headers, version, previous_version_id,
                is_correction, correction_reason, correlation_id, aggregate_id, created_at
            FROM bitemporal_event_log
            WHERE transaction_time <= NOW()
            ORDER BY valid_time DESC
            """);

        stmt.execute("""
            CREATE OR REPLACE VIEW bitemporal_event_stats AS
            SELECT
                COUNT(*) as total_events,
                COUNT(*) FILTER (WHERE is_correction = TRUE) as total_corrections,
                COUNT(DISTINCT event_type) as unique_event_types,
                COUNT(DISTINCT aggregate_id) as unique_aggregates,
                MIN(valid_time) as oldest_event_time,
                MAX(valid_time) as newest_event_time,
                MIN(transaction_time) as oldest_transaction_time,
                MAX(transaction_time) as newest_transaction_time
            FROM bitemporal_event_log
            """);

        stmt.execute("""
            CREATE OR REPLACE VIEW bitemporal_event_type_stats AS
            SELECT
                event_type,
                COUNT(*) as event_count,
                COUNT(*) FILTER (WHERE is_correction = TRUE) as correction_count,
                COUNT(DISTINCT aggregate_id) as unique_aggregates,
                MIN(valid_time) as oldest_event_time,
                MAX(valid_time) as newest_event_time
            FROM bitemporal_event_log
            GROUP BY event_type
            ORDER BY event_count DESC
            """);
    }

    private static void initializeMetricsSchema(Statement stmt) throws Exception {
        // Metrics and monitoring tables
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS queue_metrics (
                id BIGSERIAL PRIMARY KEY,
                metric_name VARCHAR(100) NOT NULL,
                metric_value DOUBLE PRECISION NOT NULL,
                tags JSONB DEFAULT '{}',
                timestamp TIMESTAMP WITH TIME ZONE DEFAULT NOW()
            )
            """);

        // Connection pool metrics
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS connection_pool_metrics (
                id BIGSERIAL PRIMARY KEY,
                pool_name VARCHAR(100) NOT NULL,
                active_connections INT NOT NULL,
                idle_connections INT NOT NULL,
                total_connections INT NOT NULL,
                pending_threads INT NOT NULL,
                timestamp TIMESTAMP WITH TIME ZONE DEFAULT NOW()
            )
            """);

        // Performance indexes for metrics tables
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_queue_metrics_name_timestamp ON queue_metrics(metric_name, timestamp)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_connection_metrics_pool_timestamp ON connection_pool_metrics(pool_name, timestamp)");
    }

    private static void initializeConsumerGroupFanoutSchema(Statement stmt) throws Exception {
        // 1. CREATE TOPIC CONFIGURATION TABLE (in public schema)
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS outbox_topics (
                topic VARCHAR(255) PRIMARY KEY,

                -- Topic semantics: QUEUE (distribute) or PUB_SUB (replicate)
                semantics VARCHAR(20) DEFAULT 'QUEUE'
                    CHECK (semantics IN ('QUEUE', 'PUB_SUB')),

                -- Retention policies
                message_retention_hours INT DEFAULT 24,
                zero_subscription_retention_hours INT DEFAULT 24,

                -- Zero-subscription protection
                block_writes_on_zero_subscriptions BOOLEAN DEFAULT FALSE,

                -- Completion tracking mode (for future Offset/Watermark support)
                completion_tracking_mode VARCHAR(20) DEFAULT 'REFERENCE_COUNTING'
                    CHECK (completion_tracking_mode IN ('REFERENCE_COUNTING', 'OFFSET_WATERMARK')),

                -- Metadata
                created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
            )
            """);

        // 2. CREATE SUBSCRIPTION MANAGEMENT TABLE
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS outbox_topic_subscriptions (
                id BIGSERIAL PRIMARY KEY,
                topic VARCHAR(255) NOT NULL,
                group_name VARCHAR(255) NOT NULL,

                -- Subscription lifecycle
                subscription_status VARCHAR(20) DEFAULT 'ACTIVE'
                    CHECK (subscription_status IN ('ACTIVE', 'PAUSED', 'CANCELLED', 'DEAD')),

                -- Timestamps
                subscribed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                last_active_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

                -- Start position for late-joining consumers
                start_from_message_id BIGINT,
                start_from_timestamp TIMESTAMP WITH TIME ZONE,

                -- Heartbeat tracking for dead consumer detection
                heartbeat_interval_seconds INT DEFAULT 60,
                heartbeat_timeout_seconds INT DEFAULT 300,
                last_heartbeat_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

                -- Backfill tracking (for resumable backfill - Phase 8)
                backfill_status VARCHAR(20) DEFAULT 'NONE'
                    CHECK (backfill_status IN ('NONE', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'FAILED')),
                backfill_checkpoint_id BIGINT,
                backfill_processed_messages BIGINT DEFAULT 0,
                backfill_total_messages BIGINT,
                backfill_started_at TIMESTAMP WITH TIME ZONE,
                backfill_completed_at TIMESTAMP WITH TIME ZONE,

                -- Ensure one subscription per group per topic
                UNIQUE(topic, group_name)
            )
            """);

        // 3. ADD FANOUT COLUMNS TO OUTBOX TABLE
        stmt.execute("ALTER TABLE outbox ADD COLUMN IF NOT EXISTS required_consumer_groups INT DEFAULT 1");
        stmt.execute("ALTER TABLE outbox ADD COLUMN IF NOT EXISTS completed_consumer_groups INT DEFAULT 0");
        stmt.execute("ALTER TABLE outbox ADD COLUMN IF NOT EXISTS completed_groups_bitmap BIGINT DEFAULT 0");

        // 4. CREATE AUDIT AND TRACKING TABLES
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS processed_ledger (
                id BIGSERIAL PRIMARY KEY,
                message_id BIGINT NOT NULL,
                group_name VARCHAR(255) NOT NULL,
                topic VARCHAR(255) NOT NULL,
                processed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                processing_duration_ms BIGINT,
                status VARCHAR(50) NOT NULL,
                error_message TEXT,

                -- Partition key for time-based partitioning
                partition_key TIMESTAMP WITH TIME ZONE DEFAULT NOW()
            )
            """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS partition_drop_audit (
                id BIGSERIAL PRIMARY KEY,
                partition_name VARCHAR(255) NOT NULL,
                topic VARCHAR(255) NOT NULL,
                dropped_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                watermark_id BIGINT NOT NULL,
                message_count BIGINT,
                oldest_message_created_at TIMESTAMP WITH TIME ZONE,
                newest_message_created_at TIMESTAMP WITH TIME ZONE
            )
            """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS consumer_group_index (
                id BIGSERIAL PRIMARY KEY,
                topic VARCHAR(255) NOT NULL,
                group_name VARCHAR(255) NOT NULL,
                last_processed_id BIGINT DEFAULT 0,
                last_processed_at TIMESTAMP WITH TIME ZONE,
                pending_count BIGINT DEFAULT 0,
                processing_count BIGINT DEFAULT 0,
                completed_count BIGINT DEFAULT 0,
                failed_count BIGINT DEFAULT 0,
                updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

                UNIQUE(topic, group_name)
            )
            """);

        // 5. CREATE INDEXES FOR PERFORMANCE
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_outbox_fanout_completion ON outbox(topic, status, completed_consumer_groups, required_consumer_groups) WHERE status IN ('PENDING', 'PROCESSING')");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_outbox_fanout_cleanup ON outbox(status, processed_at, completed_consumer_groups, required_consumer_groups) WHERE status = 'COMPLETED'");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_topic_subscriptions_active ON outbox_topic_subscriptions(topic, subscription_status) WHERE subscription_status = 'ACTIVE'");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_topic_subscriptions_heartbeat ON outbox_topic_subscriptions(subscription_status, last_heartbeat_at) WHERE subscription_status = 'ACTIVE'");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_outbox_consumer_groups_group_status ON outbox_consumer_groups(group_name, status, message_id)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_processed_ledger_time ON processed_ledger(topic, processed_at)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_consumer_group_index_topic ON consumer_group_index(topic, group_name)");

        // 6. CREATE TRIGGER FUNCTION FOR REQUIRED_CONSUMER_GROUPS
        stmt.execute("""
            CREATE OR REPLACE FUNCTION set_required_consumer_groups()
            RETURNS TRIGGER AS $$
            DECLARE
                topic_semantics VARCHAR(20);
                active_subscription_count INT;
            BEGIN
                -- Get topic semantics (default to QUEUE if not configured)
                SELECT COALESCE(semantics, 'QUEUE') INTO topic_semantics
                FROM outbox_topics
                WHERE topic = NEW.topic;

                -- If topic not configured, treat as QUEUE
                IF topic_semantics IS NULL THEN
                    topic_semantics := 'QUEUE';
                END IF;

                -- For PUB_SUB topics, count ACTIVE subscriptions
                IF topic_semantics = 'PUB_SUB' THEN
                    SELECT COUNT(*) INTO active_subscription_count
                    FROM outbox_topic_subscriptions
                    WHERE topic = NEW.topic
                      AND subscription_status = 'ACTIVE';

                    NEW.required_consumer_groups := active_subscription_count;
                ELSE
                    -- For QUEUE topics, set to 1 (backward compatibility)
                    NEW.required_consumer_groups := 1;
                END IF;

                -- Initialize completed_consumer_groups to 0
                NEW.completed_consumer_groups := 0;
                NEW.completed_groups_bitmap := 0;

                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
            """);

        // 7. CREATE TRIGGER
        stmt.execute("DROP TRIGGER IF EXISTS trigger_set_required_consumer_groups ON outbox");
        stmt.execute("""
            CREATE TRIGGER trigger_set_required_consumer_groups
                BEFORE INSERT ON outbox
                FOR EACH ROW
                EXECUTE FUNCTION set_required_consumer_groups()
            """);
    }
}
