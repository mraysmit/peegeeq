-- Queue template priority index
CREATE INDEX idx_queue_template_priority ON {schema}.queue_template(priority, created_at);
