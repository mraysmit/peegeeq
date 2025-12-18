# PgNativeQueueFactory Refactoring Plan

This document outlines a refactoring plan to eliminate code smells in `PgNativeQueueFactory`, primarily focusing on removing reflection usage and improving architectural clarity.

## Problem Summary

The `PgNativeQueueFactory` class contains several code smells that violate clean architecture principles:

| Issue | Severity | Location |
|-------|----------|----------|
| Reflection to extract Vertx from PgClientFactory | Critical | Lines 88-98 |
| Reflection to extract PgClientFactory from DatabaseService | Critical | Lines 141-158 |
| Reflection to extract Vertx from DatabaseService | Critical | Lines 160-173 |
| Reflection to extract PeeGeeQMetrics from MetricsProvider | Critical | Lines 382-398 |
| Reflection for CloudEvents module (unnecessary) | Medium | Lines 451-460 |
| String-based type checking | High | Lines 145, 162, 386 |
| Dual mode support complexity | Medium | Throughout |
| Blocking call in async context | Medium | Lines 342-346 |
| Duplicate Javadoc comments | Low | Lines 32-48 |
| Excessive/redundant logging | Low | Lines 204-205, 250-253 |

## Root Cause Analysis

The reflection usage stems from **insufficient interface design** in the API layer. The `DatabaseService` interface doesn't expose the underlying `Vertx` instance or `PgClientFactory` that the native queue implementation needs.

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

The factory needs access to `Vertx` and `PgClientFactory` but can only see `DatabaseService`, forcing reflection.

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

The proposed solution (creating `VertxProvider` and `PoolProvider` interfaces) would bring PgNativeQueueFactory to the same clean level as OutboxFactory by:
1. Adding `getVertx()` to `DatabaseService` interface
2. Adding `getPool()` to `DatabaseService` interface
3. Adding `getUnderlyingMetrics()` to `MetricsProvider` interface

This would eliminate all reflection and string-based type checking.

## Proposed Solution

### Strategy: Dependency Inversion with Explicit Interfaces

Instead of extracting dependencies via reflection, we will:
1. Create explicit provider interfaces in `peegeeq-api`
2. Have implementation classes implement these interfaces
3. Pass the required dependencies directly to constructors

## Detailed Changes

### Phase 1: Create Provider Interfaces in peegeeq-api

#### [NEW] VertxProvider.java
**Path:** `peegeeq-api/src/main/java/dev/mars/peegeeq/api/VertxProvider.java`

```java
package dev.mars.peegeeq.api;

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
**Path:** `peegeeq-api/src/main/java/dev/mars/peegeeq/api/PoolProvider.java`

```java
package dev.mars.peegeeq.api;

import io.vertx.sqlclient.Pool;

/**
 * Interface for components that can provide a database connection pool.
 */
public interface PoolProvider {
    /**
     * Returns the database connection pool.
     * @return the Pool instance, never null
     */
    Pool getPool();
    
    /**
     * Returns a pool for the specified pool ID.
     * @param poolId the pool identifier
     * @return the Pool instance for the given ID
     */
    Pool getPool(String poolId);
}
```

#### [MODIFY] MetricsProvider.java
**Path:** `peegeeq-api/src/main/java/dev/mars/peegeeq/api/metrics/MetricsProvider.java`

Add a method to get the underlying metrics implementation:

```java
/**
 * Returns the underlying metrics implementation object.
 * This allows implementation-specific access when needed.
 * @return the underlying metrics object, may be null if not configured
 */
default Object getUnderlyingMetrics() {
    return null;
}
```

#### [MODIFY] DatabaseService.java
**Path:** `peegeeq-api/src/main/java/dev/mars/peegeeq/api/database/DatabaseService.java`

Extend from the new provider interfaces:

```java
public interface DatabaseService extends VertxProvider, PoolProvider, AutoCloseable {
    // existing methods...
}
```

### Phase 2: Implement Interfaces in peegeeq-db

#### [MODIFY] PgDatabaseService.java
Implement the new interface methods:

```java
@Override
public Vertx getVertx() {
    return manager.getVertx();
}

@Override
public Pool getPool() {
    return manager.getClientFactory().getPool();
}

