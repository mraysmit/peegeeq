# PeeGeeQ System Properties Configuration Guide

This guide covers the four key system properties that control runtime behavior in PeeGeeQ message queues.

## Overview

PeeGeeQ supports runtime configuration through system properties, allowing you to tune performance, reliability, and behavior without code changes. These properties control:

- **Retry behavior** - How many times messages are retried before dead letter queue
- **Polling frequency** - How often the system checks for new messages  
- **Concurrency** - Number of threads processing messages simultaneously
- **Batch processing** - Number of messages processed together for efficiency

## System Properties

### 1. `peegeeq.queue.max-retries`

**Purpose**: Controls the maximum number of retry attempts before a message is moved to the dead letter queue.

**Default**: `3`  
**Type**: Integer  
**Range**: 0 to 100 (recommended)

**Examples**:
```bash
# Quick failure for real-time systems
-Dpeegeeq.queue.max-retries=1

# Standard retry behavior
-Dpeegeeq.queue.max-retries=3

# Extensive retries for critical messages
-Dpeegeeq.queue.max-retries=10
```

**Use Cases**:
- **Low values (1-2)**: Real-time systems where fast failure is preferred
- **Medium values (3-5)**: Standard applications with balanced reliability
- **High values (8-15)**: Critical systems where message loss is unacceptable

### 2. `peegeeq.queue.polling-interval`

**Purpose**: Controls how frequently the system polls for new messages.

**Default**: `PT1S` (1 second)  
**Type**: ISO-8601 Duration  
**Format**: `PT{seconds}S` or `PT{milliseconds}MS` or `PT{minutes}M`

**Examples**:
```bash
# High-frequency polling for low latency
-Dpeegeeq.queue.polling-interval=PT0.1S    # 100ms

# Standard polling
-Dpeegeeq.queue.polling-interval=PT1S      # 1 second

# Low-frequency polling for batch systems
-Dpeegeeq.queue.polling-interval=PT10S     # 10 seconds

# Sub-second precision
-Dpeegeeq.queue.polling-interval=PT0.5S    # 500ms
```

**Use Cases**:
- **Fast polling (100-500ms)**: Low-latency, real-time applications
- **Standard polling (1-2s)**: General-purpose applications
- **Slow polling (5-30s)**: Batch processing, resource-constrained environments

### 3. `peegeeq.consumer.threads`

**Purpose**: Controls the number of threads used for concurrent message processing.

**Default**: `1`  
**Type**: Integer  
**Range**: 1 to 50 (recommended)

**Examples**:
```bash
# Single-threaded processing
-Dpeegeeq.consumer.threads=1

# Moderate concurrency
-Dpeegeeq.consumer.threads=4

# High concurrency for throughput
-Dpeegeeq.consumer.threads=8

# Maximum concurrency
-Dpeegeeq.consumer.threads=16
```

**Use Cases**:
- **Single thread (1)**: Simple applications, ordered processing required
- **Low concurrency (2-4)**: Standard applications with moderate load
- **High concurrency (8-16)**: High-throughput systems, CPU-intensive processing

**Important**: More threads don't always mean better performance. Consider:
- Database connection pool size
- CPU cores available
- Memory usage per thread
- Message processing complexity

### 4. `peegeeq.queue.batch-size`

**Purpose**: Controls how many messages are fetched and processed together in a single batch.

**Default**: `10`  
**Type**: Integer  
**Range**: 1 to 1000 (recommended)

**Examples**:
```bash
# Single message processing
-Dpeegeeq.queue.batch-size=1

# Small batches for balanced latency/throughput
-Dpeegeeq.queue.batch-size=10

# Large batches for maximum throughput
-Dpeegeeq.queue.batch-size=100

# Very large batches for bulk processing
-Dpeegeeq.queue.batch-size=500
```

**Use Cases**:
- **Small batches (1-10)**: Low-latency applications, real-time processing
- **Medium batches (25-50)**: Balanced latency and throughput
- **Large batches (100-500)**: High-throughput, batch processing systems

## Configuration Patterns

### High-Throughput Configuration
Optimized for maximum message processing rate:
```bash
-Dpeegeeq.queue.max-retries=5
-Dpeegeeq.queue.polling-interval=PT1S
-Dpeegeeq.consumer.threads=8
-Dpeegeeq.queue.batch-size=100
```

