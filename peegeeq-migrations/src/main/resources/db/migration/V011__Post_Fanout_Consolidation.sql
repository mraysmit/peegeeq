-- =============================================================================
-- V011: Post-V010 Consolidation
-- =============================================================================
-- Consolidates prior additive migrations (V011-V015) into a single post-V010
-- convergence migration for non-production environments.
--
-- Includes:
-- 1) Idempotency key support
-- 2) Tracing/grouping compatibility columns
-- 3) Bitemporal notification channel-name fix
-- 4) DLQ failed_at NOT NULL enforcement
-- 5) Bitemporal causation_id hardening/index
-- =============================================================================

DO $$
BEGIN
	RAISE NOTICE '[PEEGEEQ MIGRATION] script=V011__Post_Fanout_Consolidation.sql db=% schema=%',
		current_database(), current_schema();
END $$;

-- -----------------------------------------------------------------------------
-- 1) Idempotency key support
-- -----------------------------------------------------------------------------

ALTER TABLE queue_messages
ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS idx_queue_messages_idempotency_key
ON queue_messages(topic, idempotency_key)
WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_queue_messages_idempotency_key_lookup
ON queue_messages(idempotency_key)
WHERE idempotency_key IS NOT NULL;

COMMENT ON COLUMN queue_messages.idempotency_key IS
'Optional idempotency key for message deduplication. When provided, prevents duplicate messages with the same key from being inserted into the same topic.';

ALTER TABLE outbox
ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS idx_outbox_idempotency_key
ON outbox(topic, idempotency_key)
WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_outbox_idempotency_key_lookup
ON outbox(idempotency_key)
WHERE idempotency_key IS NOT NULL;

COMMENT ON COLUMN outbox.idempotency_key IS
'Optional idempotency key for message deduplication in outbox pattern.';

-- -----------------------------------------------------------------------------
-- 2) Tracing/grouping compatibility columns
-- -----------------------------------------------------------------------------

ALTER TABLE outbox
ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(255),
ADD COLUMN IF NOT EXISTS message_group VARCHAR(255),
ADD COLUMN IF NOT EXISTS priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10);

ALTER TABLE queue_messages
ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(255),
ADD COLUMN IF NOT EXISTS message_group VARCHAR(255),
ADD COLUMN IF NOT EXISTS priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10);

ALTER TABLE bitemporal_event_log
ADD COLUMN IF NOT EXISTS causation_id VARCHAR(255);

-- -----------------------------------------------------------------------------
-- 3) Bitemporal notification channel-name fix
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION notify_bitemporal_event() RETURNS TRIGGER AS $$
DECLARE
	schema_name TEXT;
	table_name TEXT;
	channel_prefix TEXT;
	general_channel TEXT;
	type_channel TEXT;
	type_suffix TEXT;
	hash_suffix TEXT;
BEGIN
	schema_name := TG_TABLE_SCHEMA;
	table_name := TG_TABLE_NAME;

	channel_prefix := schema_name || '_bitemporal_events_' || table_name;

	IF length(channel_prefix) > 63 THEN
		hash_suffix := '_' || substr(md5(channel_prefix), 1, 8);
		general_channel := substr(channel_prefix, 1, 63 - length(hash_suffix)) || hash_suffix;
	ELSE
		general_channel := channel_prefix;
	END IF;

	PERFORM pg_notify(
		general_channel,
		json_build_object(
			'event_id', NEW.event_id,
			'event_type', NEW.event_type,
			'aggregate_id', NEW.aggregate_id,
			'correlation_id', NEW.correlation_id,
			'causation_id', NEW.causation_id,
			'is_correction', NEW.is_correction,
			'transaction_time', extract(epoch from NEW.transaction_time),
			'channel_name', general_channel
		)::text
	);

	type_suffix := replace(NEW.event_type, '.', '_');
	type_channel := channel_prefix || '_' || type_suffix;

	IF length(type_channel) > 63 THEN
		hash_suffix := '_' || substr(md5(type_channel), 1, 8);
		type_channel := substr(type_channel, 1, 63 - length(hash_suffix)) || hash_suffix;
	END IF;

	PERFORM pg_notify(
		type_channel,
		json_build_object(
			'event_id', NEW.event_id,
			'event_type', NEW.event_type,
			'aggregate_id', NEW.aggregate_id,
			'correlation_id', NEW.correlation_id,
			'causation_id', NEW.causation_id,
			'is_correction', NEW.is_correction,
			'transaction_time', extract(epoch from NEW.transaction_time),
			'channel_name', type_channel,
			'general_channel', general_channel
		)::text
	);

	RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION notify_bitemporal_event() IS
	'Sends PostgreSQL NOTIFY using schema-qualified channel names expected by reactive listeners.';

-- -----------------------------------------------------------------------------
-- 4) DLQ data quality hardening
-- -----------------------------------------------------------------------------

UPDATE dead_letter_queue
SET failed_at = NOW()
WHERE failed_at IS NULL;

ALTER TABLE dead_letter_queue
ALTER COLUMN failed_at SET NOT NULL;

-- -----------------------------------------------------------------------------
-- 5) Bitemporal causation_id hardening/index
-- -----------------------------------------------------------------------------

ALTER TABLE IF EXISTS bitemporal_event_log
	ADD COLUMN IF NOT EXISTS causation_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_bitemporal_causation_id
	ON bitemporal_event_log(causation_id)
	WHERE causation_id IS NOT NULL;
