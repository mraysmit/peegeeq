# Outbox Schema Qualification Regression

**Date:** 2026-05-24
**Scope:** `peegeeq-outbox` — all classes with database access
**Status:** Analysis complete. Remediation pending.
**Linked task:** `OUTBOX-DLQ-FILTER-ERRORS-DEAD-CODE-AUDIT.md` — Step 7 (multi-tenant schema isolation)

---

## Background

Step 7 of the DLQ audit added multi-tenant schema isolation to `OutboxConsumer.java`. All 9+ SQL statements that previously used unqualified table references (`FROM outbox`, `INSERT INTO dead_letter_queue`, etc.) were changed to use schema-qualified references using a `quoteIdentifier(schemaName)` helper.

This was the correct intent. However the change introduced three regressions:
1. A NullPointerException path when schema is configured but `peegeeq.database.schema` is not set.
2. Inconsistent fallback values between `OutboxConsumer` (`"public"`) and `OutboxFactory` / `OutboxQueueBrowser` (`"peegeeq"`).
3. A breaking change to the original behavior for any deployment that did not set `peegeeq.database.schema`.

---

## Schema configuration chain

The schema flows through the following chain when an outbox consumer is created:

```
System property: peegeeq.database.schema
  → PeeGeeQConfiguration.getDatabaseConfig().getSchema()
      [peegeeq-db/src/main/java/dev/mars/peegeeq/db/config/PeeGeeQConfiguration.java, line 456]
      getString("peegeeq.database.schema", null)  ← returns null if property is not set
  → PgQueueFactoryProvider(PeeGeeQConfiguration configuration)
      [peegeeq-db/src/main/java/dev/mars/peegeeq/db/provider/PgQueueFactoryProvider.java, line 61]
      Stores as peeGeeQConfiguration field.
      PgQueueFactoryProvider() no-arg constructor → peeGeeQConfiguration = null
  → PgQueueFactoryProvider.createFactory(type, databaseService, configMap)
      [line 104-105] Injects peeGeeQConfiguration into configMap under key "peeGeeQConfiguration"
      If peeGeeQConfiguration == null, key is NOT added to the map.
  → OutboxFactoryRegistrar.OutboxFactoryCreator.create(databaseService, configMap)
      [peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxFactoryRegistrar.java, line 70]
      Extracts peeGeeQConfig from map under key "peeGeeQConfiguration" → null if not present.
      Does NOT fall back to databaseService.getPeeGeeQConfiguration() even though that method exists.
  → OutboxFactory(databaseService, objectMapper, peeGeeQConfig, ...)
      [peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxFactory.java, line 113]
      Stores as configuration field.
  → OutboxConsumer(databaseService, objectMapper, topic, payloadType, metrics, configuration, ...)
      [peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxConsumer.java, line 162]
      schemaName = configuration != null ? configuration.getDatabaseConfig().getSchema() : "public";
```

**Key facts established by code inspection:**
- `PgConnectionConfig.getSchema()` returns `null` when the schema field is not set in the builder. There is no default. Field declared as `private String schema;` at line 42 — Java default is `null`.
- `PeeGeeQConfiguration.getDatabaseConfig()` passes `null` as the default to `getString("peegeeq.database.schema", null)`. So if the property is not in the configuration source, schema = `null`.
- `PeeGeeQTestConfig.Builder` defaults `schema = "public"` and always sets `peegeeq.database.schema` in the built `Properties`. Integration tests using `PeeGeeQTestConfig` always have an explicit schema.
- `PgDatabaseService.getPeeGeeQConfiguration()` exists at line 208 and returns `manager.getConfiguration()`. It is NOT currently used by `OutboxFactoryRegistrar`.
- Flyway migrations (`V001__Create_Base_Tables.sql`) create all tables WITHOUT schema prefix. Tables land in whatever schema the connection's `search_path` resolves to at migration time.

---

## Pre-change vs post-change SQL behaviour

| Scenario | Before Step 7 | After Step 7 (current state) |
|---|---|---|
| `OutboxConsumer` — schema configured | Unqualified: `FROM outbox` | `"myschema".outbox` ✓ |
| `OutboxConsumer` — no configuration (null) | Unqualified: `FROM outbox` | `"public".outbox` (hard fallback) |
| `OutboxConsumer` — configuration present, schema property not set | Unqualified: `FROM outbox` | NullPointerException ✗ |
| `OutboxFactory.getStats/countMessages/purgeMessages/createBrowser` — schema configured | `"myschema".outbox` | `"myschema".outbox` ✓ (unchanged) |
| `OutboxFactory` utility methods — no configuration | `"peegeeq".outbox` | `"peegeeq".outbox` (unchanged) |
| `OutboxQueueBrowser` — no schema argument | `"peegeeq".outbox` | `"peegeeq".outbox` (unchanged) |

---

## Issue 1 — NullPointerException when `peegeeq.database.schema` is not set

**Severity:** Critical (runtime crash)

When a user creates `PeeGeeQConfiguration` from `Properties` that do NOT include `peegeeq.database.schema`:
- `configuration.getDatabaseConfig().getSchema()` returns `null`
- `OutboxConsumer.schemaName = null` (because `configuration != null` → left branch taken)
- Every SQL poll calls `quoteIdentifier(schemaName)` which is:

```java
// OutboxConsumer.java, line 364
private static String quoteIdentifier(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
}
```

`null.replace(...)` → `NullPointerException` on the first polling cycle. The consumer starts but crashes immediately.

**Affected callers:** All 9+ SQL-building paths in `OutboxConsumer` — `pollMessages`, `markMessageCompleted`, `resetFilteredMessageToPending`, `handleMessageFailureWithRetry`, `incrementRetryAndReset`, `storeDeadLetterMessage` (2 statements inside a transaction), and `processConsumerGroupMessage`.

---

## Issue 2 — Inconsistent fallbacks across outbox classes

**Severity:** Medium (silent wrong-schema query, no crash, wrong results)

After step 7, the three outbox classes that build SQL with a schema have diverged:

| Class | Fallback when schema is null/unconfigured | Location |
|---|---|---|
| `OutboxConsumer` | `"public"` | constructor, line 122 and 162 |
| `OutboxFactory.createBrowser()` | `"peegeeq"` | line 318 |
| `OutboxFactory.getStats()` | `"peegeeq"` | line 371 |
| `OutboxFactory.countMessages()` | `"peegeeq"` | line 425 |
| `OutboxFactory.purgeMessages()` | `"peegeeq"` | line 448 |
| `OutboxQueueBrowser` (no-arg constructor) | `"peegeeq"` | line 65 |

A deployment that creates an `OutboxFactory` via `PgQueueFactoryProvider()` (no-arg) + empty config map and creates tables in the `"public"` schema (PostgreSQL default) will find that `OutboxConsumer` can poll (`"public".outbox`) but `factory.getStats(topic)`, `factory.countMessages(topic)`, and `factory.purgeMessages(topic)` will fail or return wrong data because they query `"peegeeq".outbox`.

This is the exact situation of the example integration tests.

---

## Issue 3 — Breaking change for deployments that relied on unqualified SQL

**Severity:** High (existing deployments broken silently or with table-not-found errors)

Before step 7, `OutboxConsumer` used entirely unqualified SQL. PostgreSQL resolves unqualified table references using the session's `search_path`. Users who:
- Deploy PeeGeeQ in a non-`"public"` schema (e.g. `SET search_path TO myapp`)
- Do NOT set `peegeeq.database.schema` explicitly
- Create their `PgQueueFactoryProvider` without a `PeeGeeQConfiguration`

will now receive `relation "public.outbox" does not exist` errors on every poll, whereas before they received results correctly.

The Flyway migration itself also uses unqualified DDL — tables are created wherever `search_path` points. The post-step-7 consumer is inconsistent with this.

---

## Issue 4 — OutboxFactoryRegistrar does not extract config from PgDatabaseService

**Severity:** Low (architectural gap, minor usability issue)

`PgDatabaseService.getPeeGeeQConfiguration()` (line 208) exposes the configuration from the underlying `PeeGeeQManager`. When users start PeeGeeQ via `PeeGeeQManager` and then create a factory provider from the resulting `PgDatabaseService`, the configuration is available on the service object.

