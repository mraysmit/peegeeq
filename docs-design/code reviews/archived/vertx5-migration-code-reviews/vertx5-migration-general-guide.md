# Vert.x 5 Migration Guide

**Author**: Consolidated from Code Reviews  
**Date**: January 2025  
**Version**: 1.0  
**Status**: Production Ready

---

## Executive Summary

This guide consolidates findings from comprehensive code reviews of the PeeGeeQ Vert.x 5 migration. It provides actionable patterns, anti-patterns, and concrete fixes for building production-ready reactive applications with Vert.x 5.x.

**Key Principles**: Follow the 10 core coding principles - investigate first, follow existing patterns, verify assumptions, fix root causes, document honestly, validate incrementally, classify tests clearly, fail fast, read logs carefully, and use modern Vert.x 5.x composable Future patterns.

---

## Critical Anti-Patterns to Avoid

### 1. **Never Create Vert.x Instances in Factories**

❌ **Wrong**:
```java
// OutboxFactory creating its own Vert.x - NEVER DO THIS
new PgClientFactory(Vertx.vertx())
```

✅ **Correct**:
```java
// Use shared Vert.x instance from application context
public OutboxFactory(Vertx vertx, DatabaseService databaseService, ...) {
    this.vertx = vertx; // Use provided instance
}
```

**Why**: Creating Vert.x spawns new event-loop threads, independent lifecycle, thread leaks, double-metrics, and unreliable shutdown.

### 2. **Don't Mix ScheduledThreadPoolExecutor with Vert.x**

❌ **Wrong**:
```java
// Bypasses Vert.x context and complicates lifecycle
scheduledExecutor.scheduleAtFixedRate(task, 0, 30, SECONDS);
```

✅ **Correct**:
```java
// Use Vert.x timers with executeBlocking for potentially blocking work
vertx.setPeriodic(TimeUnit.SECONDS.toMillis(30), id -> {
    vertx.executeBlocking(() -> {
        // Blocking work here
        return null;
    });
});
```

### 3. **Avoid Blocking Operations on Event Loop**

❌ **Wrong**:
```java
public void start() {
    startReactive().toCompletionStage().toCompletableFuture().get(30, SECONDS);
}
```

✅ **Correct**:
```java
public void start() {
    // Guard against event loop blocking
    if (Vertx.currentContext() != null && Vertx.currentContext().isEventLoopContext()) {
        throw new IllegalStateException("Do not call blocking start() on event-loop thread");
    }
    // ... proceed with blocking call
}
```

### 4. **Don't Use JDBC Patterns in Reactive Code**

❌ **Wrong**:
```java
// JDBC-style configuration that Vert.x ignores
private final long connectionTimeout; // ambiguous units
private final int minimumIdle; // not used by Vert.x
private final boolean autoCommit; // JDBC-only concept
```

✅ **Correct**:
```java
// Vert.x-native configuration
private final Duration connectionTimeout;
private final int maxWaitQueueSize; // Vert.x pool concept
private final SslMode sslMode; // Vert.x SSL configuration
```

---

## Essential Patterns for Vert.x 5

### 1. **Reactive Pool Management**

```java
// Single pool reference pattern
private final Pool pool;

// Initialize once, inject everywhere
this.pool = clientFactory.getConnectionManager()
    .getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

// Use across components
this.metrics = new PeeGeeQMetrics(pool, instanceId);
this.healthCheckManager = new HealthCheckManager(pool, timeout, interval);
```

### 2. **Proper Async Lifecycle Management**

```java
// Async start/stop pattern
public Future<Void> startReactive() {
    return validateDatabaseConnectivity()
        .compose(v -> startBackgroundTasks())
        .compose(v -> startHealthChecks())
        .onSuccess(v -> {
            started = true;
            publishLifecycleEvent("STARTED");
        });
}

public Future<Void> stopReactive() {
    if (!started) return Future.succeededFuture();
    
    stopBackgroundTasks(); // Cancel timers first
    return healthCheckManager.stopReactive()
        .onComplete(ar -> started = false);
}
```

### 3. **Modern Future Composition**

```java
// Use .compose(), .onSuccess(), .onFailure() instead of callbacks
return pool.withConnection(conn ->
    conn.query("SELECT 1").execute()
        .compose(rs -> processResults(rs))
        .onSuccess(result -> logger.info("Success: {}", result))
        .onFailure(err -> logger.error("Failed", err))
);
```

### 4. **Proper Resource Cleanup**

