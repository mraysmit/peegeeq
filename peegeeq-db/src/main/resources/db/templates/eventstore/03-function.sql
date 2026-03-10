-- Create notification trigger for event store
CREATE OR REPLACE FUNCTION {schema}."notify_{tableName}_events"()
RETURNS TRIGGER AS $$
DECLARE
    channel_prefix TEXT;
    general_channel TEXT;
    type_channel TEXT;
    type_suffix TEXT;
    table_clean TEXT;
    hash_suffix TEXT;
    general_original_name TEXT;
    type_original_name TEXT;
BEGIN
    -- PostgreSQL channel names are limited to 63 characters
    -- Build channel names to match ReactiveNotificationHandler:
    --   General: {schema}_bitemporal_events_{table}
    --   Type:    {schema}_bitemporal_events_{table}_{event_type}
    --
    -- Note: notificationPrefix is intentionally not used here for bitemporal stores,
    -- to keep runtime LISTEN channels deterministic and compatible with Flyway V013.

    -- Clean table name: replace hyphens with underscores
    table_clean := replace('{tableName}', '-', '_');
    channel_prefix := lower('{schema}_bitemporal_events_' || table_clean);

    -- General channel (wildcard/all subscriptions)
    general_original_name := channel_prefix;
    IF length(channel_prefix) > 63 THEN
        hash_suffix := '_' || substr(md5(channel_prefix), 1, 8);
        general_channel := substr(channel_prefix, 1, 63 - length(hash_suffix)) || hash_suffix;
    ELSE
        general_channel := channel_prefix;
    END IF;

    PERFORM pg_notify(
        general_channel,
        json_build_object(
            'action', TG_OP,
            'id', NEW.id,
            'event_id', NEW.event_id,
            'event_type', NEW.event_type,
            'aggregate_id', NEW.aggregate_id,
            'correlation_id', NEW.correlation_id,
            'causation_id', NEW.causation_id,
            'is_correction', NEW.is_correction,
            'transaction_time', extract(epoch from NEW.transaction_time),
            'channel_name', general_channel,
            'original_channel_name', general_original_name
        )::text
    );

    -- Type-specific channel (exact subscriptions)
    type_suffix := replace(NEW.event_type, '.', '_');
    type_original_name := channel_prefix || '_' || type_suffix;

    IF length(type_original_name) > 63 THEN
        hash_suffix := '_' || substr(md5(type_original_name), 1, 8);
        type_channel := substr(type_original_name, 1, 63 - length(hash_suffix)) || hash_suffix;
    ELSE
        type_channel := type_original_name;
    END IF;

    PERFORM pg_notify(
        type_channel,
        json_build_object(
            'action', TG_OP,
            'id', NEW.id,
            'event_id', NEW.event_id,
            'event_type', NEW.event_type,
            'aggregate_id', NEW.aggregate_id,
            'correlation_id', NEW.correlation_id,
            'causation_id', NEW.causation_id,
            'is_correction', NEW.is_correction,
            'transaction_time', extract(epoch from NEW.transaction_time),
            'channel_name', type_channel,
            'general_channel', general_channel,
            'original_channel_name', type_original_name
        )::text
    );

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
