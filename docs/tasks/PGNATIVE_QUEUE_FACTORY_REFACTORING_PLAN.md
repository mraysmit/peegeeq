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
| Reflection for optional CloudEvents module | Low | Lines 451-460 |
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

#### Handle CloudEvents Module Registration
Keep the reflection for optional module loading but document it clearly:

```java
/**
 * Attempts to register CloudEvents Jackson module if available on classpath.
 * This uses reflection intentionally as CloudEvents is an optional dependency.
 */
private static void registerCloudEventsModuleIfAvailable(ObjectMapper mapper) {
    // existing reflection code with clear documentation
}
```

## Migration Strategy

### Backward Compatibility

The refactoring maintains backward compatibility by:

1. **Keeping legacy constructors** (deprecated) that accept `PgClientFactory`
2. **Gradual migration** - old code continues to work while new code uses interfaces
3. **No breaking API changes** to `QueueFactory` interface

### Deprecation Plan

```java
/**
 * @deprecated Use {@link #PgNativeQueueFactory(DatabaseService)} instead.
 *             This constructor will be removed in version 2.0.
 */
@Deprecated(since = "1.1", forRemoval = true)
public PgNativeQueueFactory(PgClientFactory clientFactory) {
    // legacy implementation
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

| Step | Module | Task | Effort |
|------|--------|------|--------|
| 1 | peegeeq-api | Create `VertxProvider` interface | Small |
| 2 | peegeeq-api | Create `PoolProvider` interface | Small |
| 3 | peegeeq-api | Modify `MetricsProvider` | Small |
| 4 | peegeeq-api | Modify `DatabaseService` to extend interfaces | Small |
| 5 | peegeeq-db | Implement interfaces in `PgDatabaseService` | Medium |
| 6 | peegeeq-db | Implement `VertxProvider` in `PgConnectionManager` | Small |
| 7 | peegeeq-db | Implement `getUnderlyingMetrics()` in `PgMetricsProvider` | Small |
| 8 | peegeeq-native | Update `VertxPoolAdapter` | Medium |
| 9 | peegeeq-native | Refactor `PgNativeQueueFactory` | Large |
| 10 | peegeeq-native | Clean up logging and Javadoc | Small |
| 11 | All | Run tests and verify | Medium |

**Total Estimated Effort:** 2-3 hours

## Benefits After Refactoring

1. **No reflection** - Clean dependency injection via interfaces
2. **Type safety** - Compile-time checking instead of runtime reflection
3. **Testability** - Easy to mock interfaces in unit tests
4. **Maintainability** - Clear contracts between layers
5. **Performance** - No reflection overhead
6. **IDE support** - Better navigation and refactoring support
