# Migration Guide: Filter Error Handling System

## Overview

This guide helps teams migrate from the basic filter error handling to the new enterprise-grade error handling system with async retries, circuit breakers, and dead letter queue integration.

## Migration Phases

### Phase 1: Backward Compatibility (Immediate)

The new system is **100% backward compatible**. Existing code will continue to work without changes.

#### Before (Legacy)
```java
OutboxConsumerGroupMember<OrderEvent> consumer = new OutboxConsumerGroupMember<>(
    "order-processor",
    "order-group", 
    "orders",
    orderHandler,
    orderFilter,
    consumerGroup  // No error handling config
);
```

#### After (Backward Compatible)
```java
// Existing code continues to work unchanged
OutboxConsumerGroupMember<OrderEvent> consumer = new OutboxConsumerGroupMember<>(
    "order-processor",
    "order-group", 
    "orders",
    orderHandler,
    orderFilter,
    consumerGroup  // Uses default error handling config internally
);
```

**What Changed Internally:**
- Filter exceptions are now logged at INFO level instead of WARN
- No stack traces in production logs
- Circuit breaker protection is enabled by default
- Graceful error handling without message loss

### Phase 2: Basic Configuration (Week 1-2)

Add basic error handling configuration to gain immediate benefits.

#### Step 1: Add Basic Configuration
```java
// Create basic error handling configuration
FilterErrorHandlingConfig basicConfig = FilterErrorHandlingConfig.builder()
    .defaultStrategy(FilterErrorStrategy.REJECT_IMMEDIATELY)
    .circuitBreakerEnabled(true)
    .build();

// Apply to consumer
OutboxConsumerGroupMember<OrderEvent> consumer = new OutboxConsumerGroupMember<>(
    "order-processor",
    "order-group", 
    "orders",
    orderHandler,
    orderFilter,
    consumerGroup,
    basicConfig  // Add basic error handling
);
```

#### Step 2: Monitor Circuit Breaker
```java
// Add monitoring for circuit breaker state
FilterCircuitBreaker.CircuitBreakerMetrics metrics = 
    consumer.getFilterCircuitBreakerMetrics();

if (metrics.getState() == FilterCircuitBreaker.State.OPEN) {
    logger.warn("Circuit breaker opened for {}: {} failures out of {} requests", 
        consumer.getConsumerId(), metrics.getFailureCount(), metrics.getRequestCount());
}
```

**Benefits Gained:**
- ✅ Circuit breaker protection against cascading failures
- ✅ Clean logging without stack traces
- ✅ Basic metrics for monitoring
- ✅ Fast-fail behavior during outages

### Phase 3: Error Classification (Week 2-3)

Add intelligent error classification for better handling of different error types.

#### Step 1: Analyze Current Errors
```bash
# Review current error patterns in logs
grep "Filter exception" application.log | head -20

# Common patterns to look for:
# - "timeout", "connection" -> Transient
# - "invalid", "unauthorized" -> Permanent
# - "malformed", "parsing" -> Permanent
```

#### Step 2: Configure Error Classification
```java
FilterErrorHandlingConfig classifiedConfig = FilterErrorHandlingConfig.builder()
    // Transient errors (should be retried)
    .addTransientErrorPattern("timeout")
    .addTransientErrorPattern("connection")
    .addTransientErrorPattern("network")
    .addTransientExceptionType(SocketTimeoutException.class)
    .addTransientExceptionType(ConnectException.class)
    
    // Permanent errors (should not be retried)
    .addPermanentErrorPattern("invalid")
    .addPermanentErrorPattern("unauthorized")
    .addPermanentErrorPattern("malformed")
    .addPermanentExceptionType(IllegalArgumentException.class)
    .addPermanentExceptionType(SecurityException.class)
    
    // Default strategy for classified errors
    .defaultStrategy(FilterErrorStrategy.RETRY_THEN_REJECT)
    .maxRetries(2)
    .initialRetryDelay(Duration.ofMillis(100))
    
    .circuitBreakerEnabled(true)
    .build();
```

