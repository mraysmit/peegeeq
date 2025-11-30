-- Event store template headers GIN index
CREATE INDEX idx_event_store_template_headers_gin ON bitemporal.event_store_template USING GIN(headers);
