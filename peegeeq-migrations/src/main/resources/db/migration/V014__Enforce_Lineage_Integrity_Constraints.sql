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

ALTER TABLE bitemporal_event_log
    ADD CONSTRAINT uq_bitemporal_event_id UNIQUE (event_id);

DROP INDEX IF EXISTS idx_bitemporal_event_id;

-- ── 2. Self-referential FK ──────────────────────────────────────────────
-- previous_version_id must reference an existing event_id when set.
-- ON DELETE RESTRICT: deleting an event that has successors is forbidden
-- (append-only log — rows are never deleted in normal operation, and if
-- administrative purge is needed it must walk the chain leaf-first).

ALTER TABLE bitemporal_event_log
    ADD CONSTRAINT fk_bitemporal_previous_version
    FOREIGN KEY (previous_version_id) REFERENCES bitemporal_event_log(event_id)
    ON DELETE RESTRICT;

-- ── 3. Fork prevention ─────────────────────────────────────────────────
-- At most one event may claim a given previous_version_id. This ensures
-- the correction chain is strictly linear (no branching).
-- The partial WHERE clause excludes NULLs — root events (version 1) have
-- previous_version_id IS NULL and are not subject to this constraint.
--
-- This supersedes idx_bitemporal_corrections from V001 (same column,
-- same WHERE filter, but now unique).

CREATE UNIQUE INDEX idx_bitemporal_no_fork
    ON bitemporal_event_log(previous_version_id)
    WHERE previous_version_id IS NOT NULL;

DROP INDEX IF EXISTS idx_bitemporal_corrections;
