-- Subscription management table
CREATE TABLE IF NOT EXISTS {schema}.outbox_topic_subscriptions (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    subscription_status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (subscription_status IN ('ACTIVE', 'PAUSED', 'CANCELLED', 'DEAD')),
    subscribed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_active_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    start_from_message_id BIGINT,
    start_from_timestamp TIMESTAMP WITH TIME ZONE,
    heartbeat_interval_seconds INT DEFAULT 60,
    heartbeat_timeout_seconds INT DEFAULT 300,
    last_heartbeat_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    backfill_status VARCHAR(20) DEFAULT 'NONE' CHECK (backfill_status IN ('NONE', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'FAILED')),
    backfill_checkpoint_id BIGINT,
    backfill_processed_messages BIGINT DEFAULT 0,
    backfill_total_messages BIGINT,
    backfill_started_at TIMESTAMP WITH TIME ZONE,
    backfill_completed_at TIMESTAMP WITH TIME ZONE,
    UNIQUE(topic, group_name)
);
