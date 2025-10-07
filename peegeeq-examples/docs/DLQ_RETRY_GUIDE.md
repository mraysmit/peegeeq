# Dead Letter Queue and Retry Patterns Guide

**Examples**: `springboot-dlq`, `springboot-retry`  
**Use Case**: Payment processing with automatic retry and manual intervention  
**Focus**: Retry strategies, DLQ management, circuit breaker pattern

---

## What This Guide Covers

This guide demonstrates how to handle message processing failures using retry strategies and dead letter queues (DLQ). It covers both automatic retry for transient failures and DLQ management for permanent failures.

**Key Patterns**:
- Automatic retry with configurable max retries
- Dead letter queue for permanently failed messages
- Exponential backoff for retry timing
- Circuit breaker pattern to prevent cascading failures
- Failure classification (transient vs permanent)
- DLQ monitoring and reprocessing

**What This Guide Does NOT Cover**:
- Message production (see PRIORITY_FILTERING_GUIDE.md)
- Consumer groups (see CONSUMER_GROUP_GUIDE.md)
- Bi-temporal event storage (see REACTIVE_BITEMPORAL_GUIDE.md)

---

## Core Concepts

### Retry Pattern

**Definition**: Automatically retry failed message processing up to a maximum number of attempts.

**How It Works**:
1. Message processing fails (exception thrown)
2. Transaction rolled back (message NOT deleted)
3. PeeGeeQ increments `retry_count` in outbox table
4. Message becomes available for next poll
5. Process repeats until success or max retries reached
6. After max retries, message moves to DLQ

**When to Use**:
- Transient failures (network timeouts, temporary service unavailability)
- External system temporarily down
- Rate limiting or throttling
- Database deadlocks

### Dead Letter Queue (DLQ)

**Definition**: Separate queue for messages that failed permanently after all retry attempts.

**How It Works**:
1. Message fails max retry attempts
2. PeeGeeQ automatically moves message to `dead_letter_queue` table
3. Original message deleted from outbox
4. DLQ message includes failure reason and retry history
5. Operations team investigates and manually reprocesses

**When to Use**:
- Permanent failures (invalid data, business rule violations)
- Messages that require manual investigation
- Failures that won't resolve with retry
- Need audit trail of failed messages

### Circuit Breaker Pattern

**Definition**: Prevent cascading failures by temporarily blocking requests to failing downstream systems.

**States**:
- **CLOSED**: Normal operation, requests allowed
- **OPEN**: Too many failures, requests blocked
- **HALF_OPEN**: Testing if system recovered, limited requests allowed

**How It Works**:
1. Track failure rate
2. If failures exceed threshold → OPEN circuit
3. Block all requests while OPEN
4. After timeout → HALF_OPEN (test recovery)
5. If test succeeds → CLOSED (resume normal operation)
6. If test fails → OPEN again

---

## Retry Implementation

### Basic Retry Configuration

**Configuration** (`application.yml`):
```yaml
peegeeq:
  queue:
    max-retries: 3
    polling-interval: PT0.5S
    batch-size: 10
```

**How PeeGeeQ Handles Retries**:
- Outbox table has `retry_count` column
- Each failure increments `retry_count`
- Messages with `retry_count >= max_retries` move to DLQ
- No application code needed for retry logic

### Transient Failure Handling

**Pattern**: Throw exception to trigger retry.

```java
private CompletableFuture<Void> processPayment(Message<PaymentEvent> message) {
    PaymentEvent event = message.getPayload();
    
    return databaseService.getConnectionProvider()
        .withTransaction("peegeeq-main", connection -> {
            // Process payment
            Payment payment = processPaymentGateway(event);
            
            // Save to database
            return savePayment(connection, payment);
        })
        .toCompletionStage()
        .toCompletableFuture()
        .exceptionally(ex -> {
            log.error("Payment processing failed: {}", event.getPaymentId(), ex);
            
            // Throw exception to trigger retry
            if (ex instanceof RuntimeException) {
                throw (RuntimeException) ex;
            }
            throw new RuntimeException("Payment processing failed", ex);
        });
}
```

**Behavior**:
1. Exception thrown → transaction rolled back
2. Message NOT deleted from outbox
3. `retry_count` incremented
4. Next poll picks up message again
5. Retry continues until success or max retries

### Exponential Backoff

**Pattern**: Use `next_retry_at` field for delayed retry.

**Database Schema**:
```sql
CREATE TABLE outbox (
    id BIGSERIAL PRIMARY KEY,
    queue_name VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB,
    retry_count INT DEFAULT 0,
    next_retry_at TIMESTAMPTZ,  -- When to retry next
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
```

