-- Corrections index (partial index for corrections only)
CREATE INDEX IF NOT EXISTS idx_{tableName}_corrections
    ON {schema}.{tableName}(event_id, is_correction) WHERE is_correction = TRUE;
