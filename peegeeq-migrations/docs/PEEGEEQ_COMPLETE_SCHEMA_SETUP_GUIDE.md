# Complete Schema Setup - Usage Guide

## Overview

`COMPLETE_SCHEMA_SETUP.sql` is a standalone SQL script that creates the entire PeeGeeQ database schema in a single execution. This script is **independent of Flyway** and can be used for:

- **Manual database initialization** - Quick setup without Flyway
- **Development environments** - Rapid local database creation
- **Database recovery** - Rebuild schema from scratch
- **Documentation** - Complete schema reference in one file
- **CI/CD pipelines** - Automated test database creation
- **Troubleshooting** - Compare against Flyway migrations

## Quick Start

### Basic Usage

```bash
# Connect to PostgreSQL and run the script (uses default 'public' schema)
psql -U postgres -d peegeeq_db -f COMPLETE_SCHEMA_SETUP.sql
```

### Custom Schema

```bash
# Create schema and run setup in custom schema
psql -U postgres -d peegeeq_db -c "CREATE SCHEMA myschema;"
psql -U postgres -d peegeeq_db -v schema=myschema -f COMPLETE_SCHEMA_SETUP.sql
```

### Create New Database and Run Setup

```bash
# Create database
psql -U postgres -c "CREATE DATABASE peegeeq_db;"

# Run complete setup (default public schema)
psql -U postgres -d peegeeq_db -f COMPLETE_SCHEMA_SETUP.sql

# Or use custom schema
psql -U postgres -d peegeeq_db -c "CREATE SCHEMA app_schema;"
psql -U postgres -d peegeeq_db -v schema=app_schema -f COMPLETE_SCHEMA_SETUP.sql
```

### Docker PostgreSQL

```bash
# Using Docker container (default schema)
docker exec -i postgres_container psql -U postgres -d peegeeq_db < COMPLETE_SCHEMA_SETUP.sql

# Using Docker container (custom schema)
docker exec postgres_container psql -U postgres -d peegeeq_db -c "CREATE SCHEMA myschema;"
docker exec -i postgres_container psql -U postgres -d peegeeq_db -v schema=myschema < COMPLETE_SCHEMA_SETUP.sql
```

## Script Features

### ✅ Optional Schema Support

The script supports both default (`public`) and custom schemas:

```bash
# Use default 'public' schema
psql -d peegeeq_db -f COMPLETE_SCHEMA_SETUP.sql

# Use custom schema (must exist first)
psql -d peegeeq_db -c "CREATE SCHEMA myschema;"
psql -d peegeeq_db -v schema=myschema -f COMPLETE_SCHEMA_SETUP.sql
```

The script automatically sets `search_path` to the specified schema, so all objects (tables, functions, triggers) are created in that schema. This matches how the PeeGeeQ application handles schema configuration via `SET search_path`.

### ✅ Idempotent

The script uses `CREATE TABLE IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`, and `CREATE OR REPLACE` to safely run multiple times without errors.

```bash
# Safe to run multiple times
psql -U postgres -d peegeeq_db -f COMPLETE_SCHEMA_SETUP.sql
psql -U postgres -d peegeeq_db -f COMPLETE_SCHEMA_SETUP.sql  # No errors!
```

### ✅ Progress Feedback

The script provides real-time feedback using `\echo` commands:

```
===============================================================================
PeeGeeQ Complete Database Schema Setup v2.0.0
===============================================================================

Creating schema version tracking table...
Creating outbox pattern table...
Creating consumer groups tracking table...
...
```

### ✅ Transaction Safety

The script runs in a single transaction with error handling:

```sql
\set ON_ERROR_STOP on
```

If any command fails, the entire transaction is rolled back.

### ✅ Complete Schema

Includes everything from Flyway migrations V001 and V010:

- **14 tables** - Core messaging, fanout, bi-temporal, metrics
- **50+ indexes** - Performance optimized
- **4 views** - Bi-temporal event views
- **15+ functions** - Message processing, cleanup, fanout
- **6 triggers** - Real-time notifications and automation

**Schema Parity**: This standalone script creates the **exact same schema** as Flyway migrations (V001 + V010)

## What's Included