@Override
public Pool getPool(String poolId) {
    return manager.getClientFactory().getPool(poolId);
}
```

#### [MODIFY] PgMetricsProvider.java
Implement `getUnderlyingMetrics()`:

```java
@Override
public Object getUnderlyingMetrics() {
    return this.metrics;  // Return the PeeGeeQMetrics instance
}
```

#### [MODIFY] PgConnectionManager.java
Implement `VertxProvider`:

```java
public class PgConnectionManager implements VertxProvider {
    // existing code...

    @Override
    public Vertx getVertx() {
        return this.vertx;
    }
}
```

### Phase 3: Refactor PgNativeQueueFactory

#### [MODIFY] PgNativeQueueFactory.java
**Path:** `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/PgNativeQueueFactory.java`

**Remove reflection methods and simplify constructors:**

```java
public class PgNativeQueueFactory implements QueueFactory {
    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueueFactory.class);

    private final Vertx vertx;
    private final PoolProvider poolProvider;
    private final PeeGeeQConfiguration configuration;
    private final ObjectMapper objectMapper;
    private final PeeGeeQMetrics metrics;
    private volatile boolean closed = false;

    /**
     * Primary constructor using the new interface pattern.
     */
    public PgNativeQueueFactory(DatabaseService databaseService) {
        this(databaseService, createDefaultObjectMapper(), null);
    }

    public PgNativeQueueFactory(DatabaseService databaseService, PeeGeeQConfiguration configuration) {
        this(databaseService, createDefaultObjectMapper(), configuration);
    }

    public PgNativeQueueFactory(DatabaseService databaseService, ObjectMapper objectMapper,
                                 PeeGeeQConfiguration configuration) {
        // Direct access via interfaces - no reflection needed
        this.vertx = databaseService.getVertx();
        this.poolProvider = databaseService;
        this.configuration = configuration;
        this.objectMapper = objectMapper != null ? objectMapper : createDefaultObjectMapper();

        // Get metrics via the new interface method
        var metricsProvider = databaseService.getMetricsProvider();
        Object underlying = metricsProvider.getUnderlyingMetrics();
        this.metrics = (underlying instanceof PeeGeeQMetrics) ? (PeeGeeQMetrics) underlying : null;

        logger.info("Initialized PgNativeQueueFactory");
    }

    // Remove: extractVertx(PgClientFactory)
    // Remove: extractVertx(DatabaseService)
    // Remove: extractClientFactory(DatabaseService)
    // Remove: getMetrics() reflection logic

    // ... rest of implementation using this.vertx, this.poolProvider, this.metrics directly
}
```

### Phase 4: Update VertxPoolAdapter

#### [MODIFY] VertxPoolAdapter.java
**Path:** `peegeeq-native/src/main/java/dev/mars/peegeeq/pgqueue/VertxPoolAdapter.java`

Update to accept `PoolProvider` instead of `PgClientFactory`:

```java
public class VertxPoolAdapter {
    private final Vertx vertx;
    private final PoolProvider poolProvider;
    private final String poolId;

    public VertxPoolAdapter(Vertx vertx, PoolProvider poolProvider, String poolId) {
        this.vertx = vertx;
        this.poolProvider = poolProvider;
        this.poolId = poolId;
    }

    public Pool getPool() {
        return poolProvider.getPool(poolId);
    }

