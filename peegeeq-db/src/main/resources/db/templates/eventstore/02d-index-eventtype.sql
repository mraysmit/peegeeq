-- Event type index
CREATE INDEX IF NOT EXISTS idx_{tableName}_event_type
    ON {schema}.{tableName}(event_type);
