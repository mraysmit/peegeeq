-- Correlation ID index (partial index for non-null values)
CREATE INDEX IF NOT EXISTS "idx_{tableName}_correlation"
    ON {schema}."{tableName}"(correlation_id) WHERE correlation_id IS NOT NULL;
