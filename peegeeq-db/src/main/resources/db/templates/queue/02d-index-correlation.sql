-- Correlation ID index for message tracking (partial index for non-null values)
CREATE INDEX IF NOT EXISTS "idx_{queueName}_correlation_id"
    ON {schema}."{queueName}"(correlation_id) WHERE correlation_id IS NOT NULL;
