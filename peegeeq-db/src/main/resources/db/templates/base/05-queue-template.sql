-- Drop and recreate base queue table template with LOG-level logging
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = '{schema}' AND table_name = 'queue_template') THEN
        DROP TABLE {schema}.queue_template CASCADE;
        RAISE LOG 'Dropped existing queue_template table for recreation' USING DETAIL = 'PGQINF0552';
    END IF;

    CREATE TABLE {schema}.queue_template (
        id BIGSERIAL PRIMARY KEY,
        topic VARCHAR(255) NOT NULL,
        payload JSONB NOT NULL,
        visible_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
        created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
        lock_id BIGINT,
        lock_until TIMESTAMP WITH TIME ZONE,
        retry_count INT DEFAULT 0,
        max_retries INT DEFAULT 3,
        status VARCHAR(50) DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'LOCKED', 'PROCESSED', 'FAILED', 'DEAD_LETTER')),
        headers JSONB DEFAULT '{}',
        error_message TEXT,
        correlation_id VARCHAR(255),
        message_group VARCHAR(255),
        priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10),
        idempotency_key VARCHAR(255)
    );
    RAISE LOG 'Created queue_template table' USING DETAIL = 'PGQINF0552';
END
$$;
