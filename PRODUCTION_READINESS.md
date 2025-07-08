# PeeGeeQ Production Readiness Features

This document describes the comprehensive production readiness features implemented in PeeGeeQ, including database schema management, metrics & monitoring, configuration management, and resilience patterns.

## Overview

PeeGeeQ now includes enterprise-grade features that make it suitable for production deployments:

- **Database Schema Management**: Versioned migrations with validation
- **Metrics & Monitoring**: Comprehensive metrics collection with Micrometer
- **Configuration Management**: Environment-specific configurations with validation
- **Health Checks**: Multi-component health monitoring
- **Circuit Breakers**: Resilience patterns to prevent cascading failures
- **Backpressure Management**: Adaptive flow control and rate limiting
- **Dead Letter Queues**: Failed message handling and reprocessing

## Database Schema Management

### Features
- Versioned SQL migration scripts
- Checksum validation for migration integrity
- Automatic schema version tracking
- Rollback capabilities
- Migration history

### Usage
```java
// Initialize migration manager
SchemaMigrationManager migrationManager = new SchemaMigrationManager(dataSource);

// Apply all pending migrations
int appliedMigrations = migrationManager.migrate();

// Validate existing migrations
boolean isValid = migrationManager.validateMigrations();

// Get current schema version
String currentVersion = migrationManager.getCurrentVersion();
```

### Migration Files
Place migration files in `src/main/resources/db/migration/`:
- `V001__Create_Base_Tables.sql` - Initial schema
- `V002__Add_Indexes.sql` - Additional migrations
- Follow naming convention: `V{version}__{description}.sql`

## Metrics & Monitoring

### Features
- Message processing metrics (sent, received, processed, failed)
- Queue depth monitoring (outbox, native, dead letter)
- Connection pool metrics
- Performance counters and timers
- Prometheus integration
- Historical metrics persistence

### Usage
```java
// Initialize metrics
PeeGeeQMetrics metrics = new PeeGeeQMetrics(dataSource, "instance-id");
metrics.bindTo(meterRegistry);

// Record message operations
metrics.recordMessageSent("topic");
metrics.recordMessageReceived("topic");
metrics.recordMessageProcessed("topic", Duration.ofMillis(100));
metrics.recordMessageFailed("topic", "error-type");

// Get metrics summary
PeeGeeQMetrics.MetricsSummary summary = metrics.getSummary();
```

### Available Metrics
- `peegeeq.messages.sent` - Total messages sent
- `peegeeq.messages.received` - Total messages received
- `peegeeq.messages.processed` - Successfully processed messages
- `peegeeq.messages.failed` - Failed message processing
- `peegeeq.queue.depth.outbox` - Pending outbox messages
- `peegeeq.queue.depth.native` - Available native queue messages
- `peegeeq.connection.pool.active` - Active database connections

## Configuration Management

### Features
- Environment-specific configuration profiles
- Externalized configuration support
- Configuration validation
- Environment variable overrides
- Type-safe configuration access

### Configuration Profiles
- `peegeeq-default.properties` - Base configuration
- `peegeeq-development.properties` - Development overrides
- `peegeeq-production.properties` - Production settings

### Usage
```java
// Load configuration for specific profile
PeeGeeQConfiguration config = new PeeGeeQConfiguration("production");

// Access typed configuration
PgConnectionConfig dbConfig = config.getDatabaseConfig();
QueueConfig queueConfig = config.getQueueConfig();
MetricsConfig metricsConfig = config.getMetricsConfig();

// Environment variable override
// PEEGEEQ_DATABASE_HOST=localhost
String host = config.getString("peegeeq.database.host");
```

### Key Configuration Properties
```properties
# Database
peegeeq.database.host=localhost
peegeeq.database.port=5432
peegeeq.database.pool.max-size=10

# Queue
peegeeq.queue.max-retries=3
peegeeq.queue.visibility-timeout=PT30S
peegeeq.queue.dead-letter.enabled=true

# Metrics
peegeeq.metrics.enabled=true
peegeeq.metrics.reporting-interval=PT1M

# Circuit Breaker
peegeeq.circuit-breaker.enabled=true
peegeeq.circuit-breaker.failure-threshold=5
```

## Health Checks

### Features
- Multi-component health monitoring
- Database connectivity checks
- Queue health validation
- System resource monitoring
- Configurable check intervals
- Health status aggregation

### Usage
```java
// Initialize health check manager
HealthCheckManager healthManager = new HealthCheckManager(dataSource, 
    Duration.ofSeconds(30), Duration.ofSeconds(5));

// Start health checks
healthManager.start();

// Check overall health
boolean isHealthy = healthManager.isHealthy();
OverallHealthStatus status = healthManager.getOverallHealth();

// Register custom health check
healthManager.registerHealthCheck("custom", () -> {
    // Custom health check logic
    return HealthStatus.healthy("custom");
});
```

