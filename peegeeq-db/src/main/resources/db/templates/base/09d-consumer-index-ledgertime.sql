-- Processed ledger time index
CREATE INDEX IF NOT EXISTS idx_processed_ledger_time ON peegeeq.processed_ledger(topic, processed_at);
