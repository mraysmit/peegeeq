-- Migration: Add idempotency_key column for message deduplication
-- Priority 2: Message Deduplication
-- Date: 2025-12-24

-- Add idempotency_key column to queue_messages table
ALTER TABLE queue_messages
ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(255);

-- Create unique index on (topic, idempotency_key) to prevent duplicates
-- Partial index: only for non-null idempotency keys
CREATE UNIQUE INDEX IF NOT EXISTS idx_queue_messages_idempotency_key
ON queue_messages(topic, idempotency_key)
WHERE idempotency_key IS NOT NULL;

-- Add index for faster lookups
CREATE INDEX IF NOT EXISTS idx_queue_messages_idempotency_key_lookup
ON queue_messages(idempotency_key)
WHERE idempotency_key IS NOT NULL;

-- Add comment explaining the column
COMMENT ON COLUMN queue_messages.idempotency_key IS
'Optional idempotency key for message deduplication. When provided, prevents duplicate messages with the same key from being inserted into the same topic. Typically set from the Idempotency-Key HTTP header.';

-- Add idempotency_key column to outbox table (for consistency)
ALTER TABLE outbox
ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(255);

-- Create unique index on (topic, idempotency_key) for outbox
CREATE UNIQUE INDEX IF NOT EXISTS idx_outbox_idempotency_key
ON outbox(topic, idempotency_key)
WHERE idempotency_key IS NOT NULL;

-- Add index for faster lookups on outbox
CREATE INDEX IF NOT EXISTS idx_outbox_idempotency_key_lookup
ON outbox(idempotency_key)
WHERE idempotency_key IS NOT NULL;

-- Add comment for outbox
COMMENT ON COLUMN outbox.idempotency_key IS
'Optional idempotency key for message deduplication in outbox pattern. Prevents duplicate messages with the same key from being inserted into the same topic.';