```java
// Async close with Future aggregation
public Future<Void> closeAsync() {
    var futures = pools.keySet().stream()
        .map(this::closePoolAsync)
        .toList();
    
    pools.clear();
    return Future.all(futures)
        .onSuccess(v -> logger.info("All pools closed"))
        .mapEmpty();
}

// AutoCloseable wrapper for compatibility
@Override
public void close() {
    try {
        closeAsync().toCompletionStage().toCompletableFuture().get(5, SECONDS);
    } catch (Exception e) {
        logger.error("Error during close", e);
    }
}
```

---

## Configuration Best Practices

### 1. **Connection Configuration**

```java
public final class PgConnectionConfig {
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password; // Consider char[] for security
    private final SslMode sslMode; // Vert.x native SSL
    private final Duration connectTimeout;
    private final int reconnectAttempts;
    
    // Remove JDBC artifacts like getJdbcUrl()
    @Deprecated(forRemoval = true)
    public String getJdbcUrl() {
        throw new UnsupportedOperationException("Not used in Vert.x reactive mode");
    }
}
```

### 2. **Pool Configuration**

```java
public final class PgPoolConfig {
    private final int maxSize; // Match Vert.x API names
    private final int maxWaitQueueSize; // Critical for backpressure
    private final Duration idleTimeout;
    private final Duration connectionTimeout;
    private final boolean shared;
    
    // Remove JDBC-only fields
    // private final int minimumIdle; // Not used by Vert.x
    // private final Duration maxLifetime; // Not used by Vert.x
}
```

---

## Health Check Implementation

```java
// Real health check with actual database query
public Future<Boolean> checkHealth(String serviceId) {
    Pool pool = pools.get(serviceId);
    if (pool == null) return Future.succeededFuture(false);
    
    return pool.withConnection(conn ->
        conn.query("SELECT 1").execute().map(rs -> true)
    ).recover(err -> {
        logger.warn("Health check failed for {}: {}", serviceId, err.getMessage());
        return Future.succeededFuture(false);
    });
}
```

---

## Error Handling Patterns

### 1. **Fail Fast Validation**

```java
// Validate inputs early
private static void validate(String clientId, PgConnectionConfig conn, PgPoolConfig pool) {
    if (clientId == null || clientId.isBlank())
        throw new IllegalArgumentException("clientId must be non-blank");
    Objects.requireNonNull(conn, "connectionConfig");
    Objects.requireNonNull(pool, "poolConfig");
}
```

### 2. **Graceful Degradation**

```java
// Handle failures without breaking periodic tasks
vertx.setPeriodic(intervalMs, id -> {
    vertx.executeBlocking(() -> {
        try {
            performMaintenanceTask();
            return null;
        } catch (Exception e) {
            logger.warn("Maintenance task failed", e);
            return null; // Don't fail the periodic timer
        }
    });
});
```

---

## Security Considerations

### 1. **Password Handling**

```java
// Don't expose passwords in toString()
@Override
public String toString() {
    return "PgConnectionConfig{" +
        "host='" + host + '\'' +
        ", database='" + database + '\'' +
        ", user='" + username + '\'' +
        ", sslMode=" + sslMode +
        '}'; // No password field
}
```

### 2. **SSL Configuration**

```java
// Proper SSL setup for production
PgConnectOptions connectOptions = new PgConnectOptions()
    .setSslMode(config.getSslMode())
    .setTrustAll(config.isTrustAll()) // Only for dev
    .setPemTrustOptions(new PemTrustOptions()
        .addCertPath(config.getCaCertPath()));
```

---

## Testing Strategies

### 1. **Unit Testing with Vert.x**

```java
@Test
void testReactiveOperation() {
    Vertx testVertx = Vertx.vertx();
    try {
        Component component = new Component(testVertx, /* other params */);
        
        Future<String> result = component.performOperation();
        
        // Use Vert.x test utilities
        assertThat(result.toCompletionStage().toCompletableFuture().get())
            .isEqualTo("expected");
    } finally {
        testVertx.close();
    }
}
```

### 2. **Integration Testing**

```java
// Test resource cleanup and lifecycle
@Test
void testResourceCleanup() {
    // Verify no resource leaks
    Set<Vertx> instances = trackVertxInstances();
    
    // Create and use components
    createAndUseComponents();
    
    // Verify single instance usage
    assertThat(instances).hasSize(1);
}
```

---

## Migration Checklist

### Phase 1: Foundation
- [ ] Remove all `Vertx.vertx()` calls from factories and components
- [ ] Add Vert.x instance injection to constructors
- [ ] Replace ScheduledExecutorService with Vert.x timers

### Phase 2: Configuration
- [ ] Remove JDBC artifacts from configuration classes
- [ ] Add Vert.x-native fields (SslMode, Duration, maxWaitQueueSize)
- [ ] Update pool creation to use proper PoolOptions

