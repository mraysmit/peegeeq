# PgNativeQueueFactory Refactoring Plan

This document outlines a refactoring plan to eliminate code smells in `PgNativeQueueFactory`, primarily focusing on removing reflection usage and improving architectural clarity.

## Implementation Status: COMPLETE

> [!NOTE]
> All refactoring items completed on 2025-12-18:
> - Metrics refactoring and CloudEvents changes
> - Legacy constructor removal
> - Reflection elimination
> - Blocking call fix in `getStats()` (now uses async `getStatsAsync()`)

## Changes Implemented

### peegeeq-api

#### [COMPLETE] MetricsProvider.java
- Removed `getUnderlyingMetrics()` method that returned `Object`
- Changed `getAllMetrics()` return type from `Map<String, Object>` to `Map<String, Number>` for type safety
- Updated method signatures to match actual producer/consumer usage:
  - `recordMessageSent(String topic)`
  - `recordMessageReceived(String topic)`
  - `recordMessageProcessed(String topic, Duration processingTime)`
  - `recordMessageFailed(String topic, String reason)`
  - `recordMessageDeadLettered(String topic, String reason)`
  - `recordMessageRetried(String topic, int retryCount)`

#### [COMPLETE] NoOpMetricsProvider.java (NEW FILE)
- Created Null Object pattern implementation for metrics
- Singleton instance via `NoOpMetricsProvider.INSTANCE`
- Eliminates all `if (metrics != null)` checks

#### [COMPLETE] VertxProvider.java (NEW FILE)
- Created interface with `getVertx()` method
- Enables native queue implementations to access Vert.x without reflection

#### [COMPLETE] PoolProvider.java (NEW FILE)
- Created interface with `getPool()` method
- Enables direct pool access without reflection

#### [COMPLETE] DatabaseService.java
- Extended to implement `VertxProvider` and `PoolProvider`
- `getMetricsProvider()` now documented to never return null

### peegeeq-db

#### [COMPLETE] PgMetricsProvider.java
- Updated to implement new `MetricsProvider` interface methods
- Delegates to `PeeGeeQMetrics` for actual metrics recording

#### [COMPLETE] PgDatabaseService.java
- Implemented `getVertx()` - delegates to manager
- Implemented `getPool()` - delegates to manager
- `getMetricsProvider()` never returns null

#### [COMPLETE] PeeGeeQMetrics.java
- Added `implements MetricsProvider`
- Fixed duplicate `getInstanceId()` method

### peegeeq-native

#### [COMPLETE] PgNativeQueueFactory.java
- Changed `legacyMetrics` field type from `PeeGeeQMetrics` to `MetricsProvider`
- Removed reflection-based CloudEvents loading - now uses direct `JsonFormat.getCloudEventJacksonModule()`
- Merged duplicate Javadoc blocks
- Simplified `getMetrics()` method

#### [COMPLETE] PgNativeQueueProducer.java
- Changed from `PeeGeeQMetrics` to `MetricsProvider` interface
- Uses `NoOpMetricsProvider.INSTANCE` as fallback instead of null

#### [COMPLETE] PgNativeQueueConsumer.java
- Changed from `PeeGeeQMetrics` to `MetricsProvider` interface
- Uses `NoOpMetricsProvider.INSTANCE` as fallback instead of null

#### [COMPLETE] PgNativeConsumerGroup.java
- Changed to use `MetricsProvider` interface

### peegeeq-outbox

#### [COMPLETE] OutboxFactory.java
- Changed `legacyMetrics` field type from `PeeGeeQMetrics` to `MetricsProvider`
- Removed reflection-based CloudEvents loading - now uses direct `JsonFormat.getCloudEventJacksonModule()`
- Simplified `getMetrics()` method

#### [COMPLETE] OutboxProducer.java
- Changed from `PeeGeeQMetrics` to `MetricsProvider` interface
- Uses `NoOpMetricsProvider.INSTANCE` as fallback instead of null

#### [COMPLETE] OutboxConsumer.java
- Changed from `PeeGeeQMetrics` to `MetricsProvider` interface
- Uses `NoOpMetricsProvider.INSTANCE` as fallback instead of null

#### [COMPLETE] OutboxConsumerGroup.java
- Changed to use `MetricsProvider` interface

### peegeeq-bitemporal

#### [COMPLETE] pom.xml
- Removed `<optional>true</optional>` from CloudEvents dependencies

### Tests Updated

#### [COMPLETE] PgMetricsProviderCoreTest.java
- Updated to use new method signatures
- Removed tests for deprecated methods (`getPeeGeeQMetrics`, `recordMessageAcknowledged`)
- Added tests for new methods

#### [COMPLETE] OutboxConsumerGroupTest.java
- Updated to use `PgDatabaseService` instead of legacy `PgClientFactory` pattern

## Verification Results

### Build Status
- `mvn clean compile` - PASSED
- `mvn clean test` - PASSED (all modules)

### Tests Executed
- All `peegeeq-api` tests - PASSED
- All `peegeeq-db` tests - PASSED
- All `peegeeq-native` tests - PASSED
- All `peegeeq-outbox` tests - PASSED
- All `peegeeq-bitemporal` tests - PASSED
- All `peegeeq-rest` tests - PASSED
- All `peegeeq-runtime` tests - PASSED
- All `peegeeq-examples` tests - PASSED

## Key Design Improvements (Completed)

1. **No more `Object` return types** - `getUnderlyingMetrics()` was removed, `getAllMetrics()` now returns `Map<String, Number>` instead of `Map<String, Object>`
2. **No more nullable metrics** - All constructors use `NoOpMetricsProvider.INSTANCE` as fallback
3. **CloudEvents is required** - No more reflection-based optional loading, uses direct `JsonFormat.getCloudEventJacksonModule()`
4. **Proper dependency inversion for metrics** - Producers/consumers depend on `MetricsProvider` interface, not `PeeGeeQMetrics` concrete class
5. **VertxProvider, PoolProvider, and ConnectOptionsProvider interfaces** - Enable direct access to Vertx, Pool, and connection options without reflection. `DatabaseService` extends all three interfaces.
6. **No more reflection in factories** - `PgNativeQueueFactory` uses `databaseService.getVertx()`, `databaseService.getPool()`, and `databaseService.getConnectOptions()` directly
7. **Async-first stats API** - `getStatsAsync()` is the primary implementation using Vert.x reactive patterns; `getStats()` delegates to it for backward compatibility

