-- Topic subscriptions heartbeat index
CREATE INDEX IF NOT EXISTS idx_topic_subscriptions_heartbeat ON peegeeq.outbox_topic_subscriptions(subscription_status, last_heartbeat_at) WHERE subscription_status = 'ACTIVE';
