-- Event store template event_id index
CREATE INDEX idx_event_store_template_event_id ON bitemporal.event_store_template(event_id);