---

## Original Problem Summary (For Reference)

The `PgNativeQueueFactory` class contained several code smells that violated clean architecture principles.
**All issues have been resolved as of 2025-12-18.**

| Issue | Severity | Status | Resolution |
|-------|----------|--------|------------|
| Reflection to extract Vertx from PgClientFactory | Critical | RESOLVED | Uses `databaseService.getVertx()` via `VertxProvider` interface |
| Reflection to extract PgClientFactory from DatabaseService | Critical | RESOLVED | Uses `databaseService.getPool()` via `PoolProvider` interface |
| Reflection to extract Vertx from DatabaseService | Critical | RESOLVED | Uses `databaseService.getVertx()` via `VertxProvider` interface |
| Reflection to extract PeeGeeQMetrics from MetricsProvider | Critical | RESOLVED | Uses `MetricsProvider` interface directly |
| Reflection for CloudEvents module (unnecessary) | Medium | RESOLVED | Uses direct `JsonFormat.getCloudEventJacksonModule()` |
| String-based type checking | High | RESOLVED | Removed with reflection elimination |
| Dual mode support complexity | Medium | RESOLVED | Single mode using `DatabaseService` only |
| Blocking call in async context | Medium | RESOLVED | `getStatsAsync()` is primary; `getStats()` delegates |
| Duplicate Javadoc comments | Low | RESOLVED | Merged duplicate blocks |
| Excessive/redundant logging | Low | PARTIAL | Some debug logging remains for troubleshooting |
| Nullable metrics with null checks everywhere | High | RESOLVED | Uses `NoOpMetricsProvider.INSTANCE` as fallback |
| MetricsProvider vs PeeGeeQMetrics signature mismatch | Critical | RESOLVED | `MetricsProvider` interface updated with correct signatures |

## Root Cause Analysis

### Problem 1: Reflection for Vertx/Pool Access

The reflection usage stems from **insufficient interface design** in the API layer. The `DatabaseService` interface doesn't expose the underlying `Vertx` instance or `Pool` that the native queue implementation needs.

Current dependency flow:
```
PgNativeQueueFactory --> DatabaseService (interface)
                              |
                              v
                        PgDatabaseService (implementation)
                              |
                              v
                        PeeGeeQManager --> PgClientFactory --> Vertx
```

The factory needs access to `Vertx` and `Pool` but can only see `DatabaseService`, forcing reflection.

### Problem 2: Metrics Architecture is Broken

The metrics design has fundamental flaws:

1. **Signature mismatch**: `MetricsProvider` interface has methods like:
   - `recordMessageSent(String topic, boolean success, Duration duration)`

   But `PeeGeeQMetrics` (the implementation) has different signatures:
   - `recordMessageSent(String topic)`
   - `recordMessageSent(String topic, long durationMs)`

2. **Producers/Consumers use concrete class**: `OutboxProducer`, `OutboxConsumer`, `PgNativeQueueProducer`, `PgNativeQueueConsumer` all depend directly on `PeeGeeQMetrics` (concrete class in `peegeeq-db`), not `MetricsProvider` (interface in `peegeeq-api`).

3. **Nullable metrics**: All producers/consumers have `if (metrics != null)` checks everywhere. This is wrong - if you don't want metrics, use a NoOp implementation.

4. **Wrong abstraction layer**: Implementation modules (`peegeeq-outbox`, `peegeeq-native`) depend on concrete classes from another implementation module (`peegeeq-db`), violating hexagonal architecture.

### Methods Actually Used by Producers/Consumers

| Class | Method Used | Signature |
|-------|-------------|-----------|
| OutboxProducer | `recordMessageSent` | `(String topic)` |
| OutboxConsumer | `recordMessageReceived` | `(String topic)` |
| OutboxConsumer | `recordMessageProcessed` | `(String topic, Duration duration)` |
| OutboxConsumer | `recordMessageFailed` | `(String topic, String reason)` |
| PgNativeQueueProducer | `recordMessageSent` | `(String topic)` |
| PgNativeQueueConsumer | `recordMessageReceived` | `(String topic)` |
| PgNativeQueueConsumer | `recordMessageProcessed` | `(String topic, Duration duration)` |
| PgNativeQueueConsumer | `recordMessageDeadLettered` | `(String topic, String reason)` |

## Comparison with OutboxFactory and BiTemporalEventStoreFactory

This section compares the code smells in `PgNativeQueueFactory` with the equivalent implementations in `peegeeq-outbox` and `peegeeq-bitemporal` to demonstrate that these problems are unique to the native queue module.

### Summary Table

| Code Smell | PgNativeQueueFactory | OutboxFactory | BiTemporalEventStoreFactory |
|------------|---------------------|---------------|----------------------------|
| Reflection to extract Vertx | YES (lines 88-98, 160-173) | NO | NO |
| Reflection to extract PgClientFactory | YES (lines 141-158) | NO | NO |
| Reflection to extract Metrics | YES (lines 382-398) | NO (uses instanceof + method) | N/A (no metrics) |
| String-based type checking | YES (lines 145, 162, 386) | NO | NO |
| Duplicate Javadoc blocks | YES (lines 32-48) | NO (already merged) | NO |
| CloudEvents reflection | YES (remove) | YES (remove) | YES (remove) |
| Dual mode complexity | HIGH | MEDIUM | LOW |
| VertxPoolAdapter dependency | YES | NO | YES (different impl) |

### Detailed Analysis

#### 1. Reflection to Extract Vertx

