-- V021: Add STOPPED status to outbox_topic_subscriptions
--
-- Extends the outbox_topic_subscriptions subscription_status CHECK constraint to
-- include STOPPED. A graceful stop of a whole consumer group stores STOPPED. A start
-- of the group stores ACTIVE again. The start position and the committed offsets
-- are kept while the subscription is STOPPED.
--
-- Rollback: no row may hold STOPPED when the previous constraint is restored.

DO $$
BEGIN
    RAISE NOTICE '[PEEGEEQ MIGRATION] script=V021__Add_Stopped_Subscription_Status.sql db=% schema=%',
        current_database(), current_schema();
END $$;

-- Drop the existing CHECK constraint and recreate with STOPPED
ALTER TABLE outbox_topic_subscriptions
    DROP CONSTRAINT IF EXISTS outbox_topic_subscriptions_subscription_status_check;

ALTER TABLE outbox_topic_subscriptions
    ADD CONSTRAINT outbox_topic_subscriptions_subscription_status_check
    CHECK (subscription_status IN ('ACTIVE', 'PAUSED', 'CANCELLED', 'DEAD', 'STOPPED'));

COMMENT ON CONSTRAINT outbox_topic_subscriptions_subscription_status_check ON outbox_topic_subscriptions IS
    'Subscription lifecycle statuses: ACTIVE, PAUSED, CANCELLED, DEAD, STOPPED (graceful stop of the whole group)';
