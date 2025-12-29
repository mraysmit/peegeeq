-- Lock tracking index (partial index for locked messages)
CREATE INDEX IF NOT EXISTS "idx_{queueName}_lock"
    ON {schema}."{queueName}"(lock_id) WHERE lock_id IS NOT NULL;