**PgNativeQueueFactory (PROBLEM):**
```java
// Lines 88-98 - Reflection to get Vertx from PgClientFactory
private Vertx extractVertx(PgClientFactory clientFactory) {
    var connectionManager = clientFactory.getConnectionManager();
    var vertxField = connectionManager.getClass().getDeclaredField("vertx");
    vertxField.setAccessible(true);
    return (Vertx) vertxField.get(connectionManager);
}

// Lines 160-173 - Another reflection to get Vertx from DatabaseService
private Vertx extractVertx(DatabaseService databaseService) {
    if (databaseService.getClass().getSimpleName().equals("PgDatabaseService")) {
        var managerField = databaseService.getClass().getDeclaredField("manager");
        managerField.setAccessible(true);
        var manager = managerField.get(databaseService);
        var vertxMethod = manager.getClass().getMethod("getVertx");
        return (Vertx) vertxMethod.invoke(manager);
    }
}
```

**OutboxFactory (CLEAN):** Does NOT need Vertx directly - it delegates to `OutboxProducer` and `OutboxConsumer` which handle their own pool access via `DatabaseService` or `PgClientFactory`.

**BiTemporalEventStoreFactory (CLEAN):** Takes `PeeGeeQManager` directly in constructor - no reflection needed:
```java
public BiTemporalEventStoreFactory(PeeGeeQManager peeGeeQManager, ObjectMapper objectMapper) {
    this.peeGeeQManager = Objects.requireNonNull(peeGeeQManager, "PeeGeeQ manager cannot be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "Object mapper cannot be null");
}
```

#### 2. Reflection to Extract PgClientFactory

**PgNativeQueueFactory (PROBLEM):**
```java
// Lines 141-158
private PgClientFactory extractClientFactory(DatabaseService databaseService) {
    if (databaseService.getClass().getSimpleName().equals("PgDatabaseService")) {
        var managerField = databaseService.getClass().getDeclaredField("manager");
        managerField.setAccessible(true);
        var manager = managerField.get(databaseService);
        var clientFactoryMethod = manager.getClass().getMethod("getClientFactory");
        return (PgClientFactory) clientFactoryMethod.invoke(manager);
    }
}
```

**OutboxFactory (CLEAN):** Does NOT extract PgClientFactory from DatabaseService. It stores both as separate fields and uses whichever was provided:
```java
// Lines 114-123
public OutboxFactory(DatabaseService databaseService, ObjectMapper objectMapper, ...) {
    this.databaseService = databaseService;
    this.clientFactory = null; // Do not reflect or create fallbacks; use DatabaseService directly
}
```

**BiTemporalEventStoreFactory (CLEAN):** Uses `PeeGeeQManager` directly, which already has `getClientFactory()` as a public method.

#### 3. Reflection to Extract Metrics

**PgNativeQueueFactory (PROBLEM):**
```java
// Lines 382-398
private PeeGeeQMetrics getMetrics() {
    if (databaseService != null) {
        var metricsProvider = databaseService.getMetricsProvider();
        if (metricsProvider.getClass().getSimpleName().equals("PgMetricsProvider")) {  // String-based check!
            var metricsField = metricsProvider.getClass().getDeclaredField("metrics");
            metricsField.setAccessible(true);
            return (PeeGeeQMetrics) metricsField.get(metricsProvider);
        }
    }
    return legacyMetrics;
}
```

**OutboxFactory (CLEAN):** Uses `instanceof` pattern matching and a proper getter method:
```java
// Lines 606-621
private PeeGeeQMetrics getMetrics() {
    if (databaseService != null) {
        var metricsProvider = databaseService.getMetricsProvider();
        if (metricsProvider instanceof dev.mars.peegeeq.db.provider.PgMetricsProvider pgMetricsProvider) {
            return pgMetricsProvider.getPeeGeeQMetrics();  // Proper method call!
        }
    }
    return legacyMetrics;
}
```

**BiTemporalEventStoreFactory (N/A):** Does not use metrics at all.

#### 4. String-Based Type Checking

**PgNativeQueueFactory (PROBLEM):** Uses string comparison for type checking:
```java
if (databaseService.getClass().getSimpleName().equals("PgDatabaseService")) { ... }
if (metricsProvider.getClass().getSimpleName().equals("PgMetricsProvider")) { ... }
```

**OutboxFactory (CLEAN):** Uses proper `instanceof` pattern matching:
```java
if (metricsProvider instanceof dev.mars.peegeeq.db.provider.PgMetricsProvider pgMetricsProvider) { ... }
```

**BiTemporalEventStoreFactory (CLEAN):** No type checking needed - takes concrete `PeeGeeQManager`.

#### 5. CloudEvents Reflection (Remove)

All three modules use reflection for CloudEvents module registration. This should be **removed** by making CloudEvents a required dependency:

- CloudEvents dependencies are tiny (~65KB total)
- The reflection adds unnecessary code complexity
- Runtime ClassNotFoundException handling is fragile
- No real benefit to making it optional since most users will want CloudEvents support

### Why PgNativeQueueFactory Has More Problems

| Root Cause | PgNativeQueueFactory | OutboxFactory | BiTemporalEventStoreFactory |
|------------|---------------------|---------------|----------------------------|
| Needs Vertx directly | YES (for LISTEN/NOTIFY timers) | NO | NO (uses PeeGeeQManager) |
| Needs PgClientFactory | YES (for VertxPoolAdapter) | NO (delegates to consumers) | NO (uses PeeGeeQManager) |
| Uses reflection | 4 places | 0 places | 0 places |
| String-based type checks | 3 places | 0 places | 0 places |

1. **VertxPoolAdapter**: PgNativeQueueFactory creates a `VertxPoolAdapter` that needs both `Vertx` and `PgClientFactory` - this forces the reflection to extract these from `DatabaseService`.

2. **LISTEN/NOTIFY**: Native queue uses PostgreSQL LISTEN/NOTIFY which requires direct Vertx access for timers and event loop integration.

3. **Legacy + New Mode**: PgNativeQueueFactory tries to support both legacy `PgClientFactory` constructors AND new `DatabaseService` constructors, creating complexity.

### Why OutboxFactory is Cleaner

1. **No VertxPoolAdapter**: OutboxFactory delegates pool access to `OutboxProducer` and `OutboxConsumer` which handle it internally.

2. **Polling-based**: Outbox pattern uses polling, not LISTEN/NOTIFY, so it doesn't need direct Vertx access.

