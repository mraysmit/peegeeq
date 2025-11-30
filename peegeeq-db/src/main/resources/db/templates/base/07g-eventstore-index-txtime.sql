-- Event store template transaction_time index
CREATE INDEX idx_event_store_template_tx_time ON bitemporal.event_store_template(transaction_time);
