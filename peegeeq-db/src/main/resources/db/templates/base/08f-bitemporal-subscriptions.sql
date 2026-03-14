-- Durable bi-temporal subscription metadata
CREATE TABLE IF NOT EXISTS {schema}.bitemporal_subscriptions (
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

CREATE INDEX IF NOT EXISTS idx_bitemporal_subs_table_status
    ON {schema}.bitemporal_subscriptions(table_name, subscription_status);