-- Aggregate ID index (partial index for non-null values)
CREATE INDEX IF NOT EXISTS "idx_{tableName}_aggregate"
    ON {schema}."{tableName}"(aggregate_id) WHERE aggregate_id IS NOT NULL;
