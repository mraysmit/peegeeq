-- Ensure dead letter failure timestamp is always present.
-- Backfill any legacy null values defensively before adding constraint.
UPDATE dead_letter_queue
SET failed_at = NOW()
WHERE failed_at IS NULL;

ALTER TABLE dead_letter_queue
ALTER COLUMN failed_at SET NOT NULL;
