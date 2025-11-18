-- Template for creating bi-temporal event store tables
-- Parameters: {tableName}, {schema}, {notificationPrefix}

CREATE TABLE IF NOT EXISTS {schema}.{tableName} (
    LIKE bitemporal.event_store_template INCLUDING ALL
);

-- Create event store specific indexes (non-concurrent for template processing)
-- Note: Templates are processed in transaction context, so CONCURRENTLY cannot be used
-- These indexes will be created quickly since event store tables are empty initially

-- Primary temporal indexes
CREATE INDEX IF NOT EXISTS idx_{tableName}_valid_time
    ON {schema}.{tableName}(valid_time);

CREATE INDEX IF NOT EXISTS idx_{tableName}_tx_time
    ON {schema}.{tableName}(transaction_time);

-- Event identification and type indexes
CREATE INDEX IF NOT EXISTS idx_{tableName}_event_id
    ON {schema}.{tableName}(event_id);

CREATE INDEX IF NOT EXISTS idx_{tableName}_event_type
    ON {schema}.{tableName}(event_type);

-- Aggregate and correlation indexes
CREATE INDEX IF NOT EXISTS idx_{tableName}_aggregate
    ON {schema}.{tableName}(aggregate_id) WHERE aggregate_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_{tableName}_correlation
    ON {schema}.{tableName}(correlation_id) WHERE correlation_id IS NOT NULL;

-- Versioning indexes
CREATE INDEX IF NOT EXISTS idx_{tableName}_version
    ON {schema}.{tableName}(event_id, version);

CREATE INDEX IF NOT EXISTS idx_{tableName}_corrections
    ON {schema}.{tableName}(event_id, is_correction) WHERE is_correction = TRUE;

-- GIN indexes for JSONB queries
CREATE INDEX IF NOT EXISTS idx_{tableName}_payload_gin
    ON {schema}.{tableName} USING GIN(payload);

CREATE INDEX IF NOT EXISTS idx_{tableName}_headers_gin
    ON {schema}.{tableName} USING GIN(headers);

-- Create notification trigger for event store
CREATE OR REPLACE FUNCTION {schema}.notify_{tableName}_events()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify('{notificationPrefix}{tableName}',
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

CREATE TRIGGER trigger_{tableName}_notify
    AFTER INSERT ON {schema}.{tableName}
    FOR EACH ROW EXECUTE FUNCTION {schema}.notify_{tableName}_events();