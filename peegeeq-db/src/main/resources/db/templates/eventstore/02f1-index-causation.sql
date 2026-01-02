-- Causation ID index (partial index for non-null values)
CREATE INDEX IF NOT EXISTS "idx_{tableName}_causation"
    ON {schema}."{tableName}"(causation_id) WHERE causation_id IS NOT NULL;

