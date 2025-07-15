
-- PeeGeeQ Database Schema - Bi-Temporal Event Log
-- Version: 1.1.0
-- Description: Create bi-temporal event log table for append-only event sourcing

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

-- Indexes for efficient querying

-- Primary temporal indexes
CREATE INDEX IF NOT EXISTS idx_bitemporal_valid_time 
    ON bitemporal_event_log(valid_time);

CREATE INDEX IF NOT EXISTS idx_bitemporal_transaction_time 
    ON bitemporal_event_log(transaction_time);

-- Bi-temporal composite index for point-in-time queries
CREATE INDEX IF NOT EXISTS idx_bitemporal_valid_transaction 
    ON bitemporal_event_log(valid_time, transaction_time);

-- Event identification and type indexes
CREATE INDEX IF NOT EXISTS idx_bitemporal_event_id 
    ON bitemporal_event_log(event_id);

CREATE INDEX IF NOT EXISTS idx_bitemporal_event_type 
    ON bitemporal_event_log(event_type);

CREATE INDEX IF NOT EXISTS idx_bitemporal_event_type_valid_time 
    ON bitemporal_event_log(event_type, valid_time);

-- Grouping and correlation indexes
CREATE INDEX IF NOT EXISTS idx_bitemporal_aggregate_id 
    ON bitemporal_event_log(aggregate_id) WHERE aggregate_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_bitemporal_correlation_id 
    ON bitemporal_event_log(correlation_id) WHERE correlation_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_bitemporal_aggregate_valid_time 
    ON bitemporal_event_log(aggregate_id, valid_time) WHERE aggregate_id IS NOT NULL;

-- Version and correction indexes
CREATE INDEX IF NOT EXISTS idx_bitemporal_version 
    ON bitemporal_event_log(event_id, version);

CREATE INDEX IF NOT EXISTS idx_bitemporal_corrections 
    ON bitemporal_event_log(previous_version_id) WHERE previous_version_id IS NOT NULL;

-- Performance index for latest events
CREATE INDEX IF NOT EXISTS idx_bitemporal_latest_events 
    ON bitemporal_event_log(event_type, transaction_time DESC) 
    WHERE is_correction = FALSE;

-- GIN index for JSONB payload queries
CREATE INDEX IF NOT EXISTS idx_bitemporal_payload_gin 
    ON bitemporal_event_log USING GIN(payload);

-- GIN index for JSONB headers queries
CREATE INDEX IF NOT EXISTS idx_bitemporal_headers_gin 
    ON bitemporal_event_log USING GIN(headers);

-- Views for common queries

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

-- Trigger function for NOTIFY on new events
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

-- Create trigger for real-time notifications
CREATE TRIGGER trigger_notify_bitemporal_event
    AFTER INSERT ON bitemporal_event_log
    FOR EACH ROW
    EXECUTE FUNCTION notify_bitemporal_event();

-- Comments for documentation
COMMENT ON TABLE bitemporal_event_log IS 'Bi-temporal event log for append-only event sourcing with valid time and transaction time dimensions';
COMMENT ON COLUMN bitemporal_event_log.valid_time IS 'When the event actually happened in the real world (business time)';
COMMENT ON COLUMN bitemporal_event_log.transaction_time IS 'When the event was recorded in the system (system time)';
COMMENT ON COLUMN bitemporal_event_log.version IS 'Version number for tracking event evolution and corrections';
COMMENT ON COLUMN bitemporal_event_log.is_correction IS 'Indicates whether this event is a correction of a previous event';
COMMENT ON VIEW bitemporal_current_state IS 'Current state view showing latest version of each event as of now';
COMMENT ON FUNCTION get_events_as_of_time IS 'Function to query events as they existed at a specific point in bi-temporal time';
