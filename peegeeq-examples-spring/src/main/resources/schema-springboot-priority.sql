-- Schema for Spring Boot Priority Example
-- Demonstrates priority-based message processing for trade settlement

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
    priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10)
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
    UNIQUE(outbox_message_id, consumer_group_name)
);

-- Trade settlements table - stores processed trade settlement events
CREATE TABLE IF NOT EXISTS trade_settlements (
    id VARCHAR(255) PRIMARY KEY,
    trade_id VARCHAR(255) NOT NULL,
    counterparty VARCHAR(255) NOT NULL,
    amount DECIMAL(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    settlement_date DATE NOT NULL,
    status VARCHAR(50) NOT NULL,
    priority VARCHAR(20) NOT NULL,
    failure_reason TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP,
    processed_by VARCHAR(255)
);

-- Priority consumer metrics table - tracks consumer performance by priority
CREATE TABLE IF NOT EXISTS priority_consumer_metrics (
    consumer_id VARCHAR(255) PRIMARY KEY,
    consumer_type VARCHAR(50) NOT NULL,
    priority_filter VARCHAR(20),
    messages_processed BIGINT NOT NULL DEFAULT 0,
    critical_processed BIGINT NOT NULL DEFAULT 0,
    high_processed BIGINT NOT NULL DEFAULT 0,
    normal_processed BIGINT NOT NULL DEFAULT 0,
    messages_filtered BIGINT NOT NULL DEFAULT 0,
    last_message_at TIMESTAMP,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(50) NOT NULL
);

-- Indexes for performance

-- Outbox indexes
CREATE INDEX IF NOT EXISTS idx_outbox_topic_status ON outbox(topic, status);
CREATE INDEX IF NOT EXISTS idx_outbox_status_created_at ON outbox(status, created_at);
CREATE INDEX IF NOT EXISTS idx_outbox_next_retry_at ON outbox(next_retry_at) WHERE status = 'FAILED';
CREATE INDEX IF NOT EXISTS idx_outbox_consumer_groups_message_id ON outbox_consumer_groups(outbox_message_id);
CREATE INDEX IF NOT EXISTS idx_outbox_consumer_groups_status ON outbox_consumer_groups(status);

-- Trade settlements indexes
CREATE INDEX IF NOT EXISTS idx_trade_settlements_trade_id ON trade_settlements(trade_id);
CREATE INDEX IF NOT EXISTS idx_trade_settlements_priority ON trade_settlements(priority);
CREATE INDEX IF NOT EXISTS idx_trade_settlements_status ON trade_settlements(status);
CREATE INDEX IF NOT EXISTS idx_trade_settlements_settlement_date ON trade_settlements(settlement_date);
CREATE INDEX IF NOT EXISTS idx_trade_settlements_created_at ON trade_settlements(created_at);

-- Priority consumer metrics indexes
CREATE INDEX IF NOT EXISTS idx_priority_consumer_metrics_type ON priority_consumer_metrics(consumer_type);
CREATE INDEX IF NOT EXISTS idx_priority_consumer_metrics_updated_at ON priority_consumer_metrics(updated_at);

