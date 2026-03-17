# Code Review: `peegeeq-bitemporal` Module

**Date:** 2026-03-17  
**Scope:** All 5 production source files (~3200 lines), 28 test files  
**Compiler Errors:** None  

---

## Executive Summary

The module's core architecture — bi-temporal event sourcing over PostgreSQL with Vert.x 5.x reactive patterns — is sound. The main risks cluster around resource lifecycle management (dual Vertx instances, fire-and-forget async close), SQL construction patterns, and thread-safety gaps in the notification handler.

---

## Findings

### Critical

#### C1. SQL Injection Risk — Unquoted Table Names in All SQL Statements

**Files:** `PgBiTemporalEventStore.java` (all SQL-building methods)

Every SQL statement interpolates `tableName` via `String.formatted()`:

```java
String sql = """
    INSERT INTO %s
    ...
    """.formatted(tableName);
```

While `validateTableName()` calls `PostgreSqlIdentifierValidator.validate()`, the table name is **not** double-quoted in the SQL. If the validator ever allows edge-case identifiers (reserved words, mixed-case), the unquoted identifier becomes a syntax error or semantic mismatch.

#### C2. SQL Injection Risk — `pg_total_relation_size` String Literal Interpolation

**File:** `PgBiTemporalEventStore.java` — `getStats()` (~line 1175)

```java
String basicStatsSql = """
    SELECT ... pg_total_relation_size('%s') as storage_size_bytes,
    ...
    FROM %s
    """.formatted(tableName, tableName);
```

The table name is placed inside **single quotes** (a SQL string literal). This is a different injection class from identifier interpolation — a crafted table name like `'; DROP TABLE x; --` would escape the string context. Though `validateTableName` mitigates this, the pattern is fragile and structurally unsafe.

---

### High

#### H1. Dual `sharedVertx` Singletons

**Files:** `PgBiTemporalEventStore.java` (~line 103), `VertxPoolAdapter.java` (~line 139)

Both classes independently create and cache a `static volatile Vertx sharedVertx`. Two `Vertx` instances can coexist in the same JVM with separate event loop pools, thread pools, and contexts. `TransactionPropagation.CONTEXT` depends on a shared Vert.x context — if pools from these two classes see different Vertx instances, transaction propagation silently breaks.

#### H2. `close()` Is Synchronous but Calls Async Operations

**File:** `PgBiTemporalEventStore.java` — `close()` (~line 1237)

`close()` is `void` (from `AutoCloseable`) but calls `reactiveNotificationHandler.stop()` which returns `Future<Void>`. The future is never awaited:
- The LISTEN connection may not be cleaned up before the method returns.
- `reactivePool.close()` is called immediately after, creating a race.
- Test teardown calling `close()` followed by pool recreation can hit stale connections.

#### H3. Inconsistent JSONB Casting Across SQL Paths

**File:** `PgBiTemporalEventStore.java`

The propagation-aware append path uses `$5, $6` (no cast), while the non-propagation path uses `$5::jsonb, $6::jsonb`. Other methods (`appendBatchReactive`, `appendHighPerformance`, `appendCorrectionWithTransaction`) also use `::jsonb`. This inconsistency may cause runtime type-inference failures on one path but not another.

|  Path  |  Cast  |
|--------|--------|
| `appendWithTransactionInternal` (propagation != null) | `$5, $6` — no cast |
| `appendWithTransactionInternal` (propagation == null) | `$5::jsonb, $6::jsonb` |
| `appendBatchReactive` | `$5::jsonb, $6::jsonb` |
| `appendHighPerformance` | `$5::jsonb, $6::jsonb` |
| `appendCorrectionWithTransaction` | `$5::jsonb, $6::jsonb` |
| `appendInTransactionReactive` | `$5::jsonb, $6::jsonb` |

#### H4. Hardcoded Credentials in `VertxPoolAdapter`

**File:** `VertxPoolAdapter.java` — `createPoolWithDefaults()` (~line 161)

