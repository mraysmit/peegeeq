-- Event store template aggregate_id index
CREATE INDEX idx_event_store_template_aggregate ON bitemporal.event_store_template(aggregate_id) WHERE aggregate_id IS NOT NULL;
