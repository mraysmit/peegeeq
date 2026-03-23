-- Defense-in-depth index to catch version collisions within a correction family.
--
-- COALESCE(previous_version_id, event_id) groups each event with its immediate
-- predecessor: root events (previous_version_id IS NULL) coalesce to their own
-- event_id, corrections coalesce to their predecessor's event_id. This prevents
-- two concurrent corrections from claiming the same (predecessor, version) pair.
--
-- NOTE: This does NOT normalize to the family root for chains deeper than two.
-- In a chain A(v1) → B(v2) → C(v3), C's COALESCE is 'B', not 'A'. The index
-- guards against duplicate versions for the same immediate predecessor, not
-- across the entire lineage. Full lineage serialization is enforced by the
-- advisory lock on the canonical root in appendCorrectionOwnTransaction.
-- Fork prevention (at most one successor per event) is enforced separately
-- by idx_bitemporal_no_fork (V014).

CREATE UNIQUE INDEX idx_bitemporal_version_family_unique
    ON bitemporal_event_log(COALESCE(previous_version_id, event_id), version);
