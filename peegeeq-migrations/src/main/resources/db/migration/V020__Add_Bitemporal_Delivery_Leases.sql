-- V020: Fence durable replay owners. Existing definitions remain unowned.
-- Rollback requires stopping durable consumers before dropping these three columns.
DO $$
BEGIN
    RAISE NOTICE '[PEEGEEQ MIGRATION] script=V020__Add_Bitemporal_Delivery_Leases.sql db=% schema=%',
        current_database(), current_schema();
END $$;

ALTER TABLE bitemporal_subscriptions
    ADD COLUMN IF NOT EXISTS lease_owner UUID,
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS lease_generation BIGINT NOT NULL DEFAULT 0;
