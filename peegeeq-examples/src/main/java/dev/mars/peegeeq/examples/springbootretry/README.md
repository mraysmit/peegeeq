# Spring Boot Retry Strategies Example

This example demonstrates advanced retry patterns with PeeGeeQ in a Spring Boot application.

## Overview

The Retry Strategies pattern handles failures intelligently by:
1. Classifying failures as transient or permanent
2. Automatically retrying transient failures up to max retries
3. Using circuit breaker to prevent cascading failures
4. Implementing exponential backoff for retries
5. Monitoring retry metrics for observability
6. Providing health indicators for circuit breaker state

## Architecture

```
Transaction Events → MessageConsumer → Circuit Breaker Check
                                              ↓
                                        Transaction Processor
                                              ↓
                                    (Classify Failure)
                                              ↓
                            Transient ←→ Retry (exponential backoff)
                            Permanent ←→ DLQ (no retry)
                                              ↓
                                        Circuit Breaker
                                              ↓
                                    Metrics & Monitoring
```

## Key Components

### 1. TransactionProcessorService
- Processes transaction events with retry logic
- Classifies failures as transient or permanent
- Checks circuit breaker before processing
- Records success/failure for circuit breaker
- Tracks retry metrics

### 2. CircuitBreakerService
- Implements circuit breaker pattern (CLOSED/OPEN/HALF_OPEN)
- Tracks failure rate in sliding window
- Opens circuit when threshold exceeded
- Automatically attempts recovery after reset period
- Provides health indicators

### 3. RetryMonitoringController
- REST endpoints for monitoring retry behavior
- GET `/api/retry/metrics` - Get processing metrics
- GET `/api/retry/circuit-breaker` - Get circuit breaker state
- POST `/api/retry/circuit-breaker/reset` - Reset circuit breaker
- GET `/api/retry/health` - Health check endpoint

### 4. Exception Classification
- `TransientFailureException` - Retryable errors (network, timeout, etc.)
- `PermanentFailureException` - Non-retryable errors (validation, auth, etc.)

## Configuration

### application-springboot-retry.yml

```yaml
peegeeq:
  retry:
    queue-name: transaction-events
    max-retries: 5                      # Maximum retry attempts
    polling-interval-ms: 500            # Polling interval
    initial-backoff-ms: 1000            # Initial backoff delay
    backoff-multiplier: 2.0             # Exponential backoff multiplier
    max-backoff-ms: 60000               # Maximum backoff delay
    # Circuit breaker settings
    circuit-breaker-threshold: 10       # Failures before opening circuit
    circuit-breaker-window-ms: 60000    # Sliding window duration
    circuit-breaker-reset-ms: 30000     # Time before attempting recovery
    database:
      host: localhost
      port: 5432
      name: peegeeq
      username: postgres
      password: postgres
```

## Running the Example

### 1. Start PostgreSQL

```bash
docker run -d \
  --name peegeeq-postgres \
  -e POSTGRES_DB=peegeeq \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  postgres:15-alpine
```

### 2. Run the Application

```bash
mvn spring-boot:run -pl peegeeq-examples \
  -Dspring-boot.run.main-class=dev.mars.peegeeq.examples.springbootretry.SpringBootRetryApplication \
  -Dspring-boot.run.profiles=springboot-retry
```

### 3. Test the Endpoints

```bash
# Get retry metrics
curl http://localhost:8083/api/retry/metrics

# Get circuit breaker state
curl http://localhost:8083/api/retry/circuit-breaker

# Reset circuit breaker
curl -X POST http://localhost:8083/api/retry/circuit-breaker/reset

# Health check
curl http://localhost:8083/api/retry/health
```

## Code Structure

```
springbootretry/
├── SpringBootRetryApplication.java           # Main application
├── config/
│   ├── PeeGeeQRetryConfig.java              # PeeGeeQ configuration
│   └── PeeGeeQRetryProperties.java          # Configuration properties
├── controller/
│   └── RetryMonitoringController.java       # REST endpoints for monitoring
├── service/
│   ├── TransactionProcessorService.java     # Transaction processing with retry
│   └── CircuitBreakerService.java           # Circuit breaker implementation
├── exception/
│   ├── TransientFailureException.java       # Retryable exception
│   └── PermanentFailureException.java       # Non-retryable exception
├── events/
│   └── TransactionEvent.java                # Transaction event model
└── model/
    └── Transaction.java                     # Transaction domain model
```

## Key Patterns Demonstrated

