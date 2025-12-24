-- Create notification trigger
CREATE OR REPLACE FUNCTION {schema}.notify_{queueName}_changes()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify('{schema}_queue_{queueName}',
        json_build_object(
            'action', TG_OP,
            'id', COALESCE(NEW.id, OLD.id),
            'topic', COALESCE(NEW.topic, OLD.topic)
        )::text
    );
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;
