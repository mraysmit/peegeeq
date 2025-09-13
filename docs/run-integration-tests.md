# PeeGeeQ Bi-temporal Integration and Performance Tests

This document describes how to run the comprehensive integration and performance tests that demonstrate the integration between PeeGeeQ and the bi-temporal event store.

## Test Overview

The test suite includes:

1. **Basic Producer-Consumer Integration**: Shows PeeGeeQ producers creating events and consumers receiving them
2. **Bi-temporal Store Persistence**: Shows events being transactionally written to the bi-temporal store
3. **Real-time Event Subscriptions**: Shows real-time event notifications via PostgreSQL LISTEN/NOTIFY
4. **End-to-End Integration**: Complete workflow showing PeeGeeQ → Consumer → Bi-temporal Store → Subscription
5. **Event Correlation**: Proves that events received via PeeGeeQ match those persisted in the bi-temporal store
6. **Performance Benchmarking**: Comprehensive performance testing across different scenarios

## Test Classes

### Integration Tests

#### PeeGeeQBiTemporalWorkingIntegrationTest (Recommended)

The working integration test class containing:

- `testPeeGeeQProducerConsumerIntegration()`: Basic PeeGeeQ producer/consumer functionality
- `testPeeGeeQToBiTemporalStoreIntegration()`: PeeGeeQ messages with bi-temporal store persistence
- `testEventCorrelationAndDataConsistency()`: Event correlation and data consistency validation

#### PeeGeeQBiTemporalIntegrationTest (Complete test suite)

The complete integration test class containing:

- `testBasicProducerConsumerIntegration()`: Basic PeeGeeQ producer/consumer functionality
- `testPeeGeeQWithBiTemporalStorePersistence()`: PeeGeeQ messages with bi-temporal store persistence
- `testRealTimeEventSubscriptions()`: Real-time event subscriptions
- `testEndToEndIntegration()`: Complete end-to-end integration

### Performance Tests

#### BiTemporalAppendPerformanceTest
Benchmarks event append operations:
- Sequential vs concurrent append performance
- Batch vs individual operation comparisons
- Throughput and latency measurements

#### BiTemporalQueryPerformanceTest
Tests query performance with large datasets:
- Query all events performance
- Event type filtering performance
- Time range query optimization
- Query optimization patterns

#### BiTemporalThroughputValidationTest
Validates system throughput under load:
- High-volume event processing
- Sustained throughput measurements
- Resource utilization monitoring

#### BiTemporalLatencyAnalysisTest
Analyzes system latency characteristics:
- End-to-end latency measurements
- Latency distribution analysis
- Performance under different load patterns

#### BiTemporalResourceManagementTest
Tests resource management and cleanup:
- Connection pool management
- Memory usage patterns
- Resource cleanup validation

### Supporting Classes

- `BiTemporalTestBase`: Base class providing shared test infrastructure
- `OrderEvent`: Test event class representing order events
- `TestEvent`: Shared test event class for performance tests
- `IntegrationTestUtils`: Utility methods for testing and logging

## Running the Tests

### Prerequisites

1. Java 17 or higher
2. Maven 3.6 or higher
3. Docker (for PostgreSQL test containers)

### Command Line

#### Integration Tests

```bash
# Run all working integration tests (recommended)
mvn test -Dtest=PeeGeeQBiTemporalWorkingIntegrationTest

# Run complete integration test suite
mvn test -Dtest=PeeGeeQBiTemporalIntegrationTest

# Run a specific integration test
mvn test -Dtest=PeeGeeQBiTemporalWorkingIntegrationTest#testPeeGeeQToBiTemporalStoreIntegration

# Run with verbose logging
mvn test -Dtest=PeeGeeQBiTemporalWorkingIntegrationTest -Dlogging.level.dev.mars.peegeeq=DEBUG
```

#### Performance Tests