**Backoff Calculation**:
```java
private Instant calculateNextRetry(int retryCount) {
    // Exponential backoff: 1s, 2s, 4s, 8s, 16s, ...
    long delaySeconds = (long) Math.pow(2, retryCount);
    return Instant.now().plusSeconds(delaySeconds);
}
```

**PeeGeeQ Polling**:
- Only polls messages where `next_retry_at IS NULL OR next_retry_at <= NOW()`
- Messages with future `next_retry_at` are skipped
- Automatic exponential backoff without application code

---

## Dead Letter Queue (DLQ)

### DLQ Table Schema

```sql
CREATE TABLE IF NOT EXISTS dead_letter_queue (
    id BIGSERIAL PRIMARY KEY,
    original_queue VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB,
    error_message TEXT,
    retry_count INT NOT NULL,
    failed_at TIMESTAMPTZ DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_dlq_original_queue ON dead_letter_queue(original_queue);
CREATE INDEX idx_dlq_failed_at ON dead_letter_queue(failed_at);
```

**Key Fields**:
- `original_queue`: Which queue the message came from
- `payload`: Original message payload
- `headers`: Original message headers
- `error_message`: Last error that caused failure
- `retry_count`: How many times message was retried
- `failed_at`: When message moved to DLQ
- `created_at`: When message was originally created

### Automatic DLQ Movement

**How PeeGeeQ Moves Messages to DLQ**:
1. Message fails processing (exception thrown)
2. `retry_count` incremented
3. If `retry_count >= max_retries`:
   - Insert into `dead_letter_queue` table
   - Delete from `outbox` table
   - Both operations in same transaction
4. Message now in DLQ for manual investigation

**No Application Code Needed**: PeeGeeQ handles DLQ movement automatically.

### DLQ Monitoring

**Service Implementation**:
```java
@Service
public class DlqManagementService {
    
    private final DatabaseService databaseService;
    
    public CompletableFuture<Long> getDlqDepth() {
        return databaseService.getConnectionProvider()
            .withTransaction("peegeeq-main", connection -> {
                String sql = "SELECT COUNT(*) FROM dead_letter_queue";
                
                return connection.query(sql)
                    .execute()
                    .map(rows -> rows.iterator().next().getLong(0));
            })
            .toCompletionStage()
            .toCompletableFuture();
    }
    
    public CompletableFuture<List<DlqMessage>> getDlqMessages(int limit) {
        return databaseService.getConnectionProvider()
            .withTransaction("peegeeq-main", connection -> {
                String sql = "SELECT id, original_queue, payload, error_message, " +
                            "retry_count, failed_at " +
                            "FROM dead_letter_queue " +
                            "ORDER BY failed_at DESC " +
                            "LIMIT $1";
                
                return connection.preparedQuery(sql)
                    .execute(Tuple.of(limit))
                    .map(rows -> {
                        List<DlqMessage> messages = new ArrayList<>();
                        for (Row row : rows) {
                            messages.add(new DlqMessage(
                                row.getLong("id"),
                                row.getString("original_queue"),
                                row.getJsonObject("payload"),
                                row.getString("error_message"),
                                row.getInteger("retry_count"),
                                row.getLocalDateTime("failed_at")
                            ));
                        }
                        return messages;
                    });
            })
            .toCompletionStage()
            .toCompletableFuture();
    }
}
```

### DLQ Alerting

**Pattern**: Alert when DLQ depth exceeds threshold.

```java
@Scheduled(fixedDelay = 60000)  // Every minute
public void checkDlqDepth() {
    dlqManagementService.getDlqDepth()
        .thenAccept(depth -> {
            if (depth > dlqAlertThreshold) {
                log.warn("DLQ depth ({}) exceeds threshold ({})", depth, dlqAlertThreshold);
                sendAlert("DLQ depth alert", depth);
            }
        });
}
```

### DLQ Reprocessing

**Pattern**: Manually reprocess DLQ messages after fixing root cause.