`OutboxFactoryRegistrar` currently only looks for `peeGeeQConfiguration` in the options map (line 70). It does not fall back to `databaseService.getPeeGeeQConfiguration()`. This means callers who use the two-argument `createFactory(type, databaseService)` or pass an empty map always get `peeGeeQConfig = null` in the factory, even though the configuration is reachable via the service.

---

## Remediation plan

### Guiding principle

When `peegeeq.database.schema` is not configured (schema is null), `OutboxConsumer`, `OutboxFactory`, and `OutboxQueueBrowser` must use **unqualified table references** — the same behavior as before step 7. This preserves backward compatibility with `search_path`-based deployments and with the Flyway migration behavior.

When schema IS configured (non-null), schema-qualified references must be used — this is the multi-tenant isolation guarantee from step 7.

---

### Step A — Fix `OutboxConsumer`: null schema → unqualified SQL

In both constructors (lines 122 and 162), change:
```java
this.schemaName = configuration != null ? configuration.getDatabaseConfig().getSchema() : "public";
```
to:
```java
this.schemaName = configuration != null ? configuration.getDatabaseConfig().getSchema() : null;
```

Add a private helper to replace `quoteIdentifier(schemaName) + "."` call sites:
```java
private String qt(String table) {
    return schemaName != null
        ? quoteIdentifier(schemaName) + ".\"" + table + "\""
        : table;
}
```

Replace all schema-injection call sites in `OutboxConsumer` using `qt("outbox")` and `qt("dead_letter_queue")`. Affected methods: `pollMessages`, `markMessageCompleted`, `resetFilteredMessageToPending`, `handleMessageFailureWithRetry` (selectSql), `incrementRetryAndReset`, `storeDeadLetterMessage` (selectSql + insertSql + updateSql), `processConsumerGroupMessage`.

---

### Step B — Fix `OutboxFactory`: null schema → unqualified SQL

Add a private null-safe helper:
```java
private String qt(String table) {
    String schema = configuration != null ? configuration.getDatabaseConfig().getSchema() : null;
    return schema != null ? quoteIdentifier(schema) + ".\"" + table + "\"" : table;
}
```
Apply to: `createBrowser()` (line 318), `getStats()` (line 371), `countMessages()` (line 425), `purgeMessages()` (line 448).

The existing `static quoteIdentifier()` method must be preserved — `OutboxQueueBrowser` calls it as `OutboxFactory.quoteIdentifier(schema)`.

---

### Step C — Fix `OutboxQueueBrowser`: null schema → unqualified SQL

In the two-argument constructor (line 65):
```java
this.schema = schema != null ? schema : "peegeeq";
```
change to:
```java
this.schema = schema;  // null = unqualified SQL
```

In `browse()`, replace the `.formatted(OutboxFactory.quoteIdentifier(schema))` schema injection with a null-safe prefix:
```java
String schemaPrefix = schema != null ? OutboxFactory.quoteIdentifier(schema) + "." : "";
// SQL template becomes: "SELECT ... FROM " + schemaPrefix + "outbox WHERE ..."
```

---

### Step D — Fix `OutboxFactoryRegistrar`: fall back to `PgDatabaseService.getPeeGeeQConfiguration()`

In `OutboxFactoryCreator.create()`, after the existing map extraction block (after line 73), add:
```java
if (peeGeeQConfig == null && databaseService instanceof PgDatabaseService pgDs) {
    peeGeeQConfig = pgDs.getPeeGeeQConfiguration();
}
```

This correctly propagates the configuration for users who construct `PgQueueFactoryProvider(peeGeeQConfig)` or start via `PeeGeeQManager` and use the resulting `PgDatabaseService`.

---

### Step E — Verify multi-tenant isolation tests still pass

`DlqMultiTenantSchemaIsolationTest` (TC-7a, TC-7b) provides explicit schema via `PeeGeeQConfiguration`. With the fix applied:
- `configuration.getDatabaseConfig().getSchema()` returns `"tenant_a_schema"` (non-null)
- `qt("outbox")` → `"tenant_a_schema"."outbox"` ← schema-qualified, correct
- All 6 tests must continue to pass unchanged.

---

## Files to be changed

| File | Changes |
|---|---|
| `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxConsumer.java` | `schemaName` null logic (lines 122, 162); add `qt()` helper; update all 9+ SQL call sites |
| `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxFactory.java` | Add `qt()` helper; update 4 SQL call sites (lines 318, 371, 425, 448) |
| `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxQueueBrowser.java` | Fix null default (line 65); fix schema injection in `browse()` SQL |
| `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxFactoryRegistrar.java` | Add fallback config extraction from `PgDatabaseService` (after line 73) |

---

## Verification

1. Banned-pattern grep on every changed file: `\.recover\(|\.otherwise\(|CompletableFuture|toCompletionStage|Thread\.sleep|\.join\(\)`. Expected: zero matches.
2. Null-safety check: grep `quoteIdentifier(schemaName)` in `OutboxConsumer.java` after the refactor — must return zero results (all calls replaced by `qt()`).
3. Integration tests that must pass:
   - `DlqMultiTenantSchemaIsolationTest` — 6 tests, explicit schema, schema-qualified SQL expected
   - `FilterExceptionFrameworkRetryIntegrationTest` — null schema path through examples
   - `SystemPropertiesConfigurationExampleTest` — no-config factory, unqualified SQL expected
   - `OutboxConsumerCrashRecoveryTest`, `ReactiveOutboxProducerTest` — baseline regression
4. Full suite: `mvn clean test -Pall-tests 2>&1 | Tee-Object -FilePath logs\all-tests-YYYYMMDD.txt`

---

## Scope boundary

**Included:** `OutboxConsumer`, `OutboxFactory`, `OutboxQueueBrowser`, `OutboxFactoryRegistrar` production code only.

**Excluded:**
- Flyway migration changes (migrations intentionally use unqualified DDL)
- `peegeeq-native` module (uses its own schema handling, no regression)
- Spring Boot auto-configuration changes (Spring Boot defaults `schema = "public"` and always sets the property)

---

## Complete database access inventory for peegeeq-outbox

### Definitive class inventory — all 14 production classes

Every class in `peegeeq-outbox/src/main/java` is listed here. This is the single authoritative reference.

| Class | DB access | SQL schema | Bugs |
|---|---|---|---|
| `OutboxConsumer` | YES | Qualified — `quoteIdentifier(schemaName)` | **BUG A**: `schemaName = config != null ? config.getDatabaseConfig().getSchema() : "public"` → when config=null, hardcodes `"public"`, breaks search_path. **BUG B**: when config!=null but `getSchema()` returns null → `schemaName=null` → NPE in `quoteIdentifier` |
| `OutboxFactory` | YES (stats, count, purge, browser SQL) | Qualified — `quoteIdentifier(config != null ? config.getSchema() : "peegeeq")` | **BUG C**: when config=null, hardcodes `"peegeeq"` (inconsistent with `"public"` in Consumer). **BUG B**: when schema=null → NPE |
| `OutboxQueueBrowser` | YES | Qualified — `OutboxFactory.quoteIdentifier(schema)` | **BUG D**: constructor `schema != null ? schema : "peegeeq"` hardcodes `"peegeeq"` fallback |
| `OutboxFactoryRegistrar` | NO (no SQL) | — | **BUG E**: does not fall back to `PgDatabaseService.getPeeGeeQConfiguration()` → passes null config to OutboxFactory → triggers BUG C |
| `OutboxProducer` | YES | **Unqualified** (intentional — search_path design) | NONE — correct |
| `OutboxConsumerGroup` | NO (delegates to OutboxConsumer + peegeeq-db) | — | NONE — configuration passed through correctly |
| `OutboxConsumerGroupMember` | NO | — | NONE |
| `OutboxConsumerConfig` | NO | — | NONE |
| `OutboxQueue` | YES (creates own raw pool) | Unqualified `tableName` | **LATENT**: bypasses `PgConnectionManager` — no `search_path` injection. All SQL methods are stubs (no SQL executed). Not used in production. Schema-blind if ever implemented. |
| `OutboxMessage` | NO | — | NONE |
| `PgNotificationStream` | NO | — | NONE |
| `MessageFilteredException` | NO | — | NONE |
| `FilterCircuitBreaker` | NO | — | NONE |
| `FilterErrorHandlingConfig` | NO | — | NONE |

