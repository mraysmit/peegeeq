-- Template for creating individual queue tables
-- Parameters: {queueName}, {schema}

CREATE TABLE IF NOT EXISTS {schema}.{queueName} (
    LIKE peegeeq.queue_template INCLUDING ALL
);

-- Create queue-specific indexes
CREATE INDEX IF NOT EXISTS idx_{queueName}_status_visible 
    ON {schema}.{queueName}(status, visible_at) 
    WHERE status IN ('PENDING', 'PROCESSING');

CREATE INDEX IF NOT EXISTS idx_{queueName}_created_at 
    ON {schema}.{queueName}(created_at);

-- Create notification trigger
CREATE OR REPLACE FUNCTION {schema}.notify_{queueName}_changes()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify('peegeeq_{queueName}', 
        json_build_object(
            'action', TG_OP,
            'id', COALESCE(NEW.id, OLD.id),
            'queue_name', '{queueName}'
        )::text
    );
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_{queueName}_notify
    AFTER INSERT OR UPDATE OR DELETE ON {schema}.{queueName}
    FOR EACH ROW EXECUTE FUNCTION {schema}.notify_{queueName}_changes();