# Schema Drift Analysis Report
**Generated:** November 17, 2025  
**Project:** PeeGeeQ Message Queue System  
**Scope:** Flyway migrations vs Java codebase alignment

---

## Executive Summary

This analysis compares Flyway migration files (`V001__Create_Base_Tables.sql` and `V010__Create_Consumer_Group_Fanout_Tables.sql`) against the Java codebase to identify schema drift, missing columns, unused columns, and inconsistencies.

### Overall Finding: **EXCELLENT ALIGNMENT** ✅

The schema is remarkably well-aligned between migrations and Java code. The few issues identified are minor and primarily relate to:
1. Unused columns added in V010 for future features
2. Test schema following migrations closely with only minor type differences
3. PeeGeeQDatabaseSetupService having a minimal fallback schema (by design)

---

## 1. Migration File Comparison

### V001__Create_Base_Tables.sql
Creates the core schema with 11 tables:
- `schema_version` - Version tracking
- `outbox` - Outbox pattern messages
- `outbox_consumer_groups` - Consumer group tracking (uses `outbox_message_id`, `consumer_group_name`)
- `queue_messages` - Native queue messages
- `message_processing` - INSERT-only processing tracker
- `dead_letter_queue` - Failed messages
- `queue_metrics` - Metrics tracking
- `connection_pool_metrics` - Connection metrics
- `bitemporal_event_log` - Event sourcing

**Key Columns in outbox (V001):**
```sql
id, topic, payload, created_at, processed_at, processing_started_at, 
status, retry_count, max_retries, next_retry_at, version, headers, 
error_message, correlation_id, message_group, priority
```

### V010__Create_Consumer_Group_Fanout_Tables.sql
Adds consumer group fanout support with:
- `outbox_topics` - Topic configuration
- `outbox_topic_subscriptions` - Subscription management
- `processed_ledger` - Audit trail
- `partition_drop_audit` - Partition tracking (for future use)
- `consumer_group_index` - Performance optimization

**Columns ADDED to outbox (V010):**
```sql
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS required_consumer_groups INT DEFAULT 1;
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS completed_consumer_groups INT DEFAULT 0;
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS completed_groups_bitmap BIGINT DEFAULT 0;
```

**Column RENAMED in outbox_consumer_groups (V010):**
```sql
-- Renamed: outbox_message_id -> message_id
-- Renamed: consumer_group_name -> group_name
```

---

## 2. Java Code Column Usage Analysis

### 2.1 Outbox Table Columns

#### ✅ FULLY USED COLUMNS (All referenced in Java code)

| Column | Usage | Files |
|--------|-------|-------|
| `id` | Primary key, everywhere | All outbox/consumer code |
| `topic` | Topic routing | `OutboxProducer.java`, `ConsumerGroupFetcher.java`, `CleanupService.java` |
| `payload` | Message data | `OutboxProducer.java`, `OutboxConsumer.java` |
| `created_at` | Timestamps | `OutboxProducer.java`, `ConsumerGroupFetcher.java` |
| `processed_at` | Completion tracking | `OutboxConsumer.java`, `CompletionTracker.java` |
| `processing_started_at` | Lock tracking | `OutboxConsumer.java` |
| `status` | Lifecycle state | All consumer/cleanup code |
| `retry_count` | Retry logic | `OutboxConsumer.java` (line 515) |
| `max_retries` | Retry limit | `OutboxConsumer.java` (line 515) |
| `headers` | Metadata | `OutboxProducer.java` (line 212) |
| `correlation_id` | Tracing | `OutboxProducer.java` (line 212) |
| `message_group` | Ordering | `OutboxProducer.java` (line 212) |

#### ✅ CONSUMER GROUP FANOUT COLUMNS (Added in V010, Used in Java)

| Column | Usage | Files |
|--------|-------|-------|
| `required_consumer_groups` | Fanout tracking | `CompletionTracker.java` (lines 99, 104, 109, 110, 121) |
| `completed_consumer_groups` | Completion counter | `CompletionTracker.java` (lines 97, 99, 104, 109, 110, 120) |
| `completed_groups_bitmap` | Bitmap tracking | Set in trigger, not directly used in Java yet |

