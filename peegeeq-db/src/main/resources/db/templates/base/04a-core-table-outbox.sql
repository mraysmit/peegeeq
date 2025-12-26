-- Outbox pattern table for reliable message delivery
CREATE TABLE IF NOT EXISTS {schema}.outbox (
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
    idempotency_key VARCHAR(255),
    
    -- Consumer group fanout columns
    required_consumer_groups INT DEFAULT 1,
    completed_consumer_groups INT DEFAULT 0,
    completed_groups_bitmap BIGINT DEFAULT 0
);

-- Create unique index on (topic, idempotency_key) for outbox
CREATE UNIQUE INDEX IF NOT EXISTS idx_outbox_idempotency_key
ON {schema}.outbox(topic, idempotency_key)
WHERE idempotency_key IS NOT NULL;

-- Add index for faster lookups on outbox
CREATE INDEX IF NOT EXISTS idx_outbox_idempotency_key_lookup
ON {schema}.outbox(idempotency_key)
WHERE idempotency_key IS NOT NULL;