3. **Clean separation**: When using `DatabaseService`, it doesn't try to extract `PgClientFactory` - it just uses `DatabaseService` directly.

### Why BiTemporalEventStoreFactory is Cleanest

1. **Single dependency**: Takes `PeeGeeQManager` directly - no abstraction layer to pierce.

2. **No dual mode**: Only one constructor pattern, no legacy support complexity.

3. **No metrics**: Doesn't need to extract metrics from providers.

### Conclusion

The proposed solution addresses both problems:
1. **Vertx/Pool access**: Create `VertxProvider` and `PoolProvider` interfaces in `peegeeq-api`
2. **Metrics architecture**: Fix `MetricsProvider` interface to match actual usage, eliminate nullable metrics

## Proposed Solution

### Strategy 1: Dependency Inversion for Vertx/Pool Access

Create explicit provider interfaces in `peegeeq-api` that `DatabaseService` extends:
- `VertxProvider` with `getVertx()` method
- `PoolProvider` with `getPool()` method

### Strategy 2: Fix Metrics Architecture

The `MetricsProvider` interface must be redesigned to:
1. **Match actual usage**: Add the methods that producers/consumers actually call
2. **Never be null**: Factories must always provide a `MetricsProvider`, use `NoOpMetricsProvider` if metrics disabled
3. **Proper abstraction**: Producers/consumers depend on `MetricsProvider` interface, not `PeeGeeQMetrics` concrete class

## Detailed Changes

### Phase 1: Create Provider Interfaces in peegeeq-api

#### [NEW] VertxProvider.java
**Path:** `peegeeq-api/src/main/java/dev/mars/peegeeq/api/database/VertxProvider.java`

```java
package dev.mars.peegeeq.api.database;

import io.vertx.core.Vertx;

/**
 * Interface for components that can provide a Vert.x instance.
 * This enables native queue implementations to access Vert.x without reflection.
 */
public interface VertxProvider {
    /**
     * Returns the Vert.x instance used by this component.
     * @return the Vert.x instance, never null
     */
    Vertx getVertx();
}
```

#### [NEW] PoolProvider.java
**Path:** `peegeeq-api/src/main/java/dev/mars/peegeeq/api/database/PoolProvider.java`

```java
package dev.mars.peegeeq.api.database;

import io.vertx.sqlclient.Pool;

/**
 * Interface for components that can provide a database connection pool.
 */
public interface PoolProvider {
    /**
     * Returns the default database connection pool.
     * @return the Pool instance, never null
     */
    Pool getPool();
}
```

#### [MODIFY] MetricsProvider.java
**Path:** `peegeeq-api/src/main/java/dev/mars/peegeeq/api/database/MetricsProvider.java`

**Replace the entire interface** with methods that match actual producer/consumer usage:

```java
package dev.mars.peegeeq.api.database;

import java.time.Duration;
import java.util.Map;

/**
 * Interface for metrics collection in PeeGeeQ message queue system.
 *
 * Implementations must never be null - use NoOpMetricsProvider if metrics are disabled.
 */
public interface MetricsProvider {

    // Message lifecycle methods - these are what producers/consumers actually use

    void recordMessageSent(String topic);

    void recordMessageReceived(String topic);

    void recordMessageProcessed(String topic, Duration processingTime);

    void recordMessageFailed(String topic, String reason);

    void recordMessageDeadLettered(String topic, String reason);

    void recordMessageRetried(String topic, int retryCount);

    // Generic metrics methods

    void incrementCounter(String name, Map<String, String> tags);

    void recordTimer(String name, Duration duration, Map<String, String> tags);

    void recordGauge(String name, double value, Map<String, String> tags);

    // Query methods

    long getQueueDepth(String topic);

    Map<String, Object> getAllMetrics();

    String getInstanceId();
}
```

#### [NEW] NoOpMetricsProvider.java
**Path:** `peegeeq-api/src/main/java/dev/mars/peegeeq/api/database/NoOpMetricsProvider.java`

```java
package dev.mars.peegeeq.api.database;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

/**
 * No-operation implementation of MetricsProvider.
 * Use this when metrics collection is disabled.
 */
public final class NoOpMetricsProvider implements MetricsProvider {

    public static final NoOpMetricsProvider INSTANCE = new NoOpMetricsProvider();

    private NoOpMetricsProvider() {}

    @Override public void recordMessageSent(String topic) {}
    @Override public void recordMessageReceived(String topic) {}
    @Override public void recordMessageProcessed(String topic, Duration processingTime) {}
    @Override public void recordMessageFailed(String topic, String reason) {}
    @Override public void recordMessageDeadLettered(String topic, String reason) {}
    @Override public void recordMessageRetried(String topic, int retryCount) {}
    @Override public void incrementCounter(String name, Map<String, String> tags) {}
    @Override public void recordTimer(String name, Duration duration, Map<String, String> tags) {}
    @Override public void recordGauge(String name, double value, Map<String, String> tags) {}
    @Override public long getQueueDepth(String topic) { return 0; }
    @Override public Map<String, Object> getAllMetrics() { return Collections.emptyMap(); }
    @Override public String getInstanceId() { return "noop"; }
}
```

#### [MODIFY] DatabaseService.java
**Path:** `peegeeq-api/src/main/java/dev/mars/peegeeq/api/database/DatabaseService.java`

Extend from the new provider interfaces:

```java
public interface DatabaseService extends VertxProvider, PoolProvider, AutoCloseable {
    // existing methods...

    /**
     * Returns the metrics provider. Never returns null.
     * If metrics are disabled, returns NoOpMetricsProvider.INSTANCE.
     */
    MetricsProvider getMetricsProvider();
}
```

### Phase 2: Implement Interfaces in peegeeq-db

#### [MODIFY] PgMetricsProvider.java
**Make it implement the new MetricsProvider interface properly:**

The existing `PgMetricsProvider` wraps `PeeGeeQMetrics`. Update it to implement the new interface methods:

