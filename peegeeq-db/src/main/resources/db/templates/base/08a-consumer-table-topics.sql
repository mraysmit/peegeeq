-- Topic configuration table
CREATE TABLE IF NOT EXISTS {schema}.outbox_topics (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(255) NOT NULL UNIQUE,
    semantics VARCHAR(20) DEFAULT 'QUEUE' CHECK (semantics IN ('QUEUE', 'PUB_SUB')),
    message_retention_hours INT DEFAULT 24,
    zero_subscription_retention_hours INT DEFAULT 24,
    block_writes_on_zero_subscriptions BOOLEAN DEFAULT FALSE,
    completion_tracking_mode VARCHAR(20) DEFAULT 'REFERENCE_COUNTING' CHECK (completion_tracking_mode IN ('REFERENCE_COUNTING', 'OFFSET_WATERMARK')),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