### Phase 3: Lifecycle
- [ ] Implement async start/stop methods
- [ ] Add event loop detection guards
- [ ] Proper Future composition and error handling

### Phase 4: Testing
- [ ] Update tests to use injected Vert.x instances
- [ ] Add resource leak detection tests
- [ ] Validate performance improvements

---

## Common Pitfalls and Solutions

| Pitfall | Impact | Solution |
|---------|--------|----------|
| Creating Vert.x in factories | Thread leaks, resource waste | Inject shared instance |
| Blocking on event loop | Deadlocks | Add event loop detection |
| JDBC configuration in reactive code | Confusion, unused settings | Use Vert.x-native config |
| Missing maxWaitQueueSize | Memory leaks under load | Add to pool configuration |
| Synchronous pool.close() | Resource leaks | Use async closeAsync() |
| No input validation | Runtime failures | Validate early, fail fast |

---

## Advanced Patterns

### 1. **Factory Pattern with Dependency Injection**

```java
// Proper factory that doesn't create Vert.x instances
public class OutboxFactory implements MessageFactory {
    private final Vertx vertx; // Injected, not created
    private final DatabaseService databaseService;

    public OutboxFactory(Vertx vertx, DatabaseService databaseService, ...) {
        this.vertx = Objects.requireNonNull(vertx);
        this.databaseService = Objects.requireNonNull(databaseService);
    }

    @Override
    public <T> MessageProducer<T> createProducer(String topic, Class<T> payloadType) {
        validateTopic(topic, payloadType);

        // Pass shared Vert.x instance to components
        return new OutboxProducer<>(vertx, topic, payloadType,
                                   clientFactory, objectMapper, metrics);
    }
}
```

### 2. **Connection Pool Idempotency**

```java
// Thread-safe, idempotent pool creation
public Pool getOrCreateReactivePool(String serviceId,
                                   PgConnectionConfig cfg,
                                   PgPoolConfig poolCfg) {
    return pools.computeIfAbsent(serviceId, id -> {
        try {
            Pool pool = createReactivePool(cfg, poolCfg);
            logger.info("Created reactive pool for service '{}'", id);
            return pool;
        } catch (Exception e) {
            logger.error("Failed to create pool for {}: {}", id, e.getMessage());
            pools.remove(id); // Clean up on failure
            throw e;
        }
    });
}
```

### 3. **Backpressure and Circuit Breaking**

```java
// Configurable backpressure management
public class BackpressureManager {
    private final int maxConcurrentRequests;
    private final Duration timeout;
    private final AtomicInteger activeRequests = new AtomicInteger(0);

    public <T> Future<T> execute(Supplier<Future<T>> operation) {
        if (activeRequests.get() >= maxConcurrentRequests) {
            return Future.failedFuture(new BackpressureException("Too many concurrent requests"));
        }

        activeRequests.incrementAndGet();
        return operation.get()
            .onComplete(ar -> activeRequests.decrementAndGet());
    }
}
```

---

## Performance Optimization

### 1. **Connection Pool Tuning**

```java
// Production-ready pool configuration
PoolOptions poolOptions = new PoolOptions()
    .setMaxSize(20) // Based on database connection limits
    .setMaxWaitQueueSize(64) // Prevent memory leaks
    .setIdleTimeout(Duration.ofMinutes(10)) // Clean up idle connections
    .setConnectionTimeout(Duration.ofSeconds(30)) // Fail fast on connection issues
    .setShared(true); // Share across verticles with same config
```

### 2. **Metrics and Monitoring**

```java
// Reactive metrics collection
private Future<Void> startMetricsCollection() {
    if (!metricsConfig.isEnabled()) return Future.succeededFuture();

    long intervalMs = metricsConfig.getReportingInterval().toMillis();
    metricsTimerId = vertx.setPeriodic(intervalMs, id -> {
        vertx.executeBlocking(() -> {
            try {
                collectAndPersistMetrics();
                return null;
            } catch (Exception e) {
                logger.warn("Failed to collect metrics", e);
                return null; // Don't fail periodic timer
            }
        });
    });

    return Future.succeededFuture();
}
```

### 3. **Memory Management**

```java
// Proper resource tracking and cleanup
public class ResourceTracker implements AutoCloseable {
    private final List<AutoCloseable> resources = new CopyOnWriteArrayList<>();

    public <T extends AutoCloseable> T track(T resource) {
        resources.add(resource);
        return resource;
    }

    @Override
    public void close() {
        for (AutoCloseable resource : resources) {
            try {
                resource.close();
            } catch (Exception e) {
                logger.warn("Error closing resource: {}", e.getMessage());
            }
        }
        resources.clear();
    }
}
```

---

