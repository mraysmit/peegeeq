-- Queue template correlation ID index
CREATE INDEX idx_queue_template_correlation_id ON {schema}.queue_template(correlation_id) WHERE correlation_id IS NOT NULL;
