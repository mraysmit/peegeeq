# Schema Synchronization Strategy

## Problem Statement

PeeGeeQ has **multiple schema definitions** across different modules:

1. **peegeeq-migrations** - Flyway migrations (V001, V010) + standalone SQL (authoritative)
2. **peegeeq-db** - `PeeGeeQDatabaseSetupService` (minimal core schema for tests)
3. **peegeeq-outbox** - `TestSchemaInitializer` (test schema)
4. **peegeeq-native** - `NativeQueueIntegrationTest` (test schema)
5. **peegeeq-examples-spring** - `schema-springboot2.sql`, `schema-springboot-priority.sql`

**Risk**: Schema drift where test schemas diverge from production migrations, causing:
- Tests passing with incorrect schema assumptions
- Production failures when migrations don't match application expectations
- Maintenance burden keeping multiple definitions in sync

---

## Solution: Multi-Layered Validation

### Layer 1: Schema Contract Tests ✅ **IMPLEMENTED**

**File**: `peegeeq-migrations/src/test/java/dev/mars/peegeeq/migrations/SchemaContractTest.java`

**Purpose**: Validates that migrations create schemas compatible with application modules.

**What it validates**:
- ✅ All columns expected by peegeeq-native, peegeeq-outbox, peegeeq-db exist
- ✅ Column types match what the application code expects
- ✅ Required indexes exist for performance-critical queries
- ✅ Functions and triggers used by application code are present
- ✅ CHECK constraints allow expected values
- ✅ V010 fanout tables and columns exist

**When to run**:
```bash
cd peegeeq-migrations
mvn test -Dtest=SchemaContractTest
```

**When it fails**: Schema drift detected - migrations don't match application expectations.

---

### Layer 2: Existing Schema Validation Tests

**File**: `peegeeq-migrations/src/test/java/dev/mars/peegeeq/migrations/SchemaValidationTest.java`

**Purpose**: Validates detailed column structures, constraints, defaults.

**What it validates**:
- Column NOT NULL constraints
- Default values
- JSONB column types
- Timestamp types
- Views are queryable
- Functions are callable

**Status**: ⚠️ **Needs V010 coverage** (see Gap Analysis below)

---

### Layer 3: Migration Integration Tests

**File**: `peegeeq-migrations/src/test/java/dev/mars/peegeeq/migrations/MigrationIntegrationTest.java`

**Purpose**: Validates migrations execute successfully and create expected objects.

**What it validates**:
- 17 integration tests covering table existence, indexes, functions, triggers, views
- Custom schema support
- Migration version tracking

---

## Gap Analysis

### ⚠️ V010 Validation Gap

**Issue**: `SchemaValidationTest` doesn't yet cover V010 additions.

**Missing validations**:
1. **outbox fanout columns**:
   - `required_consumer_groups` (INTEGER)
   - `completed_consumer_groups` (INTEGER)
   - `completed_groups_bitmap` (BIGINT)

2. **outbox_topics table**:
   - `id`, `topic`, `semantics`, `message_retention_hours`, `created_at`

3. **outbox_topic_subscriptions table**:
   - `id`, `topic`, `group_name`, `created_at`

4. **processed_ledger table**:
   - Partition-aware cleanup tracking

**Action Required**: Extend `SchemaValidationTest` to cover V010 (see Recommendations below).

---

## Recommendations

### 1. Single Source of Truth (High Priority)

**Goal**: Make migrations the only authoritative schema definition.

**Actions**:
- ✅ Keep `peegeeq-migrations` as authoritative source
- ⚠️ **Remove duplicate schemas** from test modules (see below)
- ✅ Use `SchemaContractTest` to validate application expectations

**Test Schema Strategy**:
```java
// BEFORE (duplicated schema):
@BeforeEach
void setup() {
    stmt.execute("CREATE TABLE outbox (...)"); // 20+ lines of DDL
}

// AFTER (use migrations):
@BeforeEach
void setup() {
    // Option A: Use Flyway in tests
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate();
    
    // Option B: Use PeeGeeQDatabaseSetupService
    setupService.setupDatabase(config).join();
}
```

### 2. Extend SchemaValidationTest for V010 (Medium Priority)

**File**: `peegeeq-migrations/src/test/java/dev/mars/peegeeq/migrations/SchemaValidationTest.java`

**Add tests**:
```java
@Test
void testOutboxFanoutColumnsExist() {
    // Validate required_consumer_groups, completed_consumer_groups, completed_groups_bitmap
}

@Test
void testOutboxTopicsTableStructure() {
    // Validate outbox_topics columns and constraints
}

@Test
void testOutboxTopicSubscriptionsTableStructure() {
    // Validate outbox_topic_subscriptions columns and constraints
}
```

### 3. CI/CD Integration (Low Priority)

**Goal**: Automated drift detection in build pipeline.

**Implementation**:
```yaml
# .github/workflows/schema-validation.yml
name: Schema Validation
on: [push, pull_request]
jobs:
  validate-schema:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Run Schema Contract Tests
        run: |
          cd peegeeq-migrations
          mvn test -Dtest=SchemaContractTest
```

---

## Migration Workflow

### When Adding a New Column

**Step 1**: Create versioned migration
```bash
cd peegeeq-migrations/src/main/resources/db/migration
touch V011__Add_New_Column.sql
```

**Step 2**: Write migration SQL
```sql
-- V011__Add_New_Column.sql
ALTER TABLE outbox ADD COLUMN new_column VARCHAR(255);
CREATE INDEX idx_outbox_new_column ON outbox(new_column);
```

**Step 3**: Update SchemaContractTest
```java
@Test
void testOutboxTableContract() {
    Map<String, String> requiredColumns = Map.ofEntries(
        // ... existing columns ...
        Map.entry("new_column", "character varying")  // ADD THIS
    );
    validateTableColumns("outbox", requiredColumns);
}
```

**Step 4**: Run tests
```bash
mvn test
```

**Step 5**: Update standalone SQL (if needed)
```bash
# Update PEEGEEQ_COMPLETE_SCHEMA_SETUP.sql to include new column
```

---

## Files to Clean Up (Future Work)

### Duplicate Schema Definitions to Remove

1. **peegeeq-db/src/main/java/.../PeeGeeQDatabaseSetupService.java**
   - Keep for minimal test setup, but document it's a subset
   - Add comment: "Minimal schema for tests - use migrations for production"

2. **peegeeq-outbox/src/test/java/.../TestSchemaInitializer.java** (2 copies!)
   - Replace with Flyway migration in tests
   - Or use `PeeGeeQDatabaseSetupService`

3. **peegeeq-native/src/test/java/.../NativeQueueIntegrationTest.java**
   - Replace inline DDL with Flyway migration

4. **peegeeq-examples-spring/src/main/resources/schema-*.sql**
   - Keep for Spring Boot examples, but add warning comment
   - Add comment: "Example schema - use peegeeq-migrations for production"

---

## Summary

✅ **Implemented**: `SchemaContractTest` validates application expectations against migrations

⚠️ **Next Steps**:
1. Extend `SchemaValidationTest` to cover V010 additions
2. Replace duplicate test schemas with Flyway migrations
3. Add CI/CD integration for automated validation

🎯 **Goal**: Single source of truth (migrations) with automated validation preventing drift.