### 1. Failure Classification
```java
// Transient failure - will be retried
if ("TRANSIENT".equals(event.getFailureType())) {
    throw new TransientFailureException("Network timeout");
}

// Permanent failure - will NOT be retried (goes to DLQ)
if ("PERMANENT".equals(event.getFailureType())) {
    throw new PermanentFailureException("Invalid account");
}
```

### 2. Circuit Breaker Pattern
```java
// Check circuit breaker before processing
if (!circuitBreaker.allowRequest()) {
    throw new TransientFailureException("Circuit breaker is open");
}

// Record success/failure
try {
    processTransaction();
    circuitBreaker.recordSuccess();
} catch (Exception e) {
    circuitBreaker.recordFailure();
    throw e;
}
```

### 3. Automatic Retry (PeeGeeQ)
```java
// PeeGeeQ automatically retries failed messages
private CompletableFuture<Void> processTransaction(Message<TransactionEvent> message) {
    return databaseService.getConnectionProvider()
        .withTransaction(CLIENT_ID, connection -> {
            // Process transaction
            if (shouldFail) {
                // Throw exception - PeeGeeQ will retry automatically
                throw new TransientFailureException("Temporary failure");
            }
            // Success - save to database
            return saveTransaction(connection, transaction);
        })
        .toCompletionStage()
        .toCompletableFuture();
}
```

### 4. Circuit Breaker States
```java
public enum State {
    CLOSED,      // Normal operation - requests pass through
    OPEN,        // Too many failures - requests rejected
    HALF_OPEN    // Testing recovery - limited requests allowed
}
```

## Testing

Run the tests:

```bash
mvn test -pl peegeeq-examples -Dtest=TransactionProcessorServiceTest
```

Tests include:
- ✅ Successful transaction processing
- ✅ Bean injection verification
- ✅ Retry metrics endpoint
- ✅ Circuit breaker endpoint
- ✅ Circuit breaker reset endpoint
- ✅ Retry health endpoint

## Common Patterns

### Simulating Transient Failures

```java
TransactionEvent transientFailure = new TransactionEvent(
    "TXN-001", "ACC-001", 
    new BigDecimal("100.00"), "DEBIT",
    "TRANSIENT"  // Will be retried
);
producer.send(transientFailure);
```

### Simulating Permanent Failures

```java
TransactionEvent permanentFailure = new TransactionEvent(
    "TXN-002", "ACC-002", 
    new BigDecimal("200.00"), "CREDIT",
    "PERMANENT"  // Will go to DLQ without retry
);
producer.send(permanentFailure);
```

### Monitoring Circuit Breaker

```java
CircuitBreakerService.State state = circuitBreaker.getState();
int failures = circuitBreaker.getFailureCount();
boolean allowingRequests = circuitBreaker.allowRequest();

if (state == CircuitBreakerService.State.OPEN) {
    log.warn("Circuit breaker is OPEN - service degraded");
}
```

## Exponential Backoff

PeeGeeQ supports exponential backoff via the `next_retry_at` column:

```java
// Calculate next retry time with exponential backoff
long backoffMs = initialBackoffMs * Math.pow(backoffMultiplier, retryCount);
backoffMs = Math.min(backoffMs, maxBackoffMs);
Instant nextRetry = Instant.now().plusMillis(backoffMs);
```

## Troubleshooting

### Circuit Breaker Always Open

**Problem**: Circuit breaker stays in OPEN state.

**Solution**: Check that:
1. Failure threshold is not too low
2. Reset period is appropriate
3. Underlying service has recovered
4. Manually reset circuit breaker if needed

### Too Many Retries

**Problem**: Messages are retried too many times.

**Solution**: Verify:
1. `max-retries` is configured appropriately
2. Failures are classified correctly
3. Permanent failures use `PermanentFailureException`

### Exponential Backoff Not Working

**Problem**: Retries happen too quickly.

**Solution**: Ensure:
1. `initial-backoff-ms` is set correctly
2. `backoff-multiplier` is > 1.0
3. `max-backoff-ms` is reasonable
4. PeeGeeQ respects `next_retry_at` column

## Best Practices

1. **Classify Failures Correctly**: Use appropriate exception types
2. **Configure Appropriate Thresholds**: Balance between retry attempts and circuit breaker
3. **Monitor Circuit Breaker**: Set up alerts for OPEN state
4. **Use Exponential Backoff**: Prevent overwhelming downstream services
5. **Track Metrics**: Monitor retry rates and failure patterns
6. **Test Failure Scenarios**: Verify retry behavior under different conditions

## Related Examples

- **springboot-consumer**: Basic consumer pattern
- **springboot-dlq**: Dead letter queue handling
- **springboot-integrated**: Outbox + Bi-temporal integration

