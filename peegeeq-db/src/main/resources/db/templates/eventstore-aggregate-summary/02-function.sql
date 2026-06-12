-- Maintains the aggregate summary on every event insert. Corrections are
-- counted (they are inserts), matching the live GROUP BY which counts all rows.
-- LEAST on first_event_at: bi-temporal valid times can legitimately arrive out
-- of order, so "first" can move backwards. NULL aggregate_id rows are skipped,
-- matching the live query's WHERE aggregate_id IS NOT NULL.
CREATE OR REPLACE FUNCTION {schema}."maintain_{tableName}_aggregate_summary"()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.aggregate_id IS NULL THEN
        RETURN NEW;
    END IF;

    INSERT INTO {schema}."{tableName}_aggregate_summary"
        (aggregate_id, event_type, event_count, first_event_at, last_event_at)
    VALUES (NEW.aggregate_id, NEW.event_type, 1, NEW.valid_time, NEW.transaction_time)
    ON CONFLICT (aggregate_id, event_type) DO UPDATE SET
        event_count    = "{tableName}_aggregate_summary".event_count + 1,
        first_event_at = LEAST("{tableName}_aggregate_summary".first_event_at, EXCLUDED.first_event_at),
        last_event_at  = GREATEST("{tableName}_aggregate_summary".last_event_at, EXCLUDED.last_event_at);

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
