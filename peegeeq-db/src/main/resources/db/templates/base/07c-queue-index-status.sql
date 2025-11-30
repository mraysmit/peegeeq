-- Queue template status index
CREATE INDEX idx_queue_template_status ON peegeeq.queue_template(status, created_at);
