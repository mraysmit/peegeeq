-- Self-identifying setup metadata: a single row declaring "this schema IS setup X".
--
-- The setupId is otherwise an in-memory-only label. Recording it here once, at provisioning, makes
-- the schema recognisable on connect and lets the estate registry be cross-checked / rebuilt from
-- the databases themselves.
CREATE TABLE IF NOT EXISTS {schema}.peegeeq_setup_metadata (
    setup_id       VARCHAR(255) PRIMARY KEY,
    schema_name    VARCHAR(255) NOT NULL,
    schema_version VARCHAR(50)  NOT NULL DEFAULT '1',
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
