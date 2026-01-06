-- Create notification trigger for event store
CREATE OR REPLACE FUNCTION {schema}."notify_{tableName}_events"()
RETURNS TRIGGER AS $$
DECLARE
    channel_name TEXT;
    base_name TEXT;
    table_clean TEXT;
    hash_suffix TEXT;
    original_channel_name TEXT;
BEGIN
    -- PostgreSQL channel names are limited to 63 characters
    -- Build channel name: {schema}_{notificationPrefix}{tableName}
    -- Clean table name: replace hyphens with underscores
    table_clean := replace('{tableName}', '-', '_');
    base_name := lower('{schema}_{notificationPrefix}' || table_clean);

    -- Store original for debugging
    original_channel_name := base_name;

    -- If base name exceeds 63 chars, truncate and add hash for uniqueness
    IF length(base_name) > 63 THEN
        -- Create deterministic hash suffix from full name
        hash_suffix := '_' || substr(md5(base_name), 1, 8);
        -- Truncate to fit: 63 - length(hash_suffix) = available for prefix
        channel_name := substr(base_name, 1, 63 - length(hash_suffix)) || hash_suffix;

        -- Debug: Log channel name truncation (can be disabled in production)
        RAISE DEBUG 'Channel name truncated: original=% (len=%), hashed=% (len=%)',
            original_channel_name, length(original_channel_name),
            channel_name, length(channel_name);
    ELSE
        channel_name := base_name;
    END IF;

    PERFORM pg_notify(
        channel_name,
        json_build_object(
            'action', TG_OP,
            'id', NEW.id,
            'event_id', NEW.event_id,
            'event_type', NEW.event_type,
            'transaction_time', NEW.transaction_time,
            'correlation_id', NEW.correlation_id,
            'causation_id', NEW.causation_id,
            'channel_name', channel_name,
            'original_channel_name', original_channel_name
        )::text
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
