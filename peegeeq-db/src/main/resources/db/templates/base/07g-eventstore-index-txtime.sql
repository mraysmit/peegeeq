-- Event store template transaction_time index
CREATE INDEX idx_event_store_template_tx_time ON {schema}.event_store_template(transaction_time);
