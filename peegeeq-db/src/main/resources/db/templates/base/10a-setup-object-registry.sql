-- Self-describing object registry: one row per queue / event-store in this setup's schema.
--
-- Native and outbox queues produce byte-identical table DDL, so a queue's implementation kind and
-- full config are NOT recoverable from the schema shape. This registry records them explicitly,
-- written in the same transaction that creates the object (and removed with it), so
-- connectToExistingSetup can rebuild the setup's factories with the exact kind + config rather than
-- inferring anything from table shapes.
CREATE TABLE IF NOT EXISTS {schema}.peegeeq_object_registry (
    object_name VARCHAR(255) PRIMARY KEY,
    kind        VARCHAR(20)  NOT NULL CHECK (kind IN ('native', 'outbox', 'bitemporal')),
    config      JSONB        NOT NULL DEFAULT '{}',
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