```java
.setUser("peegeeq")
.setPassword("peegeeq");
```

Hardcoded credentials in production code. Even as a fallback, this silently connects with wrong config instead of failing fast. If `PeeGeeQManager` config is misconfigured, the system connects to what might be a different database.

#### H5. `Pool.close()` Future Ignored

**File:** `PgBiTemporalEventStore.java` — `close()` (~line 1260)

```java
reactivePool.close();   // returns Future<Void>, never handled
pipelinedClient.close(); // same
```

Errors from pool/client close (e.g., connections stuck mid-transaction) are silently swallowed. Combined with H2, cleanup is entirely fire-and-forget.

---

### Medium

#### M1. Aggressive Default Pool Size (100)

**File:** `PgBiTemporalEventStore.java` (~line 1808)

```java
int defaultSize = 100;
```

PostgreSQL's default `max_connections` is 100. A single bitemporal event store consuming all available connections will starve other services. Combined with `waitQueueMultiplier=10`, the wait queue grows to 1000 entries by default.

#### M2. Static `eventBusInstanceRegistry` Without Eviction

**File:** `PgBiTemporalEventStore.java` (~line 110)

Every instance registers itself on construction. While `close()` removes the entry, if `close()` is never called (exception during construction after registration, abandoned references), entries leak. Keys include random UUIDs, so duplicates accumulate.

#### M3. `SELECT *` in `queryReactive()`

**File:** `PgBiTemporalEventStore.java` — `queryReactive()` (~line 1432)

```java
StringBuilder sql = new StringBuilder("SELECT * FROM %s WHERE 1=1");
```

Fragile if columns are added or removed. Other query methods (`getById`, `getAllVersions`, `getAsOfTransactionTime`) correctly enumerate columns. This method should follow the same pattern.

#### M4. Non-Thread-Safe `reconnectAttempts` Counter

**File:** `ReactiveNotificationHandler.java` (~line 83)

`reconnectAttempts` is a plain `int` accessed from multiple threads (close handler on event loop, reconnection timer callback). The compound check-then-increment pattern (`reconnectAttempts < MAX && reconnectAttempts++`) is not atomic. Under concurrent close-handler invocations, the counter can be incremented past the max.

---

### Low

#### L1. MD5 Fallback Bug in `createSafeChannelName`

**File:** `ReactiveNotificationHandler.java` (~line 269)

The fallback when MD5 is unavailable:
```java
md5Hash = Integer.toHexString(Math.abs(baseChannel.hashCode())).substring(0, 8);
```

Two bugs:
1. `Math.abs(Integer.MIN_VALUE)` returns `Integer.MIN_VALUE` (negative), making the hex string contain a minus sign.
2. The hex string can be shorter than 8 chars (e.g., hashCode of 0 → `"0"`), causing `StringIndexOutOfBoundsException`.

#### L2. Dead `subscriptions` Field in `PgBiTemporalEventStore`

**File:** `PgBiTemporalEventStore.java` (~line 89)

Subscription tracking is fully delegated to `ReactiveNotificationHandler`, but the event store still maintains its own `ConcurrentHashMap<String, MessageHandler>` field that is only `.clear()`-ed in `close()` and `unsubscribe()`. It never holds any data. This is dead state.

#### L3. Mixed Copyright Headers

- `BiTemporalEventStoreFactory.java` and `PgBiTemporalEventStore.java` — Apache 2.0 license
- `SubscriptionKey.java`, `VertxPoolAdapter.java`, `ReactiveNotificationHandler.java` — Proprietary Cityline Ltd license

If the intent is to ship under Apache 2.0 (which matches the root `LICENSE` file), the proprietary headers should be updated.

#### L4. `BatchEventData` Uses Public Fields

**File:** `PgBiTemporalEventStore.java` (~line 2447)

```java
public static class BatchEventData<T> {
    public final String eventType;
    public final T payload;
    ...
}
```

Rest of the codebase uses accessor methods. A Java `record` would be idiomatic.