#### Step 3: Test Error Classification
```java
@Test
void testErrorClassification() {
    // Test transient error classification
    Exception timeoutError = new RuntimeException("Connection timeout occurred");
    assertEquals(ErrorClassification.TRANSIENT, config.classifyError(timeoutError));
    
    // Test permanent error classification
    Exception invalidError = new IllegalArgumentException("Invalid message format");
    assertEquals(ErrorClassification.PERMANENT, config.classifyError(invalidError));
}
```

**Benefits Gained:**
- ✅ Intelligent retry behavior based on error type
- ✅ Reduced unnecessary retries for permanent errors
- ✅ Better resource utilization
- ✅ Improved system resilience

### Phase 4: Dead Letter Queue Integration (Week 3-4)

Add dead letter queue support for critical message retention.

#### Step 1: Enable Dead Letter Queue
```java
FilterErrorHandlingConfig dlqConfig = FilterErrorHandlingConfig.builder()
    // Previous configuration...
    .addTransientErrorPattern("timeout")
    .addPermanentErrorPattern("invalid")
    .defaultStrategy(FilterErrorStrategy.RETRY_THEN_DEAD_LETTER)
    
    // Add DLQ configuration
    .deadLetterQueueEnabled(true)
    .deadLetterQueueTopic("order-processing-errors")
    .build();
```

#### Step 2: Monitor Dead Letter Queue
```java
// Get DLQ metrics
DeadLetterQueueManager dlqManager = // obtained from consumer
DeadLetterQueueManager.DeadLetterManagerMetrics dlqMetrics = dlqManager.getMetrics();

logger.info("DLQ Statistics: {} messages sent, {:.2f}% success rate", 
    dlqMetrics.getTotalSent(), dlqMetrics.getSuccessRate() * 100);
```

#### Step 3: Process Dead Letter Messages
```java
// Set up DLQ message processing (example with logging DLQ)
@Scheduled(fixedDelay = 60000) // Every minute
public void processDLQMessages() {
    // In production, this would read from actual DLQ topic
    // For logging DLQ, messages are in logs with "DEAD LETTER QUEUE" prefix
    
    // Example: Parse logs and create alerts for DLQ messages
    List<String> dlqMessages = parseDeadLetterMessages();
    if (!dlqMessages.isEmpty()) {
        alertingService.sendAlert("Dead letter queue has " + dlqMessages.size() + " messages");
    }
}
```

**Benefits Gained:**
- ✅ No message loss for critical failures
- ✅ Manual intervention capability for failed messages
- ✅ Audit trail of all processing failures
- ✅ Ability to replay failed messages

### Phase 5: Advanced Features (Week 4-5)

Implement advanced features like async retries and custom DLQ implementations.

#### Step 1: Async Retry Integration (Optional)
```java
// For advanced use cases requiring async retries
AsyncFilterRetryManager retryManager = new AsyncFilterRetryManager(
    "order-processor-filter", dlqConfig);

// Custom filter execution with async retries
CompletableFuture<AsyncFilterRetryManager.FilterResult> result = 
    retryManager.executeFilterWithRetry(message, filter, circuitBreaker);

result.thenAccept(filterResult -> {
    switch (filterResult.getStatus()) {
        case ACCEPTED:
            processMessage(message);
            break;
        case REJECTED:
            logRejection(message, filterResult.getReason());
            break;
        case DEAD_LETTER:
            logDeadLetter(message, filterResult.getReason());
            break;
    }
});
```

#### Step 2: Custom DLQ Implementation
```java
// Implement custom DLQ for your message queue system
public class KafkaDeadLetterQueue implements DeadLetterQueue {
    private final KafkaProducer<String, Object> producer;
    
    @Override
    public <T> CompletableFuture<Void> sendToDeadLetter(
            Message<T> originalMessage, String reason, int attempts,
            Map<String, String> metadata) {
        
        ProducerRecord<String, Object> record = new ProducerRecord<>(
            getDeadLetterTopic(), originalMessage.getId(), originalMessage.getPayload());
        
        // Enrich with metadata
        metadata.forEach((key, value) -> 
            record.headers().add(key, value.getBytes()));
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                producer.send(record).get();
                return null;
            } catch (Exception e) {
                throw new RuntimeException("Failed to send to DLQ", e);
            }
        });
    }
}

// Register custom DLQ implementation
// This would require extending the DeadLetterQueueManager
```

