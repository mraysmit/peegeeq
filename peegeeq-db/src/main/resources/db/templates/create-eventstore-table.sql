-- Template for creating bi-temporal event store tables
-- Parameters: {tableName}, {schema}, {notificationPrefix}

CREATE TABLE IF NOT EXISTS {schema}.{tableName} (
    LIKE bitemporal.event_store_template INCLUDING ALL
);

-- Create event store specific indexes CONCURRENTLY to avoid ExclusiveLock warnings
-- Following PostgreSQL best practices: "Always create your indexes concurrently"
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_{tableName}_event_type_tx_time
    ON {schema}.{tableName}(event_type, transaction_time DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_{tableName}_valid_time_range
    ON {schema}.{tableName} USING GIST (
        tstzrange(valid_from, valid_to, '[)')
    );

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_{tableName}_correlation_causation
    ON {schema}.{tableName}(correlation_id, causation_id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_{tableName}_event_data_gin
    ON {schema}.{tableName} USING GIN (event_data);

-- Create notification trigger for event store
CREATE OR REPLACE FUNCTION {schema}.notify_{tableName}_events()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify('{notificationPrefix}{tableName}', 
        json_build_object(
            'action', TG_OP,
            'id', NEW.id,
            'event_type', NEW.event_type,
            'transaction_time', NEW.transaction_time,
            'correlation_id', NEW.correlation_id
        )::text
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_{tableName}_notify
    AFTER INSERT ON {schema}.{tableName}
    FOR EACH ROW EXECUTE FUNCTION {schema}.notify_{tableName}_events();