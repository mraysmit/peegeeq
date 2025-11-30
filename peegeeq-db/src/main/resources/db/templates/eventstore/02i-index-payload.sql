-- GIN index for JSONB payload queries
CREATE INDEX IF NOT EXISTS idx_{tableName}_payload_gin
    ON {schema}.{tableName} USING GIN(payload);
