-- Outbox consumer groups group status index
CREATE INDEX IF NOT EXISTS idx_outbox_consumer_groups_group_status ON peegeeq.outbox_consumer_groups(group_name, status, message_id);
