-- PeeGeeQ Database Schema - Complete Schema
-- Version: 1.2.0
-- Description: Complete schema creation for PeeGeeQ message queue system with bi-temporal event log and consumer group tracking
-- executeInTransaction=false

-- Schema version tracking table
CREATE TABLE IF NOT EXISTS schema_version (
    version VARCHAR(50) PRIMARY KEY,
    description TEXT,
    applied_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    checksum VARCHAR(64)
);

-- Outbox pattern table for reliable message delivery
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
    idempotency_key VARCHAR(255)
);

-- Table to track which consumer groups have processed which messages
CREATE TABLE IF NOT EXISTS outbox_consumer_groups (
    id BIGSERIAL PRIMARY KEY,
    outbox_message_id BIGINT NOT NULL REFERENCES outbox(id) ON DELETE CASCADE,
    consumer_group_name VARCHAR(255) NOT NULL,
    status VARCHAR(50) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    processed_at TIMESTAMP WITH TIME ZONE,
    processing_started_at TIMESTAMP WITH TIME ZONE,
    retry_count INT DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

    -- Ensure each consumer group processes each message only once
    UNIQUE(outbox_message_id, consumer_group_name)
);

-- Native queue messages table
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
);

-- Message processing table for INSERT-only message processing
-- This eliminates ExclusiveLock conflicts by avoiding UPDATE-based locking
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

-- Dead letter queue for failed messages
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

