-- Event store template payload GIN index
CREATE INDEX idx_event_store_template_payload_gin ON {schema}.event_store_template USING GIN(payload);
