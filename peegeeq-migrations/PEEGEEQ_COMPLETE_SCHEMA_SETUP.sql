-- =============================================================================
-- PeeGeeQ Complete Database Schema Setup
-- =============================================================================
-- Version: 2.0.0
-- Purpose: Standalone SQL script for complete database initialization
-- Usage: psql -U postgres -d peegeeq_db -f COMPLETE_SCHEMA_SETUP.sql
--        psql -U postgres -d peegeeq_db -v schema=myschema -f COMPLETE_SCHEMA_SETUP.sql
-- 
-- This script can be run independently of Flyway migrations for:
-- - Manual database setup
-- - Development environment initialization
-- - Database recovery/rebuild
-- - Documentation and reference
--
-- Note: This script is idempotent - it can be run multiple times safely
-- =============================================================================

-- Set error handling
\set ON_ERROR_STOP on
\set ECHO all

-- Optional schema configuration (defaults to 'public')
-- Override with: psql -v schema=myschema -f COMPLETE_SCHEMA_SETUP.sql
\set schema :schema
\if :{?schema}
    \echo 'Using schema:' :schema
    SET search_path TO :schema;
\else
    \set schema 'public'
    \echo 'Using default schema: public'
    SET search_path TO public;
\endif

-- Display banner
\echo ''
\echo '==============================================================================='
\echo 'PeeGeeQ Complete Database Schema Setup v2.0.0'
\echo '==============================================================================='
\echo ''

-- =============================================================================
-- SECTION 1: SCHEMA VERSION TRACKING
-- =============================================================================

\echo 'Creating schema version tracking table...'

CREATE TABLE IF NOT EXISTS schema_version (
    version VARCHAR(50) PRIMARY KEY,
    description TEXT,
    applied_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    checksum VARCHAR(64)
);

-- =============================================================================
-- SECTION 2: CORE MESSAGE TABLES
-- =============================================================================

\echo 'Creating outbox pattern table...'

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
    priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10),
    
    -- Consumer group fanout columns
    required_consumer_groups INT DEFAULT 1,
    completed_consumer_groups INT DEFAULT 0,
    completed_groups_bitmap BIGINT DEFAULT 0
);

\echo 'Creating consumer groups tracking table...'

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
    
    -- Ensure each consumer group processes each message only once
    UNIQUE(message_id, group_name)
);

\echo 'Creating queue messages table...'

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
    priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10),
    idempotency_key VARCHAR(255)
);

\echo 'Creating message processing table (INSERT-only for lock-free processing)...'

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
    
    -- Foreign key constraint to queue_messages
    CONSTRAINT fk_message_processing_message_id
        FOREIGN KEY (message_id) REFERENCES queue_messages(id) ON DELETE CASCADE,
    
    -- Check constraint for valid status values
    CONSTRAINT chk_message_processing_status
        CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED', 'RETRYING'))
);

\echo 'Creating dead letter queue table...'

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
);

-- =============================================================================
-- SECTION 3: CONSUMER GROUP FANOUT TABLES
-- =============================================================================

\echo 'Creating topic configuration table...'

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
    
    -- Completion tracking mode
    completion_tracking_mode VARCHAR(20) DEFAULT 'REFERENCE_COUNTING'
        CHECK (completion_tracking_mode IN ('REFERENCE_COUNTING', 'OFFSET_WATERMARK')),
    
    -- Metadata
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

\echo 'Creating topic subscriptions table...'

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
    
    -- Backfill tracking
    backfill_status VARCHAR(20) DEFAULT 'NONE'
        CHECK (backfill_status IN ('NONE', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'FAILED')),
    backfill_checkpoint_id BIGINT,
    backfill_processed_messages BIGINT DEFAULT 0,
    backfill_total_messages BIGINT,
    backfill_started_at TIMESTAMP WITH TIME ZONE,
    backfill_completed_at TIMESTAMP WITH TIME ZONE,
    
    -- Ensure one subscription per group per topic
    UNIQUE(topic, group_name)
);

\echo 'Creating processed ledger for audit trail...'

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
);

\echo 'Creating partition drop audit table...'