### Low-Latency Configuration  
Optimized for minimal message processing delay:
```bash
-Dpeegeeq.queue.max-retries=3
-Dpeegeeq.queue.polling-interval=PT0.1S
-Dpeegeeq.consumer.threads=2
-Dpeegeeq.queue.batch-size=1
```

### Reliable Configuration
Optimized for maximum reliability and fault tolerance:
```bash
-Dpeegeeq.queue.max-retries=10
-Dpeegeeq.queue.polling-interval=PT2S
-Dpeegeeq.consumer.threads=4
-Dpeegeeq.queue.batch-size=25
```

### Resource-Constrained Configuration
Optimized for minimal resource usage:
```bash
-Dpeegeeq.queue.max-retries=3
-Dpeegeeq.queue.polling-interval=PT5S
-Dpeegeeq.consumer.threads=1
-Dpeegeeq.queue.batch-size=5
```

## Environment-Specific Examples

### Development Environment
```bash
# Fast feedback, minimal resources
-Dpeegeeq.queue.max-retries=2
-Dpeegeeq.queue.polling-interval=PT0.5S
-Dpeegeeq.consumer.threads=2
-Dpeegeeq.queue.batch-size=5
```

### Staging Environment  
```bash
# Production-like but with faster failure detection
-Dpeegeeq.queue.max-retries=5
-Dpeegeeq.queue.polling-interval=PT1S
-Dpeegeeq.consumer.threads=4
-Dpeegeeq.queue.batch-size=25
```

### Production Environment
```bash
# Balanced performance and reliability
-Dpeegeeq.queue.max-retries=7
-Dpeegeeq.queue.polling-interval=PT2S
-Dpeegeeq.consumer.threads=6
-Dpeegeeq.queue.batch-size=50
```

## Performance Tuning Guidelines

### 1. Start with Defaults
Begin with default values and measure baseline performance.

### 2. Tune One Property at a Time
Change one property at a time to understand its impact.

### 3. Monitor Key Metrics
- **Throughput**: Messages processed per second
- **Latency**: Time from message send to processing completion
- **Error Rate**: Percentage of messages that fail processing
- **Resource Usage**: CPU, memory, database connections

### 4. Consider Trade-offs
- **Polling Interval**: Faster polling = lower latency but higher CPU usage
- **Batch Size**: Larger batches = higher throughput but higher latency
- **Thread Count**: More threads = higher throughput but more resource usage
- **Max Retries**: More retries = higher reliability but slower failure detection

## Examples in Code

See the following example classes for practical demonstrations:

- **`SystemPropertiesConfigurationExample.java`**: Comprehensive demonstration of all properties
- **`RetryAndFailureHandlingExample.java`**: Focus on retry behavior and failure handling
- **`PerformanceComparisonExample.java`**: Performance impact of different configurations

## Best Practices

1. **Test in staging** with production-like load before deploying configuration changes
2. **Monitor performance** after configuration changes
3. **Document** your configuration choices and reasoning
4. **Use environment variables** or configuration management tools for different environments
5. **Start conservative** and increase values gradually based on monitoring data
6. **Consider your infrastructure** limits (CPU, memory, database connections)
7. **Plan for failure scenarios** when setting retry limits
8. **Balance latency vs throughput** based on your application requirements

## Troubleshooting

### High CPU Usage
- Reduce polling frequency (increase `polling-interval`)
- Reduce thread count (`consumer.threads`)
- Increase batch size to reduce polling overhead

### High Memory Usage  
- Reduce thread count (`consumer.threads`)
- Reduce batch size (`batch-size`)
- Check for memory leaks in message processing code

### Poor Throughput
- Increase thread count (`consumer.threads`)
- Increase batch size (`batch-size`)
- Decrease polling interval (`polling-interval`)

### Messages Stuck in Dead Letter Queue
- Increase max retries (`max-retries`)
- Check message processing logic for bugs
- Monitor error logs for failure patterns

### High Latency
- Decrease polling interval (`polling-interval`)
- Decrease batch size (`batch-size`)
- Check database performance and connection pool settings