**Benefits Gained:**
- ✅ Non-blocking retry operations
- ✅ Integration with existing message queue infrastructure
- ✅ Custom DLQ processing workflows
- ✅ Maximum flexibility and control

## Configuration Migration Examples

### Development Environment
```java
// Before: No error handling
FilterErrorHandlingConfig devConfig = null; // Default behavior

// After: Basic error handling for development
FilterErrorHandlingConfig devConfig = FilterErrorHandlingConfig.builder()
    .defaultStrategy(FilterErrorStrategy.REJECT_IMMEDIATELY)
    .circuitBreakerEnabled(false)  // Disable for easier debugging
    .deadLetterQueueEnabled(false) // Use logging only
    .build();
```

### Staging Environment
```java
// Staging: Test full error handling without production impact
FilterErrorHandlingConfig stagingConfig = FilterErrorHandlingConfig.builder()
    .addTransientErrorPattern("timeout")
    .addPermanentErrorPattern("invalid")
    .defaultStrategy(FilterErrorStrategy.RETRY_THEN_REJECT)
    .maxRetries(2)
    .initialRetryDelay(Duration.ofMillis(50))
    .circuitBreakerEnabled(true)
    .circuitBreakerFailureThreshold(3)
    .deadLetterQueueEnabled(true)
    .deadLetterQueueTopic("staging-errors")
    .build();
```

### Production Environment
```java
// Production: Full error handling with conservative settings
FilterErrorHandlingConfig prodConfig = FilterErrorHandlingConfig.builder()
    // Comprehensive error classification
    .addTransientErrorPattern("timeout")
    .addTransientErrorPattern("connection")
    .addTransientErrorPattern("network")
    .addPermanentErrorPattern("invalid")
    .addPermanentErrorPattern("unauthorized")
    .addPermanentErrorPattern("malformed")
    
    // Conservative retry strategy
    .defaultStrategy(FilterErrorStrategy.RETRY_THEN_DEAD_LETTER)
    .maxRetries(3)
    .initialRetryDelay(Duration.ofMillis(200))
    .retryBackoffMultiplier(2.0)
    .maxRetryDelay(Duration.ofSeconds(30))
    
    // Circuit breaker protection
    .circuitBreakerEnabled(true)
    .circuitBreakerFailureThreshold(5)
    .circuitBreakerMinimumRequests(10)
    .circuitBreakerTimeout(Duration.ofMinutes(1))
    
    // Dead letter queue for critical messages
    .deadLetterQueueEnabled(true)
    .deadLetterQueueTopic("production-critical-errors")
    .build();
```

## Testing Migration

### Unit Tests
```java
@Test
void testMigratedErrorHandling() {
    // Test that migrated configuration works correctly
    FilterErrorHandlingConfig config = createMigratedConfig();
    
    OutboxConsumerGroupMember<TestMessage> consumer = new OutboxConsumerGroupMember<>(
        "test-consumer", "test-group", "test-topic",
        mockHandler, errorProneFilter, null, config
    );
    
    // Test error handling behavior
    TestMessage message = new TestMessage("test-1", "Test message");
    Message<TestMessage> testMsg = new SimpleMessage<>("test-1", "test-topic", message);
    
    // Should handle errors gracefully
    boolean result = consumer.acceptsMessage(testMsg);
    
    // Verify circuit breaker metrics
    FilterCircuitBreaker.CircuitBreakerMetrics metrics = 
        consumer.getFilterCircuitBreakerMetrics();
    assertNotNull(metrics);
}
```

### Integration Tests
```java
@Test
void testEndToEndMigration() {
    // Test complete migration scenario
    FilterErrorHandlingConfig migratedConfig = createProductionConfig();
    
    // Create consumer with migrated configuration
    OutboxConsumerGroupMember<OrderEvent> consumer = createConsumerWithConfig(migratedConfig);
    
    // Send various message types
    sendValidMessage();
    sendTransientErrorMessage();
    sendPermanentErrorMessage();
    
    // Verify behavior
    assertThat(getProcessedCount()).isGreaterThan(0);
    assertThat(getRejectedCount()).isGreaterThan(0);
    assertThat(getDeadLetterCount()).isGreaterThan(0);
    
    // Verify no message loss
    assertThat(getTotalHandled()).isEqualTo(getTotalSent());
}
```

