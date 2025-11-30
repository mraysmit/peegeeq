-- Consumer group index topic index
CREATE INDEX IF NOT EXISTS idx_consumer_group_index_topic ON peegeeq.consumer_group_index(topic, group_name);
