-- V002__Create_Message_Processing_Table.sql
-- Migration to add INSERT-only message processing table for native queue
-- This eliminates ExclusiveLock conflicts by avoiding UPDATE-based locking

-- Create message_processing table for INSERT-only message processing
CREATE TABLE IF NOT EXISTS message_processing (
    id BIGSERIAL PRIMARY KEY,
    message_id BIGINT NOT NULL,
    consumer_id VARCHAR(255) NOT NULL,
    topic VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PROCESSING',
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP WITH TIME ZONE,
    error_message TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Create unique constraint to prevent duplicate processing
CREATE UNIQUE INDEX IF NOT EXISTS idx_message_processing_unique 
ON message_processing (message_id, consumer_id) 
WHERE status IN ('PROCESSING', 'COMPLETED');

-- Create index for efficient querying by status and topic
CREATE INDEX IF NOT EXISTS idx_message_processing_status_topic 
ON message_processing (status, topic, started_at);

-- Create index for cleanup queries
CREATE INDEX IF NOT EXISTS idx_message_processing_completed 
ON message_processing (completed_at) 
WHERE status = 'COMPLETED';

-- Add foreign key constraint to queue_messages
ALTER TABLE message_processing 
ADD CONSTRAINT fk_message_processing_message_id 
FOREIGN KEY (message_id) REFERENCES queue_messages(id) 
ON DELETE CASCADE;

-- Add check constraint for valid status values
ALTER TABLE message_processing 
ADD CONSTRAINT chk_message_processing_status 
CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED', 'RETRYING'));

-- Create function to update updated_at timestamp
CREATE OR REPLACE FUNCTION update_message_processing_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Create trigger to automatically update updated_at
DROP TRIGGER IF EXISTS trigger_message_processing_updated_at ON message_processing;
CREATE TRIGGER trigger_message_processing_updated_at
    BEFORE UPDATE ON message_processing
    FOR EACH ROW
    EXECUTE FUNCTION update_message_processing_updated_at();

-- Create cleanup function for completed processing records
CREATE OR REPLACE FUNCTION cleanup_completed_message_processing()
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    -- Delete completed processing records older than 1 hour
    DELETE FROM message_processing 
    WHERE status = 'COMPLETED' 
    AND completed_at < NOW() - INTERVAL '1 hour';
    
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    
    IF deleted_count > 0 THEN
        RAISE NOTICE 'Cleaned up % completed message processing records', deleted_count;
    END IF;
    
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- Add comment explaining the INSERT-only approach
COMMENT ON TABLE message_processing IS 
'INSERT-only message processing table that eliminates ExclusiveLock conflicts. 
Instead of UPDATE-based locking on queue_messages, consumers create processing 
records here to claim messages. This approach avoids row locks and concurrent 
transaction conflicts in Vert.x environments.';

COMMENT ON COLUMN message_processing.message_id IS 
'Foreign key to queue_messages.id - the message being processed';

COMMENT ON COLUMN message_processing.consumer_id IS 
'Unique identifier for the consumer processing this message';

COMMENT ON COLUMN message_processing.status IS 
'Processing status: PROCESSING, COMPLETED, FAILED, RETRYING';

COMMENT ON INDEX idx_message_processing_unique IS 
'Prevents multiple consumers from processing the same message simultaneously';
