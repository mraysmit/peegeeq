-- =============================================================================
-- V013: Fix Bi-Temporal Notification Channel Names
-- =============================================================================
-- 
-- ISSUE: The notify_bitemporal_event() trigger function was sending notifications
-- to channels that don't match what ReactiveNotificationHandler listens on.
--
-- Before (BROKEN):
--   Trigger sent to: 'bitemporal_events' and 'bitemporal_events_{event_type}'
--   Handler listened on: '{schema}_bitemporal_events_{table}' and 
--                        '{schema}_bitemporal_events_{table}_{event_type}'
--
-- After (FIXED):
--   Trigger sends to: '{schema}_bitemporal_events_{table}' (general channel)
--                     '{schema}_bitemporal_events_{table}_{event_type}' (type-specific)
--   Handler listens on: Same channels - MATCH!
--
-- The fix uses TG_TABLE_SCHEMA and TG_TABLE_NAME to dynamically construct
-- channel names that match the handler's expected format.
--
-- BREAKING CHANGE: Any external systems listening on the old channel names
-- ('bitemporal_events', 'bitemporal_events_*') will need to update their
-- subscriptions to use the new schema-qualified format.
-- =============================================================================

-- Replace the trigger function with the corrected version
CREATE OR REPLACE FUNCTION notify_bitemporal_event() RETURNS TRIGGER AS $$
DECLARE
    -- Channel name components
    schema_name TEXT;
    table_name TEXT;
    channel_prefix TEXT;
    general_channel TEXT;
    type_channel TEXT;
    type_suffix TEXT;
    hash_suffix TEXT;
BEGIN
    -- Get the schema and table name from trigger context
    schema_name := TG_TABLE_SCHEMA;
    table_name := TG_TABLE_NAME;
    
    -- Build channel prefix: {schema}_bitemporal_events_{table}
    -- This matches ReactiveNotificationHandler.setupListenChannels() format
    channel_prefix := schema_name || '_bitemporal_events_' || table_name;
    
    -- PostgreSQL channel names are limited to 63 characters
    -- If prefix is too long, truncate and add hash for uniqueness
    IF length(channel_prefix) > 63 THEN
        hash_suffix := '_' || substr(md5(channel_prefix), 1, 8);
        general_channel := substr(channel_prefix, 1, 63 - length(hash_suffix)) || hash_suffix;
    ELSE
        general_channel := channel_prefix;
    END IF;
    
    -- Send notification on general channel (for wildcard subscriptions)
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
    
    -- Build type-specific channel: {prefix}_{event_type}
    -- Replace dots with underscores in event type (same as handler)
    type_suffix := replace(NEW.event_type, '.', '_');
    type_channel := channel_prefix || '_' || type_suffix;
    
    -- Truncate type-specific channel if needed
    IF length(type_channel) > 63 THEN
        hash_suffix := '_' || substr(md5(type_channel), 1, 8);
        type_channel := substr(type_channel, 1, 63 - length(hash_suffix)) || hash_suffix;
    END IF;
    
    -- Send notification on type-specific channel (for exact match subscriptions)
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

-- Note: The trigger itself doesn't need to be recreated since it just calls
-- the function by name. The new function definition takes effect immediately.

COMMENT ON FUNCTION notify_bitemporal_event() IS 
    'Sends PostgreSQL NOTIFY on event insert. Channels are schema-qualified: '
    '{schema}_bitemporal_events_{table} for general notifications and '
    '{schema}_bitemporal_events_{table}_{event_type} for type-specific. '
    'Fixed in V013 to match ReactiveNotificationHandler expected channel names.';
