-- Idempotency key indexes for message deduplication
-- Unique index on (topic, idempotency_key) to prevent duplicates
CREATE UNIQUE INDEX IF NOT EXISTS "idx_{queueName}_idempotency_key"
    ON {schema}."{queueName}"(topic, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Lookup index for faster idempotency key queries
CREATE INDEX IF NOT EXISTS "idx_{queueName}_idempotency_key_lookup"
    ON {schema}."{queueName}"(idempotency_key)
    WHERE idempotency_key IS NOT NULL;