#### L5. Dangling Javadoc Fragment

**File:** `PgBiTemporalEventStore.java` (~line 385)

An incomplete Javadoc comment block (`/**` without closing `*/` before next `/**`).

---

## Severity Summary

| Severity     | Count | IDs |
|:-------------|:-----:|:----|
| **Critical** | 2     | C1, C2 |
| **High**     | 5     | H1, H2, H3, H4, H5 |
| **Medium**   | 4     | M1, M2, M3, M4 |
| **Low**      | 5     | L1, L2, L3, L4, L5 |

---

## Remediation Status

All 16 findings have been remediated and verified. 109 tests pass with 0 failures across all three phases.

### Phase 1 — Critical & High (COMPLETE)

| ID | Finding | Status | Notes |
|----|---------|--------|-------|
| C1 | Unquoted table names in SQL | **DONE** | Added `quotedTableName` field; all 15 SQL interpolation points updated. Inner class `DatabaseWorkerVerticle` also quotes its own `tableName`. |
| C2 | `pg_total_relation_size` string literal injection | **DONE** | Changed to `pg_total_relation_size($1::regclass)` with `tableName` as bind parameter. |
| H1 | Dual `sharedVertx` singletons | **DONE** | Removed duplicate `sharedVertx` from `VertxPoolAdapter`; delegates to `PgBiTemporalEventStore.getOrCreateSharedVertx()`. |
| H2 | Sync `close()` with async operations | **DONE** | Added `closeFuture()` composing stop → pool close → client close sequentially. `close()` delegates with 5s timeout. |
| H3 | Inconsistent JSONB casts | **DONE** | All INSERT statements now include `$5::jsonb, $6::jsonb` casts. |
| H4 | Hardcoded credentials fallback | **DONE** | Removed `createPoolWithDefaults()`. Throws `IllegalStateException` if config missing. |
| H5 | `Pool.close()` future ignored | **DONE** | Handled in `closeFuture()` composition with per-step failure logging. |

### Phase 2 — Medium (COMPLETE)

| ID | Finding | Status | Notes |
|----|---------|--------|-------|
| M1 | Aggressive default pool size | **DONE** | Default lowered from 100 to 20 with guidance to override via PeeGeeQManager config. |
| M2 | `eventBusInstanceRegistry` leak | **DONE** | Moved `put()` to after full construction; added try-catch cleanup if `ReactiveNotificationHandler` construction fails. |
| M3 | `SELECT *` in `queryReactive` | **DONE** | Replaced with explicit 14-column list matching `mapRowToEvent()`. |
| M4 | Non-thread-safe `reconnectAttempts` | **DONE** | Replaced with `AtomicInteger`; uses `incrementAndGet()` and `set()` for atomic operations. |

### Phase 3 — Low (COMPLETE)

| ID | Finding | Status | Notes |
|----|---------|--------|-------|
| L1 | MD5 fallback `substring` bug | **DONE** | Replaced with `String.format("%08x", hashCode & 0xFFFFFFFFL)` — always 8 hex chars. |
| L2 | Dead `subscriptions` field | **DONE** | Removed field, constructor init, and all `subscriptions.clear()` calls. |
| L3 | Mixed copyright headers | **DONE** | Standardized `ReactiveNotificationHandler`, `SubscriptionKey`, and `VertxPoolAdapter` to Apache 2.0 matching root LICENSE. |
| L4 | `BatchEventData` public fields | **DONE** | Converted to Java `record`; updated all field accesses to accessor methods in source and tests. |
| L5 | Dangling Javadoc | **DONE** | Removed incomplete `/** ... withTransaction` fragment. |

---

## Verification Results

All phases verified with:
1. `mvn compile -pl peegeeq-bitemporal -am` — clean compile
2. `mvn test -pl peegeeq-bitemporal` — 109 tests, 0 failures, 0 errors
3. SQL injection audit — `grep` for `.formatted(tableName)` returns 0 matches; all SQL uses `quotedTableName` or parameterized binds
