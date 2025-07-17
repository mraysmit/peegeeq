# PeeGeeQ Multi-Configuration Guide

This guide demonstrates how to use PeeGeeQ's multi-configuration capabilities to support different message queue configurations within the same application.

## Overview

PeeGeeQ supports multiple message queue configurations through:

- **MultiConfigurationManager** - Manages multiple named configurations
- **QueueConfigurationBuilder** - Creates specialized queue configurations
- **Named Configuration Templates** - Predefined configurations for common use cases
- **Profile-based Configuration** - Environment-specific settings

## Key Benefits

- **Flexibility** - Different queues can have different performance characteristics
- **Isolation** - Queue configurations don't interfere with each other
- **Scalability** - Optimize each queue type for its specific use case
- **Maintainability** - Clear separation of concerns between different queue configurations

## Quick Start

### 1. Basic Multi-Configuration Setup

```java
import dev.mars.peegeeq.db.config.MultiConfigurationManager;
import dev.mars.peegeeq.api.QueueFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// Initialize multi-configuration manager
MultiConfigurationManager configManager = new MultiConfigurationManager(new SimpleMeterRegistry());

// Register different configurations
configManager.registerConfiguration("high-throughput", "high-throughput");
configManager.registerConfiguration("low-latency", "low-latency");
configManager.registerConfiguration("reliable", "reliable");

// Start all configurations
configManager.start();

// Create different queue factories
QueueFactory batchQueue = configManager.createFactory("high-throughput", "native");
QueueFactory realTimeQueue = configManager.createFactory("low-latency", "native");
QueueFactory transactionalQueue = configManager.createFactory("reliable", "outbox");
```

### 2. Using Configuration Builder

```java
import dev.mars.peegeeq.db.config.QueueConfigurationBuilder;

// Create specialized queues using builder pattern
QueueFactory highThroughputQueue = QueueConfigurationBuilder.createHighThroughputQueue(databaseService);
QueueFactory lowLatencyQueue = QueueConfigurationBuilder.createLowLatencyQueue(databaseService);
QueueFactory reliableQueue = QueueConfigurationBuilder.createReliableQueue(databaseService);
QueueFactory durableQueue = QueueConfigurationBuilder.createDurableQueue(databaseService);

// Create custom queue with specific settings
QueueFactory customQueue = QueueConfigurationBuilder.createCustomQueue(
    databaseService,
    "native",                           // implementation type
    5,                                  // batch size
    Duration.ofMillis(500),             // polling interval
    3,                                  // max retries
    Duration.ofSeconds(30),             // visibility timeout
    true                                // dead letter enabled
);
```

## Configuration Profiles

### High-Throughput Configuration
Optimized for batch processing and maximum message throughput:

```properties
# peegeeq-high-throughput.properties
peegeeq.queue.batch-size=100
peegeeq.queue.polling-interval=PT0.1S
peegeeq.queue.prefetch-count=50
peegeeq.queue.concurrent-consumers=10
peegeeq.database.pool.max-size=100
```

### Low-Latency Configuration
Optimized for real-time processing and minimal latency:

```properties
# peegeeq-low-latency.properties
peegeeq.queue.batch-size=1
peegeeq.queue.polling-interval=PT0.01S
peegeeq.queue.prefetch-count=1
peegeeq.queue.concurrent-consumers=1
peegeeq.database.pool.max-size=20
```

### Reliable Configuration
Optimized for guaranteed delivery and fault tolerance:

```properties
# peegeeq-reliable.properties
peegeeq.queue.max-retries=10
peegeeq.queue.visibility-timeout=PT300S
peegeeq.queue.dead-letter.enabled=true
peegeeq.circuit-breaker.enabled=true
peegeeq.queue.retention-period=P30D
```

## Advanced Usage

### Multiple Database Connections

```java
// Register configurations with different database connections
configManager.registerConfiguration("primary-db", createPrimaryConfig());
configManager.registerConfiguration("analytics-db", createAnalyticsConfig());
configManager.registerConfiguration("archive-db", createArchiveConfig());

// Create factories using different databases
QueueFactory primaryQueue = configManager.createFactory("primary-db", "native");
QueueFactory analyticsQueue = configManager.createFactory("analytics-db", "native");
QueueFactory archiveQueue = configManager.createFactory("archive-db", "outbox");
```

### Named Configuration Templates

```java
// Use predefined configuration templates
QueueFactory factory = provider.createNamedFactory("native", "high-throughput", databaseService);

// Override specific settings
Map<String, Object> overrides = Map.of(
    "batch-size", 200,
    "polling-interval", "PT0.05S"
);
QueueFactory customFactory = provider.createNamedFactory("native", "high-throughput", databaseService, overrides);
```

### Configuration Schema

Get detailed configuration schema for any implementation type:

