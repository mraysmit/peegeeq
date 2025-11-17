# PeeGeeQ Database Migrations

Complete database migration and setup solution for PeeGeeQ message queue system.

> **⚠️ Important**: This module is for **deployment/setup ONLY**. Never run migrations from application code.

## 📚 Quick Navigation

- [Quick Start](#quick-start) - Get running in 3 minutes
- [Setup Methods](#setup-methods) - Choose your approach
- [Testing](#testing) - Run migration tests
- [Production Deployment](#production-deployment) - Deploy to production
- [Troubleshooting](#troubleshooting) - Common issues
- [Schema Reference](#schema-reference) - What's included

---

## Quick Start

### 1. Development Setup (Fastest)

```bash
# Create database
createdb peegeeq_db

# Run complete setup (default 'public' schema)
psql -d peegeeq_db -f PEEGEEQ_COMPLETE_SCHEMA_SETUP.sql

# Or use custom schema
psql -d peegeeq_db -c "CREATE SCHEMA myschema;"
psql -d peegeeq_db -v schema=myschema -f PEEGEEQ_COMPLETE_SCHEMA_SETUP.sql

# Verify
psql -d peegeeq_db -c "SELECT * FROM schema_version;"
```

### 2. Production Setup

See [Production Deployment](#production-deployment) section below.

### 3. Testing

```bash
# Run all migration tests (17 tests)
mvn test
```

---

## Setup Methods

Choose the method that best fits your use case:

### Method 1: Standalone SQL Script 🚀

**Best For**: Development, testing, quick setup, database recovery

**Files**: 
- `PEEGEEQ_COMPLETE_SCHEMA_SETUP.sql` - Complete schema
- `PEEGEEQ_SCHEMA_QUICK_REFERENCE.sql` - Quick reference commands

**Usage**:
```bash
# Default 'public' schema
psql -d peegeeq_db -f PEEGEEQ_COMPLETE_SCHEMA_SETUP.sql

# Custom schema
psql -d peegeeq_db -v schema=myschema -f PEEGEEQ_COMPLETE_SCHEMA_SETUP.sql
```

**Features**:
- ✅ Single command setup
- ✅ No Flyway dependency
- ✅ Optional schema support
- ✅ Idempotent (safe to rerun)
- ✅ Perfect for CI/CD
- ✅ Transaction-safe with progress feedback

**Use Cases**:
- Local development environment
- CI/CD test databases
- Database recovery/rebuild
- Schema documentation reference
- Quick prototyping

### Method 2: Flyway Migrations 🏗️

**Best For**: Production deployments, version tracking, team collaboration

**Files**: 
- `src/main/resources/db/migration/V001__Create_Base_Tables.sql`
- `src/main/resources/db/migration/V010__Create_Consumer_Group_Fanout_Tables.sql`

**Usage**:
```bash
flyway migrate -url=jdbc:postgresql://localhost:5432/peegeeq_db
```

**Features**:
- ✅ Full migration history
- ✅ Version tracking and audit trail
- ✅ Rollback capabilities
- ✅ Production-grade deployment
- ✅ Uses `CREATE INDEX CONCURRENTLY` (zero-downtime)

**Use Cases**:
- Production deployments
- Staging environments
- Multi-environment management
- Team collaboration with version control

### Method 3: Test Infrastructure 🧪

**Best For**: Automated testing, CI/CD validation, schema verification

**Files**:
- `src/test/java/dev/mars/peegeeq/migrations/MigrationIntegrationTest.java`
- `src/test/resources/db/migration/` (test migrations)

**Usage**:
```bash
# Run all 17 integration tests
mvn test

# Run specific test
mvn test -Dtest=MigrationIntegrationTest#testOutboxTableExists
```

**Features**:
- ✅ 17 comprehensive tests
- ✅ Schema validation
- ✅ Zero-drift confirmation
- ✅ Automated in CI/CD
- ✅ Uses standard indexes (modified from production)

---

## Testing

### Test Infrastructure Status

✅ **All 17 migration integration tests passing**  
✅ **Zero schema drift detected**  
✅ **Production and test migrations validated**

### Run Tests

```bash
# Run all migration tests
mvn test

# Run specific test class
mvn test -Dtest=MigrationIntegrationTest

# Run with verbose output
mvn test -X
```

### Test Coverage

The test suite validates:

**V001 Migration** (Base Schema):
- ✅ Core tables: outbox, queue_messages, message_processing, dead_letter_queue
- ✅ Consumer group tables: outbox_consumer_groups
- ✅ Bi-temporal tables: bitemporal_event_log, bitemporal_event_type_stats
- ✅ Metrics tables: queue_metrics, connection_pool_metrics, health_check, partition_metadata
- ✅ All indexes (50+ indexes)
- ✅ All functions (15+ functions)
- ✅ All triggers (6 triggers)
- ✅ All views (bi-temporal views)

**V010 Migration** (Consumer Group Fanout):
- ✅ Consumer group tables: outbox_topics, outbox_topic_subscriptions
- ✅ Ledger tables: processed_ledger, partition_drop_audit, consumer_group_index
- ✅ Outbox enhancements: required_consumer_groups, completed_consumer_groups, completed_groups_bitmap
- ✅ Fanout functions and triggers

### Schema Contract Tests 🔒

**Purpose**: Ensures migrations stay in sync with application modules (peegeeq-native, peegeeq-outbox, peegeeq-db).

**File**: `SchemaContractTest.java`

**What it validates**:
- ✅ All columns expected by application code exist
- ✅ Column types match application expectations
- ✅ Critical indexes exist for performance queries
- ✅ Required functions and triggers are present
- ✅ CHECK constraints allow expected values

**Run contract tests**:
```bash
mvn test -Dtest=SchemaContractTest
```

**When it fails**: Schema drift detected - migrations don't match what the application expects.

**See**: `SCHEMA_SYNC_STRATEGY.md` for complete synchronization strategy.

### Production vs Test Migrations

**Important Difference**: Production migrations use `CREATE INDEX CONCURRENTLY`, test migrations use standard `CREATE INDEX`.

**Why?**
- Flyway doesn't support mixed transactional/non-transactional statements
- CONCURRENTLY indexes can't run in transactions
- Test migrations prioritize transaction safety
- Production migrations prioritize zero-downtime

**Production** (`src/main/resources/`):
```sql
CREATE INDEX CONCURRENTLY idx_bitemporal_valid_time
    ON bitemporal_event_log(valid_time);
```

**Test** (`src/test/resources/`):
```sql
CREATE INDEX idx_bitemporal_valid_time
    ON bitemporal_event_log(valid_time);
```

---

## Production Deployment

### Prerequisites

1. **PostgreSQL 12+** installed and running
2. **Flyway CLI** or Maven Flyway plugin configured
3. **Database created** with appropriate user permissions
4. **Backup** of existing database (if upgrading)

### Deployment Steps

#### Step 1: Install Flyway

```bash
# macOS
brew install flyway

# Linux
wget -qO- https://repo1.maven.org/maven2/org/flywaydb/flyway-commandline/10.10.0/flyway-commandline-10.10.0-linux-x64.tar.gz | tar xvz

# Windows
# Download from https://flywaydb.org/download
```

#### Step 2: Configure Flyway

Create `flyway.conf`:

```properties
flyway.url=jdbc:postgresql://localhost:5432/peegeeq_db
flyway.user=postgres
flyway.password=your_password
flyway.locations=filesystem:src/main/resources/db/migration
flyway.baselineOnMigrate=true
```

#### Step 3: Run Migrations

```bash
# Check migration status
flyway info

# Run migrations
flyway migrate

# Verify
flyway info
psql -d peegeeq_db -c "SELECT * FROM schema_version;"
```

### Production Deployment Checklist

**Pre-Deployment**:
- [ ] Database backed up
- [ ] Flyway configured correctly
- [ ] Migration scripts reviewed
- [ ] Tested in staging environment
- [ ] Maintenance window scheduled (if needed)
- [ ] Rollback plan prepared

**During Deployment**:
- [ ] Run `flyway info` to check status
- [ ] Run `flyway migrate`
- [ ] Verify migration success
- [ ] Check application connectivity
- [ ] Monitor database performance

**Post-Deployment**:
- [ ] Verify schema version: `SELECT * FROM schema_version;`
- [ ] Run application smoke tests
- [ ] Monitor error logs
- [ ] Check index creation progress (CONCURRENTLY indexes)
- [ ] Verify database metrics

### Production Index Strategy

Production migrations use `CREATE INDEX CONCURRENTLY` for zero-downtime:

**Advantages**:
- No table locking during index creation
- Allows concurrent writes
- Critical for production systems

**Considerations**:
- Takes longer than standard index creation
- Requires two table scans
- Cannot run in transaction
- Monitor `pg_stat_progress_create_index` view

**Monitor Progress**:
```sql
SELECT * FROM pg_stat_progress_create_index;
```

### Using Standalone Script in Production

If you prefer the standalone SQL script instead of Flyway:

```bash
# 1. Create indexes WITHOUT CONCURRENTLY first
psql -d peegeeq_prod -f PEEGEEQ_COMPLETE_SCHEMA_SETUP.sql

# 2. Add CONCURRENTLY indexes separately (see PEEGEEQ_SCHEMA_QUICK_REFERENCE.sql)
psql -d peegeeq_prod -c "CREATE INDEX CONCURRENTLY idx_bitemporal_valid_time ON bitemporal_event_log(valid_time);"
# ... repeat for all 14 indexes
```

**Note**: The standalone script creates standard indexes for transaction safety. For production zero-downtime, create CONCURRENTLY indexes separately after the main setup.

---

## Troubleshooting

### Common Issues

#### 1. Migration Fails: "Detected both transactional and non-transactional statements"

**Cause**: Trying to use CONCURRENTLY indexes in Flyway transaction

**Solution**:
```bash
# Option A: Use test migrations (no CONCURRENTLY)
mvn test

# Option B: Configure Flyway for production
flyway.mixed=true  # Not recommended

# Option C: Use standalone script
psql -d peegeeq_db -f PEEGEEQ_COMPLETE_SCHEMA_SETUP.sql
```

#### 2. Schema Already Exists

**Cause**: Running migrations on existing database

**Solution**:
```bash
# Check current state
flyway info

# If needed, baseline existing database
flyway baseline -baselineVersion=1

# Then migrate
flyway migrate
```

#### 3. Permission Denied

**Cause**: Insufficient database permissions

**Solution**:
```sql
-- Grant necessary permissions
GRANT CREATE ON DATABASE peegeeq_db TO your_user;
GRANT ALL PRIVILEGES ON SCHEMA public TO your_user;
```

#### 4. Connection Timeout During Tests

**Cause**: PostgreSQL container taking too long to start

**Solution**:
```bash
# Increase statement timeout
psql -d peegeeq_db -c "SET statement_timeout = '60s';"

# Or in test configuration
.withEnv("POSTGRES_INITDB_ARGS", "-c statement_timeout=60000")
```

#### 5. Flyway Version Mismatch

**Cause**: Different Flyway versions between environments

**Solution**:
```xml
<!-- Pin Flyway version in pom.xml -->
<flyway.version>10.10.0</flyway.version>
```

### Getting Help

**Documentation**:
- This README - Complete overview
- `PEEGEEQ_COMPLETE_SCHEMA_SETUP_GUIDE.md` - Standalone setup details
- `PEEGEEQ_MIGRATIONS_AND_DEPLOYMENTS.md` - Flyway deployment guide
- `PEEGEEQ_SCHEMA_QUICK_REFERENCE.sql` - Quick reference commands

**Verification Queries**:
```sql
-- Check schema version
SELECT * FROM schema_version ORDER BY version;

-- List all tables
\dt

-- List all indexes
\di

-- List all functions
\df

-- Check table row counts
SELECT 
    schemaname,
    tablename,
    n_live_tup AS row_count
FROM pg_stat_user_tables
ORDER BY tablename;
```

---

## Schema Reference

### What's Included

**20 Tables**:
- Core: `outbox`, `queue_messages`, `message_processing`, `dead_letter_queue`
- Consumer Groups: `outbox_consumer_groups`, `outbox_topics`, `outbox_topic_subscriptions`
- Fanout: `processed_ledger`, `partition_drop_audit`, `consumer_group_index`
- Bi-temporal: `bitemporal_event_log`, `bitemporal_event_type_stats`
- Metrics: `queue_metrics`, `connection_pool_metrics`, `connection_health_check`, `partition_metadata`
- Tracking: `schema_version`

**50+ Indexes**:
- Performance indexes on all tables
- Bi-temporal time-based queries
- JSONB GIN indexes for payload searches
- Consumer group lookup optimization

**15+ Functions**:
- Message processing and notifications
- Consumer group management (register, mark dead, backfill)
- Cleanup and retention (completed messages, old partitions)
- Bi-temporal queries (current state, as-of-time, events)
- Heartbeat monitoring

**6 Triggers**:
- Real-time LISTEN/NOTIFY
- Automatic timestamp updates
- Consumer group fanout automation
- Bi-temporal event notifications

**2 Views**:
- `bitemporal_current_state` - Current state view
- `bitemporal_as_of_now` - As-of-now view

### Configuration

After setup, configure your PeeGeeQ instance:

```sql
-- 1. Configure topics
INSERT INTO outbox_topics (topic, semantics, message_retention_hours)
VALUES 
    ('orders', 'QUEUE', 24),      -- Single consumer
    ('events', 'PUB_SUB', 48);    -- Multi-consumer

-- 2. Register consumer groups
INSERT INTO outbox_topic_subscriptions (topic, group_name)
VALUES ('orders', 'order-processing-service');

-- Backfill existing messages
SELECT register_consumer_group_for_existing_messages('order-processing-service');

-- 3. Schedule maintenance (using pg_cron)
SELECT cron.schedule('cleanup-processing', '0 * * * *', 
    'SELECT cleanup_completed_message_processing()');

SELECT cron.schedule('cleanup-outbox', '0 0 * * *', 
    'SELECT cleanup_completed_outbox_messages()');

SELECT cron.schedule('dead-groups', '*/5 * * * *', 
    'SELECT mark_dead_consumer_groups()');
```

See `PEEGEEQ_SCHEMA_QUICK_REFERENCE.sql` for more examples.

---

## Module Structure

```
peegeeq-migrations/
├── src/
│   ├── main/
│   │   └── resources/
│   │       └── db/
│   │           └── migration/
│   │               ├── V001__Create_Base_Tables.sql              # Base schema (CONCURRENTLY)
│   │               └── V010__Create_Consumer_Group_Fanout_Tables.sql
│   └── test/
│       ├── java/
│       │   └── dev/mars/peegeeq/migrations/
│       │       ├── MigrationIntegrationTest.java                # 17 integration tests
│       │       └── SchemaValidationTest.java
│       └── resources/
│           └── db/
│               └── migration/
│                   ├── V001__Create_Base_Tables.sql              # Test version (standard indexes)
│                   └── V010__Create_Consumer_Group_Fanout_Tables.sql
├── PEEGEEQ_COMPLETE_SCHEMA_SETUP.sql                            # Standalone setup script
├── PEEGEEQ_SCHEMA_QUICK_REFERENCE.sql                           # Quick reference
├── PEEGEEQ_COMPLETE_SCHEMA_SETUP_GUIDE.md                       # Detailed setup guide
├── PEEGEEQ_MIGRATIONS_AND_DEPLOYMENTS.md                        # Flyway deployment guide
├── README.md                                                     # This file
└── pom.xml                                                       # Maven config
```

---

## CI/CD Integration

### GitHub Actions

```yaml
name: Database Tests
on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Setup PostgreSQL
        run: |
          docker run -d -p 5432:5432 \
            -e POSTGRES_PASSWORD=test \
            postgres:15-alpine
          sleep 5
      
      - name: Create Database
        run: |
          PGPASSWORD=test psql -h localhost -U postgres -c "CREATE DATABASE peegeeq_test;"
      
      - name: Setup Schema
        run: |
          PGPASSWORD=test psql -h localhost -U postgres -d peegeeq_test \
            -f peegeeq-migrations/PEEGEEQ_COMPLETE_SCHEMA_SETUP.sql
      
      - name: Run Tests
        run: |
          cd peegeeq-migrations
          mvn test
```

### GitLab CI

```yaml
test:
  image: maven:3.9-eclipse-temurin-17
  services:
    - postgres:15-alpine
  variables:
    POSTGRES_DB: peegeeq_test
    POSTGRES_USER: postgres
    POSTGRES_PASSWORD: test
  script:
    - apt-get update && apt-get install -y postgresql-client
    - psql -h postgres -U postgres -d peegeeq_test -f peegeeq-migrations/PEEGEEQ_COMPLETE_SCHEMA_SETUP.sql
    - cd peegeeq-migrations && mvn test
```

---

## Version History

| Version | Date | Description |
|---------|------|-------------|
| V010 | 2024-11 | Consumer group fanout tables and functions |
| V001 | 2024-10 | Base schema with bi-temporal event log |

---

## Additional Documentation

- **PEEGEEQ_COMPLETE_SCHEMA_SETUP_GUIDE.md** - Comprehensive standalone setup guide with troubleshooting
- **PEEGEEQ_MIGRATIONS_AND_DEPLOYMENTS.md** - Detailed Flyway deployment procedures and best practices
- **PEEGEEQ_SCHEMA_QUICK_REFERENCE.sql** - Quick copy-paste reference for common operations

---

**Version**: 2.0.0  
**Last Updated**: November 17, 2025  
**PostgreSQL**: 12+  
**Flyway**: 10.10.0+

