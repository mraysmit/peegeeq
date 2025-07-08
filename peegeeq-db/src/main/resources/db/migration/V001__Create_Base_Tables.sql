-- PeeGeeQ Database Schema - Base Tables
-- Version: 1.0.0
-- Description: Initial schema creation for PeeGeeQ message queue system

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

-- Performance indexes for outbox table
CREATE INDEX IF NOT EXISTS idx_outbox_status_created ON outbox(status, created_at);
CREATE INDEX IF NOT EXISTS idx_outbox_next_retry ON outbox(status, next_retry_at) WHERE status = 'FAILED';
CREATE INDEX IF NOT EXISTS idx_outbox_topic ON outbox(topic);
CREATE INDEX IF NOT EXISTS idx_outbox_correlation_id ON outbox(correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_outbox_message_group ON outbox(message_group) WHERE message_group IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_outbox_priority ON outbox(priority, created_at);

-- Performance indexes for queue_messages table
CREATE INDEX IF NOT EXISTS idx_queue_messages_topic_visible ON queue_messages(topic, visible_at, status);
CREATE INDEX IF NOT EXISTS idx_queue_messages_lock ON queue_messages(lock_id) WHERE lock_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_queue_messages_status ON queue_messages(status, created_at);
CREATE INDEX IF NOT EXISTS idx_queue_messages_correlation_id ON queue_messages(correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_queue_messages_priority ON queue_messages(priority, created_at);

-- Performance indexes for dead letter queue
CREATE INDEX IF NOT EXISTS idx_dlq_original ON dead_letter_queue(original_table, original_id);
CREATE INDEX IF NOT EXISTS idx_dlq_topic ON dead_letter_queue(topic);
CREATE INDEX IF NOT EXISTS idx_dlq_failed_at ON dead_letter_queue(failed_at);

-- Performance indexes for metrics tables
CREATE INDEX IF NOT EXISTS idx_queue_metrics_name_timestamp ON queue_metrics(metric_name, timestamp);
CREATE INDEX IF NOT EXISTS idx_connection_metrics_pool_timestamp ON connection_pool_metrics(pool_name, timestamp);

-- Functions for message processing
CREATE OR REPLACE FUNCTION notify_message_inserted()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify('peegeeq_' || NEW.topic, NEW.id::text);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Triggers for real-time notifications
CREATE TRIGGER IF NOT EXISTS trigger_outbox_notify
    AFTER INSERT ON outbox
    FOR EACH ROW
    EXECUTE FUNCTION notify_message_inserted();

CREATE TRIGGER IF NOT EXISTS trigger_queue_messages_notify
    AFTER INSERT ON queue_messages
    FOR EACH ROW
    EXECUTE FUNCTION notify_message_inserted();

-- Function to clean up old metrics (retention policy)
CREATE OR REPLACE FUNCTION cleanup_old_metrics(retention_days INT DEFAULT 30)
RETURNS INT AS $$
DECLARE
    deleted_count INT;
BEGIN
    DELETE FROM queue_metrics 
    WHERE timestamp < NOW() - INTERVAL '1 day' * retention_days;
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    
    DELETE FROM connection_pool_metrics 
    WHERE timestamp < NOW() - INTERVAL '1 day' * retention_days;
    GET DIAGNOSTICS deleted_count = deleted_count + ROW_COUNT;
    
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- Insert initial schema version
INSERT INTO schema_version (version, description, checksum) 
VALUES ('V001', 'Create base tables for PeeGeeQ message queue system', 'base_schema_v1')
ON CONFLICT (version) DO NOTHING;
