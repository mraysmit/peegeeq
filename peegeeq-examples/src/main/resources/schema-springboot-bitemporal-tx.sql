-- Spring Boot Bi-Temporal Transaction Coordination Schema

-- Enable UUID extension for generating UUIDs
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
-- 
-- This schema supports advanced bi-temporal event store patterns with
-- multi-event store transaction coordination using PostgreSQL ACID transactions.
-- 
-- Features:
-- - Multiple bi-temporal event stores (Order, Inventory, Payment, Audit)
-- - Shared transaction coordination across all stores
-- - Optimized indexes for temporal queries
-- - Regulatory compliance and audit trail support

-- Enable required PostgreSQL extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "btree_gin";

-- =============================================================================
-- BI-TEMPORAL EVENT LOG TABLE
-- =============================================================================
-- This table stores all bi-temporal events across all event stores
-- Each event store uses this same table with different payload types

CREATE TABLE IF NOT EXISTS bitemporal_event_log (
    -- Primary identifiers
    event_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    event_type VARCHAR(100) NOT NULL,
    
    -- Bi-temporal dimensions
    valid_time TIMESTAMPTZ NOT NULL,
    transaction_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    -- Event data
    payload JSONB NOT NULL,
    headers JSONB DEFAULT '{}',
    
    -- Versioning and corrections
    version BIGINT NOT NULL DEFAULT 1,
    previous_version_id UUID REFERENCES bitemporal_event_log(event_id),
    is_correction BOOLEAN NOT NULL DEFAULT FALSE,
    correction_reason TEXT,
    
    -- Event organization
    correlation_id VARCHAR(100),
    aggregate_id VARCHAR(100),
    
    -- Metadata
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    -- Constraints
    CONSTRAINT valid_version CHECK (version > 0),
    CONSTRAINT correction_requires_reason CHECK (
        NOT is_correction OR correction_reason IS NOT NULL
    )
);

-- =============================================================================
-- INDEXES FOR BI-TEMPORAL EVENT LOG
-- =============================================================================

-- Primary query indexes
CREATE INDEX IF NOT EXISTS idx_bitemporal_event_type 
    ON bitemporal_event_log (event_type);

CREATE INDEX IF NOT EXISTS idx_bitemporal_aggregate_id 
    ON bitemporal_event_log (aggregate_id) 
    WHERE aggregate_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_bitemporal_correlation_id 
    ON bitemporal_event_log (correlation_id) 
    WHERE correlation_id IS NOT NULL;

-- Bi-temporal query indexes
CREATE INDEX IF NOT EXISTS idx_bitemporal_valid_time 
    ON bitemporal_event_log (valid_time);

CREATE INDEX IF NOT EXISTS idx_bitemporal_transaction_time 
    ON bitemporal_event_log (transaction_time);

CREATE INDEX IF NOT EXISTS idx_bitemporal_temporal_range 
    ON bitemporal_event_log (valid_time, transaction_time);

-- Versioning and correction indexes
CREATE INDEX IF NOT EXISTS idx_bitemporal_version 
    ON bitemporal_event_log (event_id, version);

CREATE INDEX IF NOT EXISTS idx_bitemporal_corrections 
    ON bitemporal_event_log (is_correction, previous_version_id) 
    WHERE is_correction = TRUE;

-- Payload query indexes (GIN for JSONB)
CREATE INDEX IF NOT EXISTS idx_bitemporal_payload_gin 
    ON bitemporal_event_log USING GIN (payload);

CREATE INDEX IF NOT EXISTS idx_bitemporal_headers_gin 
    ON bitemporal_event_log USING GIN (headers);

-- Composite indexes for common query patterns
CREATE INDEX IF NOT EXISTS idx_bitemporal_type_aggregate 
    ON bitemporal_event_log (event_type, aggregate_id) 
    WHERE aggregate_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_bitemporal_type_valid_time 
    ON bitemporal_event_log (event_type, valid_time);

CREATE INDEX IF NOT EXISTS idx_bitemporal_correlation_transaction_time 
    ON bitemporal_event_log (correlation_id, transaction_time) 
    WHERE correlation_id IS NOT NULL;

-- =============================================================================
-- EVENT STORE STATISTICS VIEW
-- =============================================================================
-- Provides statistics across all event stores for monitoring and reporting

