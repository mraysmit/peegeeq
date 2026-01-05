-- Migration: Add tracing, grouping, and priority columns
-- Priority: High (Schema Alignment)
-- Date: 2025-12-15

-- 1. Update queue_messages table
ALTER TABLE queue_messages
ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(255),
ADD COLUMN IF NOT EXISTS message_group VARCHAR(255),
ADD COLUMN IF NOT EXISTS priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10);

CREATE INDEX IF NOT EXISTS idx_queue_messages_correlation_id ON queue_messages(correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_queue_messages_priority ON queue_messages(priority, created_at);

-- 2. Update outbox table
ALTER TABLE outbox
ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(255),
ADD COLUMN IF NOT EXISTS message_group VARCHAR(255),
ADD COLUMN IF NOT EXISTS priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10);

CREATE INDEX IF NOT EXISTS idx_outbox_correlation_id ON outbox(correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_outbox_message_group ON outbox(message_group) WHERE message_group IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_outbox_priority ON outbox(priority, created_at);

-- 3. Update bitemporal_event_log table (if missing)
ALTER TABLE bitemporal_event_log
ADD COLUMN IF NOT EXISTS causation_id VARCHAR(255);
