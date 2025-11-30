-- Queue template priority index
CREATE INDEX idx_queue_template_priority ON peegeeq.queue_template(priority, created_at);