### Core Message Tables (V001)
- `outbox` - Outbox pattern for reliable delivery
- `outbox_consumer_groups` - Consumer group tracking
- `queue_messages` - Native queue messages
- `message_processing` - INSERT-only processing (lock-free)
- `dead_letter_queue` - Failed message storage

### Consumer Group Fanout (V010)
- `outbox_topics` - Topic configuration (QUEUE/PUB_SUB)
- `outbox_topic_subscriptions` - Subscription management
- `processed_ledger` - Audit trail
- `partition_drop_audit` - Partition cleanup tracking
- `consumer_group_index` - Performance statistics

### Bi-temporal Event Log
- `bitemporal_event_log` - Event sourcing with valid/transaction time
- Views: `bitemporal_current_state`, `bitemporal_latest_events`, `bitemporal_event_stats`, `bitemporal_event_type_stats`

### Metrics & Monitoring
- `queue_metrics` - Queue performance metrics
- `connection_pool_metrics` - Connection statistics

### Indexes

**Performance indexes on all tables** including:
- Message lookup and filtering
- Topic and consumer group queries
- Bi-temporal time-based queries
- JSONB payload searches (GIN indexes)

**Note**: Bi-temporal indexes are created **without CONCURRENTLY** for transaction safety. For production, see [Production Deployment](#production-deployment) section.

### Functions

**Message Processing**:
- `notify_message_inserted()` - LISTEN/NOTIFY integration
- `update_message_processing_updated_at()` - Timestamp updates
- `cleanup_completed_message_processing()` - Cleanup old records

**Consumer Group Management**:
- `register_consumer_group_for_existing_messages()` - Backfill support
- `create_consumer_group_entries_for_new_message()` - Auto-fanout
- `cleanup_completed_outbox_messages()` - Retention policy
- `set_required_consumer_groups()` - Dynamic group tracking
- `mark_dead_consumer_groups()` - Heartbeat monitoring
- `update_consumer_group_index()` - Statistics updates

**Bi-temporal**:
- `notify_bitemporal_event()` - Event notifications
- `get_events_as_of_time()` - Point-in-time queries

**Maintenance**:
- `cleanup_old_metrics()` - Metrics retention

### Triggers

- `trigger_outbox_notify` - Notify on outbox inserts
- `trigger_queue_messages_notify` - Notify on queue inserts
- `trigger_message_processing_updated_at` - Auto-update timestamps
- `trigger_create_consumer_group_entries` - Auto-create fanout entries
- `trigger_set_required_consumer_groups` - Set fanout requirements
- `trigger_notify_bitemporal_event` - Bi-temporal event notifications

## Verification

### Check Schema Version

```sql
SELECT * FROM schema_version ORDER BY applied_at;
```

Expected output:
```
 version |                  description                   |          applied_at           
---------+-----------------------------------------------+-------------------------------
 V001    | Base Schema - Core tables, indexes, ...       | 2025-11-17 18:45:00+00
 V010    | Consumer Group Fan-Out Support ...            | 2025-11-17 18:45:00+00
```

### List All Tables

```sql
\dt
```

Should show 20+ tables.

### Check Indexes

```sql
SELECT schemaname, tablename, indexname 
FROM pg_indexes 
WHERE schemaname = 'public' 
ORDER BY tablename, indexname;
```

Should show 50+ indexes.

### Verify Functions

```sql
\df
```

Should list 15+ functions.

### Test Basic Operations

```sql
-- Insert test message
INSERT INTO outbox (topic, payload) 
VALUES ('test-topic', '{"message": "Hello, PeeGeeQ!"}');

-- Verify insert
SELECT id, topic, status, created_at FROM outbox WHERE topic = 'test-topic';

-- Cleanup
DELETE FROM outbox WHERE topic = 'test-topic';
```

## Production Deployment

### Important: CONCURRENTLY Indexes

The script creates bi-temporal indexes **without CONCURRENTLY** for transaction safety. For **production deployments**, run these indexes separately with CONCURRENTLY:

```sql
-- After running COMPLETE_SCHEMA_SETUP.sql, run these separately:

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_bitemporal_valid_time 
    ON bitemporal_event_log(valid_time);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_bitemporal_transaction_time 
    ON bitemporal_event_log(transaction_time);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_bitemporal_valid_transaction 
    ON bitemporal_event_log(valid_time, transaction_time);

-- ... (see COMPLETE_SCHEMA_SETUP.sql Section 6 for full list)
```

**Why?**
- `CONCURRENTLY` allows zero-downtime index creation (no table locks)
- Cannot be run inside transaction blocks
- Script uses non-concurrent for transaction safety

### Production Checklist

1. ✅ Run `COMPLETE_SCHEMA_SETUP.sql` in test environment first
2. ✅ Verify schema with test queries
3. ✅ Review indexes and functions
4. ✅ Plan maintenance window (optional, for CONCURRENTLY indexes)
5. ✅ Run script in production
6. ✅ Create CONCURRENTLY indexes separately if needed
7. ✅ Configure topics: `INSERT INTO outbox_topics`
8. ✅ Register consumer groups
9. ✅ Set up monitoring queries
10. ✅ Schedule cleanup jobs (see [Maintenance](#maintenance))

## Differences from Flyway Migrations

| Aspect | Flyway Migrations (V001+V010) | COMPLETE_SCHEMA_SETUP.sql |
|--------|------------------|---------------------------|
| **Execution** | Incremental (V001, V010, ...) | Single script |
| **Schema** | 14 tables, 4 views | 14 tables, 4 views (identical) |
| **Version Tracking** | flyway_schema_history table | schema_version table |
| **Indexes** | CONCURRENTLY (production) | Non-concurrent (for transaction safety) |
| **Use Case** | Production deployments | Development, testing, recovery |
| **Migration History** | Full audit trail | Final state only |
| **Rollback** | Version-by-version | All-or-nothing |

**✅ Schema Parity**: Both methods create identical database schemas. The only differences are in execution approach and version tracking.

### When to Use Each

**Use Flyway** when:
- ✅ Deploying to production
- ✅ Need migration history and audit trail
- ✅ Incremental schema evolution required
- ✅ Multiple environments with version tracking

**Use COMPLETE_SCHEMA_SETUP.sql** when:
- ✅ Setting up development environment
- ✅ Creating test databases (CI/CD)
- ✅ Database recovery/rebuild
- ✅ Quick local setup
- ✅ Schema documentation
- ✅ Troubleshooting schema issues

## Common Use Cases

### 1. Local Development Setup

```bash
# Create and setup database in one command
createdb peegeeq_dev
psql -d peegeeq_dev -f COMPLETE_SCHEMA_SETUP.sql

# Start developing!
```

### 2. CI/CD Test Database

```yaml
# .github/workflows/test.yml
- name: Setup PostgreSQL
  run: |
    docker run -d -p 5432:5432 -e POSTGRES_PASSWORD=test postgres:15
    sleep 5
    psql -h localhost -U postgres -c "CREATE DATABASE peegeeq_test;"
    psql -h localhost -U postgres -d peegeeq_test -f peegeeq-migrations/COMPLETE_SCHEMA_SETUP.sql

- name: Run Tests
  run: mvn test
```

### 3. Database Reset

```bash
# Drop and recreate
dropdb peegeeq_dev && createdb peegeeq_dev
psql -d peegeeq_dev -f COMPLETE_SCHEMA_SETUP.sql
```

### 4. Schema Documentation

```bash
# Generate schema documentation
psql -d peegeeq_db -f COMPLETE_SCHEMA_SETUP.sql > schema_setup.log

# Or extract just the comments
grep "COMMENT ON" COMPLETE_SCHEMA_SETUP.sql > schema_comments.sql
```

## Post-Setup Configuration

### 1. Configure Topics

```sql
-- Define QUEUE topic (distribute messages to one consumer)
INSERT INTO outbox_topics (topic, semantics, message_retention_hours)
VALUES ('orders', 'QUEUE', 24);

-- Define PUB_SUB topic (replicate to all consumers)
INSERT INTO outbox_topics (topic, semantics, message_retention_hours)
VALUES ('notifications', 'PUB_SUB', 48);
```

### 2. Register Consumer Groups

```sql
-- Register a consumer group
INSERT INTO outbox_topic_subscriptions (topic, group_name)
VALUES ('orders', 'order-processor-group');

-- Backfill existing messages
SELECT register_consumer_group_for_existing_messages('order-processor-group');
```

### 3. Test Message Flow

```sql
-- Insert test message
INSERT INTO outbox (topic, payload)
VALUES ('orders', '{"order_id": 123, "amount": 99.99}');

-- Verify consumer group entry created
SELECT * FROM outbox_consumer_groups WHERE group_name = 'order-processor-group';
```

## Maintenance

### Cleanup Jobs

Schedule these functions to run periodically:

```sql
-- Clean up old completed processing records (hourly)
SELECT cleanup_completed_message_processing();

-- Clean up completed outbox messages (daily)
SELECT cleanup_completed_outbox_messages();

-- Mark dead consumer groups (every 5 minutes)
SELECT mark_dead_consumer_groups();

-- Update consumer group statistics (every minute)
SELECT update_consumer_group_index();

-- Clean up old metrics (weekly)
SELECT cleanup_old_metrics(30);  -- 30 day retention
```

### Example: pg_cron Setup

```sql
-- Install pg_cron extension
CREATE EXTENSION pg_cron;

-- Schedule cleanup jobs
SELECT cron.schedule('cleanup-processing', '0 * * * *', 
    'SELECT cleanup_completed_message_processing()');

SELECT cron.schedule('cleanup-outbox', '0 0 * * *', 
    'SELECT cleanup_completed_outbox_messages()');

SELECT cron.schedule('mark-dead-groups', '*/5 * * * *', 
    'SELECT mark_dead_consumer_groups()');

SELECT cron.schedule('update-stats', '* * * * *', 
    'SELECT update_consumer_group_index()');
```

## Troubleshooting

### Script Fails Mid-Execution

**Symptom**: Script exits with error, database partially created

**Solution**:
```bash
# Transaction rollback is automatic due to ON_ERROR_STOP
# Simply fix the issue and rerun - script is idempotent
psql -d peegeeq_db -f COMPLETE_SCHEMA_SETUP.sql
```

### Table Already Exists

**Symptom**: `ERROR: relation "outbox" already exists`

**Solution**: This shouldn't happen due to `IF NOT EXISTS`. If it does:
```sql
-- Check existing schema
\dt

-- Drop and recreate if needed
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;

-- Rerun setup
\i COMPLETE_SCHEMA_SETUP.sql
```

### Missing Schema Version

**Symptom**: `schema_version` table empty after running script

**Solution**:
```sql
-- Check if versions were inserted
SELECT * FROM schema_version;

-- If empty, manually insert
INSERT INTO schema_version (version, description)
VALUES 
    ('V001', 'Base Schema'),
    ('V010', 'Consumer Group Fanout');
```

### CONCURRENTLY Errors in Production

**Symptom**: Indexes failed to create with CONCURRENTLY

**Solution**:
```bash
# Run CONCURRENTLY indexes separately after main script
psql -d peegeeq_db << 'EOF'
-- Drop non-concurrent indexes first
DROP INDEX IF EXISTS idx_bitemporal_valid_time;
DROP INDEX IF EXISTS idx_bitemporal_transaction_time;
-- ... (drop all bi-temporal indexes)

-- Recreate with CONCURRENTLY
CREATE INDEX CONCURRENTLY idx_bitemporal_valid_time 
    ON bitemporal_event_log(valid_time);
CREATE INDEX CONCURRENTLY idx_bitemporal_transaction_time 
    ON bitemporal_event_log(transaction_time);
-- ... (create all with CONCURRENTLY)
EOF
```

## Related Documentation

- **README_TESTING.md** - Migration testing guide
- **SCHEMA_DRIFT_ANALYSIS_REPORT.md** - Schema drift analysis
- **MIGRATIONS_AND_DEPLOYMENTS.md** - Flyway migration strategy
- **docs/CONSUMER_GROUP_GETTING_STARTED.md** - Consumer group setup
- **docs/PEEGEEQ_COMPLETE_GUIDE.md** - Complete PeeGeeQ documentation

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 2.0.0 | 2025-11-17 | Initial complete schema setup combining V001 and V010 |

## Support

For issues or questions:

1. Check this guide first
2. Review related documentation
3. Verify against Flyway migrations for schema differences
4. Check test infrastructure (README_TESTING.md)

---

**Last Updated**: 2025-11-17  
**Schema Version**: V001 + V010 (v2.0.0)  
**Compatible With**: PostgreSQL 12+, PeeGeeQ v1.2.0+
