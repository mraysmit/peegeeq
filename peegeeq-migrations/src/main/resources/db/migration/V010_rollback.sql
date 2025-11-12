-- PeeGeeQ Consumer Group Fan-Out Rollback
-- Version: 2.0
-- Description: Rollback V010 migration - removes consumer group fan-out support
-- WARNING: This will drop tables and columns. Ensure you have backups!

-- ============================================================================
-- ROLLBACK INSTRUCTIONS
-- ============================================================================
-- This script is NOT automatically executed by Flyway.
-- To rollback V010, manually run this script:
--   psql -U peegeeq_dev -d peegeeq_dev -f V010_rollback.sql
--
-- IMPORTANT: This will:
-- 1. Drop fanout-specific tables (outbox_topics, outbox_topic_subscriptions, etc.)
-- 2. Remove fanout columns from outbox table
-- 3. Restore original cleanup function
-- 4. Drop fanout-specific triggers and functions
--
-- Data Loss Warning:
-- - All topic configurations will be lost
-- - All subscription registrations will be lost
-- - Audit trail in processed_ledger will be lost
-- ============================================================================

BEGIN;

-- ============================================================================
-- 1. DROP TRIGGERS
-- ============================================================================

DROP TRIGGER IF EXISTS trigger_set_required_consumer_groups ON outbox;

-- ============================================================================
-- 2. DROP FUNCTIONS
-- ============================================================================

DROP FUNCTION IF EXISTS set_required_consumer_groups();
DROP FUNCTION IF EXISTS mark_dead_consumer_groups();
DROP FUNCTION IF EXISTS update_consumer_group_index();

-- ============================================================================
-- 3. RESTORE ORIGINAL CLEANUP FUNCTION
-- ============================================================================

CREATE OR REPLACE FUNCTION cleanup_completed_outbox_messages()
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    -- Original cleanup logic (pre-fanout)
    DELETE FROM outbox
    WHERE id IN (
        SELECT o.id
        FROM outbox o
        WHERE o.status = 'COMPLETED'
        AND NOT EXISTS (
            SELECT 1 FROM outbox_consumer_groups ocg
            WHERE ocg.message_id = o.id
            AND ocg.status != 'COMPLETED'
        )
        -- Only clean up messages older than 1 hour to allow for debugging
        AND o.created_at < NOW() - INTERVAL '1 hour'
    );

    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- 4. DROP INDEXES
-- ============================================================================

DROP INDEX IF EXISTS idx_outbox_fanout_completion;
DROP INDEX IF EXISTS idx_outbox_fanout_cleanup;
DROP INDEX IF EXISTS idx_topic_subscriptions_active;
DROP INDEX IF EXISTS idx_topic_subscriptions_heartbeat;
DROP INDEX IF EXISTS idx_outbox_consumer_groups_group_status;
DROP INDEX IF EXISTS idx_processed_ledger_time;
DROP INDEX IF EXISTS idx_consumer_group_index_topic;

-- ============================================================================
-- 5. DROP TABLES
-- ============================================================================

DROP TABLE IF EXISTS consumer_group_index CASCADE;
DROP TABLE IF EXISTS partition_drop_audit CASCADE;
DROP TABLE IF EXISTS processed_ledger CASCADE;
DROP TABLE IF EXISTS outbox_topic_subscriptions CASCADE;
DROP TABLE IF EXISTS outbox_topics CASCADE;

-- ============================================================================
-- 6. REMOVE COLUMNS FROM OUTBOX TABLE
-- ============================================================================

ALTER TABLE outbox DROP COLUMN IF EXISTS required_consumer_groups;
ALTER TABLE outbox DROP COLUMN IF EXISTS completed_consumer_groups;
ALTER TABLE outbox DROP COLUMN IF EXISTS completed_groups_bitmap;

-- ============================================================================
-- 7. RESTORE OUTBOX_CONSUMER_GROUPS COLUMN NAMES
-- ============================================================================

-- Rename message_id back to outbox_message_id (if needed)
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'outbox_consumer_groups' 
        AND column_name = 'message_id'
    ) THEN
        ALTER TABLE outbox_consumer_groups RENAME COLUMN message_id TO outbox_message_id;
    END IF;
END $$;

-- Rename group_name back to consumer_group_name (if needed)
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'outbox_consumer_groups' 
        AND column_name = 'group_name'
    ) THEN
        ALTER TABLE outbox_consumer_groups RENAME COLUMN group_name TO consumer_group_name;
    END IF;
END $$;

-- ============================================================================
-- 8. REMOVE SCHEMA VERSION RECORD
-- ============================================================================

DELETE FROM schema_version WHERE version = 'V010';

-- ============================================================================
-- 9. VERIFY ROLLBACK
-- ============================================================================

DO $$
DECLARE
    table_count INT;
    column_count INT;
BEGIN
    -- Check that fanout tables are dropped
    SELECT COUNT(*) INTO table_count
    FROM information_schema.tables
    WHERE table_name IN ('outbox_topics', 'outbox_topic_subscriptions', 
                         'processed_ledger', 'partition_drop_audit', 'consumer_group_index');
    
    IF table_count > 0 THEN
        RAISE EXCEPTION 'Rollback failed: % fanout tables still exist', table_count;
    END IF;
    
    -- Check that fanout columns are dropped
    SELECT COUNT(*) INTO column_count
    FROM information_schema.columns
    WHERE table_name = 'outbox'
      AND column_name IN ('required_consumer_groups', 'completed_consumer_groups', 'completed_groups_bitmap');
    
    IF column_count > 0 THEN
        RAISE EXCEPTION 'Rollback failed: % fanout columns still exist in outbox table', column_count;
    END IF;
    
    RAISE NOTICE 'Rollback verification successful: All fanout schema changes have been removed';
END $$;

COMMIT;

-- ============================================================================
-- ROLLBACK COMPLETE
-- ============================================================================


