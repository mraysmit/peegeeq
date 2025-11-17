# PeeGeeQ Database Setup - Complete Package

## 📦 What You Get

This package provides **three ways** to set up the PeeGeeQ database, each suited for different use cases:

### 1. 🚀 Standalone SQL Script (Fastest)
**File**: `COMPLETE_SCHEMA_SETUP.sql`  
**Best For**: Development, testing, quick setup, database recovery

```bash
# Default 'public' schema
createdb peegeeq_db
psql -d peegeeq_db -f COMPLETE_SCHEMA_SETUP.sql

# Custom schema
createdb peegeeq_db
psql -d peegeeq_db -c "CREATE SCHEMA myschema;"
psql -d peegeeq_db -v schema=myschema -f COMPLETE_SCHEMA_SETUP.sql
```

**Advantages**:
- ✅ Single command setup
- ✅ No Flyway dependency
- ✅ Optional schema support
- ✅ Idempotent (safe to rerun)
- ✅ Perfect for CI/CD test databases
- ✅ Complete schema in one file

**Read**: `COMPLETE_SCHEMA_SETUP_GUIDE.md` for full instructions

---

### 2. 🏗️ Flyway Migrations (Production)
**Files**: `src/main/resources/db/migration/V*.sql`  
**Best For**: Production deployments, version tracking, incremental updates

```bash
flyway migrate -url=jdbc:postgresql://localhost:5432/peegeeq_db
```

**Advantages**:
- ✅ Full migration history
- ✅ Version tracking and audit trail
- ✅ Rollback capabilities
- ✅ Production-grade deployment
- ✅ Team collaboration friendly

**Read**: `PEEGEEQ_MIGRATIONS_AND_DEPLOYMENTS.md` for full guide

---

### 3. 🧪 Test Infrastructure
**Files**: `src/test/java/**` and `src/test/resources/**`  
**Best For**: Automated testing, CI/CD validation, schema verification

```bash
mvn test
```

**Advantages**:
- ✅ 17 comprehensive integration tests
- ✅ Schema validation
- ✅ Migration verification
- ✅ Zero-drift confirmation
- ✅ Automated in CI/CD

**Read**: `README_TESTING.md` for testing guide

---

## 📋 File Overview

| File | Purpose | When to Use |
|------|---------|-------------|
| `COMPLETE_SCHEMA_SETUP.sql` | **Single-file complete schema** | Development, testing, recovery |
| `COMPLETE_SCHEMA_SETUP_GUIDE.md` | **Usage guide for SQL script** | Learn how to use standalone setup |
| `QUICK_REFERENCE.sql` | **Post-setup commands & queries** | After setup - configure & monitor |
| `V001__Create_Base_Tables.sql` | **Flyway migration - base schema** | Production incremental deployment |
| `V010__Create_Consumer_Group_Fanout_Tables.sql` | **Flyway migration - fanout** | Production incremental deployment |
| `PEEGEEQ_MIGRATIONS_AND_DEPLOYMENTS.md` | **Flyway complete guide** | Production deployment planning |
| `README_TESTING.md` | **Test infrastructure guide** | Understand testing setup |
| `SCHEMA_DRIFT_ANALYSIS_REPORT.md` | **Schema analysis report** | Verify zero drift |
| `README.md` | **Module overview** | Quick start reference |

---

## 🎯 Quick Decision Guide

**Choose the approach based on your goal:**

### "I want to start developing locally NOW"
→ Use **COMPLETE_SCHEMA_SETUP.sql**

```bash
createdb peegeeq_dev
psql -d peegeeq_dev -f COMPLETE_SCHEMA_SETUP.sql
# Done! Start coding.
```

### "I'm deploying to production"
→ Use **Flyway Migrations**

```bash
# Read PEEGEEQ_MIGRATIONS_AND_DEPLOYMENTS.md first
flyway migrate -url=jdbc:postgresql://prod-db/peegeeq
```

### "I'm setting up CI/CD tests"
→ Use **COMPLETE_SCHEMA_SETUP.sql** in pipeline

```yaml
- run: psql -d test_db -f COMPLETE_SCHEMA_SETUP.sql
- run: mvn test
```

### "I need to verify schema correctness"
→ Run **Test Suite**

```bash
mvn test
# 17 tests verify everything
```

### "I broke my database and need to reset"
→ Use **COMPLETE_SCHEMA_SETUP.sql**

```bash
dropdb peegeeq_dev && createdb peegeeq_dev
psql -d peegeeq_dev -f COMPLETE_SCHEMA_SETUP.sql
```

---

## 🗂️ What's in the Schema

