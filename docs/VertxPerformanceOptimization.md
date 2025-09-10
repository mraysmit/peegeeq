# Vert.x PostgreSQL Performance Optimization Guide

This guide implements the Vert.x PostgreSQL performance checklist to optimize PeeGeeQ for maximum throughput and minimal latency.

## Performance Checklist Implementation

### 1. ✅ Set pool size (not 4): try 16/32 and tune with your DBA

**Default Configuration:**
```properties
# peegeeq-default.properties
peegeeq.database.pool.min-size=8
peegeeq.database.pool.max-size=32
```

**High-Performance Configuration:**
```properties
# peegeeq-high-performance.properties
peegeeq.database.pool.max-size=32
peegeeq.database.pool.min-size=8
```

**Code Implementation:**
```java
// PgPoolConfig.java - Updated defaults
private int minimumIdle = 8;
private int maximumPoolSize = 32; // Following Vert.x performance checklist: 16/32
```

### 2. ✅ Share one pool across all verticles (setShared(true))

**Configuration:**
```properties
peegeeq.database.pool.shared=true
```

**Code Implementation:**
```java
// PgConnectionManager.java
PoolOptions poolOptions = new PoolOptions()
    .setMaxSize(poolConfig.getMaximumPoolSize())
    .setShared(poolConfig.isShared()); // Share one pool across all verticles
```

### 3. ✅ Deploy multiple instances of your verticles (≃ cores)

**Configuration:**
```properties
peegeeq.verticle.instances=8
```

**Code Implementation:**
```java
// VertxPerformanceOptimizer.java
public static DeploymentOptions createOptimizedDeploymentOptions() {
    int instances = getOptimalVerticleInstances(); // ≃ cores
    return new DeploymentOptions().setInstances(instances);
}
```

**Usage:**
```java
DeploymentOptions options = VertxPerformanceOptimizer.createOptimizedDeploymentOptions();
vertx.deployVerticle(() -> new PeeGeeQRestServer(8080), options);
```

### 4. ✅ Don't hold a SqlConnection for the whole app; use pool ops or short-lived withConnection

**Best Practice Implementation:**
```java
// Use pool.withConnection() for short-lived operations
pool.withConnection(connection -> {
    return connection.preparedQuery("INSERT INTO events ...")
        .execute(tuple);
}).onSuccess(result -> {
    // Connection automatically returned to pool
});

// Use pool.withTransaction() for transactional operations
pool.withTransaction(connection -> {
    return connection.preparedQuery("INSERT ...")
        .execute(tuple)
        .compose(r -> connection.preparedQuery("UPDATE ...")
            .execute(tuple2));
});
```

### 5. ✅ Keep transactions short, and don't wrap everything in a tx

**Implementation Pattern:**
```java
// Good: Short, focused transactions
public Future<Void> appendEvent(String streamId, String eventData) {
    return pool.withTransaction(connection -> {
        // Single, focused operation
        return connection.preparedQuery(INSERT_EVENT_SQL)
            .execute(Tuple.of(streamId, eventData));
    }).mapEmpty();
}

// Avoid: Long transactions that hold locks
// Don't wrap multiple unrelated operations in one transaction
```

### 6. ✅ Enable/test pipelining (8–32), if you aren't behind a proxy that chokes on it

**Configuration:**
```properties
peegeeq.database.pipelining.enabled=true
peegeeq.database.pipelining.limit=32
```

**Code Implementation:**
```java
// PgBiTemporalEventStore.java
int pipeliningLimit = Integer.parseInt(
    System.getProperty("peegeeq.database.pipelining.limit", "32"));
connectOptions.setPipeliningLimit(pipeliningLimit);
```

### 7. ✅ Measure: p95 latency, pool wait time (requests stuck in pool's wait queue), DB CPU and iowait

**Performance Monitoring:**
```java
// SimplePerformanceMonitor.java - Essential metrics
public class SimplePerformanceMonitor {
    public void recordQueryTime(Duration duration);
    public void recordConnectionTime(Duration duration);
    public double getAverageQueryTime();
    public double getAverageConnectionTime();
    public long getMaxQueryTime();
    public long getMaxConnectionTime();
}
```

**Usage:**
```java
SimplePerformanceMonitor monitor = new SimplePerformanceMonitor();
monitor.startPeriodicLogging(vertx, 10000); // Log every 10 seconds

// Manual timing
var timing = monitor.startTiming();
// ... perform operation ...
timing.recordAsQuery();
```

## Configuration Profiles

### High-Performance Profile
```properties
# peegeeq-high-performance.properties
peegeeq.database.pool.max-size=32
peegeeq.database.pool.min-size=8
peegeeq.database.pool.shared=true
peegeeq.database.pipelining.enabled=true
peegeeq.database.pipelining.limit=32
peegeeq.verticle.instances=8
```

### Production Profile
```properties
# peegeeq-production.properties
peegeeq.database.pool.max-size=50
peegeeq.database.pool.min-size=10
peegeeq.database.pool.shared=true
peegeeq.database.pipelining.enabled=true
peegeeq.database.pipelining.limit=16
```

## Performance Optimization Utilities

### VertxPerformanceOptimizer
Provides factory methods for creating optimized Vert.x instances and deployment options:

```java
// Create optimized Vertx instance
Vertx vertx = VertxPerformanceOptimizer.createOptimizedVertx();

// Create optimized pool
Pool pool = VertxPerformanceOptimizer.createOptimizedPool(vertx, connectionConfig, poolConfig);

// Create optimized deployment options
DeploymentOptions options = VertxPerformanceOptimizer.createOptimizedDeploymentOptions();

// Validate configuration
String validation = VertxPerformanceOptimizer.validatePoolConfiguration(poolConfig);
```

### SimplePerformanceMonitor
Tracks essential performance metrics:

```java
SimplePerformanceMonitor monitor = new SimplePerformanceMonitor();
monitor.startPeriodicLogging(vertx, 10000);

// Get real-time metrics
double avgLatency = monitor.getAverageQueryTime();
double avgConnectionTime = monitor.getAverageConnectionTime();
long maxQueryTime = monitor.getMaxQueryTime();
```

## Example Usage

See `VertxPerformanceOptimizationExample.java` for a comprehensive example demonstrating all optimizations.

```bash
# Run the performance optimization example
mvn exec:java -Dexec.mainClass="dev.mars.peegeeq.examples.VertxPerformanceOptimizationExample"
```

## Performance Thresholds

The monitoring system will warn when:

- **Average Query Time > 50ms**: Consider query optimization or index tuning
- **Max Query Time > 200ms**: Investigate slow queries
- **Average Connection Time > 20ms**: Consider increasing pool size
- **Max Connection Time > 100ms**: Check pool configuration and database load
- **Connection Failure Rate > 5%**: Check database connectivity and pool configuration

## Tuning Recommendations

1. **Start with defaults** (pool size 32, pipelining 32)
2. **Monitor metrics** for 24-48 hours
3. **Adjust pool size** based on connection utilization and wait times
4. **Tune pipelining** based on network latency and proxy configuration
5. **Scale verticle instances** based on CPU utilization
6. **Work with your DBA** to optimize database-side configuration

## System Properties

All optimizations can be controlled via system properties:

```bash
-Dpeegeeq.database.pool.max-size=32
-Dpeegeeq.database.pool.shared=true
-Dpeegeeq.database.pipelining.enabled=true
-Dpeegeeq.database.pipelining.limit=32
-Dpeegeeq.database.event.loop.size=16
-Dpeegeeq.database.worker.pool.size=32
-Dpeegeeq.verticle.instances=8
```
