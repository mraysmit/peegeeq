-- Durable setup-binding registry: one row per remembered setup.
--
-- A binding is the knowledge lost on backend restart: which setup exists, and the connection
-- coordinates to reach it. Persisting it lets startup re-establish every remembered setup via
-- connectToExistingSetup with no manual step.
--
-- The registry stores connection COORDINATES ONLY plus an opaque, nullable credential_ref.
-- It must NEVER gain a password column — the password is resolved at connect time through the
-- CredentialProvider seam and is never persisted by PeeGeeQ.
CREATE TABLE IF NOT EXISTS {schema}.peegeeq_setup_bindings (
    setup_id       VARCHAR(255) PRIMARY KEY,
    host           VARCHAR(255) NOT NULL,
    port           INT          NOT NULL,
    database_name  VARCHAR(255) NOT NULL,
    schema_name    VARCHAR(255) NOT NULL,
    username       VARCHAR(255) NOT NULL,
    ssl_enabled    BOOLEAN      NOT NULL DEFAULT FALSE,
    credential_ref VARCHAR(1024),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