-- Metrics and monitoring tables
CREATE TABLE IF NOT EXISTS queue_metrics (
    id BIGSERIAL PRIMARY KEY,
    metric_name VARCHAR(100) NOT NULL,
    metric_value DOUBLE PRECISION NOT NULL,
    tags JSONB DEFAULT '{}',
    timestamp TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Connection pool metrics
CREATE TABLE IF NOT EXISTS connection_pool_metrics (
    id BIGSERIAL PRIMARY KEY,
    pool_name VARCHAR(100) NOT NULL,
    active_connections INT NOT NULL,
    idle_connections INT NOT NULL,
    total_connections INT NOT NULL,
    pending_threads INT NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Bi-temporal event log table
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

-- Performance indexes for outbox table
CREATE INDEX idx_outbox_status_created ON outbox(status, created_at);
CREATE INDEX idx_outbox_next_retry ON outbox(status, next_retry_at) WHERE status = 'FAILED';
CREATE INDEX idx_outbox_topic ON outbox(topic);
CREATE INDEX idx_outbox_correlation_id ON outbox(correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX idx_outbox_message_group ON outbox(message_group) WHERE message_group IS NOT NULL;
CREATE INDEX idx_outbox_priority ON outbox(priority, created_at);
CREATE INDEX idx_outbox_processing_started ON outbox(processing_started_at) WHERE processing_started_at IS NOT NULL;

-- Idempotency key indexes for outbox
CREATE UNIQUE INDEX idx_outbox_idempotency_key ON outbox(topic, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_outbox_idempotency_key_lookup ON outbox(idempotency_key) WHERE idempotency_key IS NOT NULL;

-- GIN index for JSONB headers queries (server-side filtering)
CREATE INDEX idx_outbox_headers_gin ON outbox USING GIN(headers);

-- Performance indexes for outbox_consumer_groups table
CREATE INDEX idx_outbox_consumer_groups_message_id ON outbox_consumer_groups(outbox_message_id);
CREATE INDEX idx_outbox_consumer_groups_status ON outbox_consumer_groups(status, created_at);
CREATE INDEX idx_outbox_consumer_groups_consumer_group ON outbox_consumer_groups(consumer_group_name);
CREATE INDEX idx_outbox_consumer_groups_processing ON outbox_consumer_groups(status, processing_started_at) WHERE status = 'PROCESSING';

-- Performance indexes for queue_messages table
CREATE INDEX idx_queue_messages_topic_visible ON queue_messages(topic, visible_at, status);
CREATE INDEX idx_queue_messages_lock ON queue_messages(lock_id) WHERE lock_id IS NOT NULL;
CREATE INDEX idx_queue_messages_status ON queue_messages(status, created_at);
CREATE INDEX idx_queue_messages_correlation_id ON queue_messages(correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX idx_queue_messages_priority ON queue_messages(priority, created_at);

-- GIN index for JSONB headers queries (server-side filtering)
CREATE INDEX idx_queue_messages_headers_gin ON queue_messages USING GIN(headers);

-- Performance indexes for message_processing table
CREATE UNIQUE INDEX idx_message_processing_unique
    ON message_processing (message_id, consumer_id)
    WHERE status IN ('PROCESSING', 'COMPLETED');

CREATE INDEX idx_message_processing_status_topic
    ON message_processing (status, topic, started_at);

CREATE INDEX idx_message_processing_completed
    ON message_processing (completed_at)
    WHERE status = 'COMPLETED';

-- Performance indexes for dead letter queue
CREATE INDEX idx_dlq_original ON dead_letter_queue(original_table, original_id);
CREATE INDEX idx_dlq_topic ON dead_letter_queue(topic);
CREATE INDEX idx_dlq_failed_at ON dead_letter_queue(failed_at);

-- Performance indexes for metrics tables
CREATE INDEX idx_queue_metrics_name_timestamp ON queue_metrics(metric_name, timestamp);
CREATE INDEX idx_connection_metrics_pool_timestamp ON connection_pool_metrics(pool_name, timestamp);

-- Indexes for bi-temporal event log

-- Primary temporal indexes
CREATE INDEX idx_bitemporal_valid_time
    ON bitemporal_event_log(valid_time);

CREATE INDEX idx_bitemporal_transaction_time
    ON bitemporal_event_log(transaction_time);

-- Bi-temporal composite index for point-in-time queries
CREATE INDEX idx_bitemporal_valid_transaction
    ON bitemporal_event_log(valid_time, transaction_time);

-- Event identification and type indexes
CREATE INDEX idx_bitemporal_event_id
    ON bitemporal_event_log(event_id);

CREATE INDEX idx_bitemporal_event_type
    ON bitemporal_event_log(event_type);

CREATE INDEX idx_bitemporal_event_type_valid_time
    ON bitemporal_event_log(event_type, valid_time);

-- Grouping and correlation indexes
CREATE INDEX idx_bitemporal_aggregate_id
    ON bitemporal_event_log(aggregate_id) WHERE aggregate_id IS NOT NULL;

CREATE INDEX idx_bitemporal_correlation_id
    ON bitemporal_event_log(correlation_id) WHERE correlation_id IS NOT NULL;

CREATE INDEX idx_bitemporal_aggregate_valid_time
    ON bitemporal_event_log(aggregate_id, valid_time) WHERE aggregate_id IS NOT NULL;

-- Version and correction indexes
CREATE INDEX idx_bitemporal_version
    ON bitemporal_event_log(event_id, version);

CREATE INDEX idx_bitemporal_corrections
    ON bitemporal_event_log(previous_version_id) WHERE previous_version_id IS NOT NULL;

-- Performance index for latest events
CREATE INDEX idx_bitemporal_latest_events
    ON bitemporal_event_log(event_type, transaction_time DESC)
    WHERE is_correction = FALSE;

-- GIN index for JSONB payload queries
CREATE INDEX idx_bitemporal_payload_gin
    ON bitemporal_event_log USING GIN(payload);

-- GIN index for JSONB headers queries
CREATE INDEX idx_bitemporal_headers_gin
    ON bitemporal_event_log USING GIN(headers);

-- Views for bi-temporal event log

-- Current state view (latest version of each event as of now)
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

-- Functions for message processing
CREATE OR REPLACE FUNCTION notify_message_inserted()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify('peegeeq_' || NEW.topic, NEW.id::text);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Function to update message_processing updated_at timestamp
CREATE OR REPLACE FUNCTION update_message_processing_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Function to cleanup completed message processing records
CREATE OR REPLACE FUNCTION cleanup_completed_message_processing()
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    -- Delete completed processing records older than 1 hour
    DELETE FROM message_processing
    WHERE status = 'COMPLETED'
    AND completed_at < NOW() - INTERVAL '1 hour';

    GET DIAGNOSTICS deleted_count = ROW_COUNT;

    IF deleted_count > 0 THEN
        RAISE LOG 'Cleaned up % completed message processing records', deleted_count
            USING DETAIL = 'PGQINF0651';
    END IF;

    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- Function to register a consumer group for all existing pending messages
CREATE OR REPLACE FUNCTION register_consumer_group_for_existing_messages(group_name VARCHAR(255))
RETURNS INTEGER AS $$
DECLARE
    inserted_count INTEGER;
BEGIN
    -- Insert records for all pending outbox messages that this consumer group hasn't seen yet
    INSERT INTO outbox_consumer_groups (outbox_message_id, consumer_group_name, status)
    SELECT o.id, group_name, 'PENDING'
    FROM outbox o
    WHERE o.status IN ('PENDING', 'PROCESSING')
    AND NOT EXISTS (
        SELECT 1 FROM outbox_consumer_groups ocg
        WHERE ocg.outbox_message_id = o.id
        AND ocg.consumer_group_name = group_name
    );

    GET DIAGNOSTICS inserted_count = ROW_COUNT;
    RETURN inserted_count;
END;
$$ LANGUAGE plpgsql;

-- Trigger function to automatically create consumer group entries for new outbox messages
CREATE OR REPLACE FUNCTION create_consumer_group_entries_for_new_message()
RETURNS TRIGGER AS $$
BEGIN
    -- For each distinct consumer group that has been registered, create a pending entry
    INSERT INTO outbox_consumer_groups (outbox_message_id, consumer_group_name, status)
    SELECT DISTINCT NEW.id, consumer_group_name, 'PENDING'
    FROM outbox_consumer_groups
    WHERE consumer_group_name IS NOT NULL
    ON CONFLICT (outbox_message_id, consumer_group_name) DO NOTHING;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Function to clean up completed messages when all consumer groups have processed them
CREATE OR REPLACE FUNCTION cleanup_completed_outbox_messages()
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    -- Delete outbox messages where all consumer groups have completed processing
    DELETE FROM outbox
    WHERE id IN (
        SELECT o.id
        FROM outbox o
        WHERE o.status = 'COMPLETED'
        AND NOT EXISTS (
            SELECT 1 FROM outbox_consumer_groups ocg
            WHERE ocg.outbox_message_id = o.id
            AND ocg.status != 'COMPLETED'
        )
        -- Only clean up messages older than 1 hour to allow for debugging
        AND o.created_at < NOW() - INTERVAL '1 hour'
    );

    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- Triggers for real-time notifications
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

-- Trigger to automatically update message_processing updated_at
DROP TRIGGER IF EXISTS trigger_message_processing_updated_at ON message_processing;
CREATE TRIGGER trigger_message_processing_updated_at
    BEFORE UPDATE ON message_processing
    FOR EACH ROW
    EXECUTE FUNCTION update_message_processing_updated_at();

-- Trigger to automatically create consumer group entries when new messages are added to outbox
DROP TRIGGER IF EXISTS trigger_create_consumer_group_entries ON outbox;
CREATE TRIGGER trigger_create_consumer_group_entries
    AFTER INSERT ON outbox
    FOR EACH ROW
    EXECUTE FUNCTION create_consumer_group_entries_for_new_message();

-- Trigger function for NOTIFY on new events (must be defined before trigger)
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

-- Create trigger for bi-temporal event notifications
DROP TRIGGER IF EXISTS trigger_notify_bitemporal_event ON bitemporal_event_log;
CREATE TRIGGER trigger_notify_bitemporal_event
    AFTER INSERT ON bitemporal_event_log
    FOR EACH ROW
    EXECUTE FUNCTION notify_bitemporal_event();

-- Function to clean up old metrics (retention policy)
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

-- Function to get events as of a specific point in time
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

-- Comments for documentation
COMMENT ON TABLE bitemporal_event_log IS 'Bi-temporal event log for append-only event sourcing with valid time and transaction time dimensions';
COMMENT ON COLUMN bitemporal_event_log.valid_time IS 'When the event actually happened in the real world (business time)';
COMMENT ON COLUMN bitemporal_event_log.transaction_time IS 'When the event was recorded in the system (system time)';
COMMENT ON COLUMN bitemporal_event_log.version IS 'Version number for tracking event evolution and corrections';
COMMENT ON COLUMN bitemporal_event_log.is_correction IS 'Indicates whether this event is a correction of a previous event';
COMMENT ON VIEW bitemporal_current_state IS 'Current state view showing latest version of each event as of now';
COMMENT ON FUNCTION get_events_as_of_time IS 'Function to query events as they existed at a specific point in bi-temporal time';
COMMENT ON TABLE outbox_consumer_groups IS 'Tracks which consumer groups have processed which outbox messages for reliable multi-consumer delivery';
COMMENT ON FUNCTION register_consumer_group_for_existing_messages IS 'Registers a new consumer group for all existing pending outbox messages';
COMMENT ON FUNCTION create_consumer_group_entries_for_new_message IS 'Automatically creates consumer group entries when new outbox messages are inserted';
COMMENT ON FUNCTION cleanup_completed_outbox_messages IS 'Cleans up outbox messages that have been processed by all registered consumer groups';

-- Comments for message_processing table
COMMENT ON TABLE message_processing IS
'INSERT-only message processing table that eliminates ExclusiveLock conflicts.
Instead of UPDATE-based locking on queue_messages, consumers create processing
records here to claim messages. This approach avoids row locks and concurrent
transaction conflicts in Vert.x environments.';

COMMENT ON COLUMN message_processing.message_id IS
'Foreign key to queue_messages.id - the message being processed';

COMMENT ON COLUMN message_processing.consumer_id IS
'Unique identifier for the consumer processing this message';

COMMENT ON COLUMN message_processing.status IS
'Processing status: PROCESSING, COMPLETED, FAILED, RETRYING';

COMMENT ON INDEX idx_message_processing_unique IS
'Prevents multiple consumers from processing the same message simultaneously';

COMMENT ON FUNCTION update_message_processing_updated_at IS
'Automatically updates the updated_at timestamp when message_processing records are modified';

COMMENT ON FUNCTION cleanup_completed_message_processing IS
'Cleans up completed message processing records older than 1 hour';

-- Note: Schema version will be automatically recorded by the migration manager
