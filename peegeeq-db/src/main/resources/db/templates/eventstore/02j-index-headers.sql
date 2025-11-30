-- GIN index for JSONB headers queries
CREATE INDEX IF NOT EXISTS idx_{tableName}_headers_gin
    ON {schema}.{tableName} USING GIN(headers);