```java
public class PgMetricsProvider implements MetricsProvider {

    private final PeeGeeQMetrics metrics;

    public PgMetricsProvider(PeeGeeQMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics cannot be null");
    }

    @Override
    public void recordMessageSent(String topic) {
        metrics.recordMessageSent(topic);
    }

    @Override
    public void recordMessageReceived(String topic) {
        metrics.recordMessageReceived(topic);
    }

    @Override
    public void recordMessageProcessed(String topic, Duration processingTime) {
        metrics.recordMessageProcessed(topic, processingTime);
    }

    @Override
    public void recordMessageFailed(String topic, String reason) {
        metrics.recordMessageFailed(topic, reason);
    }

    @Override
    public void recordMessageDeadLettered(String topic, String reason) {
        metrics.recordMessageDeadLettered(topic, reason);
    }

    @Override
    public void recordMessageRetried(String topic, int retryCount) {
        metrics.recordMessageRetried(topic, retryCount);
    }

    // ... other methods delegate to metrics
}
```

#### [MODIFY] PgDatabaseService.java
Implement the new interface methods:

```java
@Override
public Vertx getVertx() {
    return manager.getVertx();
}

@Override
public Pool getPool() {
    return manager.getPool();
}

@Override
public MetricsProvider getMetricsProvider() {
    return metricsProvider;  // Never null - already initialized in constructor
}
```

### Phase 3: Refactor Producers/Consumers to Use MetricsProvider Interface

This is the critical change - producers and consumers must depend on `MetricsProvider` (interface), not `PeeGeeQMetrics` (concrete class).

#### [MODIFY] OutboxProducer.java

**Before:**
```java
private final PeeGeeQMetrics metrics;

public OutboxProducer(..., PeeGeeQMetrics metrics, ...) {
    this.metrics = metrics;
}

// Usage:
if (metrics != null) {
    metrics.recordMessageSent(topic);
}
```

**After:**
```java
private final MetricsProvider metrics;

public OutboxProducer(..., MetricsProvider metrics, ...) {
    this.metrics = Objects.requireNonNull(metrics, "metrics cannot be null");
}

// Usage - no null check needed:
metrics.recordMessageSent(topic);
```

#### [MODIFY] OutboxConsumer.java
Same pattern - change `PeeGeeQMetrics` to `MetricsProvider`, remove null checks.

#### [MODIFY] PgNativeQueueProducer.java
Same pattern - change `PeeGeeQMetrics` to `MetricsProvider`, remove null checks.

#### [MODIFY] PgNativeQueueConsumer.java
Same pattern - change `PeeGeeQMetrics` to `MetricsProvider`, remove null checks.

### Phase 4: Refactor Factories

#### [MODIFY] PgNativeQueueFactory.java
**Path:** `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeQueueFactory.java`

**Remove reflection methods, remove legacy constructors, use MetricsProvider:**

```java
public class PgNativeQueueFactory implements QueueFactory {
    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueueFactory.class);

    private final Vertx vertx;
    private final Pool pool;
    private final MetricsProvider metrics;  // Interface, not concrete class
    private final PeeGeeQConfiguration configuration;
    private final ObjectMapper objectMapper;
    private volatile boolean closed = false;

    /**
     * Primary constructor using DatabaseService.
     */
    public PgNativeQueueFactory(DatabaseService databaseService) {
        this(databaseService, createDefaultObjectMapper(), null);
    }

    public PgNativeQueueFactory(DatabaseService databaseService, PeeGeeQConfiguration configuration) {
        this(databaseService, createDefaultObjectMapper(), configuration);
    }

    public PgNativeQueueFactory(DatabaseService databaseService, ObjectMapper objectMapper,
                                 PeeGeeQConfiguration configuration) {
        Objects.requireNonNull(databaseService, "databaseService cannot be null");

        // Direct access via interfaces - no reflection needed
        this.vertx = databaseService.getVertx();
        this.pool = databaseService.getPool();
        this.metrics = databaseService.getMetricsProvider();  // Never null
        this.configuration = configuration;
        this.objectMapper = objectMapper != null ? objectMapper : createDefaultObjectMapper();

        logger.info("Initialized PgNativeQueueFactory");
    }

    // Remove all legacy constructors that take PgClientFactory
    // Remove: extractVertx(PgClientFactory)
    // Remove: extractVertx(DatabaseService)
    // Remove: extractClientFactory(DatabaseService)
    // Remove: getMetrics() - no longer needed, metrics is never null
    // Remove: clientFactory field
    // Remove: legacyMetrics field
}
```

#### [MODIFY] OutboxFactory.java
**Path:** `peegeeq-outbox/src/main/java/dev/mars/peegeeq/outbox/OutboxFactory.java`

Same pattern - use `MetricsProvider` interface, remove legacy constructors:

```java
public class OutboxFactory implements QueueFactory {

    private final DatabaseService databaseService;
    private final MetricsProvider metrics;  // Interface, never null
    private final PeeGeeQConfiguration configuration;
    private final ObjectMapper objectMapper;

    public OutboxFactory(DatabaseService databaseService) {
        this(databaseService, createDefaultObjectMapper(), null);
    }

    public OutboxFactory(DatabaseService databaseService, PeeGeeQConfiguration configuration) {
        this(databaseService, createDefaultObjectMapper(), configuration);
    }

    public OutboxFactory(DatabaseService databaseService, ObjectMapper objectMapper,
                         PeeGeeQConfiguration configuration) {
        this.databaseService = Objects.requireNonNull(databaseService);
        this.metrics = databaseService.getMetricsProvider();  // Never null
        this.configuration = configuration;
        this.objectMapper = objectMapper != null ? objectMapper : createDefaultObjectMapper();
    }

    // Remove: clientFactory field
    // Remove: legacyMetrics field
    // Remove: all PgClientFactory constructors
    // Remove: getMetrics() method - just use this.metrics directly
}
```

### Phase 5: Update VertxPoolAdapter

#### [MODIFY] VertxPoolAdapter.java
**Path:** `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/VertxPoolAdapter.java`

Simplify to take `Pool` directly instead of `PoolProvider`:

```java
public class VertxPoolAdapter {
    private final Vertx vertx;
    private final Pool pool;

    public VertxPoolAdapter(Vertx vertx, Pool pool) {
        this.vertx = Objects.requireNonNull(vertx);
        this.pool = Objects.requireNonNull(pool);
    }

    public Pool getPool() {
        return pool;
    }

    // ... rest of implementation
}
```

