-- Priority-based ordering index
CREATE INDEX IF NOT EXISTS idx_{queueName}_priority
    ON {schema}.{queueName}(priority, created_at);
