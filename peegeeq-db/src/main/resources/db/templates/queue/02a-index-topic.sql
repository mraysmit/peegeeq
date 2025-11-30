-- Composite index for topic-based queries with visibility and status
CREATE INDEX IF NOT EXISTS idx_{queueName}_topic_visible
    ON {schema}.{queueName}(topic, visible_at, status);
