-- Queue template idempotency_key indexes
-- Unique index on (topic, idempotency_key) to prevent duplicates
CREATE UNIQUE INDEX idx_queue_template_idempotency_key ON {schema}.queue_template(topic, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- Lookup index for faster idempotency key queries
CREATE INDEX idx_queue_template_idempotency_key_lookup ON {schema}.queue_template(idempotency_key) WHERE idempotency_key IS NOT NULL;