CREATE TABLE IF NOT EXISTS partition_drop_audit (
    id BIGSERIAL PRIMARY KEY,
    partition_name VARCHAR(255) NOT NULL,
    topic VARCHAR(255) NOT NULL,
    dropped_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    watermark_id BIGINT NOT NULL,
    message_count BIGINT,
    oldest_message_created_at TIMESTAMP WITH TIME ZONE,
    newest_message_created_at TIMESTAMP WITH TIME ZONE
);

\echo 'Creating consumer group index table...'

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
);

-- =============================================================================
-- SECTION 4: BI-TEMPORAL EVENT LOG
-- =============================================================================

\echo 'Creating bi-temporal event log table...'

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
);

\echo 'Creating bi-temporal event statistics table...'

-- Event type statistics view (not a table)
-- This is created in the views section below

-- =============================================================================
-- SECTION 5: METRICS AND MONITORING
-- =============================================================================

\echo 'Creating metrics tables...'

CREATE TABLE IF NOT EXISTS queue_metrics (
    id BIGSERIAL PRIMARY KEY,
    metric_name VARCHAR(100) NOT NULL,
    metric_value DOUBLE PRECISION NOT NULL,
    tags JSONB DEFAULT '{}',
    timestamp TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS connection_pool_metrics (
    id BIGSERIAL PRIMARY KEY,
    pool_name VARCHAR(100) NOT NULL,
    active_connections INT NOT NULL,
    idle_connections INT NOT NULL,
    total_connections INT NOT NULL,
    pending_threads INT NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Note: connection_health_check and partition_metadata tables removed
-- These were not present in Flyway migrations and are not used by the application
-- Health checks use simple SELECT 1 queries without storing results in a table

-- =============================================================================
-- SECTION 6: PERFORMANCE INDEXES
-- =============================================================================

\echo 'Creating indexes on outbox table...'

CREATE INDEX IF NOT EXISTS idx_outbox_status_created ON outbox(status, created_at);
CREATE INDEX IF NOT EXISTS idx_outbox_next_retry ON outbox(status, next_retry_at) WHERE status = 'FAILED';
CREATE INDEX IF NOT EXISTS idx_outbox_topic ON outbox(topic);
CREATE INDEX IF NOT EXISTS idx_outbox_correlation_id ON outbox(correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_outbox_message_group ON outbox(message_group) WHERE message_group IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_outbox_priority ON outbox(priority, created_at);
CREATE INDEX IF NOT EXISTS idx_outbox_processing_started ON outbox(processing_started_at) WHERE processing_started_at IS NOT NULL;

-- Fanout-specific indexes
CREATE INDEX IF NOT EXISTS idx_outbox_fanout_completion 
    ON outbox(topic, status, completed_consumer_groups, required_consumer_groups)
    WHERE status IN ('PENDING', 'PROCESSING');
CREATE INDEX IF NOT EXISTS idx_outbox_fanout_cleanup
    ON outbox(status, processed_at, completed_consumer_groups, required_consumer_groups)
    WHERE status = 'COMPLETED';

\echo 'Creating indexes on outbox_consumer_groups table...'

CREATE INDEX IF NOT EXISTS idx_outbox_consumer_groups_message_id ON outbox_consumer_groups(message_id);
CREATE INDEX IF NOT EXISTS idx_outbox_consumer_groups_status ON outbox_consumer_groups(status, created_at);
CREATE INDEX IF NOT EXISTS idx_outbox_consumer_groups_consumer_group ON outbox_consumer_groups(group_name);
CREATE INDEX IF NOT EXISTS idx_outbox_consumer_groups_processing ON outbox_consumer_groups(status, processing_started_at) WHERE status = 'PROCESSING';
CREATE INDEX IF NOT EXISTS idx_outbox_consumer_groups_group_status ON outbox_consumer_groups(group_name, status, message_id);

\echo 'Creating indexes on queue_messages table...'

CREATE INDEX IF NOT EXISTS idx_queue_messages_topic_visible ON queue_messages(topic, visible_at, status);
CREATE INDEX IF NOT EXISTS idx_queue_messages_lock ON queue_messages(lock_id) WHERE lock_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_queue_messages_status ON queue_messages(status, created_at);
CREATE INDEX IF NOT EXISTS idx_queue_messages_correlation_id ON queue_messages(correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_queue_messages_priority ON queue_messages(priority, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_queue_messages_idempotency_key ON queue_messages(topic, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_queue_messages_idempotency_key_lookup ON queue_messages(idempotency_key) WHERE idempotency_key IS NOT NULL;

\echo 'Creating indexes on message_processing table...'

CREATE UNIQUE INDEX IF NOT EXISTS idx_message_processing_unique
    ON message_processing (message_id, consumer_id)
    WHERE status IN ('PROCESSING', 'COMPLETED');
CREATE INDEX IF NOT EXISTS idx_message_processing_status_topic
    ON message_processing (status, topic, started_at);
CREATE INDEX IF NOT EXISTS idx_message_processing_completed
    ON message_processing (completed_at)
    WHERE status = 'COMPLETED';

\echo 'Creating indexes on dead_letter_queue table...'

CREATE INDEX IF NOT EXISTS idx_dlq_original ON dead_letter_queue(original_table, original_id);
CREATE INDEX IF NOT EXISTS idx_dlq_topic ON dead_letter_queue(topic);
CREATE INDEX IF NOT EXISTS idx_dlq_failed_at ON dead_letter_queue(failed_at);

\echo 'Creating indexes on metrics tables...'

CREATE INDEX IF NOT EXISTS idx_queue_metrics_name_timestamp ON queue_metrics(metric_name, timestamp);
CREATE INDEX IF NOT EXISTS idx_connection_metrics_pool_timestamp ON connection_pool_metrics(pool_name, timestamp);

\echo 'Creating indexes on topic subscriptions...'

CREATE INDEX IF NOT EXISTS idx_topic_subscriptions_active 
    ON outbox_topic_subscriptions(topic, subscription_status)
    WHERE subscription_status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_topic_subscriptions_heartbeat
    ON outbox_topic_subscriptions(subscription_status, last_heartbeat_at)
    WHERE subscription_status = 'ACTIVE';

\echo 'Creating indexes on processed ledger...'

CREATE INDEX IF NOT EXISTS idx_processed_ledger_time
    ON processed_ledger(topic, processed_at);

\echo 'Creating indexes on consumer group index...'

CREATE INDEX IF NOT EXISTS idx_consumer_group_index_topic
    ON consumer_group_index(topic, group_name);

\echo 'Creating indexes on bi-temporal event log (NOTE: CONCURRENTLY not used - run separately if needed)...'

-- NOTE: CREATE INDEX CONCURRENTLY cannot be run inside a transaction block
-- For production deployment, run these separately with CONCURRENTLY keyword
-- For now, creating without CONCURRENTLY for transaction safety

CREATE INDEX IF NOT EXISTS idx_bitemporal_valid_time ON bitemporal_event_log(valid_time);
CREATE INDEX IF NOT EXISTS idx_bitemporal_transaction_time ON bitemporal_event_log(transaction_time);
CREATE INDEX IF NOT EXISTS idx_bitemporal_valid_transaction ON bitemporal_event_log(valid_time, transaction_time);
CREATE INDEX IF NOT EXISTS idx_bitemporal_event_id ON bitemporal_event_log(event_id);
CREATE INDEX IF NOT EXISTS idx_bitemporal_event_type ON bitemporal_event_log(event_type);
CREATE INDEX IF NOT EXISTS idx_bitemporal_event_type_valid_time ON bitemporal_event_log(event_type, valid_time);
CREATE INDEX IF NOT EXISTS idx_bitemporal_aggregate_id ON bitemporal_event_log(aggregate_id) WHERE aggregate_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_bitemporal_correlation_id ON bitemporal_event_log(correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_bitemporal_aggregate_valid_time ON bitemporal_event_log(aggregate_id, valid_time) WHERE aggregate_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_bitemporal_version ON bitemporal_event_log(event_id, version);
CREATE INDEX IF NOT EXISTS idx_bitemporal_corrections ON bitemporal_event_log(previous_version_id) WHERE previous_version_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_bitemporal_latest_events ON bitemporal_event_log(event_type, transaction_time DESC) WHERE is_correction = FALSE;
CREATE INDEX IF NOT EXISTS idx_bitemporal_payload_gin ON bitemporal_event_log USING GIN(payload);
CREATE INDEX IF NOT EXISTS idx_bitemporal_headers_gin ON bitemporal_event_log USING GIN(headers);

-- =============================================================================
-- SECTION 7: DATABASE VIEWS
-- =============================================================================

\echo 'Creating bi-temporal views...'

CREATE OR REPLACE VIEW bitemporal_current_state AS
SELECT DISTINCT ON (event_id)
    id, event_id, event_type, valid_time, transaction_time,
    payload, headers, version, previous_version_id,
    is_correction, correction_reason, correlation_id, aggregate_id, created_at
FROM bitemporal_event_log
WHERE transaction_time <= NOW()
ORDER BY event_id, transaction_time DESC;

-- Latest events view (most recent events by valid time)
CREATE OR REPLACE VIEW bitemporal_latest_events AS
SELECT
    id, event_id, event_type, valid_time, transaction_time,
    payload, headers, version, previous_version_id,
    is_correction, correction_reason, correlation_id, aggregate_id, created_at
FROM bitemporal_event_log
WHERE transaction_time <= NOW()
ORDER BY valid_time DESC;

-- Event statistics view
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
FROM bitemporal_event_log;

-- Event type statistics view
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
ORDER BY event_count DESC;

-- =============================================================================
-- SECTION 8: DATABASE FUNCTIONS
-- =============================================================================

\echo 'Creating message notification functions...'

CREATE OR REPLACE FUNCTION notify_message_inserted()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify('peegeeq_' || NEW.topic, NEW.id::text);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION update_message_processing_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

\echo 'Creating cleanup functions...'

CREATE OR REPLACE FUNCTION cleanup_completed_message_processing()
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM message_processing
    WHERE status = 'COMPLETED'
    AND completed_at < NOW() - INTERVAL '1 hour';
    
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    
    IF deleted_count > 0 THEN
        RAISE NOTICE 'Cleaned up % completed message processing records', deleted_count;
    END IF;
    
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION cleanup_old_metrics(retention_days INT DEFAULT 30)
RETURNS INT AS $$
DECLARE
    deleted_count INT := 0;
    temp_count INT;
BEGIN
    DELETE FROM queue_metrics
    WHERE timestamp < NOW() - INTERVAL '1 day' * retention_days;
    GET DIAGNOSTICS temp_count = ROW_COUNT;
    deleted_count := deleted_count + temp_count;
    
    DELETE FROM connection_pool_metrics
    WHERE timestamp < NOW() - INTERVAL '1 day' * retention_days;
    GET DIAGNOSTICS temp_count = ROW_COUNT;
    deleted_count := deleted_count + temp_count;
    
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

\echo 'Creating consumer group management functions...'

CREATE OR REPLACE FUNCTION register_consumer_group_for_existing_messages(group_name VARCHAR(255))
RETURNS INTEGER AS $$
DECLARE
    inserted_count INTEGER;
BEGIN
    INSERT INTO outbox_consumer_groups (message_id, group_name, status)
    SELECT o.id, group_name, 'PENDING'
    FROM outbox o
    WHERE o.status IN ('PENDING', 'PROCESSING')
    AND NOT EXISTS (
        SELECT 1 FROM outbox_consumer_groups ocg
        WHERE ocg.message_id = o.id
        AND ocg.group_name = group_name
    );
    
    GET DIAGNOSTICS inserted_count = ROW_COUNT;
    RETURN inserted_count;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION create_consumer_group_entries_for_new_message()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO outbox_consumer_groups (message_id, group_name, status)
    SELECT DISTINCT NEW.id, group_name, 'PENDING'
    FROM outbox_consumer_groups
    WHERE group_name IS NOT NULL
    ON CONFLICT (message_id, group_name) DO NOTHING;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION cleanup_completed_outbox_messages()
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM outbox
    WHERE id IN (
        SELECT o.id
        FROM outbox o
        LEFT JOIN outbox_topics ot ON ot.topic = o.topic
        WHERE o.status = 'COMPLETED'
        AND (
            (o.required_consumer_groups = 1 AND o.completed_consumer_groups >= 1)
            OR
            (o.completed_consumer_groups >= o.required_consumer_groups AND o.required_consumer_groups > 1)
            OR
            (o.required_consumer_groups = 0
             AND o.created_at < NOW() - (
                 COALESCE(ot.zero_subscription_retention_hours, 24) || ' hours'
             )::INTERVAL)
        )
        AND o.created_at < NOW() - (
            COALESCE(ot.message_retention_hours, 24) || ' hours'
        )::INTERVAL
        LIMIT 10000
    );
    
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    
    IF deleted_count > 0 THEN
        RAISE NOTICE 'Cleaned up % completed outbox messages', deleted_count;
    END IF;
    
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

\echo 'Creating fanout trigger functions...'

CREATE OR REPLACE FUNCTION set_required_consumer_groups()
RETURNS TRIGGER AS $$
DECLARE
    topic_semantics VARCHAR(20);
    active_subscription_count INT;
BEGIN
    SELECT COALESCE(semantics, 'QUEUE') INTO topic_semantics
    FROM outbox_topics
    WHERE topic = NEW.topic;
    
    IF topic_semantics IS NULL THEN
        topic_semantics := 'QUEUE';
    END IF;
    
    IF topic_semantics = 'PUB_SUB' THEN
        SELECT COUNT(*) INTO active_subscription_count
        FROM outbox_topic_subscriptions
        WHERE topic = NEW.topic
          AND subscription_status = 'ACTIVE';
        
        NEW.required_consumer_groups := active_subscription_count;
    ELSE
        NEW.required_consumer_groups := 1;
    END IF;
    
    NEW.completed_consumer_groups := 0;
    NEW.completed_groups_bitmap := 0;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION mark_dead_consumer_groups()
RETURNS INTEGER AS $$
DECLARE
    updated_count INTEGER;
BEGIN
    UPDATE outbox_topic_subscriptions
    SET subscription_status = 'DEAD',
        last_active_at = NOW()
    WHERE subscription_status = 'ACTIVE'
      AND last_heartbeat_at < NOW() - (heartbeat_timeout_seconds || ' seconds')::INTERVAL;
    
    GET DIAGNOSTICS updated_count = ROW_COUNT;
    
    IF updated_count > 0 THEN
        RAISE NOTICE 'Marked % consumer groups as DEAD due to heartbeat timeout', updated_count;
    END IF;
    
    RETURN updated_count;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION update_consumer_group_index()
RETURNS INTEGER AS $$
DECLARE
    updated_count INTEGER;
BEGIN
    INSERT INTO consumer_group_index (topic, group_name, last_processed_id, last_processed_at,
                                       pending_count, processing_count, completed_count, failed_count, updated_at)
    SELECT
        o.topic,
        ocg.group_name,
        MAX(CASE WHEN ocg.status = 'COMPLETED' THEN ocg.message_id ELSE 0 END) as last_processed_id,
        MAX(CASE WHEN ocg.status = 'COMPLETED' THEN ocg.processed_at ELSE NULL END) as last_processed_at,
        COUNT(*) FILTER (WHERE ocg.status = 'PENDING') as pending_count,
        COUNT(*) FILTER (WHERE ocg.status = 'PROCESSING') as processing_count,
        COUNT(*) FILTER (WHERE ocg.status = 'COMPLETED') as completed_count,
        COUNT(*) FILTER (WHERE ocg.status = 'FAILED') as failed_count,
        NOW() as updated_at
    FROM outbox_consumer_groups ocg
    INNER JOIN outbox o ON o.id = ocg.message_id
    GROUP BY o.topic, ocg.group_name
    ON CONFLICT (topic, group_name) DO UPDATE
    SET last_processed_id = EXCLUDED.last_processed_id,
        last_processed_at = EXCLUDED.last_processed_at,
        pending_count = EXCLUDED.pending_count,
        processing_count = EXCLUDED.processing_count,
        completed_count = EXCLUDED.completed_count,
        failed_count = EXCLUDED.failed_count,
        updated_at = EXCLUDED.updated_at;
    
    GET DIAGNOSTICS updated_count = ROW_COUNT;
    
    RETURN updated_count;
END;
$$ LANGUAGE plpgsql;

\echo 'Creating bi-temporal event log functions...'

CREATE OR REPLACE FUNCTION notify_bitemporal_event() RETURNS TRIGGER AS $$
BEGIN
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

CREATE OR REPLACE FUNCTION get_events_as_of_time(
    as_of_valid_time TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    as_of_transaction_time TIMESTAMP WITH TIME ZONE DEFAULT NOW()
)
RETURNS TABLE (
    id BIGINT,
    event_id VARCHAR(255),
    event_type VARCHAR(255),
    valid_time TIMESTAMP WITH TIME ZONE,
    transaction_time TIMESTAMP WITH TIME ZONE,
    payload JSONB,
    headers JSONB,
    version BIGINT,
    previous_version_id VARCHAR(255),
    is_correction BOOLEAN,
    correction_reason TEXT,
    correlation_id VARCHAR(255),
    aggregate_id VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE
) AS $$
BEGIN
    RETURN QUERY
    SELECT DISTINCT ON (bel.event_id)
        bel.id, bel.event_id, bel.event_type, bel.valid_time, bel.transaction_time,
        bel.payload, bel.headers, bel.version, bel.previous_version_id,
        bel.is_correction, bel.correction_reason, bel.correlation_id,
        bel.aggregate_id, bel.created_at
    FROM bitemporal_event_log bel
    WHERE bel.valid_time <= as_of_valid_time
      AND bel.transaction_time <= as_of_transaction_time
    ORDER BY bel.event_id, bel.transaction_time DESC;
END;
$$ LANGUAGE plpgsql;

-- =============================================================================
-- SECTION 9: DATABASE TRIGGERS
-- =============================================================================

\echo 'Creating database triggers...'

DROP TRIGGER IF EXISTS trigger_outbox_notify ON outbox;
CREATE TRIGGER trigger_outbox_notify
    AFTER INSERT ON outbox
    FOR EACH ROW
    EXECUTE FUNCTION notify_message_inserted();

DROP TRIGGER IF EXISTS trigger_queue_messages_notify ON queue_messages;
CREATE TRIGGER trigger_queue_messages_notify
    AFTER INSERT ON queue_messages
    FOR EACH ROW
    EXECUTE FUNCTION notify_message_inserted();

DROP TRIGGER IF EXISTS trigger_message_processing_updated_at ON message_processing;
CREATE TRIGGER trigger_message_processing_updated_at
    BEFORE UPDATE ON message_processing
    FOR EACH ROW
    EXECUTE FUNCTION update_message_processing_updated_at();

DROP TRIGGER IF EXISTS trigger_create_consumer_group_entries ON outbox;
CREATE TRIGGER trigger_create_consumer_group_entries
    AFTER INSERT ON outbox
    FOR EACH ROW
    EXECUTE FUNCTION create_consumer_group_entries_for_new_message();

DROP TRIGGER IF EXISTS trigger_set_required_consumer_groups ON outbox;
CREATE TRIGGER trigger_set_required_consumer_groups
    BEFORE INSERT ON outbox
    FOR EACH ROW
    EXECUTE FUNCTION set_required_consumer_groups();

DROP TRIGGER IF EXISTS trigger_notify_bitemporal_event ON bitemporal_event_log;
CREATE TRIGGER trigger_notify_bitemporal_event
    AFTER INSERT ON bitemporal_event_log
    FOR EACH ROW
    EXECUTE FUNCTION notify_bitemporal_event();

-- =============================================================================
-- SECTION 10: DATABASE COMMENTS (Documentation)
-- =============================================================================

\echo 'Adding documentation comments...'

COMMENT ON TABLE outbox IS 'Outbox pattern table for reliable message delivery with consumer group fanout support';
COMMENT ON TABLE outbox_consumer_groups IS 'Tracks which consumer groups have processed which outbox messages';
COMMENT ON TABLE queue_messages IS 'Native queue messages table for direct message queuing';
COMMENT ON TABLE message_processing IS 'INSERT-only message processing table that eliminates ExclusiveLock conflicts';
COMMENT ON TABLE dead_letter_queue IS 'Dead letter queue for failed messages';
COMMENT ON TABLE outbox_topics IS 'Topic configuration for QUEUE vs PUB_SUB semantics with retention policies';
COMMENT ON TABLE outbox_topic_subscriptions IS 'Consumer group subscriptions with heartbeat tracking and backfill support';
COMMENT ON TABLE processed_ledger IS 'Audit trail of message processing by consumer groups';
COMMENT ON TABLE partition_drop_audit IS 'Audit trail of partition drops for Offset/Watermark mode cleanup';
COMMENT ON TABLE consumer_group_index IS 'Performance index tracking consumer group processing statistics';
COMMENT ON TABLE bitemporal_event_log IS 'Bi-temporal event log for append-only event sourcing';
COMMENT ON TABLE queue_metrics IS 'Queue performance metrics';
COMMENT ON TABLE connection_pool_metrics IS 'Connection pool statistics';

COMMENT ON COLUMN outbox.required_consumer_groups IS 'Snapshot of required groups at message insertion time (immutable)';
COMMENT ON COLUMN outbox.completed_consumer_groups IS 'Count of consumer groups that completed processing';
COMMENT ON COLUMN outbox.completed_groups_bitmap IS 'Bitmap tracking completed groups (supports up to 64 groups)';

COMMENT ON COLUMN bitemporal_event_log.valid_time IS 'When the event actually happened (business time)';
COMMENT ON COLUMN bitemporal_event_log.transaction_time IS 'When the event was recorded in the system';
COMMENT ON COLUMN bitemporal_event_log.version IS 'Version number for event evolution tracking';
COMMENT ON COLUMN bitemporal_event_log.is_correction IS 'Indicates if this is a correction of a previous event';

COMMENT ON VIEW bitemporal_current_state IS 'Current state view showing latest version of each event as of now';
COMMENT ON VIEW bitemporal_as_of_now IS 'Latest events view ordered by valid time';

COMMENT ON FUNCTION set_required_consumer_groups IS 'Sets required_consumer_groups based on active subscriptions at insertion time';
COMMENT ON FUNCTION cleanup_completed_outbox_messages IS 'Cleans up completed outbox messages respecting fanout and retention policies';
COMMENT ON FUNCTION mark_dead_consumer_groups IS 'Marks consumer groups as DEAD when heartbeat timeout exceeded';
COMMENT ON FUNCTION update_consumer_group_index IS 'Updates consumer group index statistics';
COMMENT ON FUNCTION get_events_as_of_time IS 'Query events as they existed at a specific point in bi-temporal time';

-- =============================================================================
-- SECTION 11: RECORD SCHEMA VERSION
-- =============================================================================

\echo 'Recording schema versions...'

INSERT INTO schema_version (version, description, checksum)
VALUES 
    ('V001', 'Base Schema - Core tables, indexes, functions, and triggers', md5('V001__Create_Base_Tables.sql')),
    ('V010', 'Consumer Group Fan-Out Support (Reference Counting Mode)', md5('V010__Create_Consumer_Group_Fanout_Tables.sql'))
ON CONFLICT (version) DO NOTHING;

-- =============================================================================
-- SECTION 12: COMPLETION
-- =============================================================================

\echo ''
\echo '==============================================================================='
\echo 'PeeGeeQ Database Schema Setup Complete!'
\echo '==============================================================================='
\echo ''
\echo 'Schema Summary:'
\echo '  - Core Tables: 8 (outbox, queue_messages, message_processing, etc.)'
\echo '  - Fanout Tables: 6 (topics, subscriptions, ledger, etc.)'
\echo '  - Bi-temporal Tables: 2 (event log, event stats)'
\echo '  - Metrics Tables: 4 (queue_metrics, connection metrics, etc.)'
\echo '  - Indexes: 50+ (including bi-temporal indexes)'
\echo '  - Views: 2 (bi-temporal current state, as-of-now)'
\echo '  - Functions: 15+ (notifications, cleanup, consumer group management)'
\echo '  - Triggers: 6 (notifications, updates, fanout)'
\echo ''
\echo 'Next Steps:'
\echo '  1. Verify schema: SELECT * FROM schema_version;'
\echo '  2. Check tables: \\dt'
\echo '  3. For production: Run bi-temporal indexes with CONCURRENTLY separately'
\echo '  4. Configure topics: INSERT INTO outbox_topics VALUES (...)'
\echo '  5. Register consumer groups: SELECT register_consumer_group_for_existing_messages(...)'
\echo ''
\echo 'Documentation:'
\echo '  - README_TESTING.md - Testing guide'
\echo '  - SCHEMA_DRIFT_ANALYSIS_REPORT.md - Schema analysis'
\echo '  - docs/CONSUMER_GROUP_GETTING_STARTED.md - Consumer group setup'
\echo ''
\echo '==============================================================================='
