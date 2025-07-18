-- PeeGeeQ Database Template
-- Creates a template database with all required schemas and extensions

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_stat_statements";

-- Create schemas
CREATE SCHEMA IF NOT EXISTS peegeeq;
CREATE SCHEMA IF NOT EXISTS bitemporal;

-- Set search path
ALTER DATABASE CURRENT SET search_path TO peegeeq, bitemporal, public;

-- Create base queue table template
CREATE TABLE IF NOT EXISTS peegeeq.queue_template (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    queue_name VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    visible_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    retry_count INTEGER DEFAULT 0,
    max_retries INTEGER DEFAULT 3,
    status VARCHAR(50) DEFAULT 'PENDING',
    consumer_id VARCHAR(255),
    last_processed_at TIMESTAMP WITH TIME ZONE
);

-- Create bi-temporal event store template
CREATE TABLE IF NOT EXISTS bitemporal.event_store_template (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    event_type VARCHAR(255) NOT NULL,
    event_data JSONB NOT NULL,
    valid_from TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_to TIMESTAMP WITH TIME ZONE DEFAULT 'infinity',
    transaction_time TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    correlation_id UUID,
    causation_id UUID,
    version INTEGER DEFAULT 1,
    metadata JSONB DEFAULT '{}'::jsonb
);

-- Create indexes on templates
CREATE INDEX IF NOT EXISTS idx_queue_template_queue_name_status ON peegeeq.queue_template(queue_name, status);
CREATE INDEX IF NOT EXISTS idx_queue_template_visible_at ON peegeeq.queue_template(visible_at);

CREATE INDEX IF NOT EXISTS idx_event_store_template_type_time ON bitemporal.event_store_template(event_type, transaction_time);
CREATE INDEX IF NOT EXISTS idx_event_store_template_valid_time ON bitemporal.event_store_template(valid_from, valid_to);
CREATE INDEX IF NOT EXISTS idx_event_store_template_correlation ON bitemporal.event_store_template(correlation_id);