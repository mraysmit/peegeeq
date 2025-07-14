-- PeeGeeQ Database Schema - Add Processing Started At Column
-- Version: 1.0.1
-- Description: Add processing_started_at column to outbox table for better tracking

-- Add processing_started_at column to outbox table
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS processing_started_at TIMESTAMP WITH TIME ZONE;

-- Create index for performance on processing_started_at
CREATE INDEX IF NOT EXISTS idx_outbox_processing_started ON outbox(processing_started_at) WHERE processing_started_at IS NOT NULL;
