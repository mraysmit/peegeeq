-- V016: Add DEAD_LETTER status to outbox_consumer_groups
--
-- Extends the outbox_consumer_groups status CHECK constraint to include
-- DEAD_LETTER, supporting automated retry exhaustion and dead letter queue
-- movement for consumer group fanout.
--
-- When a consumer group has failed processing a message beyond the
-- configured max_retries threshold, the ConsumerGroupRetryService
-- moves the message to the dead_letter_queue table and marks the
-- tracking row as DEAD_LETTER to indicate it has been permanently
-- moved out of active processing.

DO $$
BEGIN
    RAISE NOTICE '[PEEGEEQ MIGRATION] script=V016__Add_Consumer_Group_Dead_Letter_Status.sql db=% schema=%',
        current_database(), current_schema();
END $$;

-- Drop the existing CHECK constraint and recreate with DEAD_LETTER
ALTER TABLE outbox_consumer_groups
    DROP CONSTRAINT IF EXISTS outbox_consumer_groups_status_check;

ALTER TABLE outbox_consumer_groups
    ADD CONSTRAINT outbox_consumer_groups_status_check
    CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD_LETTER'));

COMMENT ON CONSTRAINT outbox_consumer_groups_status_check ON outbox_consumer_groups IS
    'Status lifecycle: PENDING → PROCESSING → COMPLETED/FAILED → DEAD_LETTER (after retry exhaustion)';