```bash
# Run all performance tests
mvn test -Dtest="BiTemporal*PerformanceTest,BiTemporal*ValidationTest,BiTemporal*AnalysisTest"

# Run specific performance test categories
mvn test -Dtest=BiTemporalAppendPerformanceTest
mvn test -Dtest=BiTemporalQueryPerformanceTest
mvn test -Dtest=BiTemporalThroughputValidationTest
mvn test -Dtest=BiTemporalLatencyAnalysisTest
mvn test -Dtest=BiTemporalResourceManagementTest

# Run performance tests with detailed logging
mvn test -Dtest=BiTemporalAppendPerformanceTest -Dlogging.level.dev.mars.peegeeq=DEBUG
```

### IDE

1. Open the project in your IDE
2. Navigate to `peegeeq-bitemporal/src/test/java/dev/mars/peegeeq/bitemporal/`
3. Run individual tests or entire test classes

## Test Scenarios

### Integration Test Scenarios

#### 1. Basic Producer-Consumer Integration

**What it tests:**
- PeeGeeQ producer sends messages
- PeeGeeQ consumer receives messages
- Message correlation and headers

**Expected outcome:**
- Messages are successfully sent and received
- Correlation IDs match between sent and received messages
- Message payloads are preserved

#### 2. PeeGeeQ with Bi-temporal Store Persistence

**What it tests:**
- PeeGeeQ consumer receives messages
- Consumer persists messages to bi-temporal store
- Events are queryable from the store

**Expected outcome:**
- All PeeGeeQ messages are persisted to bi-temporal store
- Event data matches original message data
- Correlation and aggregate IDs are preserved

#### 3. Real-time Event Subscriptions

**What it tests:**
- Bi-temporal store event subscriptions
- Real-time notifications via PostgreSQL LISTEN/NOTIFY
- Event filtering by type

**Expected outcome:**
- Subscriptions receive real-time notifications
- Event data is consistent between append and subscription
- Notifications are triggered immediately

#### 4. End-to-End Integration

**What it tests:**
- Complete workflow: PeeGeeQ → Consumer → Bi-temporal Store → Subscription
- Multiple events processing
- Data consistency across all stages

**Expected outcome:**
- All events flow through the complete pipeline
- Data consistency is maintained at every stage
- Event correlation works across all components

### Performance Test Scenarios

#### 1. Append Performance Testing

**What it tests:**
- Sequential vs concurrent event append operations
- Batch processing vs individual operations
- Throughput under different load patterns

**Expected outcomes:**
- Concurrent operations show improved throughput
- System maintains performance under sustained load
- Resource utilization remains within acceptable bounds

#### 2. Query Performance Testing

**What it tests:**
- Query performance with large datasets (1000+ events)
- Different query patterns (all events, by type, by time range)
- Query optimization effectiveness

**Expected outcomes:**
- Query response times remain acceptable as dataset grows
- Indexed queries perform significantly better
- Memory usage stays controlled during large queries

#### 3. Throughput Validation

**What it tests:**
- System throughput under high-volume scenarios
- Sustained performance over extended periods
- Resource management under load

**Expected outcomes:**
- System achieves target throughput (3600+ events/sec)
- Performance remains stable over time
- No memory leaks or resource exhaustion

## Test Data

The tests use different event classes depending on the test type:

### Integration Tests
Use `OrderEvent` objects with the following structure:

```java
public class OrderEvent {
    private String orderId;
    private String customerId;
    private BigDecimal amount;
    private String status;
    private Instant orderTime;
    private String region;
}
```

### Performance Tests
Use `TestEvent` objects optimized for performance testing:

```java
public class TestEvent {
    private String id;
    private String data;
    private int value;
    // Optimized for serialization and performance benchmarking
}
```

## Monitoring and Debugging

### Logging

The tests include comprehensive logging at key points:

- Message sending and receiving
- Event persistence
- Subscription notifications
- Data correlation
- Performance metrics and benchmarks
- Resource utilization

### Test Utilities

#### IntegrationTestUtils
Provides utilities for integration tests:
- Event creation helpers
- Logging utilities
- Condition waiting helpers
- Event correlation helpers

#### BiTemporalTestBase
Provides shared infrastructure for performance tests:
- TestContainers PostgreSQL setup
- PeeGeeQ configuration and initialization
- Event store factory management
- Common setup and teardown patterns
- Performance monitoring utilities

## Expected Test Output

### Integration Test Output

When running integration tests successfully, you should see output like:

