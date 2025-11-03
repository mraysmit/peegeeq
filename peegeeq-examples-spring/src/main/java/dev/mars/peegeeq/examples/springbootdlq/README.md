# Spring Boot Dead Letter Queue (DLQ) Example

This example demonstrates the **CORRECT** way to implement Dead Letter Queue pattern with PeeGeeQ in a Spring Boot application.

## Overview

The DLQ pattern handles failed messages by:
1. Automatically retrying failed messages up to a configurable maximum
2. Moving permanently failed messages to a Dead Letter Queue (DLQ)
3. Providing admin endpoints for DLQ inspection and management
4. Monitoring DLQ depth and alerting when thresholds are exceeded
5. Allowing manual reprocessing of DLQ messages

## Architecture

```
Payment Events → MessageConsumer → Payment Processor
                                         ↓
                                    (Success)
                                         ↓
                                   Database
                                         
                                    (Failure)
                                         ↓
                                   Retry (up to max)
                                         ↓
                                   Dead Letter Queue
                                         ↓
                                   DLQ Management Service
                                         ↓
                                   Admin Endpoints
```

## Key Components

### 1. PaymentProcessorService
- Consumes payment events from queue
- Processes payments with simulated failures
- Automatic retry on failure (PeeGeeQ handles this)
- Tracks metrics for successful/failed/retried payments

### 2. DlqManagementService
- Monitors DLQ depth
- Retrieves DLQ messages for inspection
- Reprocesses DLQ messages manually
- Deletes DLQ messages after resolution
- Alerts when DLQ threshold is exceeded

### 3. DlqAdminController
- REST endpoints for DLQ management
- GET `/api/dlq/depth` - Get DLQ depth
- GET `/api/dlq/messages` - Get all DLQ messages
- POST `/api/dlq/reprocess/{id}` - Reprocess a DLQ message
- DELETE `/api/dlq/messages/{id}` - Delete a DLQ message
- GET `/api/dlq/stats` - Get DLQ statistics
- GET `/api/dlq/metrics` - Get processing metrics

## Configuration

### application-springboot-dlq.yml

```yaml
peegeeq:
  dlq:
    queue-name: payment-events
    max-retries: 3                    # Maximum retry attempts
    polling-interval-ms: 500          # Polling interval
    dlq-alert-threshold: 10           # Alert when DLQ depth exceeds this
    auto-reprocess-enabled: false     # Auto-reprocess DLQ messages
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
  -Dspring-boot.run.main-class=dev.mars.peegeeq.examples.springbootdlq.SpringBootDlqApplication \
  -Dspring-boot.run.profiles=springboot-dlq
```

### 3. Test the Endpoints

```bash
# Get DLQ depth
curl http://localhost:8082/api/dlq/depth

# Get DLQ statistics
curl http://localhost:8082/api/dlq/stats

# Get processing metrics
curl http://localhost:8082/api/dlq/metrics

# Get all DLQ messages
curl http://localhost:8082/api/dlq/messages

# Reprocess a DLQ message
curl -X POST http://localhost:8082/api/dlq/reprocess/1

# Delete a DLQ message
curl -X DELETE http://localhost:8082/api/dlq/messages/1
```

## Code Structure

```
springbootdlq/
├── SpringBootDlqApplication.java          # Main application
├── config/
│   ├── PeeGeeQDlqConfig.java             # PeeGeeQ configuration
│   └── PeeGeeQDlqProperties.java         # Configuration properties
├── controller/
│   └── DlqAdminController.java           # REST endpoints for DLQ admin
├── service/
│   ├── PaymentProcessorService.java      # Payment processing with retry
│   └── DlqManagementService.java         # DLQ management operations
├── events/
│   └── PaymentEvent.java                 # Payment event model
└── model/
    └── Payment.java                      # Payment domain model
```

## Key Patterns Demonstrated

### 1. Automatic Retry
```java
// PeeGeeQ automatically retries failed messages
private CompletableFuture<Void> processPayment(Message<PaymentEvent> message) {
    return databaseService.getConnectionProvider()
        .withTransaction(CLIENT_ID, connection -> {
            // Process payment
            if (shouldFail) {
                // Throw exception - PeeGeeQ will retry
                throw new RuntimeException("Payment failed");
            }
            // Success - save to database
            return savePayment(connection, payment);
        })
        .toCompletionStage()
        .toCompletableFuture();
}
```

### 2. DLQ Monitoring
```java
public CompletableFuture<Long> getDlqDepth() {
    return databaseService.getConnectionProvider()
        .withTransaction(CLIENT_ID, connection -> {
            return connection.preparedQuery("SELECT COUNT(*) FROM peegeeq_dlq WHERE topic = $1")
                .execute(Tuple.of(queueName))
                .map(rows -> rows.iterator().next().getLong(0));
        })
        .toCompletionStage()
        .toCompletableFuture();
}
```

### 3. DLQ Reprocessing
```java
public CompletableFuture<Boolean> reprocessDlqMessage(Long messageId) {
    return databaseService.getConnectionProvider()
        .withTransaction(CLIENT_ID, connection -> {
            // Get message from DLQ
            // Insert back into main queue
            // Delete from DLQ
            return Future.succeededFuture(true);
        })
        .toCompletionStage()
        .toCompletableFuture();
}
```

## Testing

Run the tests:

```bash
mvn test -pl peegeeq-examples -Dtest=PaymentProcessorServiceTest
```

Tests include:
- ✅ Successful payment processing
- ✅ Bean injection verification
- ✅ DLQ depth endpoint
- ✅ DLQ stats endpoint
- ✅ DLQ metrics endpoint
- ✅ DLQ messages endpoint

## Common Patterns

### Simulating Failures for Testing

```java
PaymentEvent failingEvent = new PaymentEvent(
    "PAY-001", "ORDER-001", 
    new BigDecimal("100.00"), "USD", "CREDIT_CARD",
    true  // shouldFail = true
);
producer.send(failingEvent);
```

### Monitoring DLQ Depth

```java
dlqService.getDlqDepth().thenAccept(depth -> {
    if (depth >= threshold) {
        log.warn("DLQ alert: depth={}", depth);
        // Send alert notification
    }
});
```

### Manual Reprocessing

```java
// Get all DLQ messages
List<Map<String, Object>> messages = dlqService.getDlqMessages().get();

// Reprocess specific message
dlqService.reprocessDlqMessage(messageId).get();
```

## Troubleshooting

### Messages Not Moving to DLQ

**Problem**: Failed messages are not moving to DLQ after max retries.

**Solution**: Check that:
1. `max-retries` is configured correctly
2. Exceptions are being thrown (not caught and ignored)
3. PeeGeeQ DLQ table exists in database

### DLQ Depth Always Zero

**Problem**: DLQ depth is always zero even with failures.

**Solution**: Verify:
1. Messages are actually failing (check logs)
2. Max retries have been exceeded
3. Querying correct topic name in DLQ table

## Best Practices

1. **Configure Appropriate Max Retries**: Balance between retry attempts and DLQ movement
2. **Monitor DLQ Depth**: Set up alerts for DLQ threshold
3. **Investigate DLQ Messages**: Review error messages to identify root causes
4. **Manual Reprocessing**: Only reprocess after fixing underlying issues
5. **Clean Up DLQ**: Delete resolved messages to keep DLQ manageable

## Related Examples

- **springboot-consumer**: Basic consumer pattern
- **springboot-retry**: Advanced retry strategies
- **springboot-integrated**: Outbox + Bi-temporal integration