**Summary of bugs affecting production:** 5 bugs (A–E) across 4 classes (`OutboxConsumer`, `OutboxFactory`, `OutboxQueueBrowser`, `OutboxFactoryRegistrar`). All are introduced or exposed by Step 7 schema qualification.

**`OutboxQueue` latent risk:** Not a current regression. If the stub methods are ever implemented, the raw pool will not have `search_path` set and will be schema-blind. Must be addressed before implementation.

---

This section catalogs every SQL statement in every class in `peegeeq-outbox/src/main/java` so that no database access point is missed during testing or remediation.

### OutboxConsumer — 9 schema-qualified SQL call sites (all via `schemaName` field)

All SQL is built at runtime using `quoteIdentifier(schemaName)`. With the fix applied, `schemaName = null` → unqualified table reference.

| Method | SQL shape | Tables touched |
|---|---|---|
| `processAvailableMessages()` — no filter | `UPDATE %s.outbox ... WHERE id IN (SELECT id FROM %s.outbox ...)` | `outbox` (×2) |
| `processAvailableMessages()` — server-side filter | `UPDATE %s.outbox ... WHERE id IN (SELECT id FROM %s.outbox ... AND <filter>)` | `outbox` (×2) |
| `markMessageFailed()` | `UPDATE %s.outbox SET status = 'FAILED' ...` | `outbox` |
| `markMessageCompleted()` | `UPDATE %s.outbox SET status = 'COMPLETED' ...` | `outbox` |
| `resetFilteredMessageToPending()` | `UPDATE %s.outbox SET status = 'PENDING' ...` | `outbox` |
| `handleMessageFailureWithRetry()` | `SELECT retry_count, max_retries FROM %s.outbox WHERE id = $1` | `outbox` |
| `incrementRetryAndReset()` | `UPDATE %s.outbox SET retry_count = $1, status = 'PENDING' ...` | `outbox` |
| `storeDeadLetterMessage()` — select | `SELECT topic, payload, ... FROM %s.outbox WHERE id = $1` | `outbox` |
| `storeDeadLetterMessage()` — insert+update (transaction) | `INSERT INTO %s.dead_letter_queue (...)` + `UPDATE %s.outbox SET status = 'DEAD_LETTER' ...` | `dead_letter_queue`, `outbox` |

**Schema source:** `this.schemaName` set in constructor from `configuration.getDatabaseConfig().getSchema()`.

**`quoteIdentifier()` method:** `private static String quoteIdentifier(String identifier)` at line 364. No null check — NPE if `identifier` is null.

---

### OutboxFactory — 4 SQL call sites (inline schema, fallback `"peegeeq"` — BUG)

All SQL is built inline at each call site using `quoteIdentifier(configuration != null ? configuration.getDatabaseConfig().getSchema() : "peegeeq")`.

| Method | SQL shape | Tables touched |
|---|---|---|
| `getStats(topic)` | `SELECT COUNT(*) ... FROM %s.outbox WHERE topic = $1` | `outbox` |
| `countMessages(topic)` | `SELECT COUNT(*) AS total FROM %s.outbox WHERE topic = $1` | `outbox` |
| `purgeMessages(topic)` | `DELETE FROM %s.outbox WHERE topic = $1` | `outbox` |
| `createBrowser(topic, payloadType)` | No SQL here — passes schema to `OutboxQueueBrowser` constructor | — |

**Schema source:** Inline ternary at each call site. Fallback is `"peegeeq"` when `configuration == null`. This is inconsistent with the documented default (`"public"` from `peegeeq-default.properties`) and with the `OutboxConsumer` fallback.

**`isHealthy()`:** Delegates to `databaseService.getConnectionProvider().isHealthy()` — no direct SQL, no schema concern.

**`close()` / `closeTrackedResourcesAsync()`:** No SQL — closes resources only.

---

### OutboxQueueBrowser — 1 SQL call site (schema via constructor arg, fallback `"peegeeq"` — BUG)

| Method | SQL shape | Tables touched |
|---|---|---|
| `browse(limit, offset)` | `SELECT id, payload, headers, created_at, status, correlation_id FROM %s.outbox WHERE topic = $1 ORDER BY id DESC LIMIT $2 OFFSET $3` | `outbox` |

**Schema source:** `this.schema` set in constructor. No-arg constructor defaults to `"peegeeq"`. Two-arg constructor: `this.schema = schema != null ? schema : "peegeeq"` — null is never stored, preventing the unqualified fallback.

**`OutboxFactory.quoteIdentifier(schema)` called from `browse()`** — this is a `package-private static` method on `OutboxFactory` used by `OutboxQueueBrowser`. Both classes are in the same package.

---

### OutboxProducer — 1 SQL call site (intentionally UNQUALIFIED)

```java
private static final String OUTBOX_INSERT_SQL = """
    INSERT INTO outbox (topic, payload, headers, correlation_id, message_group, created_at, status, idempotency_key)
    VALUES ($1, $2::jsonb, $3::jsonb, $4, $5, $6, 'PENDING', $7)
    ON CONFLICT (topic, idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING
    """;
```

Used in three methods: `send()`, `sendWithExternalTransaction()`, `sendInCurrentTransaction()`.

**This is correct and must NOT be changed.** The producer has no `PeeGeeQConfiguration` dependency. Schema isolation is applied at the connection level: `PgConnectionManager.createReactivePool()` sets the `search_path` PostgreSQL startup parameter on every connection when `peegeeq.database.schema` is configured. All unqualified SQL from this connection then resolves to the correct schema automatically.

**Design consequence:** In a correctly configured multi-tenant deployment, the producer and consumer point to the same table because:
- Producer → unqualified `INSERT INTO outbox` → resolved by connection `search_path = "tenant_a"`
- Consumer → qualified `"tenant_a".outbox` → explicit

Both target `tenant_a.outbox`. They are consistent.

---

### Classes with NO database access

| Class | Status | Notes |
|---|---|---|
| `PgNotificationStream` | Active | ReadStream adapter only. Dispatches pre-received events. No SQL. No LISTEN/NOTIFY issued here — that is a future placeholder. |
| `OutboxConsumerGroup` | Active | No direct SQL. `start()` and `startInternal()` create an `OutboxConsumer<T>` passing `this.configuration` — schema flows correctly. `start(SubscriptionOptions)` calls `databaseService.getSubscriptionService().subscribe()` which is in `peegeeq-db`, not outbox. |
| `OutboxConsumerGroupMember` | Active | Routing and filtering. Uses `FilterCircuitBreaker` for filter-failure circuit breaking. No SQL. |
| `FilterCircuitBreaker` | **Active — used by `OutboxConsumerGroupMember`** | In-memory state machine. `OutboxConsumerGroupMember` holds a `FilterCircuitBreaker` field and calls `allowRequest()`, `recordSuccess()`, `recordFailure()`, `reset()`. No SQL. This is NOT dead code. |
| `FilterErrorHandlingConfig` | **Active — used by `FilterCircuitBreaker`** | Configuration value object for circuit breaker thresholds, timeouts, retry counts. `FilterCircuitBreaker(String filterId, FilterErrorHandlingConfig config)` stores and reads it. No SQL. This is NOT dead code. |
| `OutboxConsumerConfig` | Active | Consumer configuration value object. No SQL. |
| `OutboxMessage` | Active | Data holder. No SQL. |
| `OutboxQueue` | Active — stub | Concrete class implementing `ReactiveQueue<T>`. Creates its own raw `Pool.pool(vertx, connectOptions, poolOptions)` — **bypasses `PgConnectionManager` entirely, no `search_path` injection**. Uses unqualified `tableName` field. All SQL-executing methods (`send`, `acknowledge`, `receive`) are stubs that complete immediately without executing SQL. Not used in any production code path. If ever implemented, it would be schema-blind. No current schema regression risk. |
| `OutboxFactoryRegistrar` | Active | Creates `OutboxFactory`; no SQL of its own. |
| `MessageFilteredException` | Active | Exception class. No SQL. |
| `deadletter/` package (outbox module) | **DELETED** | The `DeadLetterQueue` interface, `LoggingDeadLetterQueue`, and outbox `DeadLetterQueueManager` were deleted from the working directory as Step 1 of `OUTBOX-DLQ-FILTER-ERRORS-DEAD-CODE-AUDIT.md`. They still appear in git HEAD (`git:/...` URI) but do NOT exist on disk. None had database access. No schema concern. Do NOT recreate. |

