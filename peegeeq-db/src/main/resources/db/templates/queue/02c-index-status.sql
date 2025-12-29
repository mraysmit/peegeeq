-- Status and creation time index
CREATE INDEX IF NOT EXISTS "idx_{queueName}_status"
    ON {schema}."{queueName}"(status, created_at);
