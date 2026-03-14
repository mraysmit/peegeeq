-- =============================================================================
-- V012: Durable bi-temporal subscription schema
-- =============================================================================

DO $$
BEGIN
    RAISE NOTICE '[PEEGEEQ MIGRATION] script=V012__Create_Bitemporal_Durable_Subscriptions.sql db=% schema=%',
        current_database(), current_schema();
END $$;

ALTER TABLE outbox_topic_subscriptions
ADD COLUMN IF NOT EXISTS durable_enabled BOOLEAN DEFAULT TRUE;

CREATE TABLE IF NOT EXISTS bitemporal_subscriptions (
    id BIGSERIAL PRIMARY KEY,
    table_name VARCHAR(255) NOT NULL,
    subscription_name VARCHAR(255) NOT NULL,
    consumer_group VARCHAR(255) NOT NULL,
    event_type VARCHAR(255),
    aggregate_id VARCHAR(255),
    subscription_status VARCHAR(20) DEFAULT 'ACTIVE'
        CHECK (subscription_status IN ('ACTIVE', 'PAUSED', 'CANCELLED', 'DEAD')),
    start_from_event_id BIGINT,
    last_processed_id BIGINT,
    last_processed_at TIMESTAMP WITH TIME ZONE,
    heartbeat_interval_seconds INT DEFAULT 60,
    heartbeat_timeout_seconds INT DEFAULT 300,
    last_heartbeat_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    subscribed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_active_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE (table_name, subscription_name, consumer_group)
);

DO $$
BEGIN
    IF to_regclass(current_schema() || '.bitemporal_event_log') IS NOT NULL THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_bitemporal_event_log_id_type_agg ON bitemporal_event_log(id, event_type, aggregate_id)';
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_bitemporal_subs_table_status
    ON bitemporal_subscriptions(table_name, subscription_status);

COMMENT ON TABLE bitemporal_subscriptions IS
'Durable subscription definitions and cursors for bi-temporal event streams.';

COMMENT ON COLUMN outbox_topic_subscriptions.durable_enabled IS
'Formal durability flag for outbox subscriptions. Existing subscriptions remain durable by default.';