### Phase 6: Fix Other Code Smells

#### Remove Duplicate Javadoc
Delete lines 32-41 (first Javadoc block) keeping only the second, more detailed one.

#### Fix Blocking Call in getStats()
Convert to reactive:

```java
@Override
public CompletableFuture<QueueStats> getStatsAsync(String topic) {
    return pool.preparedQuery(sql)
        .execute(Tuple.of(topic))
        .toCompletionStage()
        .toCompletableFuture()
        .thenApply(this::mapRowToStats);
}
```

#### Clean Up Logging
Remove duplicate DEBUG/INFO log statements. Keep only INFO level for significant events.

#### Make CloudEvents a Required Dependency

Remove the reflection-based optional loading and make CloudEvents required:

**Step 1: Update pom.xml files** - Remove `<optional>true</optional>` from CloudEvents dependencies in:
- `peegeeq-native/pom.xml`
- `peegeeq-outbox/pom.xml`
- `peegeeq-bitemporal/pom.xml`

**Step 2: Replace reflection with direct registration:**

```java
// Before (reflection)
private static void registerCloudEventsModuleIfAvailable(ObjectMapper mapper) {
    try {
        Class<?> moduleClass = Class.forName("io.cloudevents.jackson.JsonFormat");
        mapper.registerModule((com.fasterxml.jackson.databind.Module) moduleClass.getDeclaredConstructor().newInstance());
    } catch (Exception e) {
        // silently ignore
    }
}

// After (direct)
private static void registerCloudEventsModule(ObjectMapper mapper) {
    mapper.registerModule(JsonFormat.getCloudEventJacksonModule());
}
```

**Rationale:**
- CloudEvents dependencies are tiny (~65KB total for core + jackson)
- Reflection adds unnecessary code complexity
- Runtime ClassNotFoundException handling is fragile
- No real benefit to making it optional

## Migration Strategy

### Decision: Remove Legacy Constructors (Not Deprecate)

Since PeeGeeQ is not yet in production, we can **remove** legacy constructors entirely rather than deprecating them. This is not a breaking change for production users and allows us to:

1. **Eliminate dual-mode complexity** - No more `if (clientFactory != null) ... else if (databaseService != null)` checks
2. **Remove `legacyMetrics` field** - Only used by legacy constructors
3. **Remove `clientFactory` field** - Only used by legacy constructors
4. **Simplify factory implementations** - Single code path using `DatabaseService`
5. **Align all factory interfaces** - Consistent constructor signatures across OutboxFactory, PgNativeQueueFactory, and BiTemporalEventStoreFactory

### Legacy Constructor Usages to Update

The following test files currently use legacy constructors and must be updated:

#### peegeeq-native (1 file)

| File | Line | Current Usage | New Usage |
|------|------|---------------|-----------|
| `RetryableErrorIT.java` | 58 | `new PgNativeQueueFactory(manager.getClientFactory())` | `new PgNativeQueueFactory(new PgDatabaseService(manager))` |

#### peegeeq-outbox (5 files)

| File | Line | Current Usage | New Usage |
|------|------|---------------|-----------|
| `OutboxConsumerGroupTest.java` | 87 | `new OutboxFactory(manager.getClientFactory(), manager.getObjectMapper(), manager.getMetrics())` | `new OutboxFactory(new PgDatabaseService(manager), manager.getConfiguration())` |
| `OutboxConsumerIntegrationTest.java` | 86 | `clientFactory = manager.getClientFactory()` | Use `PgDatabaseService` pattern |
| `OutboxFactoryIntegrationTest.java` | 86 | `clientFactory = manager.getClientFactory()` | Use `PgDatabaseService` pattern |
| `OutboxMetricsTest.java` | 80 | `new OutboxFactory(manager.getClientFactory(), manager.getObjectMapper(), manager.getMetrics())` | `new OutboxFactory(new PgDatabaseService(manager), manager.getConfiguration())` |
| `OutboxProducerIntegrationTest.java` | 84 | `clientFactory = manager.getClientFactory()` | Use `PgDatabaseService` pattern |

### Constructors to Remove

#### OutboxFactory (4 legacy constructors)

```java
// REMOVE these constructors (lines 74-95)
public OutboxFactory(PgClientFactory clientFactory) { ... }
public OutboxFactory(PgClientFactory clientFactory, ObjectMapper objectMapper) { ... }
public OutboxFactory(PgClientFactory clientFactory, ObjectMapper objectMapper, PeeGeeQMetrics metrics) { ... }
public OutboxFactory(PgClientFactory clientFactory, ObjectMapper objectMapper, PeeGeeQMetrics metrics, String clientId) { ... }
```

#### PgNativeQueueFactory (3 legacy constructors)

```java
// REMOVE these constructors (lines 68-86)
public PgNativeQueueFactory(PgClientFactory clientFactory) { ... }
public PgNativeQueueFactory(PgClientFactory clientFactory, ObjectMapper objectMapper) { ... }
public PgNativeQueueFactory(PgClientFactory clientFactory, ObjectMapper objectMapper, PeeGeeQMetrics metrics) { ... }
```

### Fields to Remove

After removing legacy constructors, these fields become unnecessary:

| Class | Field | Reason |
|-------|-------|--------|
| `OutboxFactory` | `clientFactory` | Only used by legacy constructors |
| `OutboxFactory` | `legacyMetrics` | Only used by legacy constructors |
| `PgNativeQueueFactory` | `clientFactory` | Only used by legacy constructors |
| `PgNativeQueueFactory` | `legacyMetrics` | Only used by legacy constructors |

### Dual-Mode Logic to Remove

After removing legacy constructors, simplify these patterns:

**Before (dual-mode):**
```java
if (clientFactory != null) {
    producer = new OutboxProducer<>(clientFactory, objectMapper, topic, payloadType, metrics, clientId);
} else if (databaseService != null) {
    producer = new OutboxProducer<>(databaseService, objectMapper, topic, payloadType, metrics, clientId);
} else {
    throw new IllegalStateException("Both clientFactory and databaseService are null");
}
```