---

### Related component: StuckMessageRecoveryManager (peegeeq-db module)

`StuckMessageRecoveryManager` is in `peegeeq-db`, not `peegeeq-outbox`, but it is tested from `StuckMessageRecoveryIntegrationTest` inside the outbox module and it operates on the `outbox` table.

Its SQL uses unqualified `outbox` table references and relies on connection-level `search_path`. It does **not** accept a `PeeGeeQConfiguration` argument and has no schema injection. This is the same approach as `OutboxProducer` — intentional, and correct given that its pool is provided by the caller who controls the `search_path`.

**Risk:** If a user constructs `StuckMessageRecoveryManager` with a pool whose `search_path` is not set, and the tables are in a non-default schema, recovery will fail silently with empty result sets. This is a pre-existing limitation, not introduced by step 7.

---

### Related component: DeadLetterQueueManager (peegeeq-db module)

`DeadLetterQueueManager` is in `peegeeq-db/src/main/java/dev/mars/peegeeq/db/deadletter/`. It implements `DeadLetterService` and is wired by `PeeGeeQManager`. It has **ALL UNQUALIFIED SQL** — it relies entirely on connection-level `search_path`. It does NOT accept a `PeeGeeQConfiguration` argument; its only constructor takes `Pool reactivePool` and `ObjectMapper`.

| Method | SQL shape | Tables touched |
|---|---|---|
| `storeDeadLetterMessage()` | `INSERT INTO dead_letter_queue (...)` | `dead_letter_queue` |
| `fetchStatistics()` | `SELECT COUNT(*) ... FROM dead_letter_queue` | `dead_letter_queue` |
| `fetchDeadLetterMessagesByTopic()` | `SELECT ... FROM dead_letter_queue WHERE topic = $1` | `dead_letter_queue` |
| `fetchAllDeadLetterMessages()` | `SELECT ... FROM dead_letter_queue ORDER BY failed_at DESC` | `dead_letter_queue` |
| `fetchDeadLetterMessage(id)` | `SELECT ... FROM dead_letter_queue WHERE id = $1` | `dead_letter_queue` |
| `reprocessDeadLetterMessageRecord()` — fetch | `SELECT ... FROM dead_letter_queue WHERE id = $1 FOR UPDATE` | `dead_letter_queue` |
| `reprocessDeadLetterMessageRecord()` — re-insert | `INSERT INTO outbox (...)` **or** `INSERT INTO queue_messages (...)` based on `original_table` | `outbox` or `queue_messages` |
| `reprocessDeadLetterMessageRecord()` — delete | `DELETE FROM dead_letter_queue WHERE id = $1` | `dead_letter_queue` |
| `removeDeadLetterMessage()` | `DELETE FROM dead_letter_queue WHERE id = $1` | `dead_letter_queue` |
| `purgeOldDeadLetterMessages()` | `DELETE FROM dead_letter_queue WHERE failed_at < NOW() - ($1 * INTERVAL '1 day')` | `dead_letter_queue` |

**Design contract:** `DeadLetterQueueManager` uses the same `search_path`-based schema resolution as `OutboxProducer`. The pool passed to its constructor must have `search_path` set to the correct tenant schema for all queries to resolve correctly. When `PeeGeeQManager` creates it, the pool comes from `PgConnectionManager` which sets `search_path` from `peegeeq.database.schema`.

**Consistency gap:** `OutboxConsumer.storeDeadLetterMessage()` writes to `"%s".dead_letter_queue` (schema-qualified). `DeadLetterQueueManager` reads from unqualified `dead_letter_queue`. These are consistent ONLY when the same pool (same `search_path`) is used for both. If a user creates `DeadLetterQueueManager` with a pool from a different `PeeGeeQManager` instance, or with a pool with a different `search_path`, the records written by `OutboxConsumer` will not be found by `DeadLetterQueueManager`.

