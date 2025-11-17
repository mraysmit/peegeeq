-- =============================================================================
-- PEEGEEQ COMPLETE SCHEMA - QUICK REFERENCE
-- =============================================================================

-- Optional schema configuration (defaults to 'public')
-- Override with: psql -v schema=myschema -f QUICK_REFERENCE.sql
\set schema :schema
\if :{?schema}
    SET search_path TO :schema;
\else
    \set schema 'public'
    SET search_path TO public;
\endif

-- USAGE:
--   Default schema (public):
--     psql -U postgres -d peegeeq_db -f COMPLETE_SCHEMA_SETUP.sql
--
--   Custom schema:
--     psql -U postgres -d peegeeq_db -v schema=myschema -f COMPLETE_SCHEMA_SETUP.sql

-- POST-SETUP VERIFICATION:
SELECT version, description, applied_at FROM schema_version ORDER BY version;
\dt                                    -- List all tables
\di                                    -- List all indexes  
\df                                    -- List all functions
SELECT COUNT(*) FROM outbox;          -- Test query

-- CONFIGURE TOPICS:
INSERT INTO outbox_topics (topic, semantics, message_retention_hours)
VALUES 
    ('orders', 'QUEUE', 24),           -- Single consumer distribution
    ('events', 'PUB_SUB', 48);         -- Multi-consumer replication

-- REGISTER CONSUMER GROUPS:
INSERT INTO outbox_topic_subscriptions (topic, group_name)
VALUES ('orders', 'order-processor');

SELECT register_consumer_group_for_existing_messages('order-processor');

-- TEST MESSAGE FLOW:
INSERT INTO outbox (topic, payload) 
VALUES ('orders', '{"order_id": 123, "amount": 99.99}');

SELECT id, topic, status, required_consumer_groups, completed_consumer_groups 
FROM outbox WHERE topic = 'orders';

SELECT message_id, group_name, status 
FROM outbox_consumer_groups 
WHERE group_name = 'order-processor';

-- MAINTENANCE FUNCTIONS (Schedule these):
SELECT cleanup_completed_message_processing();    -- Run hourly
SELECT cleanup_completed_outbox_messages();       -- Run daily
SELECT mark_dead_consumer_groups();               -- Run every 5 min
SELECT update_consumer_group_index();             -- Run every minute
SELECT cleanup_old_metrics(30);                   -- Run weekly (30 day retention)

-- MONITORING QUERIES:
-- Consumer group status
SELECT 
    topic, 
    group_name, 
    pending_count, 
    processing_count, 
    completed_count,
    failed_count,
    last_processed_at
FROM consumer_group_index
ORDER BY topic, group_name;

-- Outbox backlog
SELECT 
    topic,
    status,
    COUNT(*) as message_count,
    MIN(created_at) as oldest_message,
    MAX(created_at) as newest_message
FROM outbox
GROUP BY topic, status
ORDER BY topic, status;

-- Dead letter queue
SELECT 
    topic,
    COUNT(*) as failed_count,
    MIN(failed_at) as oldest_failure,
    MAX(failed_at) as newest_failure
FROM dead_letter_queue
GROUP BY topic
ORDER BY failed_count DESC;

-- Message processing performance
SELECT 
    topic,
    COUNT(*) as total_messages,
    AVG(EXTRACT(EPOCH FROM (completed_at - started_at))) as avg_processing_seconds,
    MAX(EXTRACT(EPOCH FROM (completed_at - started_at))) as max_processing_seconds
FROM message_processing
WHERE status = 'COMPLETED'
  AND completed_at > NOW() - INTERVAL '1 hour'
GROUP BY topic
ORDER BY total_messages DESC;

-- Bi-temporal events
SELECT 
    event_type,
    COUNT(*) as event_count,
    COUNT(DISTINCT aggregate_id) as unique_aggregates,
    MIN(valid_time) as oldest_event,
    MAX(valid_time) as newest_event
FROM bitemporal_event_log
GROUP BY event_type
ORDER BY event_count DESC;

-- PRODUCTION: CREATE CONCURRENTLY INDEXES (Run separately after main script)
-- DO NOT run these in transaction - run individually:
/*
DROP INDEX IF EXISTS idx_bitemporal_valid_time;
CREATE INDEX CONCURRENTLY idx_bitemporal_valid_time ON bitemporal_event_log(valid_time);

DROP INDEX IF EXISTS idx_bitemporal_transaction_time;
CREATE INDEX CONCURRENTLY idx_bitemporal_transaction_time ON bitemporal_event_log(transaction_time);

DROP INDEX IF EXISTS idx_bitemporal_valid_transaction;
CREATE INDEX CONCURRENTLY idx_bitemporal_valid_transaction ON bitemporal_event_log(valid_time, transaction_time);

DROP INDEX IF EXISTS idx_bitemporal_event_id;
CREATE INDEX CONCURRENTLY idx_bitemporal_event_id ON bitemporal_event_log(event_id);

DROP INDEX IF EXISTS idx_bitemporal_event_type;
CREATE INDEX CONCURRENTLY idx_bitemporal_event_type ON bitemporal_event_log(event_type);

DROP INDEX IF EXISTS idx_bitemporal_event_type_valid_time;
CREATE INDEX CONCURRENTLY idx_bitemporal_event_type_valid_time ON bitemporal_event_log(event_type, valid_time);

DROP INDEX IF EXISTS idx_bitemporal_aggregate_id;
CREATE INDEX CONCURRENTLY idx_bitemporal_aggregate_id ON bitemporal_event_log(aggregate_id) WHERE aggregate_id IS NOT NULL;

DROP INDEX IF EXISTS idx_bitemporal_correlation_id;
CREATE INDEX CONCURRENTLY idx_bitemporal_correlation_id ON bitemporal_event_log(correlation_id) WHERE correlation_id IS NOT NULL;

DROP INDEX IF EXISTS idx_bitemporal_aggregate_valid_time;
CREATE INDEX CONCURRENTLY idx_bitemporal_aggregate_valid_time ON bitemporal_event_log(aggregate_id, valid_time) WHERE aggregate_id IS NOT NULL;

DROP INDEX IF EXISTS idx_bitemporal_version;
CREATE INDEX CONCURRENTLY idx_bitemporal_version ON bitemporal_event_log(event_id, version);

DROP INDEX IF EXISTS idx_bitemporal_corrections;
CREATE INDEX CONCURRENTLY idx_bitemporal_corrections ON bitemporal_event_log(previous_version_id) WHERE previous_version_id IS NOT NULL;

DROP INDEX IF EXISTS idx_bitemporal_latest_events;
CREATE INDEX CONCURRENTLY idx_bitemporal_latest_events ON bitemporal_event_log(event_type, transaction_time DESC) WHERE is_correction = FALSE;

DROP INDEX IF EXISTS idx_bitemporal_payload_gin;
CREATE INDEX CONCURRENTLY idx_bitemporal_payload_gin ON bitemporal_event_log USING GIN(payload);

DROP INDEX IF EXISTS idx_bitemporal_headers_gin;
CREATE INDEX CONCURRENTLY idx_bitemporal_headers_gin ON bitemporal_event_log USING GIN(headers);
*/

-- DOCUMENTATION:
-- COMPLETE_SCHEMA_SETUP_GUIDE.md - Full usage guide
-- README_TESTING.md - Testing infrastructure
-- SCHEMA_DRIFT_ANALYSIS_REPORT.md - Schema analysis
-- PEEGEEQ_MIGRATIONS_AND_DEPLOYMENTS.md - Flyway migrations