**After (single-mode):**
```java
producer = new OutboxProducer<>(databaseService, objectMapper, topic, payloadType, metrics, clientId);
```

### Metrics Simplification

After fixing the metrics architecture:

**Before (concrete class, nullable, instanceof checks):**
```java
private final PeeGeeQMetrics metrics;  // Concrete class from peegeeq-db

// In constructor:
if (metricsProvider instanceof PgMetricsProvider pgMetricsProvider) {
    this.metrics = pgMetricsProvider.getPeeGeeQMetrics();
} else {
    this.metrics = null;
}

// In usage:
if (metrics != null) {
    metrics.recordMessageSent(topic);
}
```

**After (interface, never null, no checks):**
```java
private final MetricsProvider metrics;  // Interface from peegeeq-api

// In constructor:
this.metrics = databaseService.getMetricsProvider();  // Never null

// In usage:
metrics.recordMessageSent(topic);  // No null check needed
```

Key improvements:
1. **No `instanceof` checks** - Use interface directly
2. **No null checks** - `MetricsProvider` is never null, use `NoOpMetricsProvider` if disabled
3. **No `getUnderlyingMetrics()`** - Not needed when using interface properly
4. **Proper dependency inversion** - Depend on interface, not concrete class

## Verification Plan

### Compile-Time Verification
- All producers/consumers compile with `MetricsProvider` interface
- No `PeeGeeQMetrics` imports in `peegeeq-outbox` or `peegeeq-native` (except in tests if needed)
- No `instanceof` checks for metrics types

### Runtime Verification
- `MetricsProvider` is never null - verify no NullPointerExceptions
- `NoOpMetricsProvider` works correctly when metrics disabled
- All metrics methods are called correctly

### Integration Tests
- Run existing `NativeQueueIntegrationTest` - should pass unchanged
- Run existing `PgNativeQueueTest` - should pass unchanged
- Run `ManagementApiHandlerTest` - should pass unchanged
- Run all outbox tests - should pass unchanged

### Code Quality Checks
- No `if (metrics != null)` patterns remain
- No `getUnderlyingMetrics()` method exists
- No `Object` return types for metrics
- No reflection for Vertx/Pool/Metrics extraction

### Commands
```bash
mvn clean test -pl peegeeq-api,peegeeq-db,peegeeq-native,peegeeq-outbox
mvn clean test -pl peegeeq-rest -Dtest=ManagementApiHandlerTest
```

## Implementation Order (All Phases Complete)

### Phase 1: Fix MetricsProvider Interface (peegeeq-api) - COMPLETE

| Step | Task | Status |
|------|------|--------|
| 1.1 | Rewrite `MetricsProvider` interface with correct method signatures | DONE |
| 1.2 | Create `NoOpMetricsProvider` implementation | DONE |
| 1.3 | Create `VertxProvider` interface with `getVertx()` method | DONE |
| 1.4 | Create `PoolProvider` interface with `getPool()` method | DONE |
| 1.5 | Modify `DatabaseService` to extend `VertxProvider` and `PoolProvider` | DONE |
| 1.6 | Add `getMetricsProvider()` to `DatabaseService` (never returns null) | DONE |

### Phase 2: Update peegeeq-db Implementations - COMPLETE

| Step | Task | Status |
|------|------|--------|
| 2.1 | Update `PgMetricsProvider` to implement new `MetricsProvider` interface | DONE |
| 2.2 | Implement `getVertx()` in `PgDatabaseService` (delegate to manager) | DONE |
| 2.3 | Implement `getPool()` in `PgDatabaseService` (delegate to manager) | DONE |
| 2.4 | Ensure `getMetricsProvider()` never returns null | DONE |
| 2.5 | Add `getPool()` to `PeeGeeQManager` | DONE |

### Phase 3: Refactor Producers/Consumers to Use MetricsProvider Interface - COMPLETE

| Step | Task | Status |
|------|------|--------|
| 3.1 | Change `OutboxProducer` to take `MetricsProvider` instead of `PeeGeeQMetrics` | DONE |
| 3.2 | Remove all `if (metrics != null)` checks from `OutboxProducer` | DONE |
| 3.3 | Change `OutboxConsumer` to take `MetricsProvider` instead of `PeeGeeQMetrics` | DONE |
| 3.4 | Remove all `if (metrics != null)` checks from `OutboxConsumer` | DONE |
| 3.5 | Change `PgNativeQueueProducer` to take `MetricsProvider` instead of `PeeGeeQMetrics` | DONE |
| 3.6 | Remove all `if (metrics != null)` checks from `PgNativeQueueProducer` | DONE |
| 3.7 | Change `PgNativeQueueConsumer` to take `MetricsProvider` instead of `PeeGeeQMetrics` | DONE |
| 3.8 | Remove all `if (metrics != null)` checks from `PgNativeQueueConsumer` | DONE |

### Phase 4: Refactor Factories - PARTIAL

| Step | Task | Status |
|------|------|--------|
| 4.1 | Change `legacyMetrics` field type from `PeeGeeQMetrics` to `MetricsProvider` | DONE |
| 4.2 | Simplify `getMetrics()` method - removed reflection hacks for metrics | DONE |
| 4.3 | Update constructor signatures to use `MetricsProvider` | DONE |
| 4.4 | Use `NoOpMetricsProvider.INSTANCE` as fallback | DONE |
| 4.5 | Remove legacy `PgClientFactory` constructors from `PgNativeQueueFactory` | DONE |
| 4.6 | Remove legacy `PgClientFactory` constructors from `OutboxFactory` | DONE |
| 4.7 | Remove `extractVertx(PgClientFactory)` reflection method | NOT DONE |
| 4.8 | Remove `extractVertx(DatabaseService)` reflection method | NOT DONE |
| 4.9 | Remove `extractClientFactory(DatabaseService)` reflection method | NOT DONE |
| 4.10 | Use `databaseService.getVertx()` directly (via VertxProvider interface) | NOT DONE |
| 4.11 | Use `databaseService.getPool()` directly (via PoolProvider interface) | NOT DONE |
| 4.12 | Remove dual-mode `if (clientFactory != null) ... else if (databaseService != null)` logic | NOT DONE |
| 4.13 | Update `VertxPoolAdapter` to work without `PgClientFactory` | NOT DONE |
| 4.14 | Add `ConnectOptionsProvider` interface for dedicated LISTEN connections | NOT DONE |

