-- PeeGeeQ Consumer Group Fan-Out Schema
-- Version: 2.0
-- Description: Add consumer group fan-out support with Reference Counting mode
-- Related Design: CONSUMER_GROUP_FANOUT_DESIGN.md v2.0

-- ============================================================================
-- 1. CREATE TOPIC CONFIGURATION TABLE
-- ============================================================================

CREATE TABLE IF NOT EXISTS outbox_topics (
    topic VARCHAR(255) PRIMARY KEY,
    
    -- Topic semantics: QUEUE (distribute) or PUB_SUB (replicate)
    semantics VARCHAR(20) DEFAULT 'QUEUE' 
        CHECK (semantics IN ('QUEUE', 'PUB_SUB')),
    
    -- Retention policies
    message_retention_hours INT DEFAULT 24,
    zero_subscription_retention_hours INT DEFAULT 24,  -- Default: 24 hours (not 1 hour!)
    
    -- Zero-subscription protection
    block_writes_on_zero_subscriptions BOOLEAN DEFAULT FALSE,
    
    -- Completion tracking mode (for future Offset/Watermark support)
    completion_tracking_mode VARCHAR(20) DEFAULT 'REFERENCE_COUNTING'
        CHECK (completion_tracking_mode IN ('REFERENCE_COUNTING', 'OFFSET_WATERMARK')),
    
    -- Metadata
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- ============================================================================
-- 2. CREATE SUBSCRIPTION MANAGEMENT TABLE
-- ============================================================================

CREATE TABLE IF NOT EXISTS outbox_topic_subscriptions (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    
    -- Subscription lifecycle
    subscription_status VARCHAR(20) DEFAULT 'ACTIVE' 
        CHECK (subscription_status IN ('ACTIVE', 'PAUSED', 'CANCELLED', 'DEAD')),
    
    -- Timestamps
    subscribed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_active_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    -- Start position for late-joining consumers
    start_from_message_id BIGINT,
    start_from_timestamp TIMESTAMP WITH TIME ZONE,
    
    -- Heartbeat tracking for dead consumer detection
    heartbeat_interval_seconds INT DEFAULT 60,
    heartbeat_timeout_seconds INT DEFAULT 300,
    last_heartbeat_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    -- Backfill tracking (for resumable backfill - Phase 8)
    backfill_status VARCHAR(20) DEFAULT 'NONE'
        CHECK (backfill_status IN ('NONE', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'FAILED')),
    backfill_checkpoint_id BIGINT,
    backfill_processed_messages BIGINT DEFAULT 0,
    backfill_total_messages BIGINT,
    backfill_started_at TIMESTAMP WITH TIME ZONE,
    backfill_completed_at TIMESTAMP WITH TIME ZONE,
    
    -- Ensure one subscription per group per topic
    UNIQUE(topic, group_name)
);

-- ============================================================================
-- 3. ADD FANOUT COLUMNS TO OUTBOX TABLE
-- ============================================================================

-- Add required_consumer_groups (snapshot at insertion time - immutable)
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS required_consumer_groups INT DEFAULT 1;

-- Add completed_consumer_groups (incremented as groups complete)
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS completed_consumer_groups INT DEFAULT 0;

-- Add bitmap for efficient tracking (supports up to 64 consumer groups)
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS completed_groups_bitmap BIGINT DEFAULT 0;

-- ============================================================================
-- 4. UPDATE OUTBOX_CONSUMER_GROUPS TABLE
-- ============================================================================

-- Rename column for consistency with design docs
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'outbox_consumer_groups' 
        AND column_name = 'outbox_message_id'
    ) THEN
        ALTER TABLE outbox_consumer_groups RENAME COLUMN outbox_message_id TO message_id;
    END IF;
END $$;

-- Add group_name column if it doesn't exist (for consistency)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'outbox_consumer_groups' 
        AND column_name = 'group_name'
    ) THEN
        ALTER TABLE outbox_consumer_groups ADD COLUMN group_name VARCHAR(255);
        -- Migrate existing data
        UPDATE outbox_consumer_groups SET group_name = consumer_group_name WHERE group_name IS NULL;
        -- Drop old column
        ALTER TABLE outbox_consumer_groups DROP COLUMN IF EXISTS consumer_group_name;
    END IF;