```java
public CompletableFuture<Void> reprocessDlqMessage(Long dlqId) {
    return databaseService.getConnectionProvider()
        .withTransaction("peegeeq-main", connection -> {
            // Get DLQ message
            String selectSql = "SELECT original_queue, payload, headers " +
                              "FROM dead_letter_queue WHERE id = $1";
            
            return connection.preparedQuery(selectSql)
                .execute(Tuple.of(dlqId))
                .compose(rows -> {
                    if (rows.size() == 0) {
                        throw new RuntimeException("DLQ message not found: " + dlqId);
                    }
                    
                    Row row = rows.iterator().next();
                    String queueName = row.getString("original_queue");
                    JsonObject payload = row.getJsonObject("payload");
                    JsonObject headers = row.getJsonObject("headers");
                    
                    // Re-insert into outbox
                    String insertSql = "INSERT INTO outbox (queue_name, payload, headers, retry_count) " +
                                      "VALUES ($1, $2, $3, 0)";
                    
                    return connection.preparedQuery(insertSql)
                        .execute(Tuple.of(queueName, payload, headers));
                })
                .compose(result -> {
                    // Delete from DLQ
                    String deleteSql = "DELETE FROM dead_letter_queue WHERE id = $1";
                    return connection.preparedQuery(deleteSql)
                        .execute(Tuple.of(dlqId));
                })
                .map(result -> null);
        })
        .toCompletionStage()
        .toCompletableFuture();
}
```

---

## Failure Classification

### Transient vs Permanent Failures

**Transient Failures** (should retry):
- Network timeouts
- Database connection failures
- Temporary service unavailability
- Rate limiting / throttling
- Deadlocks

**Permanent Failures** (should NOT retry):
- Invalid data format
- Business rule violations
- Missing required fields
- Invalid account numbers
- Duplicate transactions

### Custom Exception Types

**Define Exception Hierarchy**:
```java
public class TransientFailureException extends RuntimeException {
    public TransientFailureException(String message) {
        super(message);
    }
}

public class PermanentFailureException extends RuntimeException {
    public PermanentFailureException(String message) {
        super(message);
    }
}
```

**Use in Processing**:
```java
private CompletableFuture<Void> processTransaction(Message<TransactionEvent> message) {
    TransactionEvent event = message.getPayload();
    
    return processTransactionLogic(event)
        .exceptionally(ex -> {
            if (isTransientFailure(ex)) {
                // Retry
                throw new TransientFailureException(ex.getMessage());
            } else {
                // Move to DLQ immediately
                throw new PermanentFailureException(ex.getMessage());
            }
        });
}

private boolean isTransientFailure(Throwable ex) {
    return ex instanceof TimeoutException ||
           ex instanceof ConnectException ||
           ex.getMessage().contains("deadlock");
}
```

---

## Circuit Breaker Pattern

### Circuit Breaker States

**State Machine**:
```
CLOSED (normal) → OPEN (failing) → HALF_OPEN (testing) → CLOSED (recovered)
       ↑                                    ↓
       └────────────────────────────────────┘
                  (test failed)
```

### Implementation

**Circuit Breaker Service**:
```java
@Service
public class CircuitBreakerService {
    
    private enum State { CLOSED, OPEN, HALF_OPEN }
    
    private State state = State.CLOSED;
    private int failureCount = 0;
    private Instant openedAt;
    
    private final int failureThreshold = 5;
    private final Duration resetTimeout = Duration.ofMinutes(1);
    
    public synchronized boolean allowRequest() {
        if (state == State.CLOSED) {
            return true;
        }
        
        if (state == State.OPEN) {
            // Check if timeout elapsed
            if (Instant.now().isAfter(openedAt.plus(resetTimeout))) {
                state = State.HALF_OPEN;
                log.info("Circuit breaker: OPEN → HALF_OPEN");
                return true;
            }
            return false;
        }
        
        // HALF_OPEN: allow one request to test
        return true;
    }
    
    public synchronized void recordSuccess() {
        if (state == State.HALF_OPEN) {
            state = State.CLOSED;
            failureCount = 0;
            log.info("Circuit breaker: HALF_OPEN → CLOSED");
        } else if (state == State.CLOSED) {
            failureCount = 0;
        }
    }
    
    public synchronized void recordFailure() {
        failureCount++;
        
        if (state == State.HALF_OPEN) {
            state = State.OPEN;
            openedAt = Instant.now();
            log.warn("Circuit breaker: HALF_OPEN → OPEN");
        } else if (state == State.CLOSED && failureCount >= failureThreshold) {
            state = State.OPEN;
            openedAt = Instant.now();
            log.warn("Circuit breaker: CLOSED → OPEN (failures: {})", failureCount);
        }
    }
    
    public State getState() {
        return state;
    }
}
```

### Using Circuit Breaker

