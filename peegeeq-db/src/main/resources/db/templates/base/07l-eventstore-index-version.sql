-- Event store template version index
CREATE INDEX idx_event_store_template_version ON bitemporal.event_store_template(event_id, version);
