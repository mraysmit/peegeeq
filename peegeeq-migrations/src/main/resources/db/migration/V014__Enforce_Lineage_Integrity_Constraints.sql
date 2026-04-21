-- Enforce the lineage invariants that the Java code assumes but the schema
-- did not previously guarantee.
--
-- 1. UNIQUE(event_id) — the entire chain/lineage model treats event_id as a
--    natural key (root resolution CTEs, advisory locks, getById). Without
--    uniqueness the recursive CTEs could traverse ambiguous graphs.
--
-- 2. FK(previous_version_id → event_id) — prevents dangling correction
--    references. Requires #1 as the FK target must be unique.
--
-- 3. UNIQUE(previous_version_id) WHERE NOT NULL — prevents chain forks.
--    Each event may have at most one successor. Without this, two concurrent
--    corrections could both claim the same predecessor (different versions)
--    and fork the lineage. The advisory lock prevents this at runtime; the
--    index is defense-in-depth.
--
-- Superseded indexes from V001 are dropped because the new unique
-- constraint/index covers the same columns.

-- ── 1. event_id uniqueness ──────────────────────────────────────────────
-- The UNIQUE constraint implicitly creates a unique B-tree index on
-- event_id, so the old non-unique idx_bitemporal_event_id is redundant.

DO $$
BEGIN
    IF to_regclass(current_schema() || '.bitemporal_event_log') IS NOT NULL THEN

        -- 1. event_id uniqueness
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint c
            JOIN pg_class t ON c.conrelid = t.oid
            WHERE t.relname = 'bitemporal_event_log' AND c.conname = 'uq_bitemporal_event_id'
        ) THEN
            EXECUTE 'ALTER TABLE bitemporal_event_log ADD CONSTRAINT uq_bitemporal_event_id UNIQUE (event_id)';
        END IF;

        DROP INDEX IF EXISTS idx_bitemporal_event_id;

        -- 2. Self-referential FK
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint c
            JOIN pg_class t ON c.conrelid = t.oid
            WHERE t.relname = 'bitemporal_event_log' AND c.conname = 'fk_bitemporal_previous_version'
        ) THEN
            EXECUTE 'ALTER TABLE bitemporal_event_log
                ADD CONSTRAINT fk_bitemporal_previous_version
                FOREIGN KEY (previous_version_id) REFERENCES bitemporal_event_log(event_id)
                ON DELETE RESTRICT';
        END IF;

        -- 3. Fork prevention
        EXECUTE 'CREATE UNIQUE INDEX IF NOT EXISTS idx_bitemporal_no_fork
            ON bitemporal_event_log(previous_version_id)
            WHERE previous_version_id IS NOT NULL';

        DROP INDEX IF EXISTS idx_bitemporal_corrections;

    END IF;
END $$;