### Built-in Health Checks
- **Database**: Connection validity and query execution
- **Outbox Queue**: Pending message count and processing status
- **Native Queue**: Available message count
- **Dead Letter Queue**: Recent failure rate
- **Memory**: JVM memory usage monitoring
- **Disk Space**: Available disk space

## Circuit Breakers

### Features
- Automatic failure detection
- Configurable failure thresholds
- Half-open state for recovery testing
- Metrics integration
- Per-operation circuit breakers

### Usage
```java
// Initialize circuit breaker manager
CircuitBreakerManager cbManager = new CircuitBreakerManager(config, meterRegistry);

// Execute operation with circuit breaker protection
String result = cbManager.executeSupplier("database-operation", () -> {
    // Database operation that might fail
    return performDatabaseOperation();
});

// Database-specific circuit breaker
Object result = cbManager.executeDatabaseOperation("select", () -> {
    return executeQuery();
});
```

### Configuration
```properties
peegeeq.circuit-breaker.enabled=true
peegeeq.circuit-breaker.failure-threshold=5
peegeeq.circuit-breaker.wait-duration=PT1M
peegeeq.circuit-breaker.failure-rate-threshold=50.0
```

## Backpressure Management

### Features
- Adaptive rate limiting
- Concurrent operation limiting
- Timeout-based request handling
- Success rate monitoring
- Automatic load shedding

### Usage
```java
// Initialize backpressure manager
BackpressureManager bpManager = new BackpressureManager(50, Duration.ofSeconds(30));

// Execute operation with backpressure control
String result = bpManager.execute("operation-name", () -> {
    // Operation that should be rate limited
    return performOperation();
});

// Get backpressure metrics
BackpressureManager.BackpressureMetrics metrics = bpManager.getMetrics();
```

### Configuration
- `maxConcurrentOperations`: Maximum concurrent operations allowed
- `timeout`: Maximum wait time for operation permit
- Adaptive limiting based on success rate and current load

## Dead Letter Queues

### Features
- Failed message storage and tracking
- Message reprocessing capabilities
- Failure reason tracking
- Retry count monitoring
- Bulk operations support
- Retention policies

### Usage
```java
// Initialize dead letter queue manager
DeadLetterQueueManager dlqManager = new DeadLetterQueueManager(dataSource, objectMapper);

// Move failed message to DLQ
dlqManager.moveToDeadLetterQueue("outbox", messageId, "topic", payload, 
    createdAt, "failure reason", retryCount, headers, correlationId, messageGroup);

// Retrieve DLQ messages
List<DeadLetterMessage> messages = dlqManager.getDeadLetterMessages("topic", 10, 0);

// Reprocess message
boolean success = dlqManager.reprocessDeadLetterMessage(dlqMessageId, "manual retry");

// Get DLQ statistics
DeadLetterQueueStats stats = dlqManager.getStatistics();
```

## Integrated Management

### PeeGeeQManager
The `PeeGeeQManager` class provides a unified interface to all production readiness features:

```java
// Initialize with configuration profile
PeeGeeQManager manager = new PeeGeeQManager("production");

// Start all services
manager.start();

// Check system health
boolean healthy = manager.isHealthy();
PeeGeeQManager.SystemStatus status = manager.getSystemStatus();

// Access individual components
PeeGeeQMetrics metrics = manager.getMetrics();
HealthCheckManager healthManager = manager.getHealthCheckManager();
CircuitBreakerManager cbManager = manager.getCircuitBreakerManager();

// Graceful shutdown
manager.close();
```

## Example Application

See `PeeGeeQExample.java` for a complete demonstration of all production readiness features.

## Testing

Comprehensive integration tests are provided in `PeeGeeQManagerIntegrationTest.java` using TestContainers for real PostgreSQL testing.

## Deployment Considerations

### Production Checklist
- [ ] Configure appropriate database connection pool sizes
- [ ] Set up monitoring and alerting for health checks
- [ ] Configure circuit breaker thresholds for your workload
- [ ] Set up log aggregation for metrics and health data
- [ ] Configure dead letter queue retention policies
- [ ] Set appropriate backpressure limits
- [ ] Enable SSL for database connections in production
- [ ] Configure proper logging levels

### Environment Variables
```bash
# Database
export PEEGEEQ_DATABASE_HOST=prod-db-host
export PEEGEEQ_DATABASE_PASSWORD=secure-password

# Instance identification
export PEEGEEQ_METRICS_INSTANCE_ID=prod-instance-1

# Profile selection
export PEEGEEQ_PROFILE=production
```

## Monitoring Integration

### Prometheus
Metrics are automatically exposed in Prometheus format when using the Prometheus registry:

```java
PrometheusMeterRegistry prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
PeeGeeQManager manager = new PeeGeeQManager(config, prometheusRegistry);
```

### Grafana Dashboards
Create dashboards monitoring:
- Message throughput and latency
- Queue depths and processing rates
- Circuit breaker states
- Health check status
- Connection pool utilization
- Dead letter queue growth

This comprehensive production readiness implementation ensures PeeGeeQ can handle enterprise workloads with proper monitoring, resilience, and operational visibility.
