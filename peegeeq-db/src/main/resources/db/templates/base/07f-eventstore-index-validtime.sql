-- Event store template valid_time index
CREATE INDEX idx_event_store_template_valid_time ON bitemporal.event_store_template(valid_time);