**Reprocess path risk:** `reprocessDeadLetterMessageRecord()` calls `getInsertSqlForTable(dlm.getOriginalTable())` which generates `INSERT INTO outbox (...)` or `INSERT INTO queue_messages (...)` — both unqualified. In a multi-tenant scenario, this re-insert goes to whatever schema `search_path` points to. If the pool is configured correctly (same `search_path` as the consumer's schema), this is correct. No dedicated test exists for this path with non-default schema.

**Note:** This is a SEPARATE class from the outbox `deadletter.DeadLetterQueueManager` which was DELETED from the working directory (see dead code note above). Only the `peegeeq-db` version exists on disk.

---

### Related component: Partitioned consumption sub-system (peegeeq-db/consumer/)

When `OutboxConsumerGroup` is started with an `OFFSET_WATERMARK` topic (i.e. when `connectionManager` is wired), it creates a `PartitionedConsumerEngine` and delegates all message fetching, offset management, partition assignment, and completion tracking to classes in `peegeeq-db/src/main/java/dev/mars/peegeeq/db/consumer/`. Every class in this sub-system uses **exclusively unqualified SQL** and relies on connection-level `search_path`.

| Class | Tables touched (all unqualified) | Key operations |
|---|---|---|
| `PartitionedConsumerEngine` | `outbox_topics` | `SELECT completion_tracking_mode FROM outbox_topics WHERE topic = $1` |
| `PartitionedFetcher` | `outbox`, `outbox_partition_offsets` | `SELECT ... FROM outbox ... FOR UPDATE SKIP LOCKED` (fetches messages); `UPDATE outbox_partition_offsets` (commit offset) |
| `PartitionAssignmentService` | `outbox`, `outbox_partition_assignments`, `outbox_partition_offsets`, `outbox_topic_subscriptions` | Discovers partition keys from `outbox`; INSERT/UPDATE/DELETE on assignment and offset tables |
| `PartitionedOffsetManager` | `outbox_partition_offsets` | INSERT/UPDATE/SELECT offsets (3 UPDATE forms for fence, commit, rollback) |
| `WatermarkCalculator` | `outbox_partition_offsets`, `outbox_topic_watermarks`, `outbox` | Calculates min offset; `INSERT INTO outbox_topic_watermarks`; **`UPDATE outbox SET status = 'COMPLETED'`** |
| `CompletionTracker` | `outbox`, `outbox_topic_subscriptions` | `SELECT ... FROM outbox JOIN outbox_topic_subscriptions`; tracking INSERT; **`UPDATE outbox SET ...`** |
| `ConsumerGroupFetcher` | `outbox`, `outbox_topic_subscriptions` | `SELECT ... FROM outbox INNER JOIN outbox_topic_subscriptions` (non-partitioned consumer group fetch) |
| `ConsumerGroupRetryService` | `outbox` | `SELECT ... FROM outbox` for stuck/stale message retry |
| `SubscriptionManager` | `outbox`, `outbox_topic_subscriptions` | `SELECT MAX(id) FROM outbox`; INSERT/UPDATE/DELETE on `outbox_topic_subscriptions` |

**Critical parallel completion path:** `WatermarkCalculator` has `UPDATE outbox SET status = 'COMPLETED' ...` (unqualified). This is a SECOND code path that marks outbox messages as completed, alongside `OutboxConsumer.markMessageCompleted()` (schema-qualified). In OFFSET_WATERMARK mode, the watermark sweeper — not the consumer — is responsible for completing messages. If the watermark calculator's pool has `search_path = tenant_a`, it correctly updates `tenant_a.outbox`. If not, it targets the wrong schema.

**Design:** All classes receive their pool via `PgConnectionManager`, which sets `search_path` from `peegeeq.database.schema`. When configuration is correct, this whole sub-system resolves to the right schema. This is the same design as `OutboxProducer`. **No code changes to these classes are needed.** However, no integration test verifies the OFFSET_WATERMARK code path with a non-default schema.

---

## Test coverage analysis

### What is covered today

| Test class | What it tests | Schema handling |
|---|---|---|
| `DlqMultiTenantSchemaIsolationTest` | TC-7a: DLQ isolation per tenant; TC-7b: cross-tenant non-contamination | Explicit `SCHEMA_A = "tenant_a_schema"`, `SCHEMA_B = "tenant_b_schema"` via `PeeGeeQTestConfig.builder().schema(...)` |
| `MultiTenantSchemaIsolationTest` | Multi-tenant message isolation (consumer path) | Two tenant schemas |
| `OutboxSchemaQuotingTest` | Schema names that are SQL reserved words (e.g. `"order"`) | SQL reserved word schemas via `createSchemaWithQuotedDDL()` |
| `OutboxConsumerCrashRecoveryTest` | Consumer crash, stuck message recovery | Default schema (`"public"`) from `PeeGeeQTestConfig` |
| `ReactiveOutboxProducerTest` | Producer send + consumer receive roundtrip | Default schema |
| `StuckMessageRecoveryIntegrationTest` | `StuckMessageRecoveryManager` recovery of PROCESSING messages | Default schema — uses unqualified SQL via `search_path` |
| `OutboxQueueBrowserIntegrationTest` | `OutboxQueueBrowser.browse()` | Default schema only |
| `OutboxFactoryIntegrationTest` | Factory create/consumer/producer lifecycle | Default schema |
| All other `Outbox*Test` classes | Consumer, producer, consumer group, DLQ, metrics, etc. | Default schema (`"public"`) — no explicit schema coverage |

### Gaps — what is NOT covered

The following database operations have NO test with an explicitly configured non-default schema:

| Gap | Class / Method | Risk |
|---|---|---|
| **G1** | `OutboxFactory.getStats()` — schema-qualified SQL | Wrong schema returns zero stats; counts for wrong tenant |
| **G2** | `OutboxFactory.countMessages()` — schema-qualified SQL | Wrong schema returns zero count; no error thrown |
| **G3** | `OutboxFactory.purgeMessages()` — schema-qualified SQL | Purge hits wrong schema; silently does nothing or deletes wrong data |
| **G4** | `OutboxFactory.createBrowser()` — schema propagation to `OutboxQueueBrowser` | Browser targets wrong schema |
| **G5** | `OutboxQueueBrowser.browse()` with non-default schema | Browse returns messages from wrong schema |
| **G6** | Producer (unqualified) + Consumer (schema-qualified) roundtrip with explicit schema | Schema mismatch between producer and consumer means messages never found |
| **G7** | Unqualified fallback path (null schema → unqualified SQL) | Regression: consumer uses `"public".outbox` hard-coded instead of unqualified |
| **G8** | `OutboxFactoryRegistrar` schema fallback from `PgDatabaseService` | Schema from manager not propagated to factory via registrar |
| **G9** | `OutboxConsumerGroup.start()` with explicit schema | Consumer group creates underlying consumer with correct schema |
| **G10** | `OutboxConsumer.markMessageFailed()` with explicit schema | Failed messages written to wrong schema; re-processed indefinitely |
| **G11** | `OutboxConsumer.resetFilteredMessageToPending()` with explicit schema | Filtered messages not reset; get stuck in PROCESSING |
| **G12** | `OutboxConsumer.storeDeadLetterMessage()` with explicit schema — the `dead_letter_queue` INSERT specifically | DLQ entries written to wrong schema (`"public".dead_letter_queue` ≠ `"tenant_a".dead_letter_queue`) — this is TC-7a's coverage but only via the full end-to-end path |
| **G13** | `OutboxConsumer.processAvailableMessages()` server-side filter path with explicit schema | Filtered poll uses `sq + ".outbox"` string concatenation — separate code path from the formatted() sites |
| **G14** | `DeadLetterQueueManager.reprocessDeadLetterMessageRecord()` — re-insert into `outbox` (unqualified) | Reprocessed DLQ message inserted into correct schema only if `search_path` is set on the pool; no multi-tenant test exists |
| **G15** | `DeadLetterQueueManager.purgeOldDeadLetterMessages()` with explicit schema | Purge operates on `dead_letter_queue` of whatever schema `search_path` resolves to; no multi-tenant isolation test |
| **G16** | `OutboxConsumer.storeDeadLetterMessage()` (qualified write) + `DeadLetterQueueManager.getDeadLetterMessages()` (unqualified read) round-trip with explicit schema | DLQ record written by consumer to `"tenant_a".dead_letter_queue` must be readable by manager using same pool; no dedicated test for this path |
| **G17** | `SubscriptionManager.subscribe()` with explicit schema (tables: `outbox`, `outbox_topic_subscriptions`) | Subscription registration reads `MAX(id) FROM outbox` and inserts into `outbox_topic_subscriptions` — both unqualified; resolves via `search_path`; no multi-tenant test exists for `OutboxConsumerGroup.start(SubscriptionOptions)` with non-default schema |
| **G18** | `PartitionedConsumerEngine.isOffsetWatermarkTopic()` + entire OFFSET_WATERMARK fetch/commit/completion sub-system with explicit schema | 8 classes (`PartitionedConsumerEngine`, `PartitionedFetcher`, `PartitionAssignmentService`, `PartitionedOffsetManager`, `WatermarkCalculator`, `CompletionTracker`, `ConsumerGroupFetcher`, `ConsumerGroupRetryService`) all use unqualified SQL on `outbox`, `outbox_topics`, `outbox_topic_subscriptions`, `outbox_partition_offsets`, `outbox_partition_assignments`, `outbox_topic_watermarks`; no multi-tenant test for the OFFSET_WATERMARK code path at all |
| **G19** | `WatermarkCalculator.UPDATE outbox SET status = 'COMPLETED'` — parallel completion path with explicit schema | This is a SECOND, separate code path that marks `outbox` messages as COMPLETED (alongside `OutboxConsumer.markMessageCompleted()`). In OFFSET_WATERMARK mode the watermark sweeper — not the consumer — completes messages. No test verifies this path targets the correct schema. A misconfigured pool would silently complete messages in the wrong schema. |

---

## Test coverage plan

New integration tests must be written in `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/`. All must be `@Tag(TestCategories.INTEGRATION)` + `@Testcontainers`. All must use `PeeGeeQTestConfig.builder().from(container).schema(...)` and `PeeGeeQTestSchemaInitializer` with explicit non-default schema names.

**Suggested test class name:** `OutboxSchemaIsolationCoverageTest`

---

### TC-S1 — `OutboxFactory.getStats()` with explicit schema (covers G1)

**Arrange:** Two separate schemas (`test_schema_a`, `test_schema_b`). Insert 3 messages into `test_schema_a.outbox`, 0 into `test_schema_b.outbox`. Create `OutboxFactory` configured with `test_schema_a`.

**Act:** `factory.getStats(topic)`

**Assert:**
- `stats.getTotalMessages()` = 3
- Create second factory configured with `test_schema_b`. Its `getStats(topic)` must return 0.
- Neither stats call throws an exception.

---

### TC-S2 — `OutboxFactory.countMessages()` with explicit schema (covers G2)

**Arrange:** 5 messages in `test_schema_a.outbox`, 0 in `test_schema_b.outbox`.

**Act:** `factory_a.countMessages(topic)` and `factory_b.countMessages(topic)`.

**Assert:** `factory_a` returns 5. `factory_b` returns 0.

---

### TC-S3 — `OutboxFactory.purgeMessages()` purges only the correct schema (covers G3)

**Arrange:** 4 messages in `test_schema_a.outbox`, 2 in `test_schema_b.outbox`.

**Act:** `factory_a.purgeMessages(topic)` — purges schema A.

**Assert:**
- Return value = 4 (rows deleted).
- `test_schema_a.outbox` is empty.
- `test_schema_b.outbox` still has 2 rows — cross-schema contamination guard.

---

### TC-S4 — `OutboxQueueBrowser.browse()` via `OutboxFactory.createBrowser()` with explicit schema (covers G4, G5)

**Arrange:** 3 messages in `test_schema_a.outbox`, 2 different messages in `test_schema_b.outbox`.

**Act:** `factory_a.createBrowser(topic, String.class)` then `browser.browse(10, 0)`.

**Assert:**
- 3 messages returned, matching schema A content.
- IDs do not overlap with schema B messages.

---

### TC-S5 — Producer (unqualified) + Consumer (schema-qualified) roundtrip with explicit schema (covers G6)

This is the critical integration test confirming the producer/consumer design contract works end-to-end when `search_path` is set at connection level.

**Arrange:**
- Schema `test_schema_roundtrip`. Tables initialized via `PeeGeeQTestSchemaInitializer`.
- `PeeGeeQConfiguration` with `schema = "test_schema_roundtrip"`.
- `PeeGeeQManager.start()` — this causes `PgConnectionManager` to set `search_path = test_schema_roundtrip` on all pool connections.
- `OutboxProducer` created from the same pool — no schema in SQL.
- `OutboxConsumer` created from the same configuration — uses `"test_schema_roundtrip".outbox` in all SQL.

**Act:** Producer sends 1 message. Consumer polls and processes it.

**Assert:** Message handler receives the message. `outbox` row moves to COMPLETED. No `relation does not exist` errors.

---

### TC-S6 — Null schema path: unqualified SQL when no schema configured (covers G7)

This test validates the backward-compatible null-schema path after the fix (Steps A–C are applied).

**Arrange:**
- `PeeGeeQTestConfig.builder().from(container)` — but explicitly `.schema(null)` OR omit the schema call to produce null.
- Tables initialized in `public` schema (PostgreSQL default).
- Verify that the built `Properties` does NOT set `peegeeq.database.schema`.

**Act:** Create `OutboxConsumer` via this config. Send a message (unqualified INSERT). Poll.

**Assert:**
- Consumer finds the message.
- No `NullPointerException`.
- No schema-qualified SQL is issued — verify via log output or by checking that `"public".outbox` does NOT appear in SQL (test only the unqualified path works, not the exact SQL form).

**Note:** This test can only be written AFTER Steps A–C (the null-schema fix) are applied. Before the fix, this path would NPE.

---

### TC-S7 — `OutboxFactoryRegistrar` schema propagation from `PgDatabaseService` (covers G8)

**Arrange:**
- `PeeGeeQManager` started with `schema = "test_schema_registrar"`.
- `PgDatabaseService` obtained from manager.
- `OutboxFactoryRegistrar` used to create the factory — no explicit config in the map.

**Act:** `factory.getStats(topic)` — verifies schema is correctly applied.

**Assert:**
- No `relation does not exist` error.
- Stats query targets `"test_schema_registrar".outbox` not `"peegeeq".outbox`.

**Note:** This test can only be written AFTER Step D (registrar fallback) is applied.

---

### TC-S8 — `OutboxConsumerGroup` with explicit schema processes messages correctly (covers G9)

**Arrange:** Schema `test_schema_group`. `OutboxConsumerGroup` built with `PeeGeeQConfiguration` referencing this schema.

**Act:** Group started. Producer sends 2 messages. Two group members each receive one.

**Assert:** Both messages are COMPLETED in `test_schema_group.outbox`. No cross-schema contamination.

---

### TC-S9 — `OutboxConsumer.markMessageFailed()` writes to correct schema (covers G10)

**Arrange:** Schema `test_schema_fail`. One message inserted in `test_schema_fail.outbox`.

**Act:** Consumer processes it. Message handler throws exception. Failure handling runs.

**Assert:** Row status = `'FAILED'` in `test_schema_fail.outbox`. Row is NOT created or modified in `public.outbox`.

---

### TC-S10 — `OutboxConsumer.resetFilteredMessageToPending()` on correct schema (covers G11)

**Arrange:** Schema `test_schema_filter`. One message in PROCESSING state in `test_schema_filter.outbox`.

**Act:** Consumer processes it. Message handler throws `MessageFilteredException`.

**Assert:** Row status = `'PENDING'` in `test_schema_filter.outbox`. Consumer continues polling.

---

### TC-S11 — `OutboxConsumer.processAvailableMessages()` server-side filter path with explicit schema (covers G13)

**Arrange:** Schema `test_schema_ssf`. 3 messages with different header values. Consumer configured with `OutboxConsumerConfig` that includes a `ServerSideFilter`.

**Act:** Consumer polls.

**Assert:** Only messages matching the filter are returned. SQL uses the server-side filter code path (the `if (filter != null)` branch), which uses `sq + ".outbox"` string concatenation rather than `.formatted()` — verify this branch works with explicit schema.

---

### Existing tests that must continue to pass after fix

| Test | What it validates |
|---|---|
| `DlqMultiTenantSchemaIsolationTest` (6 tests) | Multi-tenant DLQ isolation — primary regression guard |
| `MultiTenantSchemaIsolationTest` | Multi-tenant consumer isolation |
| `OutboxSchemaQuotingTest` | SQL reserved word schemas are properly quoted |
| `OutboxConsumerCrashRecoveryTest` | Default schema, consumer crash recovery |
| `ReactiveOutboxProducerTest` | Default schema, producer+consumer roundtrip |
| `StuckMessageRecoveryIntegrationTest` | Stuck message recovery via `StuckMessageRecoveryManager` |
| `OutboxQueueBrowserIntegrationTest` | Default schema browser |
| `OutboxFactoryIntegrationTest` | Default schema factory lifecycle |
| `FilterExceptionFrameworkRetryIntegrationTest` | Filter retry in null-schema or default-schema path |

---

### TC-S12 — `OutboxConsumer.storeDeadLetterMessage()` + `DeadLetterQueueManager` round-trip with explicit schema (covers G16)

**Arrange:** Schema `test_schema_dlq`. Tables initialized. `PeeGeeQConfiguration` with `schema = "test_schema_dlq"`. One message sent and polled. Consumer configured to fail all retries → moves to DLQ via `storeDeadLetterMessage()`.

**Act:** `PeeGeeQManager.getDeadLetterService().getDeadLetterMessages(topic, 10, 0)` after the DLQ write completes.

**Assert:**
- The DLQ list contains exactly 1 entry.
- `dlm.getOriginalTable()` = `"outbox"`.
- Cross-schema check: a manager configured with schema B returns empty list for schema A's DLQ entries.

---

### TC-S13 — `DeadLetterQueueManager.reprocessDeadLetterMessageRecord()` re-inserts to correct schema (covers G14)

**Arrange:** Schema `test_schema_reprocess`. One message in `dead_letter_queue` with `original_table = "outbox"`.

**Act:** `deadLetterManager.reprocessDeadLetterMessageRecord(dlmId, "test")` using pool from `PeeGeeQManager` configured with `schema = "test_schema_reprocess"`.

**Assert:**
- `dead_letter_queue` row is deleted.
- `outbox` table in `test_schema_reprocess` now has 1 PENDING row matching the original message.
- `public.outbox` is empty (no cross-schema contamination).

---

### TC-S14 — `SubscriptionManager.subscribe()` resolves `outbox` and `outbox_topic_subscriptions` via `search_path` with explicit schema (covers G17)

**Scope:** `peegeeq-outbox` test using `OutboxConsumerGroup.start(SubscriptionOptions)` with a non-default schema.

**Arrange:** Two schemas — `tenant_a`, `tenant_b`. Each has a full outbox DDL. `OutboxConsumerGroup` configured with schema `tenant_a`.

**Act:** Call `consumerGroup.start(subscriptionOptions)` (triggers `SubscriptionManager.subscribe(topic, groupName, ...)`).

**Assert:**
- `tenant_a.outbox_topic_subscriptions` has a row for `(topic, groupName)`.
- `tenant_b.outbox_topic_subscriptions` is empty.
- `public.outbox_topic_subscriptions` is empty (no cross-schema contamination).

---

### TC-S15 — OFFSET_WATERMARK mode: `WatermarkCalculator` completes messages in the correct schema only (covers G18, G19)

**Scope:** `peegeeq-outbox` test exercising the full OFFSET_WATERMARK code path with a non-default schema.

**Arrange:** Schema `tenant_wm`. Full DDL including `outbox_topics` (with `completion_tracking_mode = 'OFFSET_WATERMARK'`), `outbox_partition_offsets`, `outbox_partition_assignments`, `outbox_topic_watermarks`, `outbox_topic_subscriptions`. Schema `public` also has full DDL (to detect cross-schema contamination).

**Act:** Start `OutboxConsumerGroup` with `connectionManager` wired and `schema = "tenant_wm"`. Publish a message. Wait for watermark sweep to run.

**Assert:**
- Message in `tenant_wm.outbox` has `status = 'COMPLETED'`.
- `tenant_wm.outbox_topic_watermarks` has an entry.
- `tenant_wm.outbox_partition_offsets` has a committed offset.
- `public.outbox` is unchanged.
- `public.outbox_topic_watermarks` is empty.

**Note:** This test requires a `connectionManager`-wired `OutboxConsumerGroup`. Review the partitioned consumer group factory method to determine the correct construction path for the test.

---

## Implementation sequence

Tests must be written **after** Steps A–E of the remediation plan are applied, because some tests (TC-S6, TC-S7) validate behavior that only exists after the fix.

---

## Validation execution plan

### Preconditions

- All 14 production classes reviewed (done — see class inventory above).
- All 19 gaps (G1–G19) documented.
- All 15 test cases (TC-S1–TC-S15) specified.
- No code changes made yet.

The plan has six phases. Each phase has a concrete Maven command, a pass/fail criterion, and a stop condition. Do not proceed to the next phase if the current phase fails.

---

### Phase 0 — Baseline: establish the current test state

**Purpose:** Capture which tests pass today, before any changes. This is the rollback reference.

**Command:**
```
mvn clean test -Pall-tests 2>&1 | Tee-Object -FilePath logs\all-tests-baseline-20260524.txt
```

**Pass criterion:** Record the exact counts from `[INFO] Tests run: N, Failures: F, Errors: E, Skipped: S`.

**Expected:** `DlqMultiTenantSchemaIsolationTest` (6 tests) passes — those tests use explicit schema names, not the fallback. `MultiTenantSchemaIsolationTest` and `OutboxSchemaQuotingTest` should also pass. All other outbox tests run against `public` schema and should pass.

**Stop condition:** If baseline is NOT captured before code changes, rollback is impossible.

---

### Phase 1 — Apply fixes A–E (code changes only, no test changes)

Apply the remediation steps in this order (lowest risk first):

| Order | Step | Class | Change |
|---|---|---|---|
| 1 | D | `OutboxFactoryRegistrar` | Fall back to `pgDs.getPeeGeeQConfiguration()` when config map key absent |
| 2 | A | `OutboxConsumer` | `schemaName = null` when config is null or `getSchema()` is null; `quoteIdentifier()` returns unqualified name when null |
| 3 | B | `OutboxFactory` | Same null-schema → unqualified fallback in `getStats`, `countMessages`, `purgeMessages`, `createBrowser` |
| 4 | C | `OutboxQueueBrowser` | `schema = null` when null passed; `browse()` uses unqualified ref when null |

**After each individual fix:** run `mvn test-compile -pl :peegeeq-outbox` (fast, no Testcontainers) to verify the module compiles.

**Command (compile-only gate):**
```
mvn test-compile -pl :peegeeq-outbox 2>&1 | Tee-Object -FilePath logs\outbox-compile-20260524.txt
```

**Stop condition:** Do not proceed to Phase 2 if compile fails.

---

### Phase 2 — Regression gate: existing schema tests must still pass

**Purpose:** Verify that applying fixes A–E does not break any currently passing test.

**Targeted command (faster than full suite):**
```
mvn test -pl :peegeeq-outbox -Pall-tests -Dtest="DlqMultiTenantSchemaIsolationTest,MultiTenantSchemaIsolationTest,OutboxSchemaQuotingTest" 2>&1 | Tee-Object -FilePath logs\schema-regression-gate-20260524.txt
```

**Pass criterion:** All 6 tests in `DlqMultiTenantSchemaIsolationTest`, all tests in `MultiTenantSchemaIsolationTest` and `OutboxSchemaQuotingTest` pass. Zero failures.

**What to check in the log:**
- `DlqMultiTenantSchemaIsolationTest` — `Tests run: 6, Failures: 0`
- `MultiTenantSchemaIsolationTest` — `Failures: 0`
- `OutboxSchemaQuotingTest` — `Failures: 0`

**Stop condition:** Any failure in these tests means a fix was incorrectly applied. Revert the specific fix and re-examine.

---

### Phase 3 — Write and run TC-S1–TC-S13 (new test class)

**Test class name:** `OutboxSchemaIsolationCoverageTest`  
**Location:** `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/`  
**Annotations required:** `@Tag(TestCategories.INTEGRATION)`, `@Testcontainers`, `@ExtendWith(VertxExtension.class)`  
**Infrastructure:** `PostgreSQLTestConstants.createStandardContainer()`, `PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schema, SchemaComponent.QUEUE_ALL)`, `PeeGeeQTestConfig.builder().from(postgres).schema(schema).build()`

**Write tests in this priority order within the class:**

| Priority | Test | Covers | Why first |
|---|---|---|---|
| 1 | TC-S6 | G7 | Verifies null schema → unqualified SQL (the core Bug A/B/C fix) |
| 2 | TC-S3 | G6 | Producer (unqualified) + Consumer (qualified) roundtrip — proves they stay in sync |
| 3 | TC-S1 | G1 | `OutboxFactory.getStats()` with explicit schema |
| 4 | TC-S2 | G2 | `OutboxFactory.countMessages()` with explicit schema |
| 5 | TC-S3b | G3 | `OutboxFactory.purgeMessages()` with explicit schema |
| 6 | TC-S4 | G4, G5 | `OutboxQueueBrowser.browse()` via `createBrowser()` with explicit schema |
| 7 | TC-S5 | G8 | `OutboxFactoryRegistrar` schema propagation |
| 8 | TC-S7 | G7 | Explicit schema consumer ignores messages inserted outside its schema |
| 9 | TC-S8 | G9 | `OutboxConsumerGroup.start()` schema propagation |
| 10 | TC-S9 | G10 | `markMessageFailed()` with explicit schema |
| 11 | TC-S10 | G11 | `resetFilteredMessageToPending()` with explicit schema |
| 12 | TC-S11 | G13 | `processAvailableMessages()` server-side filter path with explicit schema |
| 13 | TC-S12 | G12, G16 | `storeDeadLetterMessage()` + DLQ round-trip with explicit schema |
| 14 | TC-S13 | G14 | `DeadLetterQueueManager.reprocessDeadLetterMessageRecord()` re-insert with explicit schema |

**Command after writing each test (run just this class):**
```
mvn test -pl :peegeeq-outbox -Pall-tests -Dtest=OutboxSchemaIsolationCoverageTest 2>&1 | Tee-Object -FilePath logs\schema-coverage-test-20260524.txt
```

**Pass criterion:** All 14 tests in `OutboxSchemaIsolationCoverageTest` pass. Zero failures.

**Stop condition:** A failing test reveals a bug not yet fixed or a test setup error. Fix the production code or test (not both at the same time) before proceeding.

---

### Phase 4 — Write and run TC-S14 (subscription isolation)

**Test class name:** `OutboxSchemaSubscriptionIsolationTest`  
**Location:** `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/`

**Covers:** G17 — `SubscriptionManager.subscribe()` schema isolation.

**Command:**
```
mvn test -pl :peegeeq-outbox -Pall-tests -Dtest=OutboxSchemaSubscriptionIsolationTest 2>&1 | Tee-Object -FilePath logs\schema-subscription-test-20260524.txt
```

**Pass criterion:** Test passes. `tenant_a.outbox_topic_subscriptions` has the subscription row. `tenant_b.outbox_topic_subscriptions` is empty. `public.outbox_topic_subscriptions` is empty.

**Dependency:** Requires QUEUE_ALL DDL which includes `outbox_topic_subscriptions`. Verify `SchemaComponent.QUEUE_ALL` includes this table before writing the test.

---

### Phase 5 — Write and run TC-S15 (OFFSET_WATERMARK schema isolation)

**Test class name:** `OutboxOffsetWatermarkSchemaIsolationTest`  
**Location:** `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/`

**Covers:** G18, G19 — full OFFSET_WATERMARK partitioned consumption path with explicit schema.

**Prerequisite check (before writing):** Verify which `SchemaComponent` value initializes `outbox_topics`, `outbox_partition_offsets`, `outbox_partition_assignments`, `outbox_topic_watermarks`. Read `PeeGeeQTestSchemaInitializer` to confirm the correct component enum value. If these tables are not in `QUEUE_ALL`, a new `SchemaComponent` or explicit DDL may be needed.

**Construction pattern:** The test must wire a `PgConnectionManager` to the `OutboxFactory` constructor with `connectionManager` argument. Study `OutboxOffsetWatermarkWiringTest` for the correct construction pattern before writing.

**Command:**
```
mvn test -pl :peegeeq-outbox -Pall-tests -Dtest=OutboxOffsetWatermarkSchemaIsolationTest 2>&1 | Tee-Object -FilePath logs\schema-watermark-test-20260524.txt
```

**Pass criterion:** Message in `tenant_wm.outbox` reaches `COMPLETED`. `tenant_wm.outbox_topic_watermarks` has an entry. `public.outbox` unchanged. `public.outbox_topic_watermarks` empty.

**Note:** This is the most complex test. If it cannot be completed in one session, document the blocker and return to it. The Phase 5 test gap (G18, G19) is lower risk than the core bugs (A–E) because the OFFSET_WATERMARK path is only active when `connectionManager` is wired.

---

### Phase 6 — Full suite validation

**Purpose:** Prove that all code changes and all new tests integrate cleanly with the entire codebase. No partial profiles.

**Command:**
```
mvn clean test -Pall-tests 2>&1 | Tee-Object -FilePath logs\all-tests-post-fix-20260524.txt
```

**Pass criterion:** `BUILD SUCCESS`. Zero test failures. The delta from Phase 0 baseline is:
- New passing tests: TC-S1–TC-S15 (plus any sub-tests)
- No regressions: every test that passed in Phase 0 still passes

**What to read in the log:**
1. `[INFO] Tests run:` summary line per module — compare against Phase 0 baseline
2. Any `FAILED` or `ERROR` lines
3. `[INFO] BUILD SUCCESS` at the end

**Stop condition:** Any failure at this phase requires bisecting to find whether the cause is a code fix regression or a new test error.

---

### Phase 7 (optional) — Coverage measurement with JaCoCo

**Purpose:** Quantify line coverage on the 4 modified classes.

**Scope:** `OutboxConsumer`, `OutboxFactory`, `OutboxQueueBrowser`, `OutboxFactoryRegistrar`.

**Command:**
```
mvn clean test -Pall-tests -pl :peegeeq-outbox -Dtest="DlqMultiTenantSchemaIsolationTest,MultiTenantSchemaIsolationTest,OutboxSchemaQuotingTest,OutboxSchemaIsolationCoverageTest,OutboxSchemaSubscriptionIsolationTest" jacoco:report 2>&1 | Tee-Object -FilePath logs\coverage-report-20260524.txt
```

**Target:** ≥ 90% line coverage on `OutboxConsumer`, `OutboxFactory`, `OutboxQueueBrowser`. 100% branch coverage on the `schema == null` decision in each class.

**Coverage report:** `peegeeq-outbox/target/site/jacoco/index.html` — open in browser.

---

### Phase summary table

| Phase | Action | Gate | Command prefix |
|---|---|---|---|
| 0 | Baseline | Record test counts | `mvn clean test -Pall-tests` |
| 1 | Apply fixes A–E | Compile success | `mvn test-compile -pl :peegeeq-outbox` |
| 2 | Regression gate | Existing schema tests still pass | `mvn test -pl :peegeeq-outbox -Pall-tests -Dtest=DlqMultiTenant...` |
| 3 | TC-S1–TC-S13 | 14 new tests pass | `mvn test -pl :peegeeq-outbox -Pall-tests -Dtest=OutboxSchemaIsolationCoverageTest` |
| 4 | TC-S14 | Subscription isolation test passes | `mvn test -pl :peegeeq-outbox -Pall-tests -Dtest=OutboxSchemaSubscriptionIsolationTest` |
| 5 | TC-S15 | OFFSET_WATERMARK isolation test passes | `mvn test -pl :peegeeq-outbox -Pall-tests -Dtest=OutboxOffsetWatermarkSchemaIsolationTest` |
| 6 | Full suite | `BUILD SUCCESS`, zero regressions | `mvn clean test -Pall-tests` |
| 7 | Coverage (optional) | ≥ 90% line on 4 classes | `mvn ... jacoco:report` |

---

## Appendix — Follow-up actions

Issues identified during this session that are out of scope here but should be tracked.

### A1 — `OutboxConsumerGroupMember.acceptsMessage()` filter-exception bug (FIXED this session)

**Status:** Fixed (2026-05-24, this session).  
**Root cause:** The `catch` block in `acceptsMessage()` swallowed filter exceptions and returned `false`, which triggered `MessageFilteredException` → `resetFilteredMessageToPending()` — resetting the outbox row to `PENDING` without incrementing `retry_count`. This created an infinite PENDING loop; the message never reached `handleMessageFailureWithRetry()` → retry/DLQ.  
**Fix applied:** `acceptsMessage()` now records the failure in the circuit breaker and re-throws the exception. The circuit-breaker OPEN fast-fail path (`return false` without calling the filter) is unchanged.  
**Regression test:** `FilterExceptionFrameworkRetryIntegrationTest` (TC-1, TC-2, TC-3, TC-6) — pre-written specifically for this fix; now expected to pass.  
**DLQ tests unblocked:** `DlqMultiTenantSchemaIsolationTest` TC-7a and TC-7b were timing out because of this bug; now expected to pass in an isolated run.  
**Companion change:** `CircuitBreakerRecoveryTest` phase-1 loops and partial-recovery call updated to `try-catch` around `acceptsMessage()`, since the method now propagates the exception instead of returning `false`.

---

### A2 — `CircuitBreakerRecoveryTest` uses `LockSupport.parkNanos` (banned pattern)

**Status:** Pre-existing violation, not introduced this session. Not fixed.  
**Location:** `peegeeq-outbox/src/test/java/dev/mars/peegeeq/outbox/CircuitBreakerRecoveryTest.java`, lines 134, 271, 300.  
**Also tracked in:** `docs-design/tasks/TIER5-BLOCKING-THREAD-VIOLATIONS-PLAN.md` (table entry: `CircuitBreakerRecoveryTest.java`, 2 occurrences, `parkNanos > timeout` validates CB state transition).  
**Why not fixed now:** The test is a pure Java unit test (`@Tag(TestCategories.CORE)`, no Vert.x, no DB). It uses `parkNanos` to let a real 200 ms circuit-breaker timeout expire before asserting the HALF_OPEN transition. Replacing this requires either injecting a fake/manual clock into `FilterCircuitBreaker` or converting the test to a Vert.x-backed test with `vertx.timer(...)`. Both require structural changes beyond the scope of the DLQ/schema fix.  
**Recommended approach:** Add a `Clock` or `Supplier<Instant>` seam to `FilterCircuitBreaker` so tests can fast-forward time without sleeping. `CircuitBreakerRecoveryTest` can then use `VertxTestContext` + `vertx.timer(...)` or simply advance the injected clock. Remove `LockSupport` import once all three call sites are replaced.

---

### A3 — `resetFilteredMessageToPending()` used by CB-OPEN path should not be called when filter throws

**Status:** Design observation. Low priority.  
**Context:** When the circuit breaker is OPEN, `acceptsMessage()` returns `false` (without calling the filter). `distributeMessage()` has no eligible consumers → `MessageFilteredException` → `resetFilteredMessageToPending()`. This means the message loops back to PENDING (retry_count unchanged) for every polling cycle while the CB is OPEN. TC-3 in `FilterExceptionFrameworkRetryIntegrationTest` validates this is bounded (retry_count stops climbing once CB opens).  
**Risk:** If the CB timeout is long (default: 1 minute) and `polling-interval` is short, the message loops silently for up to 1 minute. This is the intended CB protection behaviour, but the PENDING churn could be confusing in monitoring.  
**Possible improvement:** Log a periodic WARN (e.g. once per CB open event, not per poll cycle) that a message is being held pending because the filter CB is open for consumer `X` in group `Y`. No code change required now; document as a monitoring/observability improvement.

---

### A4 — TC-S1–TC-S15 (new schema isolation test class) not yet written

**Status:** Planned in this document (Section "Test coverage plan"), not yet implemented.  
**Scope:** `OutboxSchemaIsolationCoverageTest` (TC-S1–TC-S13), `OutboxSchemaSubscriptionIsolationTest` (TC-S14), `OutboxOffsetWatermarkSchemaIsolationTest` (TC-S15).  
**Prerequisite:** Full `-Pall-tests` run with all existing tests passing (schema fixes A–D done, acceptsMessage fix done).  
**Next action:** Implement TC-S1 through TC-S6 first (the highest-value gaps: `OutboxFactory` and `OutboxQueueBrowser` schema-qualified SQL paths). Run regression gate after each group.
