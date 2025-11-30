-- Event store template correlation_id index
CREATE INDEX idx_event_store_template_correlation ON bitemporal.event_store_template(correlation_id) WHERE correlation_id IS NOT NULL;
