-- Queue template lock index
CREATE INDEX idx_queue_template_lock ON peegeeq.queue_template(lock_id) WHERE lock_id IS NOT NULL;