## Troubleshooting Guide

### 1. **Common Error Messages**

**"You're already on a Vert.x context, are you sure you want to create a new Vertx instance?"**
- **Cause**: Creating Vert.x instances in components that already run on Vert.x context
- **Fix**: Use dependency injection to pass shared Vert.x instance

**"Pool is closed"**
- **Cause**: Attempting to use pool after it's been closed
- **Fix**: Ensure proper lifecycle management and avoid closing shared pools

**"Connection timeout"**
- **Cause**: Database connection taking too long
- **Fix**: Tune connectionTimeout in PgPoolConfig, check database health

### 2. **Performance Issues**

**High thread count**
- **Cause**: Multiple Vert.x instances creating duplicate thread pools
- **Fix**: Consolidate to single shared Vert.x instance

**Memory leaks**
- **Cause**: Unbounded connection queues or unclosed resources
- **Fix**: Set maxWaitQueueSize, implement proper resource cleanup

**Slow response times**
- **Cause**: Blocking operations on event loop
- **Fix**: Use executeBlocking for I/O operations

### 3. **Debugging Techniques**

```java
// Enable detailed logging for troubleshooting
logger.debug("Using Vert.x instance: {} for component: {}",
             System.identityHashCode(vertx), getClass().getSimpleName());

// Track resource usage
logger.info("Active pools: {}, Active connections: {}",
            pools.size(), getTotalActiveConnections());

// Monitor event loop utilization
vertx.eventBus().consumer("__vertx.metrics", message -> {
    logger.debug("Event loop metrics: {}", message.body());
});
```

---

## Production Deployment

### 1. **Environment Configuration**

```yaml
# application.yml - Production settings
peegeeq:
  database:
    host: ${DB_HOST:localhost}
    port: ${DB_PORT:5432}
    database: ${DB_NAME:peegeeq}
    username: ${DB_USER:peegeeq}
    password: ${DB_PASSWORD}
    ssl-mode: REQUIRE
    connect-timeout: PT30S
    reconnect-attempts: 3

  pool:
    max-size: ${POOL_MAX_SIZE:20}
    max-wait-queue-size: ${POOL_WAIT_QUEUE:64}
    idle-timeout: PT10M
    connection-timeout: PT30S
    shared: true
```

### 2. **Health Checks**

```java
// Comprehensive health check implementation
@Component
public class PeeGeeQHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        try {
            boolean dbHealthy = checkDatabaseHealth().get(5, SECONDS);
            boolean poolHealthy = checkPoolHealth().get(5, SECONDS);

            if (dbHealthy && poolHealthy) {
                return Health.up()
                    .withDetail("database", "UP")
                    .withDetail("pool", "UP")
                    .withDetail("active-connections", getActiveConnections())
                    .build();
            } else {
                return Health.down()
                    .withDetail("database", dbHealthy ? "UP" : "DOWN")
                    .withDetail("pool", poolHealthy ? "UP" : "DOWN")
                    .build();
            }
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
```

### 3. **Graceful Shutdown**

```java
// Proper shutdown sequence
@PreDestroy
public void shutdown() {
    logger.info("Starting graceful shutdown...");

    try {
        // Stop accepting new requests
        stopReactive()
            .compose(v -> {
                // Close all pools
                return clientFactory.closeAsync();
            })
            .compose(v -> {
                // Close Vert.x instance
                return vertx.close();
            })
            .toCompletionStage()
            .toCompletableFuture()
            .get(30, SECONDS);

        logger.info("Graceful shutdown completed");
    } catch (Exception e) {
        logger.error("Error during shutdown", e);
    }
}
```

---

## Migration Timeline

### Week 1: Assessment and Planning
- [ ] Audit current Vert.x usage patterns
- [ ] Identify all components creating Vert.x instances
- [ ] Plan dependency injection strategy
- [ ] Set up testing infrastructure

### Week 2: Core Infrastructure
- [ ] Refactor PeeGeeQManager to expose Vert.x instance
- [ ] Update factory classes for dependency injection
- [ ] Replace ScheduledExecutorService with Vert.x timers
- [ ] Update configuration classes

### Week 3: Component Migration
- [ ] Migrate OutboxProducer/Consumer
- [ ] Migrate PgNativeQueueConsumer
- [ ] Migrate BiTemporal components
- [ ] Update VertxPoolAdapter classes

### Week 4: Testing and Optimization
- [ ] Comprehensive integration testing
- [ ] Performance validation
- [ ] Resource leak testing
- [ ] Documentation updates

---

*This comprehensive guide provides production-tested patterns for Vert.x 5.x migration. Follow the phased approach, test incrementally, and validate each change thoroughly before proceeding to the next phase.*
