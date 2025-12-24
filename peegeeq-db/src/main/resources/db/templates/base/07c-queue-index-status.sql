-- Queue template status index
CREATE INDEX idx_queue_template_status ON {schema}.queue_template(status, created_at);
