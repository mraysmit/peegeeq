-- Aggregate summary for {tableName}: one row per (aggregate_id, event_type),
-- maintained by trigger. Keyed per event type so eventType-filtered aggregate
-- queries return per-type counts/timestamps identical to the live GROUP BY.
-- Parameters: {tableName}, {schema}

CREATE TABLE IF NOT EXISTS {schema}."{tableName}_aggregate_summary" (
    aggregate_id    VARCHAR(255) NOT NULL,
    event_type      VARCHAR(255) NOT NULL,
    event_count     BIGINT       NOT NULL DEFAULT 0,
    first_event_at  TIMESTAMP WITH TIME ZONE,
    last_event_at   TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (aggregate_id, event_type)
);
