-- Add unique index to enforce version uniqueness within each correction family.
-- COALESCE(previous_version_id, event_id) normalizes to the root event ID for all
-- members of a version family (root has NULL previous_version_id, corrections point
-- to root). Combined with version, this prevents concurrent corrections from
-- inserting duplicate version numbers — a race condition possible under READ
-- COMMITTED isolation even within Pool.withTransaction().
--
-- The advisory lock in appendCorrectionWithTransaction is the primary serialization
-- mechanism; this index is defense-in-depth.

CREATE UNIQUE INDEX idx_bitemporal_version_family_unique
    ON bitemporal_event_log(COALESCE(previous_version_id, event_id), version);