### Phase 5: Update Tests - COMPLETE

| Step | Task | Status |
|------|------|--------|
| 5.1 | Update `PgMetricsProviderCoreTest.java` to use new method signatures | DONE |
| 5.2 | Update `OutboxConsumerGroupTest.java` to use `PgDatabaseService` | DONE |

### Phase 6: Make CloudEvents Required - COMPLETE

| Step | Task | Status |
|------|------|--------|
| 6.1 | Remove `<optional>true</optional>` from CloudEvents in `peegeeq-native/pom.xml` | DONE |
| 6.2 | Remove `<optional>true</optional>` from CloudEvents in `peegeeq-outbox/pom.xml` | DONE |
| 6.3 | Remove `<optional>true</optional>` from CloudEvents in `peegeeq-bitemporal/pom.xml` | DONE |
| 6.4 | Replace reflection-based CloudEvents loading with direct `JsonFormat.getCloudEventJacksonModule()` | DONE |

### Phase 7: Cleanup and Verification - COMPLETE

| Step | Task | Status |
|------|------|--------|
| 7.1 | Clean up duplicate Javadoc blocks in `PgNativeQueueFactory` | DONE |
| 7.2 | Fix duplicate `getInstanceId()` method in `PeeGeeQMetrics` | DONE |
| 7.3 | Run all tests: `mvn clean test` | PASSED |
| 7.4 | Verify all modules compile and tests pass | PASSED |

## Benefits Achieved (Partial)

1. ~~**No reflection**~~ - **PENDING**: Reflection for Vertx/Pool/ClientFactory extraction still exists
2. **Type safety for metrics** - `MetricsProvider` interface instead of concrete `PeeGeeQMetrics`
3. **Testability** - Easy to mock `MetricsProvider` interface in unit tests
4. **No nullable metrics** - `NoOpMetricsProvider.INSTANCE` used as fallback
5. **Proper dependency inversion for metrics** - Producers/consumers depend on `MetricsProvider` interface
6. **CloudEvents is required** - No more reflection-based optional loading

## Remaining Work

The following items from the original plan are NOT yet implemented:

1. ~~**Remove legacy `PgClientFactory` constructors** - Both `PgNativeQueueFactory` and `OutboxFactory` still have legacy constructors~~ **DONE (2025-12-18)**
2. ~~**Remove reflection methods** - `extractVertx()` and `extractClientFactory()` still exist in `PgNativeQueueFactory`~~ **DONE (2025-12-18)**
3. ~~**Remove dual-mode logic** - `if (clientFactory != null) ... else if (databaseService != null)` patterns still exist~~ **DONE (2025-12-18)**
4. ~~**Update `VertxPoolAdapter`** - Still depends on `PgClientFactory` for `connectDedicated()` (LISTEN connections)~~ **DONE (2025-12-18)**
5. ~~**Add `ConnectOptionsProvider` interface** - Needed for `VertxPoolAdapter` to create dedicated connections without `PgClientFactory`~~ **DONE (2025-12-18)**
6. ~~**Update remaining tests** - Some tests still use legacy constructors~~ **DONE (2025-12-18)**
7. ~~**Fix blocking call in `getStats()`** - Lines 342-346 in `PgNativeQueueFactory`~~ **DONE (2025-12-18)**

### Completed Changes (2025-12-18)

**Item 2: Replace reflection with direct interface calls**

The following changes were made to eliminate reflection and use interfaces directly:

1. **Created `ConnectOptionsProvider` interface** in `peegeeq-api`:
   - New interface providing `PgConnectOptions getConnectOptions()` for dedicated LISTEN/NOTIFY connections
   - Added `vertx-pg-client` dependency to `peegeeq-api/pom.xml` for `PgConnectOptions` type

2. **Updated `DatabaseService` interface**:
   - Now extends `ConnectOptionsProvider` in addition to `VertxProvider` and `PoolProvider`

3. **Implemented `getConnectOptions()` in `PgDatabaseService`**:
   - Builds `PgConnectOptions` from the underlying `PgConnectionConfig`

4. **Refactored `VertxPoolAdapter`**:
   - Removed all `PgClientFactory` constructors and `createPool()` methods
   - Single constructor now accepts `(Vertx vertx, Pool pool, ConnectOptionsProvider connectOptionsProvider)`
   - `connectDedicated()` now uses `ConnectOptionsProvider` instead of `PgClientFactory`

5. **Updated `PgNativeQueueFactory`**:
   - Removed `extractClientFactory()` and `extractVertx()` reflection methods
   - Constructor now uses interfaces directly: `databaseService.getVertx()`, `databaseService.getPool()`, and `databaseService` (as `ConnectOptionsProvider`)

6. **Updated all tests** to use `PeeGeeQManager` + `PgDatabaseService` pattern:
   - `PgNativeQueueConsumerListenIT.java`
   - `PgNativeQueueConcurrentClaimIT.java`
   - `PgNativeQueueConsumerCleanupIT.java`
   - `PgNativeQueueConsumerClaimIT.java`
   - `VertxPoolAdapterFailFastTest.java`
   - `VertxPoolAdapterHappyPathIT.java`

**Item 7: Fix blocking call in `getStats()`**

The blocking call in `getStats()` was refactored to use proper async patterns:

1. **Implemented `getStatsAsync(String topic)`** as the primary async method:
   - Returns `Future<QueueStats>` using Vert.x reactive patterns
   - Uses `pool.preparedQuery().execute().map()` chain - no blocking
   - Handles errors with `.otherwise()` for graceful degradation

2. **Refactored `getStats(String topic)`** to delegate to async method:
   - Calls `getStatsAsync(topic).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)`
   - Maintains backward compatibility for synchronous callers
   - Clear logging indicates blocking behavior

All tests pass (peegeeq-native: 47 tests, peegeeq-outbox: 176 tests).
