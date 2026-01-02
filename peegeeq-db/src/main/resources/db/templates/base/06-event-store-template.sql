-- Drop and recreate bi-temporal event store template with LOG-level logging
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = '{schema}' AND table_name = 'event_store_template') THEN
        DROP TABLE {schema}.event_store_template CASCADE;
        RAISE LOG 'Dropped existing event_store_template table for recreation' USING DETAIL = 'PGQINF0552';
    END IF;

    CREATE TABLE {schema}.event_store_template (
        id BIGSERIAL PRIMARY KEY,
        event_id VARCHAR(255) NOT NULL,
        event_type VARCHAR(255) NOT NULL,
        valid_time TIMESTAMP WITH TIME ZONE NOT NULL,
        transaction_time TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
        payload JSONB NOT NULL,
        headers JSONB DEFAULT '{}',
        version BIGINT DEFAULT 1 NOT NULL,
        previous_version_id VARCHAR(255),
        is_correction BOOLEAN DEFAULT FALSE NOT NULL,
        correction_reason TEXT,
        correlation_id VARCHAR(255),
        causation_id VARCHAR(255),
        aggregate_id VARCHAR(255),
        created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
        CONSTRAINT chk_version_positive CHECK (version > 0),
        CONSTRAINT chk_correction_reason CHECK (
            (is_correction = FALSE AND correction_reason IS NULL) OR
            (is_correction = TRUE AND correction_reason IS NOT NULL)
        ),
        CONSTRAINT chk_previous_version CHECK (
            (version = 1 AND previous_version_id IS NULL) OR
            (version > 1 AND previous_version_id IS NOT NULL)
        )
    );
    RAISE LOG 'Created event_store_template table' USING DETAIL = 'PGQINF0552';
END
$$;
