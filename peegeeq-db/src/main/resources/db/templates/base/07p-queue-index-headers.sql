-- Queue template headers GIN index
CREATE INDEX idx_queue_template_headers_gin ON {schema}.queue_template USING GIN(headers);

