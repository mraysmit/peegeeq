-- Template for creating individual queue tables
-- Parameters: {queueName}, {schema}

CREATE TABLE IF NOT EXISTS {schema}.{queueName} (
    LIKE peegeeq.queue_template INCLUDING ALL
);

-- Create queue-specific indexes (non-concurrent for template processing)
-- Note: Templates are processed in transaction context, so CONCURRENTLY cannot be used
-- These indexes will be created quickly since queue tables are empty initially

-- Composite index for topic-based queries with visibility and status
CREATE INDEX IF NOT EXISTS idx_{queueName}_topic_visible
    ON {schema}.{queueName}(topic, visible_at, status);

-- Lock tracking index
CREATE INDEX IF NOT EXISTS idx_{queueName}_lock
    ON {schema}.{queueName}(lock_id) WHERE lock_id IS NOT NULL;

-- Status and creation time index
CREATE INDEX IF NOT EXISTS idx_{queueName}_status
    ON {schema}.{queueName}(status, created_at);

-- Correlation ID index for message tracking
CREATE INDEX IF NOT EXISTS idx_{queueName}_correlation_id
    ON {schema}.{queueName}(correlation_id) WHERE correlation_id IS NOT NULL;

-- Priority-based ordering index
CREATE INDEX IF NOT EXISTS idx_{queueName}_priority
    ON {schema}.{queueName}(priority, created_at);

-- Create notification trigger
CREATE OR REPLACE FUNCTION {schema}.notify_{queueName}_changes()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify('peegeeq_{queueName}',
        json_build_object(
            'action', TG_OP,
            'id', COALESCE(NEW.id, OLD.id),
            'topic', COALESCE(NEW.topic, OLD.topic)
        )::text
    );
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_{queueName}_notify
    AFTER INSERT OR UPDATE OR DELETE ON {schema}.{queueName}
    FOR EACH ROW EXECUTE FUNCTION {schema}.notify_{queueName}_changes();