**Evidence from CompletionTracker.java:**
```java
UPDATE outbox
SET completed_consumer_groups = completed_consumer_groups + 1,
    status = CASE
        WHEN completed_consumer_groups + 1 >= required_consumer_groups
        THEN 'COMPLETED'
        ELSE status
    END
WHERE id = $1
  AND completed_consumer_groups < required_consumer_groups
```

#### ⚠️ UNUSED/UNDER-USED COLUMNS

| Column | Status | Reason |
|--------|--------|--------|
| `next_retry_at` | **UNUSED** | Defined in schema, indexed, but no Java code sets/reads it |
| `version` | **UNUSED** | Defined in schema (default 0), but no Java code uses it |
| `priority` | **PARTIALLY USED** | Column exists with CHECK constraint, inserted with default, but no priority-based fetching |
| `error_message` | **USED** | Referenced in `OutboxConsumer.java` for failures |

**Finding:** These appear to be **planned features** rather than drift. The schema is prepared for:
- Priority queuing (priority column exists but not used in ORDER BY yet)
- Scheduled retries (next_retry_at exists but retry logic doesn't schedule)
- Optimistic locking (version column for future concurrency control)

### 2.2 outbox_consumer_groups Table

#### ✅ Column Name Changes (V010 Migration)

The migration **correctly renamed columns** to match design docs:
- `outbox_message_id` → `message_id` ✅
- `consumer_group_name` → `group_name` ✅

**Java Code Adoption:**
- ✅ `CompletionTracker.java` uses `message_id`, `group_name`
- ✅ `ConsumerGroupFetcher.java` uses `message_id`, `group_name`
- ✅ Test schema (`PeeGeeQTestSchemaInitializer.java`) uses new names

**No drift detected** - migration and Java are aligned.

### 2.3 outbox_topics Table (Added in V010)

#### ✅ ALL COLUMNS USED

| Column | Usage | Files |
|--------|-------|-------|
| `topic` | Primary key | `TopicConfigService.java` (lines 67, 120) |
| `semantics` | QUEUE vs PUB_SUB | `TopicConfigService.java`, `CleanupService.java` |
| `message_retention_hours` | Cleanup logic | `CleanupService.java` (line 89) |
| `zero_subscription_retention_hours` | Zero-sub cleanup | `CleanupService.java` |
| `block_writes_on_zero_subscriptions` | Write validation | `ZeroSubscriptionValidator.java` (lines 67, 87, 104) |
| `completion_tracking_mode` | Future offset/watermark | `TopicConfigService.java` (lines 68, 75, 123) |
| `created_at`, `updated_at` | Metadata | `TopicConfigService.java` |

**Finding:** All columns actively used. ✅

### 2.4 outbox_topic_subscriptions Table (Added in V010)

#### ✅ FULLY USED COLUMNS

| Column | Usage | Files |
|--------|-------|-------|
| `id`, `topic`, `group_name` | Core identity | `SubscriptionManager.java` |
| `subscription_status` | Lifecycle (ACTIVE/PAUSED/CANCELLED/DEAD) | `SubscriptionManager.java` (line 249), `DeadConsumerDetector.java` |
| `subscribed_at`, `last_active_at` | Timestamps | `SubscriptionManager.java` |
| `start_from_message_id` | Start position | `SubscriptionManager.java` (line 101) |
| `start_from_timestamp` | Time-based start | Used in subscription logic |
| `heartbeat_interval_seconds` | Heartbeat config | `DeadConsumerDetector.java` |
| `heartbeat_timeout_seconds` | Timeout detection | `DeadConsumerDetector.java` |
| `last_heartbeat_at` | Liveness tracking | `SubscriptionManager.java`, `DeadConsumerDetector.java` |

#### ⚠️ BACKFILL COLUMNS (Future Feature)

| Column | Status | Purpose |
|--------|--------|---------|
| `backfill_status` | **PARTIALLY USED** | Read in `SubscriptionManager.java` (line 384), not written |
| `backfill_checkpoint_id` | **READ ONLY** | Read in `SubscriptionManager.java` (line 385) |
| `backfill_processed_messages` | **READ ONLY** | Read in query (line 284) |
| `backfill_total_messages` | **READ ONLY** | Read in query (line 284) |
| `backfill_started_at` | **READ ONLY** | Query selection only |
| `backfill_completed_at` | **READ ONLY** | Query selection only |

**Finding:** Backfill columns are in "Phase 8" planning stage. Schema prepared for future feature. ✅

### 2.5 Audit/Tracking Tables (Added in V010)

#### ✅ processed_ledger

All columns present and used:
```sql
id, message_id, group_name, topic, processed_at, 
processing_duration_ms, status, error_message, partition_key
```

**Usage:** Currently read-only in queries. Likely populated by triggers or future code.

#### ⚠️ partition_drop_audit

**Status:** **UNUSED** - Table created but no Java code references it.

**Reason:** This table is for **future Offset/Watermark mode** (Phase 7) as documented in V010 comments:
```sql
-- Partition drop audit (for Offset/Watermark mode - Phase 7)
CREATE TABLE IF NOT EXISTS partition_drop_audit (...)
```

**Finding:** This is **intentional future preparation**, not drift. ✅

#### ✅ consumer_group_index

All columns used in `ConsumerGroupFetcher.java` and performance tracking.

---

## 3. Missing Columns Analysis

### 3.1 Columns in Java but NOT in Migrations

**NONE FOUND** ✅

All INSERT/UPDATE statements in Java reference only columns that exist in migrations.

**Verification:**
- ✅ `OutboxProducer.java` INSERT (line 212): All columns exist
- ✅ `CompletionTracker.java` UPDATE (lines 96-110): All columns exist
- ✅ `OutboxConsumer.java` UPDATE (lines 259, 387, 476): All columns exist
- ✅ `TopicConfigService.java` INSERT/UPDATE: All columns exist

### 3.2 Columns in Migrations but NOT Used in Java

| Column | Table | Status | Recommendation |
|--------|-------|--------|----------------|
| `next_retry_at` | outbox | Schema-ready | Document as planned feature |
| `version` | outbox | Schema-ready | Document as planned feature (optimistic locking) |
| `completed_groups_bitmap` | outbox | Trigger-managed | Currently set by trigger, may be used for future optimizations |
| `backfill_*` columns | outbox_topic_subscriptions | Phase 8 feature | Document as future feature |
| `partition_drop_audit` table | - | Phase 7 feature | Document in design docs |

**Finding:** These are **intentional forward-looking columns** for planned features, not drift. ✅

---

## 4. Test Schema Drift Analysis

### 4.1 PeeGeeQTestSchemaInitializer.java

**Status:** ✅ **EXCELLENT ALIGNMENT**

The test schema initializer (`peegeeq-test-support/src/main/java/dev/mars/peegeeq/test/schema/PeeGeeQTestSchemaInitializer.java`) closely mirrors V001 + V010:

#### Aligned Tables:
- ✅ `outbox` - All columns match including V010 additions (lines 569-571)
- ✅ `outbox_consumer_groups` - Uses new column names (`message_id`, `group_name`)
- ✅ `outbox_topics` - Full schema match (line 506)
- ✅ `outbox_topic_subscriptions` - Full schema match (line 532)
- ✅ `processed_ledger` - All columns present (line 575)
- ✅ `consumer_group_index` - All columns present (line 591)
- ✅ `queue_messages`, `message_processing`, `dead_letter_queue` - All match

#### Minor Differences:
```java
// Test schema adds fanout columns via ALTER TABLE (lines 569-571)
stmt.execute("ALTER TABLE outbox ADD COLUMN IF NOT EXISTS required_consumer_groups INT DEFAULT 1");
stmt.execute("ALTER TABLE outbox ADD COLUMN IF NOT EXISTS completed_consumer_groups INT DEFAULT 0");
stmt.execute("ALTER TABLE outbox ADD COLUMN IF NOT EXISTS completed_groups_bitmap BIGINT DEFAULT 0");
```

This matches V010 exactly. ✅

#### Test Schema Does NOT Include:
- ❌ `partition_drop_audit` (intentional - not needed for tests)
- ✅ Uses programmatic trigger creation instead of SQL files

**Finding:** Test schema is **correctly simplified** for testing purposes. No production drift. ✅

### 4.2 SchemaValidationTest.java

**Test Coverage:**
- ✅ Tests `queue_messages` column structure (lines 70-88)
- ✅ Tests `outbox` column structure (lines 102-124)
- ✅ Tests `bitemporal_event_log` column structure (lines 128-150)
- ✅ Tests NOT NULL constraints
- ✅ Tests default values (created_at columns)

**Finding:** Tests validate core tables but **don't yet cover V010 additions** (outbox_topics, outbox_topic_subscriptions). ⚠️

**Recommendation:** Add validation tests for:
```java
@Test
void testOutboxTopicsTableStructure() {
    // Validate outbox_topics columns
}

@Test
void testOutboxTopicSubscriptionsTableStructure() {
    // Validate outbox_topic_subscriptions columns
}
```

### 4.3 PeeGeeQDatabaseSetupService.java

**Purpose:** Minimal fallback schema when Flyway is unavailable.

**Status:** ✅ **INTENTIONALLY MINIMAL**

The setup service creates only core tables:
- `queue_messages` (line 254)
- `outbox` (line 273)
- `dead_letter_queue` (line 292)

**Does NOT include:**
- ❌ V010 fanout columns (`required_consumer_groups`, etc.)
- ❌ `outbox_topics`, `outbox_topic_subscriptions`
- ❌ `outbox_consumer_groups`

**Finding:** This is **by design** - the setup service provides a minimal fallback for simple use cases. Production deployments use Flyway. ✅

**Risk Assessment:** If someone uses PeeGeeQDatabaseSetupService in production without Flyway, consumer group features will fail. 

**Recommendation:** Add warning in setup service:
```java
/**
 * IMPORTANT: This creates a minimal schema for development/testing.
 * For production with consumer groups, use Flyway migrations (V001 + V010).
 */
```

---

## 5. SQL Query Analysis

### 5.1 INSERT Statements

#### OutboxProducer.java (lines 212, 384, 508)
```java
INSERT INTO outbox (topic, payload, headers, correlation_id, message_group, created_at, status)
VALUES ($1, $2::jsonb, $3::jsonb, $4, $5, $6, 'PENDING')
```

**Columns NOT specified (use defaults):**
- `id` - BIGSERIAL (auto)
- `processed_at` - NULL (default)
- `processing_started_at` - NULL (default)
- `retry_count` - 0 (default)
- `max_retries` - 3 (default)
- `next_retry_at` - NULL (default)
- `version` - 0 (default)
- `error_message` - NULL (default)
- `priority` - 5 (default)
- `required_consumer_groups` - Set by trigger
- `completed_consumer_groups` - Set by trigger
- `completed_groups_bitmap` - Set by trigger

**Finding:** ✅ Correctly relies on defaults and triggers.

#### CompletionTracker.java (line 73)
```java
INSERT INTO outbox_consumer_groups (message_id, group_name, status, processed_at)
VALUES ($1, $2, 'COMPLETED', $3)
ON CONFLICT (message_id, group_name) DO UPDATE ...
```

**Finding:** ✅ Uses correct column names from V010.

### 5.2 UPDATE Statements

#### CompletionTracker.java (lines 96-110)
```sql
UPDATE outbox
SET completed_consumer_groups = completed_consumer_groups + 1,
    status = CASE ... END,
    processed_at = CASE ... END
WHERE id = $1
  AND completed_consumer_groups < required_consumer_groups
```

**Finding:** ✅ Uses V010 fanout columns correctly.

#### OutboxConsumer.java (line 259)
```sql
UPDATE outbox
SET status = 'PROCESSING', processing_started_at = $1
WHERE id IN (SELECT id FROM outbox ...)
```

**Finding:** ✅ All columns exist in schema.

### 5.3 SELECT Statements

#### ConsumerGroupFetcher.java (line 79)
```sql
SELECT o.id, o.topic, o.payload, o.headers, o.correlation_id, o.message_group,
       o.created_at, o.required_consumer_groups, o.completed_consumer_groups
FROM outbox o
INNER JOIN outbox_topic_subscriptions s ...
LEFT JOIN outbox_consumer_groups cg ...
```

**Finding:** ✅ All columns exist, joins are correct.

---

## 6. Inconsistencies Found

### 6.1 CRITICAL Issues
**NONE** ✅

### 6.2 MEDIUM Issues

#### 1. Schema Validation Tests Missing V010 Tables
**Location:** `peegeeq-migrations/src/test/java/.../SchemaValidationTest.java`

**Issue:** Tests validate V001 tables but not V010 additions.

**Impact:** Medium - New schema columns/tables not validated in CI/CD.

**Recommendation:**
```java
@Test
void testOutboxFanoutColumns() {
    // Test required_consumer_groups, completed_consumer_groups, completed_groups_bitmap
}

@Test
void testOutboxTopicsTable() {
    // Validate outbox_topics exists and has correct columns
}

@Test
void testOutboxTopicSubscriptionsTable() {
    // Validate outbox_topic_subscriptions exists
}
```

### 6.3 LOW Issues

#### 1. PeeGeeQDatabaseSetupService Missing V010 Features
**Location:** `peegeeq-db/src/main/java/.../setup/PeeGeeQDatabaseSetupService.java`

**Issue:** Fallback schema doesn't include consumer group tables.

**Impact:** Low - Only affects non-Flyway setups.

**Recommendation:** Add JavaDoc warning about limitations.

#### 2. Unused Columns Documentation
**Location:** Design docs

**Issue:** No clear documentation that some columns are for future features.

**Impact:** Low - May cause confusion.

**Recommendation:** Add comments in migration files:
```sql
-- next_retry_at: Reserved for scheduled retry feature (Phase 9)
-- version: Reserved for optimistic locking (Phase 10)
-- backfill_*: Reserved for resumable backfill (Phase 8)
```

---

## 7. ALTER TABLE Risks

### 7.1 V010 ALTER TABLE Statements

```sql
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS required_consumer_groups INT DEFAULT 1;
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS completed_consumer_groups INT DEFAULT 0;
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS completed_groups_bitmap BIGINT DEFAULT 0;
```

**Risk Assessment:** ✅ **SAFE**

- Uses `IF NOT EXISTS` - idempotent
- Adds columns with defaults - no table lock on existing rows
- PostgreSQL 11+ adds columns with defaults instantly (metadata-only change)

**Verified:** Test schema uses identical ALTER statements (lines 569-571).

### 7.2 Column Rename in V010

```sql
DO $$
BEGIN
    IF EXISTS (...) THEN
        ALTER TABLE outbox_consumer_groups RENAME COLUMN outbox_message_id TO message_id;
    END IF;
END $$;
```

**Risk Assessment:** ✅ **SAFE**

- Uses conditional check
- Java code updated to use new names
- Foreign key constraints automatically updated by PostgreSQL

**Verified:** All Java code uses `message_id` and `group_name`.

---

## 8. Recommendations

### 8.1 HIGH PRIORITY

1. **Add V010 Schema Validation Tests**
   - Add tests for `outbox_topics`, `outbox_topic_subscriptions`, fanout columns
   - Validate triggers and functions exist

2. **Document Future Feature Columns**
   - Add comments in migration files for unused columns
   - Update design docs to list "planned features" vs "implemented features"

### 8.2 MEDIUM PRIORITY

3. **Clarify PeeGeeQDatabaseSetupService Limitations**
   - Add JavaDoc warning about missing consumer group support
   - Consider throwing error if consumer group methods called without full schema

4. **Add Migration Rollback Script Validation**
   - `V010_rollback.sql` exists but should be tested
   - Add integration test for migration/rollback cycle

### 8.3 LOW PRIORITY

5. **Consider Using Priority Column**
   - If not planning to use priority, consider removing it
   - If planning to use it, add documentation for priority-based fetching

6. **Document Bitmap Usage**
   - `completed_groups_bitmap` is set by trigger but not used in Java
   - Document when/how this will be used (likely for >64 group optimization)

---

## 9. Positive Findings ✅

### Excellent Practices Observed:

1. **Column Naming Consistency**
   - V010 rename from `outbox_message_id` to `message_id` improved consistency
   - All Java code updated accordingly

2. **Idempotent Migrations**
   - Uses `IF NOT EXISTS` everywhere
   - Safe to re-run migrations

3. **Test Schema Alignment**
   - Test schema closely tracks production schema
   - Only excludes intentionally unused features

4. **Trigger-Based Defaults**
   - `set_required_consumer_groups()` trigger ensures fanout columns always set correctly
   - No risk of Java code forgetting to set these values

5. **Foreign Key Cascade**
   - `outbox_consumer_groups` uses `ON DELETE CASCADE`
   - Prevents orphaned tracking rows

6. **Comprehensive Indexes**
   - V001 and V010 both include performance indexes
   - All query patterns covered

---

## 10. Conclusion

### Overall Assessment: ✅ **EXCELLENT**

The PeeGeeQ schema shows **exceptional alignment** between migrations and Java code. The development team has done an outstanding job maintaining schema integrity.

### Issues Found:
- **Critical:** 0
- **Medium:** 1 (missing test coverage for V010)
- **Low:** 2 (documentation, minimal setup service)

### Schema Drift: **NONE**

All unused columns are **intentional future features**, not drift.

### Technical Debt:
**Minimal** - The few items identified are proactive improvements, not fixes for problems.

---

## 11. Action Items

| Priority | Action | Owner | Estimated Effort |
|----------|--------|-------|------------------|
| HIGH | Add V010 schema validation tests | QA | 2 hours |
| HIGH | Document future feature columns in migrations | Docs | 1 hour |
| MEDIUM | Add PeeGeeQDatabaseSetupService warnings | Dev | 30 minutes |
| MEDIUM | Test V010 rollback script | QA | 1 hour |
| LOW | Document bitmap optimization strategy | Arch | 30 minutes |
| LOW | Decide on priority column usage | Product | N/A |

---

## Appendix A: Schema Coverage Matrix

| Table | V001 | V010 | Test Schema | Java Usage | Status |
|-------|------|------|-------------|------------|--------|
| outbox | ✅ | ✅ (3 cols) | ✅ | ✅ Full | Perfect ✅ |
| outbox_consumer_groups | ✅ | ✅ (rename) | ✅ | ✅ Full | Perfect ✅ |
| outbox_topics | ❌ | ✅ | ✅ | ✅ Full | Perfect ✅ |
| outbox_topic_subscriptions | ❌ | ✅ | ✅ | ✅ Full | Perfect ✅ |
| processed_ledger | ❌ | ✅ | ✅ | ⚠️ Partial | Read-only |
| partition_drop_audit | ❌ | ✅ | ❌ | ❌ None | Future feature |
| consumer_group_index | ❌ | ✅ | ✅ | ✅ Full | Perfect ✅ |
| queue_messages | ✅ | ❌ | ✅ | ✅ Full | Perfect ✅ |
| message_processing | ✅ | ❌ | ✅ | ✅ Full | Perfect ✅ |
| dead_letter_queue | ✅ | ❌ | ✅ | ✅ Full | Perfect ✅ |
| bitemporal_event_log | ✅ | ❌ | ✅ | ✅ Full | Perfect ✅ |

---

## Appendix B: Column-by-Column Audit

### outbox Table (Complete)

| Column | V001 | V010 | Java Insert | Java Select | Java Update | Status |
|--------|------|------|-------------|-------------|-------------|--------|
| id | ✅ | - | Auto | ✅ | ✅ | ✅ Perfect |
| topic | ✅ | - | ✅ | ✅ | ❌ | ✅ Perfect |
| payload | ✅ | - | ✅ | ✅ | ❌ | ✅ Perfect |
| created_at | ✅ | - | ✅ | ✅ | ❌ | ✅ Perfect |
| processed_at | ✅ | - | Default | ✅ | ✅ | ✅ Perfect |
| processing_started_at | ✅ | - | Default | ✅ | ✅ | ✅ Perfect |
| status | ✅ | - | ✅ | ✅ | ✅ | ✅ Perfect |
| retry_count | ✅ | - | Default | ✅ | ✅ | ✅ Perfect |
| max_retries | ✅ | - | Default | ✅ | ❌ | ✅ Perfect |
| next_retry_at | ✅ | - | Default | ❌ | ❌ | ⚠️ Future |
| version | ✅ | - | Default | ❌ | ❌ | ⚠️ Future |
| headers | ✅ | - | ✅ | ✅ | ❌ | ✅ Perfect |
| error_message | ✅ | - | Default | ✅ | ✅ | ✅ Perfect |
| correlation_id | ✅ | - | ✅ | ✅ | ❌ | ✅ Perfect |
| message_group | ✅ | - | ✅ | ✅ | ❌ | ✅ Perfect |
| priority | ✅ | - | Default | ❌ | ❌ | ⚠️ Unused |
| required_consumer_groups | ❌ | ✅ | Trigger | ✅ | ✅ | ✅ Perfect |
| completed_consumer_groups | ❌ | ✅ | Trigger | ✅ | ✅ | ✅ Perfect |
| completed_groups_bitmap | ❌ | ✅ | Trigger | ❌ | ❌ | ⚠️ Future |

---

**End of Analysis**