```java
QueueFactoryProvider provider = new PgQueueFactoryProvider();
Map<String, Object> schema = provider.getConfigurationSchema("native");

// Schema includes:
// - batch-size: Number of messages to process in a batch
// - polling-interval: Interval between polling for new messages
// - max-retries: Maximum number of retry attempts
// - visibility-timeout: Time a message remains invisible after being consumed
// - dead-letter-enabled: Whether to enable dead letter queue
// - prefetch-count: Number of messages to prefetch
// - concurrent-consumers: Number of concurrent consumers
// - buffer-size: Internal buffer size for message processing
```

## Use Case Examples

### E-commerce Order Processing

```java
// High-throughput queue for order processing
QueueFactory orderQueue = configManager.createFactory("high-throughput", "native");
MessageProducer<OrderEvent> orderProducer = orderQueue.createProducer("orders", OrderEvent.class);

// Low-latency queue for real-time notifications
QueueFactory notificationQueue = configManager.createFactory("low-latency", "native");
MessageProducer<NotificationEvent> notificationProducer = notificationQueue.createProducer("notifications", NotificationEvent.class);

// Reliable queue for payment processing
QueueFactory paymentQueue = configManager.createFactory("reliable", "outbox");
MessageProducer<PaymentEvent> paymentProducer = paymentQueue.createProducer("payments", PaymentEvent.class);
```

### IoT Data Processing

```java
// High-throughput queue for sensor data ingestion
QueueFactory sensorQueue = QueueConfigurationBuilder.createHighThroughputQueue(databaseService);
MessageConsumer<SensorData> sensorConsumer = sensorQueue.createConsumer("sensor-data", SensorData.class);

// Low-latency queue for alerts
QueueFactory alertQueue = QueueConfigurationBuilder.createLowLatencyQueue(databaseService);
MessageProducer<AlertEvent> alertProducer = alertQueue.createProducer("alerts", AlertEvent.class);

// Durable queue for historical data
QueueFactory historyQueue = QueueConfigurationBuilder.createDurableQueue(databaseService);
MessageProducer<HistoricalData> historyProducer = historyQueue.createProducer("history", HistoricalData.class);
```

## Best Practices

### 1. Configuration Isolation
- Use separate configurations for different performance requirements
- Isolate critical and non-critical message flows
- Consider separate database connections for different workloads

### 2. Resource Management
- Always close queue factories when done
- Use try-with-resources for automatic cleanup
- Monitor resource usage across configurations

### 3. Performance Tuning
- Start with predefined configuration templates
- Monitor and adjust based on actual workload
- Use appropriate implementation types (native vs outbox)

### 4. Error Handling
- Configure appropriate retry policies for each use case
- Enable dead letter queues for critical messages
- Set up proper monitoring and alerting

## Configuration Reference

### Common Configuration Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `batch-size` | Integer | 10 | Number of messages to process in a batch |
| `polling-interval` | Duration | PT1S | Interval between polling for new messages |
| `max-retries` | Integer | 3 | Maximum number of retry attempts |
| `visibility-timeout` | Duration | PT30S | Time a message remains invisible after being consumed |
| `dead-letter-enabled` | Boolean | true | Whether to enable dead letter queue |
| `prefetch-count` | Integer | 10 | Number of messages to prefetch |
| `concurrent-consumers` | Integer | 1 | Number of concurrent consumers |
| `buffer-size` | Integer | 100 | Internal buffer size for message processing |

### Implementation-Specific Properties

#### Native Queue Properties
| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `listen-notify-enabled` | Boolean | true | Enable PostgreSQL LISTEN/NOTIFY |
| `connection-pool-size` | Integer | 5 | Size of connection pool for LISTEN/NOTIFY |

#### Outbox Queue Properties
| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `retention-period` | Duration | P7D | How long to retain processed messages |
| `cleanup-interval` | Duration | PT1H | Interval for cleanup operations |

## Troubleshooting

### Common Issues

1. **Configuration Not Found**
   - Ensure configuration is registered before use
   - Check configuration name spelling

2. **Resource Leaks**
   - Always close queue factories
   - Use try-with-resources pattern

3. **Performance Issues**
   - Review configuration settings
   - Monitor database connection pools
   - Check for appropriate batch sizes

### Monitoring

Monitor key metrics for each configuration:
- Queue depth and processing latency
- Error rates and retry counts
- Database connection pool usage
- Memory and CPU utilization

## Migration Guide

### From Single Configuration

```java
// Before: Single configuration
PeeGeeQManager manager = new PeeGeeQManager("production");
QueueFactory factory = new PgQueueFactoryProvider().createFactory("native", new PgDatabaseService(manager));

// After: Multi-configuration
MultiConfigurationManager configManager = new MultiConfigurationManager();
configManager.registerConfiguration("production", "production");
configManager.start();
QueueFactory factory = configManager.createFactory("production", "native");
```

### Adding New Configurations

```java
// Add new configuration to existing manager
configManager.registerConfiguration("new-config", "new-profile");

// Create factory with new configuration
QueueFactory newFactory = configManager.createFactory("new-config", "native");
```
