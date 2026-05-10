-- V018: Add rebalance_generation to outbox_topic_subscriptions
--
-- Tracks the current rebalance generation for OFFSET_WATERMARK partitioned consumption.
-- Used as the serialization point for rebalance operations via FOR UPDATE locking.
-- Each joinGroup/leaveGroup increments this counter atomically.

DO $$
BEGIN
    RAISE NOTICE '[PEEGEEQ MIGRATION] script=V018__Add_Rebalance_Generation_To_Subscriptions.sql db=% schema=%',
        current_database(), current_schema();
END $$;

ALTER TABLE outbox_topic_subscriptions
    ADD COLUMN IF NOT EXISTS rebalance_generation INT NOT NULL DEFAULT 0;