### Core Tables (20 total)

**Message Processing** (V001):
- `outbox` - Outbox pattern for reliable delivery
- `queue_messages` - Native queue messages
- `message_processing` - INSERT-only lock-free processing
- `dead_letter_queue` - Failed message storage
- `outbox_consumer_groups` - Consumer group tracking

**Consumer Group Fanout** (V010):
- `outbox_topics` - Topic configuration (QUEUE/PUB_SUB)
- `outbox_topic_subscriptions` - Subscription management
- `processed_ledger` - Audit trail
- `partition_drop_audit` - Partition cleanup tracking
- `consumer_group_index` - Performance statistics

**Bi-temporal Event Sourcing** (V001):
- `bitemporal_event_log` - Event log with valid/transaction time
- `bitemporal_event_type_stats` - Event statistics
- Views: `bitemporal_current_state`, `bitemporal_as_of_now`

**Metrics & Monitoring** (V001):
- `queue_metrics` - Queue performance
- `connection_pool_metrics` - Connection statistics
- `connection_health_check` - Health monitoring
- `partition_metadata` - Partition information

### Indexes (50+)
- Performance indexes on all tables
- Bi-temporal time-based queries
- JSONB GIN indexes for payload searches
- Consumer group lookup optimization

### Functions (15+)
- Message processing and notifications
- Consumer group management
- Cleanup and retention policies
- Bi-temporal queries
- Heartbeat monitoring

### Triggers (6)
- Real-time LISTEN/NOTIFY
- Automatic timestamp updates
- Consumer group fanout automation
- Bi-temporal event notifications

---

## 📚 Documentation Map

### For Developers
1. **Start Here**: `README.md` - Module overview
2. **Quick Setup**: `COMPLETE_SCHEMA_SETUP_GUIDE.md` - Fast local setup
3. **Post-Setup**: `QUICK_REFERENCE.sql` - Configuration & queries
4. **Testing**: `README_TESTING.md` - Test infrastructure

### For DevOps
1. **Production Deploy**: `PEEGEEQ_MIGRATIONS_AND_DEPLOYMENTS.md` - Flyway guide
2. **Verification**: `SCHEMA_DRIFT_ANALYSIS_REPORT.md` - Zero drift proof
3. **Monitoring**: `QUICK_REFERENCE.sql` - Health check queries
4. **Troubleshooting**: `COMPLETE_SCHEMA_SETUP_GUIDE.md` - Common issues

### For Architects
1. **Schema Overview**: `COMPLETE_SCHEMA_SETUP.sql` - Complete reference
2. **Design Decisions**: `SCHEMA_DRIFT_ANALYSIS_REPORT.md` - Analysis
3. **Migration Strategy**: `PEEGEEQ_MIGRATIONS_AND_DEPLOYMENTS.md` - Approach
4. **Quality Assurance**: `README_TESTING.md` - Test coverage

---

## 🚀 Common Workflows

### Workflow 1: New Developer Onboarding

```bash
# 1. Clone repo
git clone <repo-url>
cd peegeeq/peegeeq-migrations

# 2. Create local database
createdb peegeeq_dev

# 3. Run complete setup
psql -d peegeeq_dev -f COMPLETE_SCHEMA_SETUP.sql

# 4. Verify
psql -d peegeeq_dev -c "SELECT * FROM schema_version;"

# 5. Configure (optional)
psql -d peegeeq_dev -f QUICK_REFERENCE.sql

# Done! Start developing
```

### Workflow 2: Production Deployment

```bash
# 1. Review deployment guide
cat PEEGEEQ_MIGRATIONS_AND_DEPLOYMENTS.md

# 2. Test in staging
flyway migrate -url=jdbc:postgresql://staging-db/peegeeq

# 3. Verify staging
mvn test -Ddb.url=jdbc:postgresql://staging-db/peegeeq

# 4. Deploy to production (during maintenance window)
flyway migrate -url=jdbc:postgresql://prod-db/peegeeq

# 5. Verify production
psql -d prod-db -c "SELECT * FROM schema_version;"
```

### Workflow 3: CI/CD Pipeline

```yaml
# .github/workflows/test.yml
steps:
  - name: Setup PostgreSQL
    run: |
      docker run -d -p 5432:5432 postgres:15
      sleep 5
      createdb peegeeq_test
  
  - name: Setup Schema
    run: |
      psql -d peegeeq_test -f peegeeq-migrations/COMPLETE_SCHEMA_SETUP.sql
  
  - name: Run Tests
    run: mvn test
```

### Workflow 4: Schema Verification

