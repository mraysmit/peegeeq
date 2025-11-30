-- Event store template corrections index
CREATE INDEX idx_event_store_template_corrections ON bitemporal.event_store_template(event_id, is_correction) WHERE is_correction = TRUE;