END $$;

-- Ensure unique constraint exists
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'outbox_consumer_groups_message_id_group_name_key'
    ) THEN
        ALTER TABLE outbox_consumer_groups
            ADD CONSTRAINT outbox_consumer_groups_message_id_group_name_key
            UNIQUE(message_id, group_name);
    END IF;
END $$;

-- Update the old trigger function to use new column names
CREATE OR REPLACE FUNCTION create_consumer_group_entries_for_new_message()
RETURNS TRIGGER AS $$
BEGIN
    -- For each distinct consumer group that has been registered, create a pending entry
    INSERT INTO outbox_consumer_groups (message_id, group_name, status)
    SELECT DISTINCT NEW.id, group_name, 'PENDING'
    FROM outbox_consumer_groups
    WHERE group_name IS NOT NULL
    ON CONFLICT (message_id, group_name) DO NOTHING;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- 5. CREATE AUDIT AND TRACKING TABLES
-- ============================================================================

-- Processed ledger for audit trail
CREATE TABLE IF NOT EXISTS processed_ledger (
    id BIGSERIAL PRIMARY KEY,
    message_id BIGINT NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    topic VARCHAR(255) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    processing_duration_ms BIGINT,
    status VARCHAR(50) NOT NULL,
    error_message TEXT,
    
    -- Partition key for time-based partitioning
    partition_key TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Partition drop audit (for Offset/Watermark mode - Phase 7)
CREATE TABLE IF NOT EXISTS partition_drop_audit (
    id BIGSERIAL PRIMARY KEY,
    partition_name VARCHAR(255) NOT NULL,
    topic VARCHAR(255) NOT NULL,
    dropped_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    watermark_id BIGINT NOT NULL,
    message_count BIGINT,
    oldest_message_created_at TIMESTAMP WITH TIME ZONE,
    newest_message_created_at TIMESTAMP WITH TIME ZONE
);

-- Consumer group index for performance optimization
CREATE TABLE IF NOT EXISTS consumer_group_index (
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

-- ============================================================================
-- 6. CREATE INDEXES FOR PERFORMANCE
-- ============================================================================

-- Outbox fanout completion tracking
CREATE INDEX IF NOT EXISTS idx_outbox_fanout_completion 
    ON outbox(topic, status, completed_consumer_groups, required_consumer_groups)
    WHERE status IN ('PENDING', 'PROCESSING');

-- Outbox cleanup (messages eligible for deletion)
CREATE INDEX IF NOT EXISTS idx_outbox_fanout_cleanup
    ON outbox(status, processed_at, completed_consumer_groups, required_consumer_groups)
    WHERE status = 'COMPLETED';

-- Topic subscriptions - active subscriptions
CREATE INDEX IF NOT EXISTS idx_topic_subscriptions_active 
    ON outbox_topic_subscriptions(topic, subscription_status)
    WHERE subscription_status = 'ACTIVE';

-- Topic subscriptions - heartbeat monitoring
CREATE INDEX IF NOT EXISTS idx_topic_subscriptions_heartbeat
    ON outbox_topic_subscriptions(subscription_status, last_heartbeat_at)
    WHERE subscription_status = 'ACTIVE';

-- Consumer groups - message lookup
CREATE INDEX IF NOT EXISTS idx_outbox_consumer_groups_group_status
    ON outbox_consumer_groups(group_name, status, message_id);

-- Processed ledger - time-based queries
CREATE INDEX IF NOT EXISTS idx_processed_ledger_time
    ON processed_ledger(topic, processed_at);

-- Consumer group index - topic lookup
CREATE INDEX IF NOT EXISTS idx_consumer_group_index_topic
    ON consumer_group_index(topic, group_name);

-- ============================================================================
-- 7. CREATE TRIGGER FUNCTION FOR REQUIRED_CONSUMER_GROUPS
-- ============================================================================

CREATE OR REPLACE FUNCTION set_required_consumer_groups()
RETURNS TRIGGER AS $$
DECLARE
    topic_semantics VARCHAR(20);
    active_subscription_count INT;
BEGIN
    -- Get topic semantics (default to QUEUE if not configured)
    SELECT COALESCE(semantics, 'QUEUE') INTO topic_semantics
    FROM outbox_topics
    WHERE topic = NEW.topic;
    
    -- If topic not configured, treat as QUEUE
    IF topic_semantics IS NULL THEN
        topic_semantics := 'QUEUE';
    END IF;
    
    -- For PUB_SUB topics, count ACTIVE subscriptions
    IF topic_semantics = 'PUB_SUB' THEN
        SELECT COUNT(*) INTO active_subscription_count
        FROM outbox_topic_subscriptions
        WHERE topic = NEW.topic
          AND subscription_status = 'ACTIVE';
        
        NEW.required_consumer_groups := active_subscription_count;
    ELSE
        -- For QUEUE topics, set to 1 (backward compatibility)
        NEW.required_consumer_groups := 1;
    END IF;
    
    -- Initialize completed_consumer_groups to 0
    NEW.completed_consumer_groups := 0;
    NEW.completed_groups_bitmap := 0;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- 8. CREATE/UPDATE TRIGGER
-- ============================================================================

-- Drop old trigger if exists
DROP TRIGGER IF EXISTS trigger_set_required_consumer_groups ON outbox;

-- Create new trigger
CREATE TRIGGER trigger_set_required_consumer_groups
    BEFORE INSERT ON outbox
    FOR EACH ROW
    EXECUTE FUNCTION set_required_consumer_groups();

-- ============================================================================
-- 9. UPDATE EXISTING FUNCTIONS FOR FANOUT SUPPORT
-- ============================================================================

-- Update cleanup function to respect fanout completion
CREATE OR REPLACE FUNCTION cleanup_completed_outbox_messages()
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    -- Delete outbox messages where:
    -- 1. Status is COMPLETED
    -- 2. All required consumer groups have completed (completed >= required)
    -- 3. Message is older than retention period
    DELETE FROM outbox
    WHERE id IN (
        SELECT o.id
        FROM outbox o
        LEFT JOIN outbox_topics ot ON ot.topic = o.topic
        WHERE o.status = 'COMPLETED'
        AND (
            -- Queue semantics: simple completion
            (o.required_consumer_groups = 1 AND o.completed_consumer_groups >= 1)
            OR
            -- Pub/Sub: all groups completed
            (o.completed_consumer_groups >= o.required_consumer_groups AND o.required_consumer_groups > 1)
            OR
            -- Zero subscriptions: use topic-specific minimum retention
            (o.required_consumer_groups = 0
             AND o.created_at < NOW() - (
                 COALESCE(ot.zero_subscription_retention_hours, 24) || ' hours'
             )::INTERVAL)
        )
        -- Respect message retention period
        AND o.created_at < NOW() - (
            COALESCE(ot.message_retention_hours, 24) || ' hours'
        )::INTERVAL
        LIMIT 10000
    );

    GET DIAGNOSTICS deleted_count = ROW_COUNT;

    IF deleted_count > 0 THEN
        RAISE NOTICE 'Cleaned up % completed outbox messages', deleted_count;
    END IF;

    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- Function to mark consumer group as dead based on heartbeat timeout
CREATE OR REPLACE FUNCTION mark_dead_consumer_groups()
RETURNS INTEGER AS $$
DECLARE
    updated_count INTEGER;
BEGIN
    UPDATE outbox_topic_subscriptions
    SET subscription_status = 'DEAD',
        last_active_at = NOW()
    WHERE subscription_status = 'ACTIVE'
      AND last_heartbeat_at < NOW() - (heartbeat_timeout_seconds || ' seconds')::INTERVAL;

    GET DIAGNOSTICS updated_count = ROW_COUNT;

    IF updated_count > 0 THEN
        RAISE NOTICE 'Marked % consumer groups as DEAD due to heartbeat timeout', updated_count;
    END IF;

    RETURN updated_count;
END;
$$ LANGUAGE plpgsql;

-- Function to update consumer group index statistics
CREATE OR REPLACE FUNCTION update_consumer_group_index()
RETURNS INTEGER AS $$
DECLARE
    updated_count INTEGER;
BEGIN
    -- Update statistics for all active consumer groups
    INSERT INTO consumer_group_index (topic, group_name, last_processed_id, last_processed_at,
                                       pending_count, processing_count, completed_count, failed_count, updated_at)
    SELECT
        ocg.topic,
        ocg.group_name,
        MAX(CASE WHEN ocg.status = 'COMPLETED' THEN ocg.message_id ELSE 0 END) as last_processed_id,
        MAX(CASE WHEN ocg.status = 'COMPLETED' THEN ocg.processed_at ELSE NULL END) as last_processed_at,
        COUNT(*) FILTER (WHERE ocg.status = 'PENDING') as pending_count,
        COUNT(*) FILTER (WHERE ocg.status = 'PROCESSING') as processing_count,
        COUNT(*) FILTER (WHERE ocg.status = 'COMPLETED') as completed_count,
        COUNT(*) FILTER (WHERE ocg.status = 'FAILED') as failed_count,
        NOW() as updated_at
    FROM outbox_consumer_groups ocg
    INNER JOIN outbox o ON o.id = ocg.message_id
    GROUP BY ocg.topic, ocg.group_name
    ON CONFLICT (topic, group_name) DO UPDATE
    SET last_processed_id = EXCLUDED.last_processed_id,
        last_processed_at = EXCLUDED.last_processed_at,
        pending_count = EXCLUDED.pending_count,
        processing_count = EXCLUDED.processing_count,
        completed_count = EXCLUDED.completed_count,
        failed_count = EXCLUDED.failed_count,
        updated_at = EXCLUDED.updated_at;

    GET DIAGNOSTICS updated_count = ROW_COUNT;

    RETURN updated_count;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- 10. ADD COMMENTS FOR DOCUMENTATION
-- ============================================================================

COMMENT ON TABLE outbox_topics IS
'Topic configuration for QUEUE vs PUB_SUB semantics with retention policies';

COMMENT ON TABLE outbox_topic_subscriptions IS
'Consumer group subscriptions with heartbeat tracking and backfill support';

COMMENT ON COLUMN outbox.required_consumer_groups IS
'Snapshot of required groups at message insertion time (immutable after insert)';

COMMENT ON COLUMN outbox.completed_consumer_groups IS
'Count of consumer groups that have completed processing this message';

COMMENT ON COLUMN outbox.completed_groups_bitmap IS
'Bitmap tracking which groups have completed (supports up to 64 groups)';

COMMENT ON TABLE processed_ledger IS
'Audit trail of message processing by consumer groups';

COMMENT ON TABLE partition_drop_audit IS
'Audit trail of partition drops for Offset/Watermark mode cleanup';

COMMENT ON TABLE consumer_group_index IS
'Performance index tracking consumer group processing statistics';

COMMENT ON FUNCTION set_required_consumer_groups IS
'Trigger function to set required_consumer_groups based on active subscriptions at insertion time';

COMMENT ON FUNCTION cleanup_completed_outbox_messages IS
'Cleanup function that respects fanout completion and retention policies';

COMMENT ON FUNCTION mark_dead_consumer_groups IS
'Marks consumer groups as DEAD when heartbeat timeout is exceeded';

COMMENT ON FUNCTION update_consumer_group_index IS
'Updates consumer group index statistics for monitoring and performance';

-- ============================================================================
-- 11. MIGRATION COMPLETE
-- ============================================================================

-- Insert schema version record
INSERT INTO schema_version (version, description, checksum)
VALUES ('V010', 'Consumer Group Fan-Out Support (Reference Counting Mode)',
        md5('V010__Create_Consumer_Group_Fanout_Tables.sql'))
ON CONFLICT (version) DO NOTHING;