```bash
# 1. Run all tests
mvn test

# 2. Check for drift
cat SCHEMA_DRIFT_ANALYSIS_REPORT.md

# 3. Verify against production
psql -d prod_db -c "\d+ outbox"
psql -d prod_db -c "\df"

# 4. Generate comparison report
diff <(psql -d prod_db -c "\dt") <(psql -d dev_db -c "\dt")
```

---

## ⚙️ Configuration After Setup

### Step 1: Configure Topics

```sql
-- Queue semantics (single consumer)
INSERT INTO outbox_topics (topic, semantics, message_retention_hours)
VALUES ('orders', 'QUEUE', 24);

-- Pub/Sub semantics (multiple consumers)
INSERT INTO outbox_topics (topic, semantics, message_retention_hours)
VALUES ('notifications', 'PUB_SUB', 48);
```

### Step 2: Register Consumer Groups

```sql
INSERT INTO outbox_topic_subscriptions (topic, group_name)
VALUES ('orders', 'order-processing-service');

-- Backfill existing messages
SELECT register_consumer_group_for_existing_messages('order-processing-service');
```

### Step 3: Schedule Maintenance

```sql
-- Using pg_cron (recommended)
SELECT cron.schedule('cleanup-processing', '0 * * * *', 
    'SELECT cleanup_completed_message_processing()');

SELECT cron.schedule('cleanup-outbox', '0 0 * * *', 
    'SELECT cleanup_completed_outbox_messages()');

SELECT cron.schedule('dead-groups', '*/5 * * * *', 
    'SELECT mark_dead_consumer_groups()');
```

### Step 4: Set Up Monitoring

```sql
-- Consumer group health
SELECT * FROM consumer_group_index;

-- Message backlog
SELECT topic, status, COUNT(*) 
FROM outbox 
GROUP BY topic, status;

-- See QUICK_REFERENCE.sql for more queries
```

---

## 🎓 Learning Path

### Beginner
1. Read `README.md` - Understand module purpose
2. Use `COMPLETE_SCHEMA_SETUP.sql` - Set up local database
3. Read `QUICK_REFERENCE.sql` - Learn basic operations
4. Run `mvn test` - See tests in action

### Intermediate
1. Read `COMPLETE_SCHEMA_SETUP_GUIDE.md` - Deep dive into setup
2. Read `README_TESTING.md` - Understand test infrastructure
3. Review `SCHEMA_DRIFT_ANALYSIS_REPORT.md` - Schema analysis
4. Experiment with consumer groups and topics

### Advanced
1. Read `PEEGEEQ_MIGRATIONS_AND_DEPLOYMENTS.md` - Production deployment
2. Study migration files (V001, V010) - Understand incremental changes
3. Create custom maintenance procedures
4. Set up production monitoring

---

## 📞 Getting Help

### Documentation
- **Quick Setup**: `COMPLETE_SCHEMA_SETUP_GUIDE.md`
- **Production**: `PEEGEEQ_MIGRATIONS_AND_DEPLOYMENTS.md`
- **Testing**: `README_TESTING.md`
- **Reference**: `QUICK_REFERENCE.sql`

### Common Questions

**Q: Which setup method should I use?**  
A: Development → `COMPLETE_SCHEMA_SETUP.sql`, Production → Flyway migrations

**Q: Can I use the SQL script in production?**  
A: Yes, but Flyway migrations are recommended for version tracking

**Q: How do I verify the schema is correct?**  
A: Run `mvn test` - 17 tests verify everything

**Q: What if setup fails?**  
A: Check `COMPLETE_SCHEMA_SETUP_GUIDE.md` troubleshooting section

**Q: How do I upgrade an existing database?**  
A: Use Flyway migrations as documented in `PEEGEEQ_MIGRATIONS_AND_DEPLOYMENTS.md`

---

## ✅ Success Criteria

After setup, you should have:

- ✅ 20 tables created
- ✅ 50+ indexes created
- ✅ 15+ functions defined
- ✅ 6 triggers active
- ✅ 2 views available
- ✅ `schema_version` table populated
- ✅ All tests passing (`mvn test`)
- ✅ Basic queries working

Verify with:
```bash
psql -d peegeeq_db -c "SELECT COUNT(*) FROM pg_tables WHERE schemaname='public';"
# Expected: 20+

psql -d peegeeq_db -c "SELECT * FROM schema_version;"
# Expected: V001, V010

mvn test
# Expected: All 17 tests passing
```

---

**Version**: 2.0.0  
**Last Updated**: 2025-11-17  
**Compatible With**: PostgreSQL 12+, PeeGeeQ v1.2.0+
