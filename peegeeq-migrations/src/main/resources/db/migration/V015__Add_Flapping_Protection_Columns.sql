-- V015: Add flapping protection columns to outbox_topic_subscriptions
--
-- Adds consecutive_misses counter and configurable dead_after_misses threshold
-- to implement flapping protection. Instead of marking a subscription DEAD on
-- the first heartbeat timeout, the detector increments consecutive_misses and
-- only transitions to DEAD when consecutive_misses >= dead_after_misses.
--
-- The counter resets to 0 when:
--   - A heartbeat is received (normal operation or DEAD auto-resurrection)
--   - A subscription is resubscribed (ON CONFLICT upsert)
--
-- Operational guidance:
--   - These are cheap metadata columns on a low-cardinality subscription table.
--   - Write cost is driven by heartbeat update cadence, not by the columns themselves.
--   - Prefer moderate heartbeat intervals (30-60s) unless faster failure detection is required.
--   - Do not index consecutive_misses or dead_after_misses unless a proven query requires it.
--   - State is kept on the subscription row deliberately so it survives process restarts
--     and detector/job instance changes — do not move this to in-memory tracking.
--   - At 10,000 subscriptions with 60s heartbeat intervals, expect ~167 row updates/sec.
--     Monitor autovacuum and dead tuples on outbox_topic_subscriptions at higher scale.

ALTER TABLE outbox_topic_subscriptions
    ADD COLUMN consecutive_misses INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN dead_after_misses INTEGER NOT NULL DEFAULT 3;

COMMENT ON COLUMN outbox_topic_subscriptions.consecutive_misses IS
    'Number of consecutive detection cycles where the heartbeat was expired. Reset to 0 on heartbeat or resubscription.';

COMMENT ON COLUMN outbox_topic_subscriptions.dead_after_misses IS
    'Number of consecutive missed heartbeats required before marking the subscription DEAD. Default 3.';