**In Message Handler**:
```java
private CompletableFuture<Void> processTransaction(Message<TransactionEvent> message) {
    // Check circuit breaker before processing
    if (!circuitBreaker.allowRequest()) {
        log.warn("Circuit breaker is OPEN - rejecting transaction");
        throw new TransientFailureException("Circuit breaker is open");
    }
    
    return processTransactionLogic(message.getPayload())
        .thenApply(result -> {
            circuitBreaker.recordSuccess();
            return null;
        })
        .exceptionally(ex -> {
            circuitBreaker.recordFailure();
            throw new RuntimeException(ex);
        });
}
```

---

## Best Practices

### 1. Configure Appropriate Max Retries

**Guideline**: 3-5 retries for most use cases.

```yaml
peegeeq:
  queue:
    max-retries: 3  # Good default
```

**Too Few**: Transient failures might not recover
**Too Many**: Delays DLQ movement, wastes resources

### 2. Use Exponential Backoff

**Why**: Prevents overwhelming failing systems.

```java
// 1s, 2s, 4s, 8s, 16s
long delaySeconds = (long) Math.pow(2, retryCount);
```

### 3. Monitor DLQ Depth

**Why**: High DLQ depth indicates systemic issues.

```java
@Scheduled(fixedDelay = 60000)
public void monitorDlq() {
    long depth = getDlqDepth().join();
    if (depth > threshold) {
        alertOps("DLQ depth: " + depth);
    }
}
```

### 4. Classify Failures

**Why**: Avoid retrying permanent failures.

```java
if (isPermanentFailure(ex)) {
    throw new PermanentFailureException(ex.getMessage());
} else {
    throw new TransientFailureException(ex.getMessage());
}
```

### 5. Use Circuit Breaker

**Why**: Prevent cascading failures.

```java
if (!circuitBreaker.allowRequest()) {
    throw new TransientFailureException("Circuit breaker open");
}
```

### 6. Log All Failures

**Why**: Enables debugging and root cause analysis.

```java
log.error("Payment failed: paymentId={}, retryCount={}, error={}", 
    paymentId, retryCount, ex.getMessage(), ex);
```

---

## Payment Processing Use Case

### Scenario

**Trade Settlement Payment Processing**:
- Send payment instructions to custodian
- Network timeouts common (retry)
- Invalid account numbers (DLQ)
- Custodian system occasionally down (circuit breaker)
- Operations team investigates DLQ messages

### Implementation

**Producer** (Trade Settlement System):
```java
PaymentEvent payment = new PaymentEvent(
    paymentId,
    orderId,
    amount,
    currency,
    paymentMethod
);

paymentProducer.send(payment);
```

**Consumer** (Payment Processor):
```java
private CompletableFuture<Void> processPayment(Message<PaymentEvent> message) {
    // Check circuit breaker
    if (!circuitBreaker.allowRequest()) {
        throw new TransientFailureException("Custodian system unavailable");
    }
    
    PaymentEvent event = message.getPayload();
    
    return sendToCustodian(event)
        .thenApply(response -> {
            circuitBreaker.recordSuccess();
            return savePayment(event, "COMPLETED");
        })
        .exceptionally(ex -> {
            circuitBreaker.recordFailure();
            
            if (ex instanceof TimeoutException) {
                // Retry
                throw new TransientFailureException("Custodian timeout");
            } else if (ex instanceof InvalidAccountException) {
                // DLQ
                throw new PermanentFailureException("Invalid account");
            } else {
                // Retry
                throw new TransientFailureException(ex.getMessage());
            }
        });
}
```

**DLQ Management** (Operations Team):
```bash
# Check DLQ depth
curl http://localhost:8080/api/dlq/depth

# Get DLQ messages
curl http://localhost:8080/api/dlq/messages?limit=10

# Reprocess after fixing account number
curl -X POST http://localhost:8080/api/dlq/reprocess/123
```

---

## Summary

**Key Takeaways**:

1. **Automatic Retry** - PeeGeeQ handles retry logic automatically
2. **Max Retries** - Configure appropriate max retries (3-5)
3. **Exponential Backoff** - Use `next_retry_at` for delayed retry
4. **DLQ** - Messages move to DLQ after max retries
5. **Failure Classification** - Distinguish transient vs permanent failures
6. **Circuit Breaker** - Prevent cascading failures to downstream systems
7. **DLQ Monitoring** - Alert on high DLQ depth
8. **DLQ Reprocessing** - Manually reprocess after fixing root cause

**Related Guides**:
- **CONSUMER_GROUP_GUIDE.md** - Consumer groups and message filtering
- **PRIORITY_FILTERING_GUIDE.md** - Priority-based message routing
- **INTEGRATED_PATTERN_GUIDE.md** - Combining outbox + bi-temporal patterns

