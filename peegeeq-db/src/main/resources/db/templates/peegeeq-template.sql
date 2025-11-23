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

-- Consumer Group Fanout Support Tables
-- Phase 6 requirement: Subscription management for consumer groups

-- 1. TOPIC CONFIGURATION TABLE
CREATE TABLE IF NOT EXISTS peegeeq.outbox_topics (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(255) NOT NULL UNIQUE,
    
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

-- 2. SUBSCRIPTION MANAGEMENT TABLE
CREATE TABLE IF NOT EXISTS peegeeq.outbox_topic_subscriptions (
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
    
    -- Backfill tracking (for resumable backfill)
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

-- 3. CONSUMER GROUP MESSAGE TRACKING (outbox_consumer_groups)
CREATE TABLE IF NOT EXISTS peegeeq.outbox_consumer_groups (
    id BIGSERIAL PRIMARY KEY,
    message_id BIGINT NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    claimed_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    lock_id BIGINT,
    lock_until TIMESTAMP WITH TIME ZONE,
    retry_count INT DEFAULT 0,
    error_message TEXT,
    
    UNIQUE(message_id, group_name)
);

-- 4. PROCESSED LEDGER (audit trail)
CREATE TABLE IF NOT EXISTS peegeeq.processed_ledger (
    id BIGSERIAL PRIMARY KEY,
    message_id BIGINT NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    topic VARCHAR(255) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    processing_duration_ms BIGINT,
    status VARCHAR(50) NOT NULL,
    error_message TEXT,
    partition_key TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 5. CONSUMER GROUP INDEX (performance optimization)
CREATE TABLE IF NOT EXISTS peegeeq.consumer_group_index (
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

-- Indexes for Consumer Group Fanout tables
CREATE INDEX IF NOT EXISTS idx_topic_subscriptions_active ON peegeeq.outbox_topic_subscriptions(topic, subscription_status) WHERE subscription_status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_topic_subscriptions_heartbeat ON peegeeq.outbox_topic_subscriptions(subscription_status, last_heartbeat_at) WHERE subscription_status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_outbox_consumer_groups_group_status ON peegeeq.outbox_consumer_groups(group_name, status, message_id);
CREATE INDEX IF NOT EXISTS idx_processed_ledger_time ON peegeeq.processed_ledger(topic, processed_at);
CREATE INDEX IF NOT EXISTS idx_consumer_group_index_topic ON peegeeq.consumer_group_index(topic, group_name);