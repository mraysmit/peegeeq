-- Versioning index: event_id + version
CREATE INDEX IF NOT EXISTS idx_{tableName}_version
    ON {schema}.{tableName}(event_id, version);
