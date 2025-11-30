-- Event identification index: event_id
CREATE INDEX IF NOT EXISTS idx_{tableName}_event_id
    ON {schema}.{tableName}(event_id);
