-- PeeGeeQ Database Template
-- Creates a template database with all required schemas and extensions

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_stat_statements";

-- Create schemas
CREATE SCHEMA IF NOT EXISTS peegeeq;
CREATE SCHEMA IF NOT EXISTS bitemporal;

-- Set search path for current session
SET search_path TO peegeeq, bitemporal, public;

-- Drop and recreate base queue table template to ensure schema is up-to-date
-- Matches production schema from V001__Create_Base_Tables.sql (queue_messages table)
DROP TABLE IF EXISTS peegeeq.queue_template CASCADE;
CREATE TABLE peegeeq.queue_template (
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

-- Drop and recreate bi-temporal event store template to ensure schema is up-to-date
-- Matches production schema from V001__Create_Base_Tables.sql (bitemporal_event_log table)
DROP TABLE IF EXISTS bitemporal.event_store_template CASCADE;
CREATE TABLE bitemporal.event_store_template (
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

-- Create indexes on templates (non-concurrent for template processing)
-- Note: Templates are processed in transaction context, so CONCURRENTLY cannot be used
-- These indexes will be created quickly since templates are empty initially
-- CASCADE drop above removes old indexes, so we create fresh ones

-- Queue template indexes (match production queue_messages indexes)
CREATE INDEX idx_queue_template_topic_visible ON peegeeq.queue_template(topic, visible_at, status);
CREATE INDEX idx_queue_template_lock ON peegeeq.queue_template(lock_id) WHERE lock_id IS NOT NULL;
CREATE INDEX idx_queue_template_status ON peegeeq.queue_template(status, created_at);
CREATE INDEX idx_queue_template_correlation_id ON peegeeq.queue_template(correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX idx_queue_template_priority ON peegeeq.queue_template(priority, created_at);

-- Event store template indexes (match production bitemporal_event_log indexes)
CREATE INDEX idx_event_store_template_valid_time ON bitemporal.event_store_template(valid_time);
CREATE INDEX idx_event_store_template_tx_time ON bitemporal.event_store_template(transaction_time);
CREATE INDEX idx_event_store_template_event_id ON bitemporal.event_store_template(event_id);
CREATE INDEX idx_event_store_template_event_type ON bitemporal.event_store_template(event_type);
CREATE INDEX idx_event_store_template_aggregate ON bitemporal.event_store_template(aggregate_id) WHERE aggregate_id IS NOT NULL;
CREATE INDEX idx_event_store_template_correlation ON bitemporal.event_store_template(correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX idx_event_store_template_version ON bitemporal.event_store_template(event_id, version);
CREATE INDEX idx_event_store_template_corrections ON bitemporal.event_store_template(event_id, is_correction) WHERE is_correction = TRUE;
CREATE INDEX idx_event_store_template_payload_gin ON bitemporal.event_store_template USING GIN(payload);
CREATE INDEX idx_event_store_template_headers_gin ON bitemporal.event_store_template USING GIN(headers);