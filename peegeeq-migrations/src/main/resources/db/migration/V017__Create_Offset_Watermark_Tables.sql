-- V017: Create Offset/Watermark tables for partitioned consumption
--
-- Adds the three tables required for OFFSET_WATERMARK completion tracking mode:
--   - outbox_partition_assignments: partition ownership within a consumer group
--   - outbox_partition_offsets: per-(group, partition) offset cursor
--   - outbox_topic_watermarks: per-topic cleanup boundary
--
-- Also adds the critical composite index on outbox(topic, message_group, id)
-- for fast per-partition ordered fetch.
--
-- OFFSET_WATERMARK is a second completion tracking mode alongside the existing
-- REFERENCE_COUNTING mode. It provides per-key ordering, offset-based progress,
-- and O(1) write amplification per batch commit.

DO $$
BEGIN
    RAISE NOTICE '[PEEGEEQ MIGRATION] script=V017__Create_Offset_Watermark_Tables.sql db=% schema=%',
        current_database(), current_schema();
END $$;

-- Partition assignments: which consumer instance owns which partitions
CREATE TABLE IF NOT EXISTS outbox_partition_assignments (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    partition_key VARCHAR(255) NOT NULL,
    assigned_instance_id VARCHAR(255) NOT NULL,
    assigned_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_heartbeat_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    generation INT NOT NULL DEFAULT 1,
    UNIQUE(topic, group_name, partition_key)
);

CREATE INDEX IF NOT EXISTS idx_partition_assignments_instance
    ON outbox_partition_assignments(topic, group_name, assigned_instance_id);

-- Per-group, per-partition offset tracking
CREATE TABLE IF NOT EXISTS outbox_partition_offsets (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    partition_key VARCHAR(255) NOT NULL,
    committed_offset BIGINT NOT NULL DEFAULT 0,
    committed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    pending_offset BIGINT,
    pending_since TIMESTAMP WITH TIME ZONE,
    generation INT NOT NULL DEFAULT 1,
    UNIQUE(topic, group_name, partition_key)
);

-- Per-topic watermark the safe cleanup boundary
CREATE TABLE IF NOT EXISTS outbox_topic_watermarks (
    topic VARCHAR(255) PRIMARY KEY,
    watermark_id BIGINT NOT NULL DEFAULT 0,
    watermark_updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Critical index for fast partition-ordered fetch
-- Enables efficient WHERE topic=$1 AND message_group=$2 AND id > $3 ORDER BY id
CREATE INDEX IF NOT EXISTS idx_outbox_topic_msggroup_id
    ON outbox(topic, message_group, id)
    WHERE status IN ('PENDING', 'PROCESSING');
