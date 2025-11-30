-- Consumer group index table
CREATE TABLE IF NOT EXISTS peegeeq.consumer_group_index (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    last_processed_id BIGINT DEFAULT 0,
    last_processed_at TIMESTAMP WITH TIME ZONE,
    pending_count BIGINT DEFAULT 0,
    processing_count BIGINT DEFAULT 0,
    completed_count BIGINT DEFAULT 0,
    failed_count BIGINT DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(topic, group_name)
);
