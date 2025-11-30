-- Topic subscriptions active index
CREATE INDEX IF NOT EXISTS idx_topic_subscriptions_active ON peegeeq.outbox_topic_subscriptions(topic, subscription_status) WHERE subscription_status = 'ACTIVE';