```
[INFO] Starting PeeGeeQ producer-consumer integration test...
[INFO] Sending message via PeeGeeQ...
[INFO] Message sent successfully
[INFO] RECEIVED: Message ID=..., Payload=OrderEvent{...}, Headers={...}
[INFO] ✅ PeeGeeQ producer-consumer integration test completed successfully

[INFO] Starting PeeGeeQ with bi-temporal store persistence test...
[INFO] 📤 Sending 3 test orders via PeeGeeQ...
[INFO] PEEGEEQ_RECEIVED: Message ID=..., Payload=OrderEvent{...}
[INFO] Persisted message to bi-temporal store: ORDER-101
[INFO] BITEMPORAL_EVENT_1: Event ID=..., Type=OrderEvent, Payload=OrderEvent{...}
[INFO] ✅ PeeGeeQ to bi-temporal store integration test completed successfully
[INFO] 📊 Summary: 3 PeeGeeQ messages → 3 bi-temporal events → 3 verified queries
```

### Performance Test Output

When running performance tests successfully, you should see output like:

```
[INFO] === PERFORMANCE BENCHMARK: Sequential vs Concurrent Appends ===
[INFO] 🔄 Benchmarking Sequential appends with 100 events...
[INFO] ✅ Sequential Approach: 100 events in 1250 ms (80.0 events/sec)
[INFO] 🔄 Benchmarking Concurrent appends with 100 events...
[INFO] ✅ Concurrent Approach: 100 events in 450 ms (222.2 events/sec)
[INFO] 📊 Performance Improvement: 2.8x faster with concurrent operations

[INFO] === BENCHMARK: Query Performance ===
[INFO] 🔄 Populating dataset with 1000 events...
[INFO] ✅ Dataset populated successfully
[INFO] 🔄 Benchmarking queryAll performance...
[INFO] ✅ QueryAll: 1000 events retrieved in 125 ms (8000.0 events/sec)
```

## Troubleshooting

### Common Issues

1. **PostgreSQL Connection Issues**: Ensure Docker is running and ports are available
2. **Timeout Issues**: Increase timeout values if running on slower systems
3. **Resource Cleanup**: Tests automatically clean up resources, but manual cleanup may be needed if tests are interrupted
4. **Performance Test Variability**: Performance results may vary based on system resources and load
5. **Memory Issues**: Performance tests with large datasets may require increased JVM heap size

### Debug Mode

Enable debug logging to see detailed execution:

```bash
# Integration tests with debug logging
mvn test -Dtest=PeeGeeQBiTemporalIntegrationTest -Dlogging.level.dev.mars.peegeeq=DEBUG -Dlogging.level.org.testcontainers=DEBUG

# Performance tests with debug logging
mvn test -Dtest=BiTemporalAppendPerformanceTest -Dlogging.level.dev.mars.peegeeq=DEBUG
```

### Performance Test Tuning

For consistent performance test results:

```bash
# Increase JVM heap size for large datasets
mvn test -Dtest=BiTemporalQueryPerformanceTest -Xmx2g

# Run with specific JVM options for performance testing
mvn test -Dtest="BiTemporal*PerformanceTest" -Xmx2g -XX:+UseG1GC
```

## Performance Considerations

### Integration Tests
- Tests use PostgreSQL test containers which may take time to start
- Real-time notifications may have slight delays depending on system load
- Bi-temporal queries may be slower with large datasets

### Performance Tests
- Performance results are system-dependent and may vary
- Tests are designed to validate relative performance improvements
- Baseline performance expectations:
  - Sequential appends: 50-100 events/sec
  - Concurrent appends: 200-500 events/sec
  - Query performance: 1000+ events/sec retrieval
  - Target throughput: 3600+ events/sec under optimal conditions

## Conclusion

This comprehensive test suite provides validation of:

1. **Integration Testing**: PeeGeeQ and bi-temporal store integration with real-world usage patterns
2. **Performance Testing**: System performance characteristics under various load conditions
3. **Data Consistency**: Event correlation and data integrity across all components
4. **Resource Management**: Proper cleanup and resource utilization
5. **Scalability**: System behavior under high-volume scenarios

The tests ensure the system meets both functional requirements and performance expectations for production use.
