-- Event store template payload GIN index
CREATE INDEX idx_event_store_template_payload_gin ON bitemporal.event_store_template USING GIN(payload);
