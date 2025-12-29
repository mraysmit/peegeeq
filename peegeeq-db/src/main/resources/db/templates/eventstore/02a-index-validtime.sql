-- Primary temporal index: valid_time
CREATE INDEX IF NOT EXISTS "idx_{tableName}_valid_time"
    ON {schema}."{tableName}"(valid_time);
