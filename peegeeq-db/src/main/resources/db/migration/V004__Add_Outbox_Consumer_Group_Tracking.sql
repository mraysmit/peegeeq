-- Migration V004: Add consumer group tracking for outbox pattern
-- This enables proper pub/sub semantics where each consumer group receives all messages

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
    
    -- Ensure each consumer group processes each message only once
    UNIQUE(outbox_message_id, consumer_group_name)
);

-- Indexes for efficient querying
CREATE INDEX IF NOT EXISTS idx_outbox_consumer_groups_pending 
    ON outbox_consumer_groups(consumer_group_name, status, created_at) 
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_outbox_consumer_groups_message_id 
    ON outbox_consumer_groups(outbox_message_id);

CREATE INDEX IF NOT EXISTS idx_outbox_consumer_groups_status 
    ON outbox_consumer_groups(status, processing_started_at);

-- Function to register a consumer group for all existing pending messages
CREATE OR REPLACE FUNCTION register_consumer_group_for_existing_messages(group_name VARCHAR(255))
RETURNS INTEGER AS $$
DECLARE
    inserted_count INTEGER;
BEGIN
    -- Insert records for all pending outbox messages that this consumer group hasn't seen yet
    INSERT INTO outbox_consumer_groups (outbox_message_id, consumer_group_name, status)
    SELECT o.id, group_name, 'PENDING'
    FROM outbox o
    WHERE o.status IN ('PENDING', 'PROCESSING')
    AND NOT EXISTS (
        SELECT 1 FROM outbox_consumer_groups ocg 
        WHERE ocg.outbox_message_id = o.id 
        AND ocg.consumer_group_name = group_name
    );
    
    GET DIAGNOSTICS inserted_count = ROW_COUNT;
    RETURN inserted_count;
END;
$$ LANGUAGE plpgsql;

-- Trigger function to automatically create consumer group entries for new outbox messages
CREATE OR REPLACE FUNCTION create_consumer_group_entries_for_new_message()
RETURNS TRIGGER AS $$
BEGIN
    -- For each distinct consumer group that has been registered, create a pending entry
    INSERT INTO outbox_consumer_groups (outbox_message_id, consumer_group_name, status)
    SELECT DISTINCT NEW.id, consumer_group_name, 'PENDING'
    FROM outbox_consumer_groups
    WHERE consumer_group_name IS NOT NULL
    ON CONFLICT (outbox_message_id, consumer_group_name) DO NOTHING;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger to automatically create consumer group entries when new messages are added to outbox
CREATE TRIGGER trigger_create_consumer_group_entries
    AFTER INSERT ON outbox
    FOR EACH ROW
    EXECUTE FUNCTION create_consumer_group_entries_for_new_message();

-- Function to clean up completed messages when all consumer groups have processed them
CREATE OR REPLACE FUNCTION cleanup_completed_outbox_messages()
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    -- Delete outbox messages where all consumer groups have completed processing
    DELETE FROM outbox 
    WHERE id IN (
        SELECT o.id 
        FROM outbox o
        WHERE o.status = 'COMPLETED'
        AND NOT EXISTS (
            SELECT 1 FROM outbox_consumer_groups ocg 
            WHERE ocg.outbox_message_id = o.id 
            AND ocg.status != 'COMPLETED'
        )
        -- Only clean up messages older than 1 hour to allow for debugging
        AND o.created_at < NOW() - INTERVAL '1 hour'
    );
    
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;
