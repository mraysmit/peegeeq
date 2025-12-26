-- Native queue messages table
CREATE TABLE IF NOT EXISTS {schema}.queue_messages (
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

-- Create unique index on (topic, idempotency_key) to prevent duplicates
-- Partial index: only for non-null idempotency keys
CREATE UNIQUE INDEX IF NOT EXISTS idx_queue_messages_idempotency_key
ON {schema}.queue_messages(topic, idempotency_key)
WHERE idempotency_key IS NOT NULL;

-- Add index for faster lookups
CREATE INDEX IF NOT EXISTS idx_queue_messages_idempotency_key_lookup
ON {schema}.queue_messages(idempotency_key)
WHERE idempotency_key IS NOT NULL;

