-- Processed ledger table
CREATE TABLE IF NOT EXISTS {schema}.processed_ledger (
    id BIGSERIAL PRIMARY KEY,
    message_id BIGINT NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    topic VARCHAR(255) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    processing_duration_ms BIGINT,
    status VARCHAR(50) NOT NULL,
    error_message TEXT,
    partition_key TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
