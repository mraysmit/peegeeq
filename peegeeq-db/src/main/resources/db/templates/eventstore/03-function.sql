-- Create notification trigger for event store
CREATE OR REPLACE FUNCTION {schema}.notify_{tableName}_events()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify('{schema}_{notificationPrefix}{tableName}',
        json_build_object(
            'action', TG_OP,
            'id', NEW.id,
            'event_id', NEW.event_id,
            'event_type', NEW.event_type,
            'transaction_time', NEW.transaction_time,
            'correlation_id', NEW.correlation_id
        )::text
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