    // ... rest of implementation
}
```

### Phase 5: Fix Other Code Smells

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

### Metrics Extraction Simplification

After adding `getUnderlyingMetrics()` to `MetricsProvider` interface:

**Before (instanceof check):**
```java
private PeeGeeQMetrics getMetrics() {
    if (databaseService != null) {
        var metricsProvider = databaseService.getMetricsProvider();
        if (metricsProvider instanceof PgMetricsProvider pgMetricsProvider) {
            return pgMetricsProvider.getPeeGeeQMetrics();
        }
    }
    return legacyMetrics;
}
```

**After (interface method):**
```java
private PeeGeeQMetrics getMetrics() {
    Object underlying = databaseService.getMetricsProvider().getUnderlyingMetrics();
    if (underlying instanceof PeeGeeQMetrics m) {
        return m;
    }
    return null;
}
```

## Verification Plan

### Unit Tests
- Verify `VertxProvider.getVertx()` returns non-null Vertx instance
- Verify `PoolProvider.getPool()` returns valid pool
- Verify `MetricsProvider.getUnderlyingMetrics()` returns correct type

### Integration Tests
- Run existing `NativeQueueIntegrationTest` - should pass unchanged
- Run existing `PgNativeQueueTest` - should pass unchanged
- Run `ManagementApiHandlerTest` - should pass unchanged

### Commands
```bash
mvn clean test -pl peegeeq-api,peegeeq-db,peegeeq-native
mvn clean test -pl peegeeq-rest -Dtest=ManagementApiHandlerTest
```

## Implementation Order

### Phase 1: Interface Changes (peegeeq-api)

| Step | Task | Effort |
|------|------|--------|
| 1.1 | Create `VertxProvider` interface with `getVertx()` method | Small |
| 1.2 | Create `PoolProvider` interface with `getPool()` method | Small |
| 1.3 | Add `getUnderlyingMetrics()` to `MetricsProvider` interface | Small |
| 1.4 | Modify `DatabaseService` to extend `VertxProvider` and `PoolProvider` | Small |

### Phase 2: Implementation Changes (peegeeq-db)

| Step | Task | Effort |
|------|------|--------|
| 2.1 | Implement `getVertx()` in `PgDatabaseService` (delegate to manager) | Small |
| 2.2 | Implement `getPool()` in `PgDatabaseService` (delegate to manager) | Small |
| 2.3 | Implement `getUnderlyingMetrics()` in `PgMetricsProvider` | Small |
| 2.4 | Add `getVertx()` to `PgConnectionManager` if not present | Small |

### Phase 3: Remove Legacy Constructors (peegeeq-native, peegeeq-outbox)

| Step | Task | Effort |
|------|------|--------|
| 3.1 | Remove 3 legacy constructors from `PgNativeQueueFactory` | Small |
| 3.2 | Remove `clientFactory` and `legacyMetrics` fields from `PgNativeQueueFactory` | Small |
| 3.3 | Remove 4 reflection methods from `PgNativeQueueFactory` | Medium |
| 3.4 | Simplify `PgNativeQueueFactory` to use interface methods | Medium |
| 3.5 | Remove 4 legacy constructors from `OutboxFactory` | Small |
| 3.6 | Remove `clientFactory` and `legacyMetrics` fields from `OutboxFactory` | Small |
| 3.7 | Remove `instanceof` check in `OutboxFactory.getMetrics()` | Small |
| 3.8 | Simplify dual-mode logic in `OutboxProducer`, `OutboxConsumer`, `OutboxConsumerGroup` | Medium |

### Phase 4: Update Tests

| Step | Task | Effort |
|------|------|--------|
| 4.1 | Update `RetryableErrorIT.java` to use `PgDatabaseService` | Small |
| 4.2 | Update `OutboxConsumerGroupTest.java` to use `PgDatabaseService` | Small |
| 4.3 | Update `OutboxConsumerIntegrationTest.java` to use `PgDatabaseService` | Small |
| 4.4 | Update `OutboxFactoryIntegrationTest.java` to use `PgDatabaseService` | Small |
| 4.5 | Update `OutboxMetricsTest.java` to use `PgDatabaseService` | Small |
| 4.6 | Update `OutboxProducerIntegrationTest.java` to use `PgDatabaseService` | Small |

### Phase 5: Make CloudEvents Required

| Step | Task | Effort |
|------|------|--------|
| 5.1 | Remove `<optional>true</optional>` from CloudEvents in `peegeeq-native/pom.xml` | Small |
| 5.2 | Remove `<optional>true</optional>` from CloudEvents in `peegeeq-outbox/pom.xml` | Small |
| 5.3 | Remove `<optional>true</optional>` from CloudEvents in `peegeeq-bitemporal/pom.xml` | Small |
| 5.4 | Replace `registerCloudEventsModuleIfAvailable()` with direct registration in all factories | Small |
| 5.5 | Remove try-catch/reflection code for CloudEvents module loading | Small |

### Phase 6: Cleanup and Verification

| Step | Task | Effort |
|------|------|--------|
| 6.1 | Clean up duplicate Javadoc blocks in `PgNativeQueueFactory` | Small |
| 6.2 | Update `VertxPoolAdapter` to use `PoolProvider` interface | Medium |
| 6.3 | Run all tests: `mvn clean test` | Medium |
| 6.4 | Verify no reflection warnings in logs | Small |

**Total Estimated Effort:** 4 hours

## Benefits After Refactoring

1. **No reflection** - Clean dependency injection via interfaces
2. **Type safety** - Compile-time checking instead of runtime reflection
3. **Testability** - Easy to mock interfaces in unit tests
4. **Maintainability** - Clear contracts between layers
5. **Performance** - No reflection overhead
6. **IDE support** - Better navigation and refactoring support
