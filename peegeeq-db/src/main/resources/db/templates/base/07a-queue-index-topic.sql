-- Queue template topic index
CREATE INDEX idx_queue_template_topic_visible ON {schema}.queue_template(topic, visible_at, status);