## Monitoring Migration

### Metrics to Track
```java
// Before migration metrics
long errorCount = getFilterExceptionCount();
long stackTraceCount = getStackTraceLogCount();

// After migration metrics
FilterCircuitBreaker.CircuitBreakerMetrics cbMetrics = consumer.getFilterCircuitBreakerMetrics();
DeadLetterQueueManager.DeadLetterManagerMetrics dlqMetrics = dlqManager.getMetrics();

// Compare and validate improvement
assertThat(cbMetrics.getFailureRate()).isLessThan(0.1); // <10% failure rate
assertThat(dlqMetrics.getSuccessRate()).isGreaterThan(0.95); // >95% DLQ success
```

### Health Checks
```java
@Component
public class MigrationHealthCheck implements HealthIndicator {
    @Override
    public Health health() {
        Health.Builder builder = new Health.Builder();
        
        // Check if migration is complete
        boolean migrationComplete = checkMigrationStatus();
        
        if (migrationComplete) {
            builder.up()
                .withDetail("migration", "complete")
                .withDetail("errorHandling", "enabled")
                .withDetail("circuitBreaker", "active")
                .withDetail("deadLetterQueue", "configured");
        } else {
            builder.down()
                .withDetail("migration", "incomplete");
        }
        
        return builder.build();
    }
}
```

## Rollback Plan

### Emergency Rollback
```java
// Emergency rollback to basic behavior
FilterErrorHandlingConfig rollbackConfig = FilterErrorHandlingConfig.builder()
    .defaultStrategy(FilterErrorStrategy.REJECT_IMMEDIATELY)
    .circuitBreakerEnabled(false)
    .deadLetterQueueEnabled(false)
    .build();

// Or use null for complete rollback to legacy behavior
OutboxConsumerGroupMember<OrderEvent> consumer = new OutboxConsumerGroupMember<>(
    "order-processor", "order-group", "orders",
    orderHandler, orderFilter, consumerGroup, null // Rollback to legacy
);
```

### Gradual Rollback
```java
// Gradually disable features if issues occur
FilterErrorHandlingConfig conservativeConfig = FilterErrorHandlingConfig.builder()
    .defaultStrategy(FilterErrorStrategy.REJECT_IMMEDIATELY) // Disable retries
    .circuitBreakerEnabled(true) // Keep circuit breaker
    .deadLetterQueueEnabled(false) // Disable DLQ
    .build();
```

## Success Criteria

### Phase 1 Success Criteria
- ✅ No increase in error rates
- ✅ Reduction in stack trace logs
- ✅ Circuit breaker metrics available

### Phase 2 Success Criteria
- ✅ Intelligent error classification working
- ✅ Appropriate retry behavior for transient errors
- ✅ No retries for permanent errors

### Phase 3 Success Criteria
- ✅ Dead letter queue receiving failed messages
- ✅ No message loss during failures
- ✅ DLQ processing workflow established

### Phase 4 Success Criteria
- ✅ Async retry operations (if implemented)
- ✅ Custom DLQ integration (if implemented)
- ✅ Full production monitoring in place

## Support and Troubleshooting

### Common Migration Issues

1. **Increased Latency**: Adjust retry delays and circuit breaker timeouts
2. **High DLQ Volume**: Review error classification rules
3. **Circuit Breaker Opening**: Adjust failure thresholds
4. **Memory Usage**: Monitor async retry thread pools

### Getting Help

- Review the comprehensive documentation in `FILTER_ERROR_HANDLING.md`
- Check the API reference in `FILTER_ERROR_HANDLING_API.md`
- Run the test suite to validate configuration
- Monitor metrics and adjust configuration based on observed behavior

This migration guide provides a structured approach to adopting the new enterprise-grade filter error handling system while maintaining system stability and reliability throughout the migration process.