DROP VIEW IF EXISTS bitemporal_event_stats CASCADE;
CREATE VIEW bitemporal_event_stats AS
SELECT 
    event_type,
    COUNT(*) as total_events,
    COUNT(*) FILTER (WHERE is_correction = TRUE) as total_corrections,
    MIN(valid_time) as oldest_event_time,
    MAX(valid_time) as newest_event_time,
    MIN(transaction_time) as oldest_transaction_time,
    MAX(transaction_time) as newest_transaction_time,
    COUNT(DISTINCT aggregate_id) as unique_aggregates,
    COUNT(DISTINCT correlation_id) as unique_correlations,
    pg_size_pretty(
        (pg_total_relation_size('bitemporal_event_log') *
        (COUNT(*)::float / (SELECT COUNT(*) FROM bitemporal_event_log)))::bigint
    ) as estimated_storage_size
FROM bitemporal_event_log
GROUP BY event_type
ORDER BY total_events DESC;

-- =============================================================================
-- TRANSACTION COORDINATION FUNCTIONS
-- =============================================================================

-- Function to get all events for a correlation ID (cross-store query)
CREATE OR REPLACE FUNCTION get_correlated_events(p_correlation_id VARCHAR(100))
RETURNS TABLE (
    event_id UUID,
    event_type VARCHAR(100),
    valid_time TIMESTAMPTZ,
    transaction_time TIMESTAMPTZ,
    payload JSONB,
    aggregate_id VARCHAR(100)
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        e.event_id,
        e.event_type,
        e.valid_time,
        e.transaction_time,
        e.payload,
        e.aggregate_id
    FROM bitemporal_event_log e
    WHERE e.correlation_id = p_correlation_id
    ORDER BY e.transaction_time, e.valid_time;
END;
$$ LANGUAGE plpgsql;

-- Function to get event store statistics
CREATE OR REPLACE FUNCTION get_event_store_stats()
RETURNS TABLE (
    total_events BIGINT,
    total_corrections BIGINT,
    event_types JSONB,
    storage_size TEXT
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        COUNT(*) as total_events,
        COUNT(*) FILTER (WHERE is_correction = TRUE) as total_corrections,
        jsonb_object_agg(event_type, event_count) as event_types,
        pg_size_pretty(pg_total_relation_size('bitemporal_event_log')::bigint) as storage_size
    FROM (
        SELECT 
            event_type,
            COUNT(*) as event_count
        FROM bitemporal_event_log
        GROUP BY event_type
    ) type_counts;
END;
$$ LANGUAGE plpgsql;

-- =============================================================================
-- AUDIT AND COMPLIANCE SUPPORT
-- =============================================================================

-- Audit trail view for regulatory compliance
DROP VIEW IF EXISTS audit_trail CASCADE;
CREATE VIEW audit_trail AS
SELECT 
    event_id,
    event_type,
    valid_time,
    transaction_time,
    CASE 
        WHEN is_correction THEN 'CORRECTION'
        ELSE 'ORIGINAL'
    END as record_type,
    correction_reason,
    correlation_id,
    aggregate_id,
    payload->>'userId' as user_id,
    payload->>'sourceSystem' as source_system,
    created_at
FROM bitemporal_event_log
WHERE event_type IN ('TRANSACTION_STARTED', 'TRANSACTION_COMPLETED', 'COMPLIANCE_CHECK', 'SECURITY_EVENT')
ORDER BY transaction_time DESC;

-- =============================================================================
-- PERFORMANCE OPTIMIZATION
-- =============================================================================

-- Analyze table for query optimization
ANALYZE bitemporal_event_log;

-- Set table statistics target for better query planning
ALTER TABLE bitemporal_event_log ALTER COLUMN event_type SET STATISTICS 1000;
ALTER TABLE bitemporal_event_log ALTER COLUMN valid_time SET STATISTICS 1000;
ALTER TABLE bitemporal_event_log ALTER COLUMN transaction_time SET STATISTICS 1000;

-- =============================================================================
-- COMMENTS AND DOCUMENTATION
-- =============================================================================

COMMENT ON TABLE bitemporal_event_log IS 
'Bi-temporal event log supporting multiple event stores with transaction coordination';

COMMENT ON COLUMN bitemporal_event_log.valid_time IS 
'When the event actually happened in the real world (business time)';

COMMENT ON COLUMN bitemporal_event_log.transaction_time IS 
'When the event was recorded in the system (system time)';

COMMENT ON COLUMN bitemporal_event_log.correlation_id IS 
'Links related events across multiple event stores in coordinated transactions';

COMMENT ON VIEW bitemporal_event_stats IS 
'Statistics view for monitoring event store performance and usage';

COMMENT ON FUNCTION get_correlated_events(VARCHAR) IS 
'Retrieves all events across event stores for a given correlation ID';

-- =============================================================================
-- INITIAL DATA AND CONFIGURATION
-- =============================================================================

-- Schema is now ready for bi-temporal event store coordination
-- Initial configuration will be created by the application when needed
