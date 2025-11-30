-- Event store template event_type index
CREATE INDEX idx_event_store_template_event_type ON bitemporal.event_store_template(event_type);
