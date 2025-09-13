# PeeGeeQ Bi-temporal Integration Tests

This document describes how to run the comprehensive integration tests that demonstrate the integration between PeeGeeQ and the bi-temporal event store.

## Test Overview

The integration tests demonstrate:

1. **Basic Producer-Consumer Integration**: Shows PeeGeeQ producers creating events and consumers receiving them
2. **Bi-temporal Store Persistence**: Shows events being transactionally written to the bi-temporal store
3. **Real-time Event Subscriptions**: Shows real-time event notifications via PostgreSQL LISTEN/NOTIFY
4. **End-to-End Integration**: Complete workflow showing PeeGeeQ → Consumer → Bi-temporal Store → Subscription
5. **Event Correlation**: Proves that events received via PeeGeeQ match those persisted in the bi-temporal store

## Test Classes

### PeeGeeQBiTemporalWorkingIntegrationTest (Recommended)

The working integration test class containing:

- `testPeeGeeQProducerConsumerIntegration()`: Basic PeeGeeQ producer/consumer functionality
- `testPeeGeeQToBiTemporalStoreIntegration()`: PeeGeeQ messages with bi-temporal store persistence
- `testEventCorrelationAndDataConsistency()`: Event correlation and data consistency validation

### PeeGeeQBiTemporalIntegrationTest (Includes failing tests)

The complete integration test class containing:

- `testBasicProducerConsumerIntegration()`: Basic PeeGeeQ producer/consumer functionality
- `testPeeGeeQWithBiTemporalStorePersistence()`: PeeGeeQ messages with bi-temporal store persistence
- `testRealTimeEventSubscriptions()`: Real-time event subscriptions (not yet implemented)
- `testEndToEndIntegration()`: Complete end-to-end integration (depends on subscriptions)

### Supporting Classes

- `OrderEvent`: Test event class representing order events
- `IntegrationTestUtils`: Utility methods for testing and logging

## Running the Tests

### Prerequisites

1. Java 17 or higher
2. Maven 3.6 or higher
3. Docker (for PostgreSQL test containers)

### Command Line

```bash
# Run all working integration tests (recommended)
mvn test -Dtest=PeeGeeQBiTemporalWorkingIntegrationTest

# Run all integration tests (includes failing subscription tests)
mvn test -Dtest=PeeGeeQBiTemporalIntegrationTest

# Run a specific working test
mvn test -Dtest=PeeGeeQBiTemporalWorkingIntegrationTest#testPeeGeeQToBiTemporalStoreIntegration

# Run with verbose logging
mvn test -Dtest=PeeGeeQBiTemporalWorkingIntegrationTest -Dlogging.level.dev.mars.peegeeq=DEBUG
```

### IDE

1. Open the project in your IDE
2. Navigate to `peegeeq-bitemporal/src/test/java/dev/mars/peegeeq/bitemporal/PeeGeeQBiTemporalIntegrationTest.java`
3. Run individual tests or the entire test class

## Test Scenarios

### 1. Basic Producer-Consumer Integration

**What it tests:**
- PeeGeeQ producer sends messages
- PeeGeeQ consumer receives messages
- Message correlation and headers

**Expected outcome:**
- Messages are successfully sent and received
- Correlation IDs match between sent and received messages
- Message payloads are preserved

### 2. PeeGeeQ with Bi-temporal Store Persistence

**What it tests:**
- PeeGeeQ consumer receives messages
- Consumer persists messages to bi-temporal store
- Events are queryable from the store

**Expected outcome:**
- All PeeGeeQ messages are persisted to bi-temporal store
- Event data matches original message data
- Correlation and aggregate IDs are preserved

### 3. Real-time Event Subscriptions

**What it tests:**
- Bi-temporal store event subscriptions
- Real-time notifications via PostgreSQL LISTEN/NOTIFY
- Event filtering by type

**Expected outcome:**
- Subscriptions receive real-time notifications
- Event data is consistent between append and subscription
- Notifications are triggered immediately

### 4. End-to-End Integration

**What it tests:**
- Complete workflow: PeeGeeQ → Consumer → Bi-temporal Store → Subscription
- Multiple events processing
- Data consistency across all stages

**Expected outcome:**
- All events flow through the complete pipeline
- Data consistency is maintained at every stage
- Event correlation works across all components

## Test Data

The tests use `OrderEvent` objects with the following structure:

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

## Monitoring and Debugging

### Logging

The tests include comprehensive logging at key points:

- Message sending and receiving
- Event persistence
- Subscription notifications
- Data correlation

### Test Utilities

`IntegrationTestUtils` provides:

- Event creation helpers
- Logging utilities
- Condition waiting helpers
- Event correlation helpers

## Expected Test Output

When running successfully, you should see output like:

```
[INFO] Starting basic producer-consumer integration test...
[INFO] Sending message via PeeGeeQ...
[INFO] Message sent successfully
[INFO] RECEIVED: Message ID=..., Payload=OrderEvent{...}, Headers={...}
[INFO] Basic producer-consumer integration test completed successfully

[INFO] Starting PeeGeeQ with bi-temporal store persistence test...
[INFO] Sending messages via PeeGeeQ...
[INFO] PEEGEEQ_RECEIVED: Message ID=..., Payload=OrderEvent{...}
[INFO] Persisted message to bi-temporal store: ORDER-101
[INFO] BITEMPORAL_EVENT_1: Event ID=..., Type=OrderEvent, Payload=OrderEvent{...}
[INFO] PeeGeeQ with bi-temporal store persistence test completed successfully

[INFO] Starting end-to-end integration test...
[INFO] Sending 3 test orders via PeeGeeQ...
[INFO] END_TO_END_PEEGEEQ: Message ID=..., Payload=OrderEvent{...}
[INFO] Persisted event to bi-temporal store: ...
[INFO] END_TO_END_SUBSCRIPTION: Event ID=..., Type=OrderEvent, Payload=OrderEvent{...}
[INFO] Summary: 3 PeeGeeQ messages → 3 persisted events → 3 subscription notifications
[INFO] End-to-end integration test completed successfully
```

## Troubleshooting

### Common Issues

1. **PostgreSQL Connection Issues**: Ensure Docker is running and ports are available
2. **Timeout Issues**: Increase timeout values if running on slower systems
3. **Resource Cleanup**: Tests automatically clean up resources, but manual cleanup may be needed if tests are interrupted

### Debug Mode

Enable debug logging to see detailed execution:

```bash
mvn test -Dtest=PeeGeeQBiTemporalIntegrationTest -Dlogging.level.dev.mars.peegeeq=DEBUG -Dlogging.level.org.testcontainers=DEBUG
```

## Performance Considerations

- Tests use PostgreSQL test containers which may take time to start
- Real-time notifications may have slight delays depending on system load
- Bi-temporal queries may be slower with large datasets

## Conclusion

These integration tests provide comprehensive validation of the PeeGeeQ and bi-temporal store integration, demonstrating real-world usage patterns and ensuring data consistency across all components.
